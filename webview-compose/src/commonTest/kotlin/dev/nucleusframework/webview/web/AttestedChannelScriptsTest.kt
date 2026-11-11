// Unit tests for the pure page-shim and envelope helpers behind AttestedMessageChannel.

package dev.nucleusframework.webview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttestedChannelScriptsTest {

    @Test
    fun originOf_serialisesLikeABrowser() {
        assertEquals("https://dapp.example", AttestedChannelScripts.originOf("https://dapp.example/path?q=1#h"))
        assertEquals("https://dapp.example", AttestedChannelScripts.originOf("HTTPS://Dapp.Example:443/"))
        assertEquals("http://localhost:8080", AttestedChannelScripts.originOf("http://localhost:8080/x"))
        assertEquals("http://localhost", AttestedChannelScripts.originOf("http://localhost:80/"))
        assertEquals("https://[::1]:8443", AttestedChannelScripts.originOf("https://[::1]:8443/"))
        assertEquals("https://a.example", AttestedChannelScripts.originOf("https://user:pw@a.example/"))
    }

    @Test
    fun originOf_isNullForOpaqueOrMissingUrls() {
        assertEquals("null", AttestedChannelScripts.originOf("data:text/html,hi"))
        assertEquals("null", AttestedChannelScripts.originOf("about:blank"))
        assertEquals("null", AttestedChannelScripts.originOf("blob:https://a.example/uuid"))
        assertEquals("null", AttestedChannelScripts.originOf(""))
        assertEquals("null", AttestedChannelScripts.originOf(null))
        assertEquals("null", AttestedChannelScripts.originOf("not a url"))
    }

    @Test
    fun originOf_failsClosedOnAmbiguousAuthorities() {
        for (url in listOf(
            "https://evil.example\\@good.example/",
            "https://a@evil@good.example/",
            "https://a.example\\b/",
            "https://a .example/",
            "https://a.example\t/",
            "https://a.example\u0000/",
            "https://a.example:abc/",
            "https://a.example:/",
            "https://[::1]:x/",
            "https://:443/",
        )) {
            assertEquals("null", AttestedChannelScripts.originOf(url), url)
        }
    }

    @Test
    fun parseWindowsMessage_readsRequestsAndHellosOfThisChannelOnly() {
        val request = """{"__nucleusChannel":"wallet","doc":"d0c","key":"k3y","id":7,"body":"{\"r\":1}"}"""
        assertEquals(
            WindowsMessage.Request(document = "d0c", key = "k3y", id = 7, body = """{"r":1}"""),
            AttestedChannelScripts.parseWindowsMessage(request, "wallet"),
        )
        val hello = """{"__nucleusChannel":"wallet","doc":"d0c","hello":"https://dapp.example"}"""
        assertEquals(
            WindowsMessage.Hello(document = "d0c", origin = "https://dapp.example"),
            AttestedChannelScripts.parseWindowsMessage(hello, "wallet"),
        )
        assertNull(AttestedChannelScripts.parseWindowsMessage(request, "other"), "wrong channel")
        assertNull(AttestedChannelScripts.parseWindowsMessage(hello, "other"), "wrong channel")
        assertNull(AttestedChannelScripts.parseWindowsMessage("not json", "wallet"))
        assertNull(AttestedChannelScripts.parseWindowsMessage("""{"type":"bridge"}""", "wallet"), "untagged traffic stays on ipc")
    }

    @Test
    fun parseWindowsMessage_marksMalformedTrafficOfThisChannelSoItNeverReachesIpc() {
        for ((raw, why) in listOf(
            """{"__nucleusChannel":"wallet","doc":"d","key":"k","body":"x"}""" to "no id",
            """{"__nucleusChannel":"wallet","doc":"d","key":"k","id":"1","body":"x"}""" to "id not a number",
            """{"__nucleusChannel":"wallet","doc":"d","key":"k","id":1,"body":5}""" to "body not a string",
            """{"__nucleusChannel":"wallet","doc":"d","id":1,"body":"x"}""" to "no key",
            """{"__nucleusChannel":"wallet","doc":"d","key":5,"id":1,"body":"x"}""" to "key not a string",
            """{"__nucleusChannel":"wallet","doc":"d","key":"","id":1,"body":"x"}""" to "empty key",
            """{"__nucleusChannel":"wallet","key":"k","id":1,"body":"x"}""" to "no doc",
            """{"__nucleusChannel":"wallet","doc":5,"key":"k","id":1,"body":"x"}""" to "doc not a string",
            """{"__nucleusChannel":"wallet","doc":"","key":"k","id":1,"body":"x"}""" to "empty doc",
            """{"__nucleusChannel":"wallet","doc":"d","hello":5}""" to "hello not a string",
            """{"__nucleusChannel":"wallet","doc":"","hello":"null"}""" to "hello with an empty doc",
            """{"__nucleusChannel":"wallet"}""" to "neither a hello nor a request",
        )) {
            assertEquals(WindowsMessage.Malformed, AttestedChannelScripts.parseWindowsMessage(raw, "wallet"), why)
        }
    }

    private fun session(capacity: Int = 4): WindowsChannelSession {
        var n = 0
        return WindowsChannelSession(newKey = { "key-${++n}" }, capacity = capacity)
    }

    @Test
    fun windowsSession_attestsADocumentThatReturnsTheKeyItWasHanded() {
        val s = session()
        val key = s.hello(generation = 3, document = "docA", claimedOrigin = "https://dapp.example", source = "https://dapp.example/page")
        assertEquals("key-1", key)
        assertEquals(
            "https://dapp.example",
            s.authenticate(document = "docA", key = "key-1", source = "https://dapp.example/other#x"),
        )
    }

    @Test
    fun windowsSession_refusesWhatTheTopDocumentWasNotHanded() {
        val s = session()
        s.hello(3, "docA", "https://dapp.example", "https://dapp.example/")
        s.hello(3, "docB", "https://dapp.example", "https://dapp.example/")
        assertNull(s.authenticate("docA", "guess", "https://dapp.example/"), "wrong key")
        assertNull(s.authenticate("docA", "key-2", "https://dapp.example/"), "another document's key")
        assertNull(s.authenticate("docA", "key-", "https://dapp.example/"), "part of the key")
        assertNull(s.authenticate("unknown", "key-1", "https://dapp.example/"), "no hello for this token")
        assertNull(
            s.authenticate("docA", "key-1", "https://frame.example/"),
            "the key from a document whose URL has another origin",
        )
        assertEquals("https://dapp.example", s.authenticate("docA", "key-1", "https://dapp.example/"), "the right key still works")
    }

    @Test
    fun windowsSession_forgetsEarlierDocumentsWhenANewOneLoads() {
        val s = session()
        s.hello(3, "docA", "https://dapp.example", "https://dapp.example/")
        assertEquals("key-2", s.hello(4, "docB", "https://dapp.example", "https://dapp.example/"))
        assertNull(s.authenticate("docA", "key-1", "https://dapp.example/"), "the previous document's key")
        assertEquals("https://dapp.example", s.authenticate("docB", "key-2", "https://dapp.example/"))
        assertNull(s.hello(3, "docC", "https://dapp.example", "https://dapp.example/"), "a hello from an earlier generation")
    }

    @Test
    fun windowsSession_handsEachDocumentTokenOneKeyAndCapsHowManyItTracks() {
        val s = session(capacity = 2)
        assertEquals("key-1", s.hello(1, "docA", "null", "data:text/html,x"))
        assertNull(s.hello(1, "docA", "https://dapp.example", "https://dapp.example/"), "a second hello cannot replace the first")
        assertEquals("null", s.authenticate("docA", "key-1", "data:text/html,x"))
        assertEquals("key-2", s.hello(1, "docB", "null", "about:blank"))
        assertNull(s.hello(1, "docC", "null", "about:blank"), "over capacity")
        assertEquals("null", s.authenticate("docA", "key-1", "data:text/html,x"), "the first document still authenticates")
    }

    @Test
    fun windowsSession_reportsTheDocumentsOwnOriginOnlyWhenItsUrlAgrees() {
        val s = session(capacity = 8)
        fun attested(claimed: String, source: String): String? {
            val doc = "doc-$claimed-$source"
            val key = s.hello(1, doc, claimed, source) ?: return null
            return s.authenticate(doc, key, source)
        }
        assertEquals("https://dapp.example", attested("https://dapp.example", "https://dapp.example/p"))
        assertEquals("http://localhost:8080", attested("http://localhost:8080", "http://localhost:8080/"))
        assertEquals("null", attested("null", "https://dapp.example/sandboxed"), "a sandboxed document is opaque whatever its URL")
        assertEquals("null", attested("null", "data:text/html,x"))
        assertEquals("null", attested("https://dapp.example", "blob:https://dapp.example/uuid"), "no URL origin to agree with")
        assertEquals("null", attested("https://bank.example", "https://dapp.example/"), "a claim the URL contradicts")
        assertEquals("null", attested("file://", "file:///C:/x.html"))
    }

    @Test
    fun windowsSession_comparesOriginsOfAnOpaqueDocumentByUrlToo() {
        val s = session()
        s.hello(1, "docA", "null", "https://dapp.example/sandboxed")
        assertEquals("null", s.authenticate("docA", "key-1", "https://dapp.example/sandboxed#frag"))
        assertNull(s.authenticate("docA", "key-1", "https://frame.example/"), "the key from a document at another origin's URL")
    }

    @Test
    fun windowsKeyScript_callsTheReceiverWithJsonLiterals() {
        val script = AttestedChannelScripts.windowsKeyScript("wallet", "tok\"en", "k</script>")
        assertTrue(AttestedChannelScripts.keyReceiverName("wallet") in script)
        assertTrue("(\"tok\\\"en\", \"k<\\/script>\")" in script, script)
        assertTrue("typeof r==='function'" in script, "nothing happens in a document without the shim")
    }

    @Test
    fun windowsReplyScript_guardsOnOriginAndEmbedsValuesAsJsonLiterals() {
        val script = AttestedChannelScripts.windowsReplyScript("wallet", "https://dapp.example", "tok\"en", 3, "a\"b</script>")
        assertTrue("location.origin !== \"https://dapp.example\"" in script, script)
        assertTrue(AttestedChannelScripts.resolverName("wallet") in script)
        assertTrue("\"a\\\"b<\\/script>\"" in script, "payload is a JSON string literal: $script")
        assertTrue("(\"tok\\\"en\", 3, " in script, "resolver called with (document, id, payload): $script")
    }

    @Test
    fun shims_installFrozenTopFrameOnlyObjects() {
        for (shim in listOf(AttestedChannelScripts.macosShim("wallet"), AttestedChannelScripts.windowsShim("wallet"))) {
            assertTrue("window.top !== window" in shim, "top frame only")
            assertTrue("Object.defineProperty(window, \"wallet\"" in shim)
            assertTrue("Object.freeze(" in shim)
            assertFalse("writable: true" in shim)
            assertFalse("configurable: true" in shim)
        }
        assertTrue("window.webkit.messageHandlers[\"wallet\"]" in AttestedChannelScripts.macosShim("wallet"))
        assertTrue(
            "h.postMessage.bind(h)" in AttestedChannelScripts.macosShim("wallet"),
            "the handler's postMessage is captured at document start, before page scripts run",
        )
        assertTrue("__nucleusChannel" in AttestedChannelScripts.windowsShim("wallet"))
        assertTrue(AttestedChannelScripts.resolverName("wallet") in AttestedChannelScripts.windowsShim("wallet"))
        val windows = AttestedChannelScripts.windowsShim("wallet")
        assertTrue("crypto.getRandomValues" in windows, "per-document token")
        assertTrue("doc === " in windows, "resolver checks the document token")
        assertTrue(AttestedChannelScripts.keyReceiverName("wallet") in windows)
        assertTrue("hello: origin" in windows, "the shim introduces itself with the origin it read at document start")
        assertTrue("String(window.origin)" in windows)
        assertTrue("doc !== token || key !== null" in windows, "the key is accepted once, for this document's token")
        assertTrue("key: key" in windows, "every request carries the key")
        assertTrue("e.isTrusted && e.persisted" in windows, "a document restored from the back/forward cache introduces itself again")
    }

    @Test
    fun parseLinuxEnvelope_acceptsOnlyWellFormedRelayMessages() {
        val ok = """{"origin":"https://dapp.example","isMainFrame":true,"body":"{\"r\":1}"}"""
        assertEquals(
            LinuxEnvelope("https://dapp.example", true, """{"r":1}"""),
            AttestedChannelScripts.parseLinuxEnvelope(ok),
        )
        assertEquals(
            LinuxEnvelope("null", false, ""),
            AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","isMainFrame":false,"body":""}"""),
        )
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"isMainFrame":true,"body":"x"}"""), "no origin")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":5,"isMainFrame":true,"body":"x"}"""), "origin not a string")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","body":"x"}"""), "no isMainFrame")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","isMainFrame":"true","body":"x"}"""), "isMainFrame not a boolean")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","isMainFrame":1,"body":"x"}"""), "isMainFrame not a boolean")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","isMainFrame":true}"""), "no body")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("""{"origin":"null","isMainFrame":true,"body":5}"""), "body not a string")
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("not json"))
        assertNull(AttestedChannelScripts.parseLinuxEnvelope("\"a string\""))
    }

    @Test
    fun linuxEnvelope_keepsTextOutsideTheBasicPlane() {
        val raw = """{"origin":"null","isMainFrame":true,"body":"hi\uD83E\uDD8Axyz"}"""
        assertEquals("hi\uD83E\uDD8Axyz", AttestedChannelScripts.parseLinuxEnvelope(raw)?.body)
    }

    @Test
    fun linuxShim_installsAFrozenTopFrameOnlyObjectThatTalksToTheRelay() {
        val shim = AttestedChannelScripts.linuxShim("wallet")
        assertTrue("window.top !== window" in shim, "top frame only")
        assertTrue("Object.defineProperty(window, \"wallet\"" in shim)
        assertTrue("Object.freeze(" in shim)
        assertFalse("writable: true" in shim)
        assertFalse("configurable: true" in shim)
        assertFalse("window.webkit" in shim, "the page world has no handler to call")
        for (event in listOf(
            AttestedChannelScripts.linuxHelloEvent("wallet"),
            AttestedChannelScripts.linuxRelayEvent("wallet"),
        )) {
            assertTrue("\"$event\"" in shim, "shim uses $event")
        }
        assertTrue("window.dispatchEvent.bind(window)" in shim, "dispatchEvent is captured before page scripts run")
        assertTrue("var pipe = new EventTarget();" in shim, "the pipe is an object no document holds")
        assertTrue("relatedTarget: pipe" in shim, "the hello hands the pipe to the relay")
        assertTrue("if (taken) { return; }" in shim, "a pipe a relay has taken is not offered again")
        assertTrue("pipe.dispatchEvent.bind(pipe)" in shim, "requests leave on the pipe")
        assertTrue("pipe.addEventListener(\"reply\"" in shim, "replies arrive on the pipe")
        assertFalse("listen(\"reply\"" in shim, "no reply is taken from window, where a page script can dispatch one")
        assertEquals(1, Regex("stopImmediatePropagation\\(\\)").findAll(shim).count(), "the relay's announcement stops at the shim")
    }

    @Test
    fun linuxRelay_stampsTheSenderItselfAndTakesRequestsFromItsPipeOnly() {
        val relay = AttestedChannelScripts.linuxRelay("wallet")
        assertTrue("handlers[\"wallet\"]" in relay)
        assertTrue("h.postMessage.bind(h)" in relay, "the handler is captured when the relay starts")
        assertTrue("origin: window.origin" in relay, "origin read in the relay's own world")
        assertTrue("isMainFrame: window.top === window" in relay, "frame read in the relay's own world")
        assertTrue("\"${AttestedChannelScripts.linuxHelloEvent("wallet")}\"" in relay, "the relay waits for the shim's hello")
        assertTrue("\"${AttestedChannelScripts.linuxRelayEvent("wallet")}\"" in relay, "the relay announces itself to a shim that started first")
        assertTrue("if (pipe !== null || !e.relatedTarget) { return; }" in relay, "the first pipe is the only one")
        assertTrue("e.preventDefault();" in relay, "the relay tells the shim it took the pipe by cancelling the hello")
        assertTrue("pipe.addEventListener(\"request\"" in relay, "requests are taken from the pipe")
        assertFalse("window.addEventListener(\"request\"" in relay, "no request is taken from window")
        assertTrue("typeof m.body !== \"string\"" in relay)
        assertEquals(1, Regex("stopImmediatePropagation\\(\\)").findAll(relay).count(), "the hello stops at the relay")
        assertFalse("m.origin" in relay, "nothing the page sends is used as the origin")
        assertFalse("m.isMainFrame" in relay, "nothing the page sends is used as the frame")
    }

    @Test
    fun linuxScripts_embedTheChannelNameAsAJsonLiteral() {
        val name = "wal\$let"
        assertTrue("\"wal\$let\"" in AttestedChannelScripts.linuxShim(name))
        assertTrue("\"__nucleusChannelHello_wal\$let\"" in AttestedChannelScripts.linuxRelay(name))
    }

    @Test
    fun channelNames_mustBeJsIdentifiers() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { AttestedMessageChannel("bad name") { _, _ -> } }
        kotlin.test.assertFailsWith<IllegalArgumentException> { AttestedMessageChannel("ipc") { _, _ -> } }
        AttestedMessageChannel("coinomiWallet") { _, _ -> }
    }

    @Test
    fun channelNames_mustDifferFromTheJsBridgeName() {
        val channel = AttestedMessageChannel("kmpJsBridge") { _, _ -> }
        kotlin.test.assertFailsWith<IllegalArgumentException> { channel.requireDistinctFromJsBridge("kmpJsBridge") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { channel.requireDistinctFromJsBridge(" kmpJsBridge ") }
        channel.requireDistinctFromJsBridge("otherBridge")
        channel.requireDistinctFromJsBridge(null)
    }
}
