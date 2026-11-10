// Host-side state of the Windows attested channel for one WebView: which documents introduced
// themselves in the current top-level document generation, the key each was handed, and the origin
// each is attested with. Pure, so the protocol is unit-tested in commonTest.
package dev.nucleusframework.webview.web

/**
 * Tracks the documents of one WebView2 that said hello on the attested channel.
 *
 * WebView2 does not say whether a message came from the top-level document, so the host hands each
 * hello a key through a script that only the top-level document runs. A request that returns the
 * key under the token it was issued for comes from that document. Hellos from elsewhere are
 * answered too, but their keys never reach them.
 *
 * The document generation (a count of top-level documents) only retires what earlier documents
 * registered. A request is not held to the generation it arrives in: the token, the key and the
 * sending URL's origin already tie it to one document, and they do not depend on the order in
 * which WebView2 reports a new document and delivers that document's first message.
 *
 * Call from the UI thread only.
 */
internal class WindowsChannelSession(
    private val newKey: () -> String,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Document(val key: String, val origin: String, val urlOrigin: String)

    private var generation = Long.MIN_VALUE
    private val documents = HashMap<String, Document>()

    /**
     * Registers the document that introduced itself as [document] in [generation] and returns the
     * key to hand it, or null when the hello is ignored: it is from an earlier generation, repeats
     * a token, or arrives after [capacity] hellos in this generation.
     */
    fun hello(generation: Long, document: String, claimedOrigin: String, source: String): String? {
        if (generation < this.generation) return null
        if (generation > this.generation) {
            this.generation = generation
            documents.clear()
        }
        if (document in documents || documents.size >= capacity) return null
        val urlOrigin = AttestedChannelScripts.originOf(source)
        // The shim reads its own origin before any page script runs, so a sandboxed document says
        // "null" whatever its URL. A claim the URL does not bear out is not vouched for.
        val claimed = if (claimedOrigin == OPAQUE) OPAQUE else AttestedChannelScripts.originOf(claimedOrigin)
        val origin = if (claimed != OPAQUE && claimed == urlOrigin) claimed else OPAQUE
        val key = newKey()
        documents[document] = Document(key, origin, urlOrigin)
        return key
    }

    /**
     * The attested origin of the document that sent a request, or null when the request does not
     * prove it comes from the top-level document: unknown token, wrong key, or a sending URL whose
     * origin differs from the one the hello came from.
     */
    fun authenticate(document: String, key: String, source: String): String? {
        val known = documents[document] ?: return null
        if (!sameText(known.key, key)) return null
        if (AttestedChannelScripts.originOf(source) != known.urlOrigin) return null
        return known.origin
    }

    /** Compares every character, so timing does not reveal how much of a guessed key matched. */
    private fun sameText(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }

    private companion object {
        const val OPAQUE = "null"
        const val DEFAULT_CAPACITY = 8
    }
}
