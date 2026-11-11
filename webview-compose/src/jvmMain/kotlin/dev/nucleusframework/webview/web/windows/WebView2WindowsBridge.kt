package dev.nucleusframework.webview.web.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.webview.util.KLogger
import dev.nucleusframework.webview.web.AttestedChannelScripts
import dev.nucleusframework.webview.web.AttestedMessage
import dev.nucleusframework.webview.web.AttestedMessageChannel
import dev.nucleusframework.webview.web.IpcInbox
import dev.nucleusframework.webview.web.NativeCompletions
import dev.nucleusframework.webview.web.WindowsChannelSession
import dev.nucleusframework.webview.web.WindowsMessage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * JNI bridge to `compose_webview.cpp` (WebView2 CompositionController + DComp).
 *
 * Loaded only on Windows. All native calls must run on the Tao main thread
 * (the thread that owns the parent HWND). WebView2's controller is STA.
 *
 * [WebView2Loader.dll] is a required sidecar extracted next to the JNI DLL;
 * the C++ side loads it by name after extending the DLL search path.
 */
internal object WebView2WindowsBridge {
    private const val LIBRARY_NAME = "compose_webview_windows"
    private const val LOG_TAG = "WebView2WindowsBridge"
    private const val KEY_BYTES = 16

    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            WebView2WindowsBridge::class.java,
            sidecarFiles = listOf("WebView2Loader.dll"),
        )

    private val navigateHandlers =
        ConcurrentHashMap<Long, MutableList<(String) -> Boolean>>()
    private val ipcInbox = IpcInbox()
    private val cookieRemovals = NativeCompletions()
    private val jsCallbacks =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<(String) -> Unit>>()
    private val cookieDeferreds =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<CompletableDeferred<String>>>()
    private val screenshotDeferreds =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<CompletableDeferred<ByteArray?>>>()

    private val channels = ConcurrentHashMap<Long, AttestedMessageChannel>()
    private val sessions = ConcurrentHashMap<Long, WindowsChannelSession>()
    private val random = SecureRandom()

    // Replies run native calls, which must happen on the Tao main thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun registerChannel(handle: Long, channel: AttestedMessageChannel) {
        channels[handle] = channel
        sessions[handle] = WindowsChannelSession(newKey = ::newKey)
    }

    private fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    fun addNavigateListener(handle: Long, listener: (String) -> Boolean) {
        navigateHandlers.getOrPut(handle) { mutableListOf() }.add(listener)
    }

    fun removeNavigateListener(handle: Long, listener: (String) -> Boolean) {
        navigateHandlers[handle]?.remove(listener)
    }

    /** Starts keeping the `window.ipc` messages of [handle]; until then they are dropped. */
    fun startIpcQueue(handle: Long) = ipcInbox.open(handle)

    fun drainIpcMessages(handle: Long): List<String> = ipcInbox.drain(handle)

    /** Removes every cookie and returns once the engine reports them gone. */
    suspend fun removeAllCookies(handle: Long) =
        cookieRemovals.await(handle) { requestId -> nativeRemoveAllCookies(handle, requestId) }

    /** Removes the cookies of [url]'s host and returns once the engine reports them gone. */
    suspend fun removeCookiesForUrl(handle: Long, url: String) =
        cookieRemovals.await(handle) { requestId -> nativeRemoveCookiesForUrl(handle, url, requestId) }

    fun registerJsCallback(handle: Long, callback: (String) -> Unit) {
        jsCallbacks.getOrPut(handle) { ConcurrentLinkedQueue() }.add(callback)
    }

    fun registerCookieDeferred(handle: Long, deferred: CompletableDeferred<String>) {
        cookieDeferreds.getOrPut(handle) { ConcurrentLinkedQueue() }.add(deferred)
    }

    fun registerScreenshotDeferred(handle: Long, deferred: CompletableDeferred<ByteArray?>) {
        screenshotDeferreds.getOrPut(handle) { ConcurrentLinkedQueue() }.add(deferred)
    }

    fun clearHandle(handle: Long) {
        navigateHandlers.remove(handle)
        channels.remove(handle)
        sessions.remove(handle)
        ipcInbox.close(handle)
        cookieRemovals.release(handle)
        jsCallbacks.remove(handle)?.forEach { it.invoke("") }
        cookieDeferreds.remove(handle)?.forEach {
            it.complete("[]")
        }
        screenshotDeferreds.remove(handle)?.forEach {
            it.complete(null)
        }
    }

    // ── Callbacks from native (must be public for JNI) ────────────────

    @JvmStatic
    fun nativeOnNavigate(handle: Long, url: String): Boolean {
        val handlers = navigateHandlers[handle]
        if (handlers.isNullOrEmpty()) return true
        return handlers.any { it(url) }
    }

    @JvmStatic
    fun nativeOnIpcMessage(handle: Long, message: String) {
        ipcInbox.offer(handle, message)
    }

    /**
     * A string message posted over `chrome.webview`, with the URI of the document that posted it
     * and the top-level document generation it arrived in. Messages that are not the channel's
     * (the library's own `window.ipc` and JS bridge traffic) go to the IPC queue.
     *
     * A hello is answered with a key that only the top-level document receives; a request reaches
     * the channel only when it returns that key (see [WindowsChannelSession]).
     */
    @JvmStatic
    fun nativeOnSourcedMessage(handle: Long, generation: Long, source: String, raw: String) {
        val channel = channels[handle]
        val session = sessions[handle]
        val message = channel?.let { AttestedChannelScripts.parseWindowsMessage(raw, it.name) }
        if (channel == null || session == null || message == null) {
            nativeOnIpcMessage(handle, raw)
            return
        }
        when (message) {
            WindowsMessage.Malformed -> Unit
            is WindowsMessage.Hello -> {
                val key = session.hello(generation, message.document, message.origin, source) ?: return
                nativeExecuteIfGeneration(
                    handle,
                    generation,
                    AttestedChannelScripts.windowsKeyScript(channel.name, message.document, key),
                )
            }
            is WindowsMessage.Request -> {
                val origin = session.authenticate(message.document, message.key, source) ?: return
                // The reply script compares location.origin, which follows the URL even when the
                // document's own origin is opaque.
                val urlOrigin = AttestedChannelScripts.originOf(source)
                val replied = AtomicBoolean(false)
                try {
                    channel.onMessage(AttestedMessage(body = message.body, origin = origin, isMainFrame = true)) { payload ->
                        if (!replied.compareAndSet(false, true)) return@onMessage
                        val script =
                            AttestedChannelScripts.windowsReplyScript(channel.name, urlOrigin, message.document, message.id, payload)
                        mainScope.launch {
                            if (channels.containsKey(handle)) nativeExecuteIfGeneration(handle, generation, script)
                        }
                    }
                } catch (t: Throwable) {
                    KLogger.e(t, tag = LOG_TAG) { "channel \"${channel.name}\" onMessage threw" }
                }
            }
        }
    }

    @JvmStatic
    fun nativeOnJsResult(handle: Long, result: String) {
        jsCallbacks[handle]?.poll()?.invoke(result)
    }

    @JvmStatic
    fun nativeOnCookiesResult(handle: Long, json: String) {
        cookieDeferreds[handle]?.poll()?.complete(json)
    }

    /** The removal started with [requestId] has finished (or could not be started). */
    @JvmStatic
    fun nativeOnCookiesRemoved(requestId: Long) {
        cookieRemovals.complete(requestId)
    }

    @JvmStatic
    fun nativeOnScreenshotResult(handle: Long, bytes: ByteArray?) {
        screenshotDeferreds[handle]?.poll()?.complete(bytes)
    }

    // ── Native methods ────────────────────────────────────────────────

    @JvmStatic
    external fun nativeCreate(
        parentHwnd: Long,
        userAgent: String?,
        dataDirectory: String?,
        initScript: String?,
        jsBridgeScript: String?,
        incognito: Boolean,
        enableDevtools: Boolean,
        javascriptEnabled: Boolean,
        zoomLevel: Double,
        transparent: Boolean,
        bgR: Float,
        bgG: Float,
        bgB: Float,
        bgA: Float,
        channelShim: String?,
    ): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeExecuteIfGeneration(handle: Long, generation: Long, script: String)

    @JvmStatic
    external fun nativeLoadUrl(handle: Long, url: String)

    @JvmStatic
    external fun nativeLoadUrlWithHeaders(
        handle: Long,
        url: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
    )

    @JvmStatic
    external fun nativeLoadHtml(handle: Long, html: String, baseUri: String?)

    @JvmStatic
    external fun nativeGoBack(handle: Long)

    @JvmStatic
    external fun nativeGoForward(handle: Long)

    @JvmStatic
    external fun nativeReload(handle: Long)

    @JvmStatic
    external fun nativeStopLoading(handle: Long)

    @JvmStatic
    external fun nativeCanGoBack(handle: Long): Boolean

    @JvmStatic
    external fun nativeCanGoForward(handle: Long): Boolean

    @JvmStatic
    external fun nativeCurrentUrl(handle: Long): String?

    @JvmStatic
    external fun nativeGetTitle(handle: Long): String?

    @JvmStatic
    external fun nativeIsLoading(handle: Long): Boolean

    @JvmStatic
    external fun nativeSetZoomLevel(handle: Long, zoom: Double)

    @JvmStatic
    external fun nativeFocus(handle: Long)

    @JvmStatic
    external fun nativeOpenDevTools(handle: Long)

    @JvmStatic
    external fun nativeCloseDevTools(handle: Long)

    @JvmStatic
    external fun nativeEvaluateJavaScript(handle: Long, script: String)

    @JvmStatic
    external fun nativeGetCookies(handle: Long, url: String)

    @JvmStatic
    external fun nativeSetCookie(
        handle: Long,
        name: String,
        value: String,
        domain: String?,
        path: String?,
        secure: Boolean,
        httpOnly: Boolean,
        expiresMs: Long,
        sameSite: String?,
    )

    @JvmStatic
    external fun nativeRemoveAllCookies(handle: Long, requestId: Long)

    @JvmStatic
    external fun nativeRemoveCookiesForUrl(handle: Long, url: String, requestId: Long)

    @JvmStatic
    external fun nativeCaptureScreenshot(handle: Long)

    @JvmStatic
    external fun nativeSetBounds(
        handle: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    @JvmStatic
    external fun nativeSetCornerRadius(handle: Long, radiusPx: Float)
}
