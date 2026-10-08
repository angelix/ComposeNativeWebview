package dev.nucleusframework.webview.web.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.webview.web.AttestedChannelScripts
import dev.nucleusframework.webview.web.AttestedMessage
import dev.nucleusframework.webview.web.AttestedMessageChannel
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

    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            WebView2WindowsBridge::class.java,
            sidecarFiles = listOf("WebView2Loader.dll"),
        )

    private val navigateHandlers =
        ConcurrentHashMap<Long, MutableList<(String) -> Boolean>>()
    private val ipcQueues =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>>()
    private val jsCallbacks =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<(String) -> Unit>>()
    private val cookieDeferreds =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<CompletableDeferred<String>>>()
    private val screenshotDeferreds =
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<CompletableDeferred<ByteArray?>>>()

    private val channels = ConcurrentHashMap<Long, AttestedMessageChannel>()

    // Replies run native calls, which must happen on the Tao main thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun registerChannel(handle: Long, channel: AttestedMessageChannel) {
        channels[handle] = channel
    }

    fun addNavigateListener(handle: Long, listener: (String) -> Boolean) {
        navigateHandlers.getOrPut(handle) { mutableListOf() }.add(listener)
    }

    fun removeNavigateListener(handle: Long, listener: (String) -> Boolean) {
        navigateHandlers[handle]?.remove(listener)
    }

    fun drainIpcMessages(handle: Long): List<String> {
        val queue = ipcQueues[handle] ?: return emptyList()
        val drained = ArrayList<String>()
        while (true) {
            val next = queue.poll() ?: break
            drained += next
        }
        return drained
    }

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
        ipcQueues.remove(handle)
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
        ipcQueues.getOrPut(handle) { ConcurrentLinkedQueue() }.add(message)
    }

    /**
     * A string message from the top-level document, tagged with that document's URI and the
     * navigation generation it arrived in. Messages that are not envelopes for the channel
     * (the library's own `window.ipc` and JS bridge traffic) go to the IPC queue.
     */
    @JvmStatic
    fun nativeOnSourcedMessage(handle: Long, generation: Long, source: String, raw: String) {
        val channel = channels[handle]
        val envelope = channel?.let { AttestedChannelScripts.parseWindowsEnvelope(raw, it.name) }
        if (channel == null || envelope == null) {
            nativeOnIpcMessage(handle, raw)
            return
        }
        val origin = AttestedChannelScripts.originOf(source)
        val replied = AtomicBoolean(false)
        // Only the top-level document reaches CoreWebView2.WebMessageReceived (iframes need
        // CoreWebView2Frame), and the shim installs only in the top frame.
        channel.onMessage(AttestedMessage(body = envelope.body, origin = origin, isMainFrame = true)) { payload ->
            if (!replied.compareAndSet(false, true)) return@onMessage
            val script = AttestedChannelScripts.windowsReplyScript(channel.name, origin, envelope.document, envelope.id, payload)
            mainScope.launch {
                if (channels.containsKey(handle)) nativeExecuteIfGeneration(handle, generation, script)
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
    external fun nativeRemoveAllCookies(handle: Long)

    @JvmStatic
    external fun nativeRemoveCookiesForUrl(handle: Long, url: String)

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
