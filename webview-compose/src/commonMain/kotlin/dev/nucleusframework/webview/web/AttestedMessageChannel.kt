// Public API for a desktop page-to-host message channel whose messages carry the engine-reported
// sender origin and frame. Configured through DesktopWebSettings.messageChannel.
package dev.nucleusframework.webview.web

/**
 * A named page→host message channel for the desktop backends whose messages carry the sender's
 * origin and frame as the ENGINE reports them, never as the page claims.
 *
 * The page sees `window[name].postMessage(body: string): Promise<string>`, installed at document
 * start in the top frame only and impossible to replace. [onMessage] runs on the UI thread; call
 * `reply` (any thread, at most once) to resolve the page's promise. A reply is never delivered to a
 * later document than the one that sent the message (on macOS it can still settle the old
 * document's promise after a navigation). An exception thrown by [onMessage] is logged and the
 * page's promise stays pending. Supported on macOS (WKWebView), Windows (WebView2) and Linux
 * (WebKitGTK 2.40 or later).
 *
 * [AttestedMessage.origin] is `scheme://host[:port]` for an `http(s)` origin and `"null"` for
 * anything else: sandboxed documents, `data:` and `file:` documents, `about:blank`.
 *
 * Attestation follows the browser's same-origin model: a same-origin frame (including
 * `about:blank` and `srcdoc` frames) can call `parent[name].postMessage`, and its message is
 * attested as the main frame's. Per OS:
 * - macOS: the origin is WebKit's security origin of the sending frame. Sub-frame messages arrive
 *   with `isMainFrame == false`. The promise rejects when the body is not a string, a string cannot
 *   cross to or from the host, the host is unavailable, or the WebView is released before the reply.
 * - Linux: WebKitGTK does not say who sent a script message, so the message handler lives in a
 *   script world of its own, where the page cannot reach it, and a relay script there reports the
 *   document it runs in: its `window.origin` and whether it is the top window. Only the top-level
 *   document reaches the channel. The promise rejects as on macOS.
 * - Windows: WebView2 gives the sending document's URL but not its frame, so the top-level
 *   document proves itself with a key the host hands it through a script that only that document
 *   runs. The origin is the one the document reads for itself before any page script runs, when its
 *   URL bears it out, and `"null"` otherwise (so a `blob:` top document is `"null"`). Only the
 *   top-level document reaches the channel. The promise never rejects; an unanswered message stays
 *   pending.
 *
 * [name] must be a JS identifier, must not be `ipc`, and must differ from the WebView's
 * `jsBridgeName` (checked when the native view is created).
 */
class AttestedMessageChannel(
    val name: String,
    val onMessage: (message: AttestedMessage, reply: (String) -> Unit) -> Unit,
) {
    init {
        require(Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(name)) { "channel name must be a JS identifier: $name" }
        require(name != "ipc") { "\"ipc\" is the library's own channel" }
    }
}

/** Fails when the WebView's JS bridge would install a page global with this channel's name. */
internal fun AttestedMessageChannel.requireDistinctFromJsBridge(jsBridgeName: String?) {
    require(name != jsBridgeName?.trim()) { "channel name \"$name\" is the WebView's jsBridgeName" }
}

/** One message on an [AttestedMessageChannel]: [origin] and [isMainFrame] come from the engine. */
data class AttestedMessage(
    val body: String,
    val origin: String,
    val isMainFrame: Boolean,
)
