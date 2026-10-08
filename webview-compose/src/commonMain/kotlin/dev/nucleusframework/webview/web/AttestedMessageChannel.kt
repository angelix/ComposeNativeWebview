// Public API for a desktop page-to-host message channel whose messages carry the engine-reported
// sender origin and frame. Configured through DesktopWebSettings.messageChannel.
package dev.nucleusframework.webview.web

/**
 * A named page→host message channel for the desktop backends whose messages carry the sender's
 * origin and frame as the ENGINE reports them, never as the page claims.
 *
 * The page sees `window[name].postMessage(body: string): Promise<string>`, installed at document
 * start in the top frame only and impossible to replace. [onMessage] runs on the UI thread; call
 * `reply` (any thread, at most once) to resolve the page's promise. A reply after the sending
 * document is gone is dropped. Supported on macOS (WKWebView) and Windows (WebView2); ignored on
 * Linux.
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

/** One message on an [AttestedMessageChannel]: [origin] and [isMainFrame] come from the engine. */
data class AttestedMessage(
    val body: String,
    val origin: String,
    val isMainFrame: Boolean,
)
