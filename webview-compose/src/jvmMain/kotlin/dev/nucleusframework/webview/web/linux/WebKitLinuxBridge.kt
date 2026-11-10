package dev.nucleusframework.webview.web.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.webview.util.KLogger
import dev.nucleusframework.webview.web.AttestedChannelScripts
import dev.nucleusframework.webview.web.AttestedMessage
import dev.nucleusframework.webview.web.AttestedMessageChannel
import dev.nucleusframework.webview.web.IpcInbox
import dev.nucleusframework.webview.web.NativeCompletions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * JNI bridge to `compose_webview.c` (WebKit2GTK).
 *
 * Loaded only on Linux. All native calls must run on the GTK main thread
 * (Tao application thread).
 */
internal object WebKitLinuxBridge {
    private const val LIBRARY_NAME = "compose_webview_linux"
    private const val LOG_TAG = "WebKitLinuxBridge"

    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            WebKitLinuxBridge::class.java,
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

    private val requestIds = AtomicLong(1)

    private val channels = ConcurrentHashMap<Long, AttestedMessageChannel>()

    // Replies run native calls, which must happen on the GTK main thread (the Tao event loop).
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
        // Match previous Wry semantics: any listener returning true allows.
        return handlers.any { it(url) }
    }

    @JvmStatic
    fun nativeOnIpcMessage(handle: Long, message: String) {
        ipcInbox.offer(handle, message)
    }

    /**
     * A message on the attested channel, as the relay script in the channel's script world
     * enveloped it. The page cannot reach that handler, so the envelope's origin and frame are the
     * relay's own reading of the document it runs in.
     */
    @JvmStatic
    fun nativeOnChannelMessage(handle: Long, replyId: Long, raw: String) {
        val channel = channels[handle]
        val envelope = AttestedChannelScripts.parseLinuxEnvelope(raw)
        if (channel == null || envelope == null) {
            KLogger.w(tag = LOG_TAG) { "channel message for handle $handle could not be delivered" }
            nativeChannelReply(handle, replyId, null)
            return
        }
        val message =
            AttestedMessage(
                body = envelope.body,
                origin = AttestedChannelScripts.originOf(envelope.origin),
                isMainFrame = envelope.isMainFrame,
            )
        val replied = AtomicBoolean(false)
        try {
            channel.onMessage(message) { payload ->
                if (!replied.compareAndSet(false, true)) return@onMessage
                mainScope.launch {
                    if (channels.containsKey(handle)) nativeChannelReply(handle, replyId, payload)
                }
            }
        } catch (t: Throwable) {
            KLogger.e(t, tag = LOG_TAG) { "channel \"${channel.name}\" onMessage threw" }
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
        channelName: String?,
        channelWorld: String?,
        channelRelay: String?,
        channelShim: String?,
    ): Long

    /** Resolves the page's promise for [replyId] with [payload], or rejects it when null. */
    @JvmStatic
    external fun nativeChannelReply(handle: Long, replyId: Long, payload: String?)

    @JvmStatic
    external fun nativeGetGtkWidget(handle: Long): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

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
}
