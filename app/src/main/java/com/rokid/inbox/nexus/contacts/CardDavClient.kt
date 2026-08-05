package com.rokid.inbox.nexus.contacts

import android.util.Base64
import com.rokid.inbox.nexus.channels.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * A tiny, dependency-free CardDAV client — just enough to pull an address book
 * and keep it in sync at scale (~thousands of contacts). It:
 *
 *  1. discovers the address-book collection from the account URL
 *     (`current-user-principal` -> `addressbook-home-set` -> the addressbook), and
 *  2. syncs it. The primary path is a WebDAV **sync-collection** REPORT (RFC 6578):
 *     the server returns only what changed since the last `sync-token`, so after
 *     the first pull every open costs a handful of bytes. Changed cards are then
 *     fetched in batched **addressbook-multiget** REPORTs. If the server does not
 *     support sync-collection, we fall back to a full **addressbook-query**.
 *
 * All requests use HTTP Basic auth over the shared OkHttp client.
 */
class CardDavClient(
    serverUrl: String,
    username: String,
    password: String,
) {
    private val base = serverUrl.trim().trimEnd('/')
    private val auth = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
    )
    private val xmlType = "application/xml; charset=utf-8".toMediaType()
    // Own client with auto-redirects OFF so PROPFIND/REPORT redirects keep their method+body.
    private val davClient = Http.client.newBuilder()
        .followRedirects(false).followSslRedirects(false).build()

    data class SyncResult(
        val changed: Map<String, VCardContact>, // href -> contact
        val removed: List<String>,               // hrefs no longer present
        val newToken: String?,                   // opaque sync-token to persist
        val full: Boolean,                       // true = a full snapshot (caller must replace, not merge)
    )

    class CardDavException(message: String) : RuntimeException(message)

    /* ---------------- discovery ---------------- */

    /** Resolve the address-book collection URL, trying well-known then the account root. */
    fun discoverCollection(): String {
        val roots = buildList {
            add(base)
            add("$base/.well-known/carddav")
        }
        var principal: String? = null
        var lastErr: String? = null
        for (root in roots) {
            runCatching {
                val xml = propfind(root, depth = "0", PROP_CURRENT_USER_PRINCIPAL)
                principal = parseResponses(xml).firstNotNullOfOrNull { it.principalHref }
                    ?.let { resolve(root, it) }
            }.onFailure { lastErr = it.message }
            if (principal != null) break
        }
        val principalUrl = principal ?: throw CardDavException(
            "Nao encontrei o principal CardDAV (verifique servidor/usuario/senha). ${lastErr.orEmpty()}",
        )

        val homeXml = propfind(principalUrl, depth = "0", PROP_ADDRESSBOOK_HOME)
        val home = parseResponses(homeXml).firstNotNullOfOrNull { it.homeHref }
            ?.let { resolve(principalUrl, it) }
            ?: throw CardDavException("Conta sem addressbook-home-set.")

        val listXml = propfind(home, depth = "1", PROP_RESOURCETYPE)
        val collection = parseResponses(listXml)
            .firstOrNull { r -> r.resourceTypes.any { it.equals("addressbook", true) } }
            ?.let { resolve(home, it.href) }
            ?: throw CardDavException("Nenhuma agenda (addressbook) encontrada na conta.")
        return collection
    }

    /* ---------------- sync ---------------- */

    fun sync(collectionUrl: String, syncToken: String?): SyncResult {
        // Try incremental sync-collection first.
        runCatching { syncCollection(collectionUrl, syncToken) }
            .onSuccess { return it }
            .onFailure { /* fall back to a full query below */ }
        return fullQuery(collectionUrl)
    }

    private fun syncCollection(collectionUrl: String, syncToken: String?): SyncResult {
        val body = """
            <d:sync-collection xmlns:d="DAV:">
              <d:sync-token>${syncToken.orEmpty()}</d:sync-token>
              <d:sync-level>1</d:sync-level>
              <d:prop><d:getetag/></d:prop>
            </d:sync-collection>
        """.trimIndent()
        val xml = report(collectionUrl, depth = "1", body)
        val (responses, token) = parseResponsesWithToken(xml)
        val removed = ArrayList<String>()
        val changedHrefs = ArrayList<String>()
        for (r in responses) {
            if (r.href.isBlank()) continue
            if (r.status == 404) removed += r.href else changedHrefs += r.href
        }
        val changed = if (changedHrefs.isEmpty()) emptyMap() else multiget(collectionUrl, changedHrefs)
        return SyncResult(changed, removed, token, full = syncToken.isNullOrBlank() && removed.isEmpty() && token == null)
    }

    private fun fullQuery(collectionUrl: String): SyncResult {
        val body = """
            <c:addressbook-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
              <d:prop><d:getetag/><c:address-data/></d:prop>
              <c:filter/>
            </c:addressbook-query>
        """.trimIndent()
        val xml = report(collectionUrl, depth = "1", body)
        val changed = HashMap<String, VCardContact>()
        for (r in parseResponses(xml)) {
            if (r.href.isBlank() || r.addressData.isBlank()) continue
            VCard.parseAll(r.addressData).firstOrNull()?.let { changed[r.href] = it }
        }
        return SyncResult(changed, emptyList(), newToken = null, full = true)
    }

    /** Fetch the vCard data for a set of hrefs, batched to keep requests small. */
    private fun multiget(collectionUrl: String, hrefs: List<String>): Map<String, VCardContact> {
        val out = HashMap<String, VCardContact>()
        for (batch in hrefs.chunked(MULTIGET_BATCH)) {
            val hrefXml = batch.joinToString("") { "<d:href>${xmlEscape(it)}</d:href>" }
            val body = """
                <c:addressbook-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
                  <d:prop><d:getetag/><c:address-data/></d:prop>
                  $hrefXml
                </c:addressbook-multiget>
            """.trimIndent()
            val xml = report(collectionUrl, depth = "1", body)
            for (r in parseResponses(xml)) {
                if (r.href.isBlank() || r.addressData.isBlank()) continue
                VCard.parseAll(r.addressData).firstOrNull()?.let { out[r.href] = it }
            }
        }
        return out
    }

    /* ---------------- HTTP ---------------- */

    private fun propfind(url: String, depth: String, body: String): String =
        dav("PROPFIND", url, depth, body)

    private fun report(url: String, depth: String, body: String): String =
        dav("REPORT", url, depth, body)

    private fun dav(method: String, url: String, depth: String, body: String): String {
        var target = url
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder()
                .url(target)
                .header("Authorization", auth)
                .header("Depth", depth)
                .header("Content-Type", "application/xml; charset=utf-8")
                // A real User-Agent is mandatory: providers front their CardDAV with
                // Cloudflare, which bans the default/empty UA (HTTP 403, error 1010).
                .header("User-Agent", USER_AGENT)
                .method(method, body.toRequestBody(xmlType))
                .build()
            davClient.newCall(request).execute().use { res ->
                // Follow redirects ourselves so the method + body survive: OkHttp would
                // downgrade a 302 on PROPFIND/REPORT to a GET. The account root often
                // 30x-redirects to the real DAV path (e.g. "/" -> "/dav").
                if (res.code in REDIRECT_CODES) {
                    val loc = res.header("Location")?.let { resolve(target, it) }
                    res.body?.close()
                    if (loc != null && loc != target) { target = loc; return@repeat }
                }
                val text = res.body?.string().orEmpty()
                // 207 Multi-Status is the success code for PROPFIND/REPORT; 200 also seen.
                if (res.code != 207 && res.code != 200) {
                    throw CardDavException("CardDAV $method ${res.code}: ${text.take(200).ifBlank { res.message }}")
                }
                return text
            }
        }
        throw CardDavException("CardDAV $method: too many redirects")
    }

    /* ---------------- multistatus XML ---------------- */

    private data class Resp(
        var href: String = "",
        var status: Int? = null,
        var etag: String = "",
        var addressData: String = "",
        var displayName: String = "",
        val resourceTypes: MutableSet<String> = LinkedHashSet(),
        var principalHref: String = "",
        var homeHref: String = "",
    )

    private fun parseResponses(xml: String): List<Resp> = parseResponsesWithToken(xml).first

    /**
     * Walk a DAV multistatus response. Namespace-agnostic: we match on local names
     * (prefix stripped) so it works regardless of how the server prefixes DAV:/
     * carddav. Tracks a small element stack to attribute each <href> to the right
     * parent (response href vs. the one nested in current-user-principal etc.).
     */
    private fun parseResponsesWithToken(xml: String): Pair<List<Resp>, String?> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<Resp>()
        var syncToken: String? = null
        var cur: Resp? = null
        val stack = ArrayList<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = local(parser.name)
                    stack.add(name)
                    when (name) {
                        "response" -> cur = Resp()
                        "resourcetype" -> {} // children collected below
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (text.isBlank()) { /* skip */ } else {
                        val top = stack.lastOrNull()
                        val parent = stack.getOrNull(stack.size - 2)
                        val c = cur
                        when {
                            top == "sync-token" && c == null -> syncToken = text.trim()
                            c == null -> {}
                            top == "href" && parent == "response" -> if (c.href.isBlank()) c.href = text.trim()
                            top == "href" && parent == "current-user-principal" -> c.principalHref = text.trim()
                            top == "href" && parent == "addressbook-home-set" -> c.homeHref = text.trim()
                            top == "getetag" -> c.etag = text.trim()
                            top == "address-data" -> c.addressData += text
                            top == "displayname" -> c.displayName = text.trim()
                            top == "status" -> c.status = httpStatus(text) ?: c.status
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = local(parser.name)
                    // resourcetype child elements (e.g. <addressbook/>, <collection/>)
                    if (stack.size >= 2 && stack[stack.size - 2] == "resourcetype" && name != "resourcetype") {
                        cur?.resourceTypes?.add(name)
                    }
                    if (name == "response") { cur?.let { out.add(it) }; cur = null }
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
            event = parser.next()
        }
        return out to syncToken
    }

    private companion object {
        const val MULTIGET_BATCH = 80
        const val MAX_REDIRECTS = 6
        const val USER_AGENT = "RokidInboxNexus/2.4.0 (Android; CardDAV) okhttp/4.12.0"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        val PROP_CURRENT_USER_PRINCIPAL = """
            <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>
        """.trimIndent()
        val PROP_ADDRESSBOOK_HOME = """
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
              <d:prop><c:addressbook-home-set/></d:prop>
            </d:propfind>
        """.trimIndent()
        val PROP_RESOURCETYPE = """
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>
        """.trimIndent()

        fun local(qName: String?): String = (qName ?: "").substringAfterLast(':').lowercase()

        fun httpStatus(statusLine: String): Int? =
            Regex("\\b(\\d{3})\\b").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()

        fun xmlEscape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** Resolve a possibly-relative href against a base request URL. */
        fun resolve(baseUrl: String, href: String): String {
            if (href.startsWith("http://") || href.startsWith("https://")) return href
            val schemeEnd = baseUrl.indexOf("://")
            if (schemeEnd < 0) return href
            val pathStart = baseUrl.indexOf('/', schemeEnd + 3)
            val origin = if (pathStart < 0) baseUrl else baseUrl.substring(0, pathStart)
            return if (href.startsWith("/")) origin + href else "$origin/$href"
        }
    }
}
