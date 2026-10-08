// Page-side shims and host-side helpers behind AttestedMessageChannel: origin serialisation,
// the document-start scripts for macOS and Windows, and the Windows message envelope codec.
// Pure functions so the native backends stay thin and this logic is unit-testable in commonTest.
package dev.nucleusframework.webview.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal data class WindowsEnvelope(val id: Long, val body: String)

/** Page-side shims and host-side helpers for [AttestedMessageChannel]. Pure; unit-tested. */
internal object AttestedChannelScripts {

    private const val CHANNEL_TAG = "__nucleusChannel"

    fun resolverName(channel: String): String = "__nucleusChannelResolve_$channel"

    /** `scheme://host[:port]` as browsers serialise an origin; "null" for opaque or unparsable URLs. */
    fun originOf(url: String?): String {
        if (url.isNullOrBlank()) return "null"
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(?:[^@/?#]*@)?(\\[[^\\]]+]|[^:/?#]+)(?::(\\d+))?").find(url)
            ?: return "null"
        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return "null"
        val host = match.groupValues[2].lowercase()
        val port = match.groupValues[3]
        val defaultPort = if (scheme == "https") "443" else "80"
        return if (port.isEmpty() || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun js(value: String): String = JsonPrimitive(value).toString().replace("</", "<\\/")

    /** macOS: wraps the with-reply WebKit handler (whose promise WebKit binds to the sending document). */
    fun macosShim(channel: String): String = """
        (function () {
          if (window.top !== window) { return; }
          var handlers = window.webkit && window.webkit.messageHandlers;
          var h = handlers && window.webkit.messageHandlers[${js(channel)}];
          if (!h || Object.prototype.hasOwnProperty.call(window, ${js(channel)})) { return; }
          Object.defineProperty(window, ${js(channel)}, {
            value: Object.freeze({ postMessage: function (body) { return h.postMessage(String(body)); } }),
            writable: false, configurable: false, enumerable: false
          });
        })();
    """.trimIndent()

    /** Windows: a promise per message over chrome.webview, resolved by [windowsReplyScript]. */
    fun windowsShim(channel: String): String = """
        (function () {
          if (window.top !== window) { return; }
          var wv = window.chrome && window.chrome.webview;
          if (!wv || Object.prototype.hasOwnProperty.call(window, ${js(channel)})) { return; }
          var post = wv.postMessage.bind(wv);
          var pending = {};
          var next = 1;
          Object.defineProperty(window, ${js(resolverName(channel))}, {
            value: function (id, payload) {
              var p = pending[id];
              if (!p) { return; }
              delete pending[id];
              p(payload);
            },
            writable: false, configurable: false, enumerable: false
          });
          Object.defineProperty(window, ${js(channel)}, {
            value: Object.freeze({
              postMessage: function (body) {
                return new Promise(function (resolve) {
                  var id = next++;
                  pending[id] = resolve;
                  post(JSON.stringify({ "$CHANNEL_TAG": ${js(channel)}, id: id, body: String(body) }));
                });
              }
            }),
            writable: false, configurable: false, enumerable: false
          });
        })();
    """.trimIndent()

    fun parseWindowsEnvelope(raw: String, channel: String): WindowsEnvelope? {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val tag = (obj[CHANNEL_TAG] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (tag != channel) return null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return WindowsEnvelope(id, body)
    }

    /** Runs in whatever document is current; only resolves if it is still the sender's origin. */
    fun windowsReplyScript(channel: String, origin: String, id: Long, payload: String): String =
        "(function(){if(location.origin !== ${js(origin)}){return;}" +
            "var r=window[${js(resolverName(channel))}];if(typeof r==='function'){r($id, ${js(payload)});}})();"
}
