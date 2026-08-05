package com.rokid.inbox.nexus.contacts

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.inbox.nexus.InboxConfigStore
import com.rokid.inbox.nexus.channels.ContactResolver
import java.io.File

/**
 * On-device contact directory synced from a CardDAV account (e.g. Dex, iCloud,
 * Google, Nextcloud). Cross-references a WhatsApp chat number against the saved
 * address-book name so chats show "Levi Nobrega" instead of "Contato 684412".
 *
 * Scale: designed for thousands of contacts. The synced cards live in a private
 * JSON file; from them we build an in-memory `number-key -> name` index (a few
 * ms to load). Syncing is incremental (CardDAV sync-token), so after the first
 * pull each refresh transfers only deltas. WhatsApp `@lid` privacy JIDs carry no
 * phone number themselves, so we learn each `@lid -> phone-JID` mapping from the
 * `remoteJidAlt` field on messages/chats and cache it here, then resolve through
 * the same number index. Nothing leaves the phone.
 */
class ContactDirectory(context: Context) : ContactResolver {

    private val appContext = context.applicationContext
    private val config = InboxConfigStore(appContext)
    private val gson = Gson()

    private val recordsFile = File(appContext.filesDir, RECORDS_FILE)
    private val lidFile = File(appContext.filesDir, LID_FILE)

    // href -> record; number-key -> name (rebuilt from records).
    @Volatile private var records: MutableMap<String, DavRecord> = HashMap()
    @Volatile private var index: Map<String, String> = emptyMap()

    private val lidMap = HashMap<String, String>()   // lidJid -> phoneJid
    private var lidDirty = false
    private val lock = Any()

    data class DavRecord(val name: String, val numbers: List<String>)

    data class SyncSummary(val contacts: Int, val numbers: Int, val message: String)

    init {
        loadRecords()
        loadLid()
    }

    override val ready: Boolean get() = index.isNotEmpty()

    val contactCount: Int get() = records.size
    val numberCount: Int get() = index.size

    /* ---------------- resolution ---------------- */

    override fun nameForJid(jid: String, altJid: String?): String? {
        val phone = phoneForJid(jid, altJid) ?: return null
        return nameForNumber(phone)
    }

    override fun phoneForJid(jid: String, altJid: String?): String? {
        if (jid.isBlank()) return null
        val phoneJid = when {
            jid.endsWith("@s.whatsapp.net") -> jid
            jid.endsWith("@lid") -> {
                if (altJid != null && altJid.endsWith("@s.whatsapp.net")) { noteAlt(jid, altJid); altJid }
                else synchronized(lock) { lidMap[jid] }
            }
            else -> null
        } ?: return null
        val digits = phoneJid.substringBefore('@').substringBefore(':').filter { it.isDigit() }
        return digits.ifBlank { null }
    }

    fun nameForNumber(number: String): String? {
        val idx = index
        for (key in PhoneKey.candidates(number)) idx[key]?.let { return it }
        return null
    }

    override fun noteAlt(jid: String, altJid: String?) {
        if (!jid.endsWith("@lid") || altJid == null || !altJid.endsWith("@s.whatsapp.net")) return
        synchronized(lock) {
            if (lidMap[jid] != altJid) { lidMap[jid] = altJid; lidDirty = true }
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (!lidDirty) return
            runCatching { lidFile.writeText(gson.toJson(lidMap)) }
                .onFailure { Log.w(TAG, "lid cache write failed", it) }
            lidDirty = false
        }
    }

    /* ---------------- sync ---------------- */

    /** Pull the CardDAV account and rebuild the index. Runs on a background thread. */
    fun sync(server: String, username: String, password: String): SyncSummary {
        val client = CardDavClient(server, username, password)
        val collection = config.getCardDavCollection().ifBlank {
            client.discoverCollection().also { config.setCardDavCollection(it) }
        }
        val token = config.getCardDavSyncToken().ifBlank { null }
        val result = client.sync(collection, token)

        val merged: MutableMap<String, DavRecord> = if (result.full) HashMap() else HashMap(records)
        for ((href, c) in result.changed) merged[href] = DavRecord(c.name, c.phones)
        for (href in result.removed) merged.remove(href)

        synchronized(lock) {
            records = merged
            index = buildIndex(merged)
            saveRecords()
        }
        result.newToken?.let { config.setCardDavSyncToken(it) }
        config.setCardDavLastSync(System.currentTimeMillis())
        return SyncSummary(records.size, index.size, "Sincronizado: ${records.size} contatos")
    }

    /** Forget the local directory (used when the account is cleared). */
    fun clearDirectory() {
        synchronized(lock) {
            records = HashMap(); index = emptyMap()
            runCatching { recordsFile.delete() }
        }
        config.setCardDavCollection(""); config.setCardDavSyncToken(""); config.setCardDavLastSync(0)
    }

    /* ---------------- persistence ---------------- */

    private fun buildIndex(recs: Map<String, DavRecord>): Map<String, String> {
        val map = HashMap<String, String>(recs.size * 3)
        for (r in recs.values) {
            if (r.name.isBlank()) continue
            for (num in r.numbers) for (key in PhoneKey.candidates(num)) map[key] = r.name
        }
        return map
    }

    private fun loadRecords() {
        val recs = runCatching {
            if (!recordsFile.exists()) null
            else gson.fromJson<MutableMap<String, DavRecord>>(
                recordsFile.readText(), object : TypeToken<MutableMap<String, DavRecord>>() {}.type,
            )
        }.getOrNull() ?: HashMap()
        records = recs
        index = buildIndex(recs)
    }

    private fun saveRecords() {
        runCatching { recordsFile.writeText(gson.toJson(records)) }
            .onFailure { Log.w(TAG, "records write failed", it) }
    }

    private fun loadLid() {
        val m = runCatching {
            if (!lidFile.exists()) null
            else gson.fromJson<HashMap<String, String>>(
                lidFile.readText(), object : TypeToken<HashMap<String, String>>() {}.type,
            )
        }.getOrNull()
        if (m != null) synchronized(lock) { lidMap.putAll(m) }
    }

    private companion object {
        const val TAG = "ContactDirectory"
        const val RECORDS_FILE = "contacts_dav.json"
        const val LID_FILE = "lid_pn.json"
    }
}
