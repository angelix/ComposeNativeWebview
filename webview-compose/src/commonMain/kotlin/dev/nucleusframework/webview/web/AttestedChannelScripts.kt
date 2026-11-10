// Page-side shims and host-side helpers behind AttestedMessageChannel: origin serialisation,
// the document-start scripts for macOS, Windows and Linux, and the Windows and Linux message
// envelope codecs.
// Pure functions so the native backends stay thin and this logic is unit-testable in commonTest.
package dev.nucleusframework.webview.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal data class WindowsEnvelope(val id: Long, val body: String, val document: String)

/** What the Linux relay script reports about the document it runs in, with the page's message. */
internal data class LinuxEnvelope(val origin: String, val isMainFrame: Boolean, val body: String)

/** Page-side shims and host-side helpers for [AttestedMessageChannel]. Pure; unit-tested. */
internal object AttestedChannelScripts {

    private const val CHANNEL_TAG = "__nucleusChannel"

    /**
     * The WebKitGTK script world the Linux relay runs in. A script world shares the page's DOM but
     * none of its JavaScript objects, so the page can neither reach the message handler registered
     * there nor change what the relay reads.
     */
    const val LINUX_WORLD = "nucleusAttestedChannel"

    fun linuxHelloEvent(channel: String): String = "__nucleusChannelHello_$channel"

    fun linuxRelayEvent(channel: String): String = "__nucleusChannelRelay_$channel"

    fun resolverName(channel: String): String = "__nucleusChannelResolve_$channel"

    /**
     * `scheme://host[:port]` as browsers serialise an origin; "null" for opaque or unparsable URLs
     * and for authorities a browser might read differently (backslashes, several `@`, whitespace or
     * control characters, a non-numeric port).
     */
    fun originOf(url: String?): String {
        if (url.isNullOrBlank()) return "null"
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)").find(url) ?: return "null"
        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return "null"
        val authority = match.groupValues[2]
        if (authority.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return "null"
        if (authority.count { it == '@' } > 1) return "null"
        val hostAndPort = authority.substringAfter('@')
        val hostEnd = if (hostAndPort.startsWith("[")) hostAndPort.indexOf(']') + 1 else hostAndPort.indexOf(':').let { if (it < 0) hostAndPort.length else it }
        if (hostEnd <= 0) return "null"
        val host = hostAndPort.substring(0, hostEnd).lowercase()
        if (host == "[]" || host == "[") return "null"
        val portPart = hostAndPort.substring(hostEnd)
        val port = when {
            portPart.isEmpty() -> ""
            portPart.length > 1 && portPart[0] == ':' && portPart.drop(1).all { it in '0'..'9' } -> portPart.drop(1)
            else -> return "null"
        }
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
          var post = h.postMessage.bind(h);
          Object.defineProperty(window, ${js(channel)}, {
            value: Object.freeze({ postMessage: function (body) { return post(String(body)); } }),
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
          if (!window.crypto || typeof window.crypto.getRandomValues !== "function") { return; }
          var bytes = new Uint8Array(16);
          window.crypto.getRandomValues(bytes);
          var token = "";
          for (var i = 0; i < bytes.length; i++) { token += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16); }
          var post = wv.postMessage.bind(wv);
          var pending = {};
          var next = 1;
          Object.defineProperty(window, ${js(resolverName(channel))}, {
            value: function (doc, id, payload) {
              if (doc === token) {
                var p = pending[id];
                if (p) { delete pending[id]; p(payload); }
              }
            },
            writable: false, configurable: false, enumerable: false
          });
          Object.defineProperty(window, ${js(channel)}, {
            value: Object.freeze({
              postMessage: function (body) {
                return new Promise(function (resolve) {
                  var id = next++;
                  pending[id] = resolve;
                  post(JSON.stringify({ "$CHANNEL_TAG": ${js(channel)}, doc: token, id: id, body: String(body) }));
                });
              }
            }),
            writable: false, configurable: false, enumerable: false
          });
        })();
    """.trimIndent()

    /**
     * Linux, page side. WebKitGTK tells the host nothing about who sent a script message, so the
     * page's messages go through [linuxRelay], which runs in its own script world and reports the
     * document it runs in. The two halves talk over a pipe: an event target the shim creates and
     * hands over as the `relatedTarget` of a hello event on `window`, which the relay's world
     * receives as that same object. WebKit starts the two scripts in either order, both before any
     * page script, so the shim offers the pipe when it starts and when the relay announces itself,
     * and never again once a relay has taken it. No page script ever holds the pipe, so none can
     * listen on it, send a request on it, or forge a reply.
     *
     * The pipe is part of no document: `document.open()`, which removes every listener on `window`
     * and in the document, leaves both ends of it listening.
     */
    fun linuxShim(channel: String): String = """
        (function () {
          if (window.top !== window) { return; }
          if (Object.prototype.hasOwnProperty.call(window, ${js(channel)})) { return; }
          var dispatch = window.dispatchEvent.bind(window);
          var listen = window.addEventListener.bind(window);
          var Event = window.CustomEvent;
          var Hello = window.FocusEvent;
          var detailOf = Function.prototype.call.bind(Object.getOwnPropertyDescriptor(Event.prototype, "detail").get);
          var stringify = JSON.stringify;
          var parse = JSON.parse;
          var pipe = new EventTarget();
          var send = pipe.dispatchEvent.bind(pipe);
          var taken = false;
          var queued = [];
          var pending = Object.create(null);
          var next = 1;
          function request(id, body) {
            send(new Event("request", { detail: stringify({ id: id, body: body }) }));
          }
          function offer() {
            if (taken) { return; }
            taken = !dispatch(new Hello(${js(linuxHelloEvent(channel))}, { cancelable: true, relatedTarget: pipe }));
            if (!taken) { return; }
            var waiting = queued;
            queued = [];
            for (var i = 0; i < waiting.length; i++) { request(waiting[i][0], waiting[i][1]); }
          }
          pipe.addEventListener("reply", function (e) {
            var m;
            try { m = parse(detailOf(e)); } catch (x) { return; }
            var p = m && pending[m.id];
            if (!p) { return; }
            delete pending[m.id];
            if (m.ok === true) { p[0](m.value); } else { p[1](new Error(String(m.value))); }
          });
          listen(${js(linuxRelayEvent(channel))}, function (e) {
            e.stopImmediatePropagation();
            offer();
          }, true);
          Object.defineProperty(window, ${js(channel)}, {
            value: Object.freeze({
              postMessage: function (body) {
                return new Promise(function (resolve, reject) {
                  var id = next++;
                  var text = String(body);
                  pending[id] = [resolve, reject];
                  if (taken) { request(id, text); } else { queued.push([id, text]); }
                });
              }
            }),
            writable: false, configurable: false, enumerable: false
          });
          offer();
        })();
    """.trimIndent()

    /**
     * Linux, relay side: runs in the [LINUX_WORLD] script world of the top frame, where the
     * with-reply message handler is registered. It takes the first pipe a hello offers it, cancels
     * that hello to tell the shim so, and from then on listens on the pipe alone. It stamps every
     * message with `window.origin` and whether its window is the top one, both read through this
     * world's own bindings. The handler's promise belongs to this document, so WebKit never
     * delivers a reply to a later one.
     */
    fun linuxRelay(channel: String): String = """
        (function () {
          var handlers = window.webkit && window.webkit.messageHandlers;
          var h = handlers && handlers[${js(channel)}];
          if (!h) { return; }
          var post = h.postMessage.bind(h);
          var pipe = null;
          window.addEventListener(${js(linuxHelloEvent(channel))}, function (e) {
            e.stopImmediatePropagation();
            if (pipe !== null || !e.relatedTarget) { return; }
            pipe = e.relatedTarget;
            e.preventDefault();
            pipe.addEventListener("request", function (r) {
              var m;
              try { m = JSON.parse(r.detail); } catch (x) { return; }
              if (!m || typeof m.id !== "number" || typeof m.body !== "string") { return; }
              var id = m.id;
              function settle(ok, value) {
                pipe.dispatchEvent(new CustomEvent("reply", { detail: JSON.stringify({ id: id, ok: ok, value: value }) }));
              }
              post(JSON.stringify({ origin: window.origin, isMainFrame: window.top === window, body: m.body })).then(
                function (reply) {
                  if (typeof reply === "string") { settle(true, reply); } else { settle(false, "No reply"); }
                },
                function (error) { settle(false, String((error && error.message) || error)); });
            });
          }, true);
          window.dispatchEvent(new CustomEvent(${js(linuxRelayEvent(channel))}));
        })();
    """.trimIndent()

    /** The relay's message to the host. Only [linuxRelay] can reach the handler it arrives on. */
    fun parseLinuxEnvelope(raw: String): LinuxEnvelope? {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val origin = (obj["origin"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val isMainFrame = (obj["isMainFrame"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return LinuxEnvelope(origin, isMainFrame, body)
    }

    fun parseWindowsEnvelope(raw: String, channel: String): WindowsEnvelope? {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val tag = (obj[CHANNEL_TAG] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (tag != channel) return null
        val document = (obj["doc"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return WindowsEnvelope(id, body, document)
    }

    /** Runs in whatever document is current; only resolves if it is the sending document (same origin and token). */
    fun windowsReplyScript(channel: String, origin: String, document: String, id: Long, payload: String): String =
        "(function(){if(location.origin !== ${js(origin)}){return;}" +
            "var r=window[${js(resolverName(channel))}];if(typeof r==='function'){r(${js(document)}, $id, ${js(payload)});}})();"
}
