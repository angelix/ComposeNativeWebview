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
    fun parseWindowsEnvelope_acceptsOnlyThisChannelsWellFormedMessages() {
        val ok = """{"__nucleusChannel":"wallet","doc":"d0c","id":7,"body":"{\"r\":1}"}"""
        assertEquals(WindowsEnvelope(7, """{"r":1}""", "d0c"), AttestedChannelScripts.parseWindowsEnvelope(ok, "wallet"))
        assertNull(AttestedChannelScripts.parseWindowsEnvelope(ok, "other"), "wrong channel")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","doc":"d","body":"x"}""", "wallet"), "no id")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","doc":"d","id":1,"body":5}""", "wallet"), "body not a string")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","doc":"d","id":"1","body":"x"}""", "wallet"), "id not a number")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","id":1,"body":"x"}""", "wallet"), "no doc")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","doc":5,"id":1,"body":"x"}""", "wallet"), "doc not a string")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"__nucleusChannel":"wallet","doc":"","id":1,"body":"x"}""", "wallet"), "empty doc")
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("not json", "wallet"))
        assertNull(AttestedChannelScripts.parseWindowsEnvelope("""{"type":"bridge"}""", "wallet"), "untagged traffic stays on ipc")
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
