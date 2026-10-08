package dev.nucleusframework.webview.web.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.webview.util.KLogger
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
 * JNI bridge to `compose_webview_macos.m` (WKWebView).
 *
 * Loaded only on macOS. All native calls must run on the AppKit main thread
 * (Tao application thread).
 */
internal object WebKitMacOsBridge {
    private const val LIBRARY_NAME = "compose_webview_macos"
    private const val LOG_TAG = "WebKitMacOsBridge"

    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            WebKitMacOsBridge::class.java,
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

    // Native reply handlers must be invoked on the AppKit main thread, which is the Tao event loop.
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

    @JvmStatic
    fun nativeOnChannelMessage(handle: Long, replyId: Long, origin: String, isMainFrame: Boolean, body: String) {
        val channel = channels[handle]
        if (channel == null) {
            // The native side parks the page's reply handler; teardown rejects it.
            KLogger.w(tag = LOG_TAG) { "channel message for handle $handle with no registered channel" }
            return
        }
        val replied = AtomicBoolean(false)
        try {
            channel.onMessage(AttestedMessage(body = body, origin = origin, isMainFrame = isMainFrame)) { payload ->
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
        channelShim: String?,
    ): Long

    @JvmStatic
    external fun nativeGetNsView(handle: Long): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeChannelReply(handle: Long, replyId: Long, payload: String)

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
}
