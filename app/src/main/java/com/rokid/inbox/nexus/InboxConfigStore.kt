package com.rokid.inbox.nexus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.inbox.nexus.model.ChannelKind
import com.rokid.inbox.nexus.model.QuickMessage

/** A configured account/inbox plus its channel-specific credentials. */
data class BoxConfig(
    val id: String,
    val kind: ChannelKind,
    val name: String,
    val config: Map<String, String> = emptyMap(),
)

/**
 * On-device credential storage backed by EncryptedSharedPreferences (boxes list
 * + global OpenAI key + quick messages). Nothing leaves the phone. Uninstalling
 * the plugin removes this store and its state.
 */
class InboxConfigStore(context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun getBoxes(): List<BoxConfig> {
        val raw = prefs.getString(KEY_BOXES, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<BoxConfig>>(raw, object : TypeToken<List<BoxConfig>>() {}.type)
        }.getOrNull() ?: emptyList()
    }

    fun saveBoxes(list: List<BoxConfig>) {
        prefs.edit().putString(KEY_BOXES, gson.toJson(list)).apply()
    }

    fun addBox(box: BoxConfig) = saveBoxes(getBoxes() + box)

    fun renameBox(id: String, name: String) =
        saveBoxes(getBoxes().map { if (it.id == id) it.copy(name = name) else it })

    fun deleteBox(id: String) = saveBoxes(getBoxes().filterNot { it.id == id })

    fun getOpenAiKey(): String = prefs.getString(KEY_OPENAI, "").orEmpty()

    fun setOpenAiKey(key: String) {
        prefs.edit().putString(KEY_OPENAI, key.trim()).apply()
    }

    fun getQuickMessages(): List<QuickMessage> {
        val raw = prefs.getString(KEY_QUICK, null) ?: return DEFAULT_QUICK
        return runCatching {
            gson.fromJson<List<QuickMessage>>(
                raw,
                object : TypeToken<List<QuickMessage>>() {}.type,
            )
        }.getOrNull()?.filter { it.title.isNotBlank() && it.body.isNotBlank() } ?: DEFAULT_QUICK
    }

    fun setQuickMessages(list: List<QuickMessage>) {
        prefs.edit().putString(KEY_QUICK, gson.toJson(list)).apply()
    }

    /* ---------------- speech-to-text (voice dictation) ---------------- */

    fun isSttEnabled(): Boolean = prefs.getBoolean(KEY_STT_ENABLED, false)

    fun setSttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
    }

    /** Forced transcription language (ISO code); blank = auto-detect. */
    fun getSttLanguage(): String = prefs.getString(KEY_STT_LANG, "").orEmpty()

    fun setSttLanguage(code: String) {
        prefs.edit().putString(KEY_STT_LANG, code.trim()).apply()
    }

    fun getSttModel(): String = prefs.getString(KEY_STT_MODEL, SpeechToText.DEFAULT_MODEL).orEmpty()

    fun setSttModel(model: String) {
        prefs.edit().putString(KEY_STT_MODEL, model.trim().ifBlank { SpeechToText.DEFAULT_MODEL }).apply()
    }

    /* ---------------- CardDAV contact directory ---------------- */

    fun getCardDavServer(): String = prefs.getString(KEY_DAV_SERVER, "").orEmpty()
    fun getCardDavUser(): String = prefs.getString(KEY_DAV_USER, "").orEmpty()
    fun getCardDavPassword(): String = prefs.getString(KEY_DAV_PASS, "").orEmpty()

    fun setCardDavCredentials(server: String, user: String, password: String) {
        prefs.edit()
            .putString(KEY_DAV_SERVER, server.trim().trimEnd('/'))
            .putString(KEY_DAV_USER, user.trim())
            .putString(KEY_DAV_PASS, password) // keep as typed (passwords may have spaces)
            .apply()
    }

    /** True once a server + user + password are all configured. */
    fun hasCardDav(): Boolean =
        getCardDavServer().isNotBlank() && getCardDavUser().isNotBlank() && getCardDavPassword().isNotBlank()

    /** Discovered addressbook collection URL (cached after the first sync). */
    fun getCardDavCollection(): String = prefs.getString(KEY_DAV_COLLECTION, "").orEmpty()
    fun setCardDavCollection(url: String) { prefs.edit().putString(KEY_DAV_COLLECTION, url).apply() }

    fun getCardDavSyncToken(): String = prefs.getString(KEY_DAV_TOKEN, "").orEmpty()
    fun setCardDavSyncToken(token: String) { prefs.edit().putString(KEY_DAV_TOKEN, token).apply() }

    fun getCardDavLastSync(): Long = prefs.getLong(KEY_DAV_LAST_SYNC, 0L)
    fun setCardDavLastSync(epochMs: Long) { prefs.edit().putLong(KEY_DAV_LAST_SYNC, epochMs).apply() }

    /** Wipe CardDAV credentials + cached sync state (collection/token/last-sync). */
    fun clearCardDav() {
        prefs.edit()
            .remove(KEY_DAV_SERVER).remove(KEY_DAV_USER).remove(KEY_DAV_PASS)
            .remove(KEY_DAV_COLLECTION).remove(KEY_DAV_TOKEN).remove(KEY_DAV_LAST_SYNC)
            .apply()
    }

    private fun createPrefs(context: Context): SharedPreferences =
        runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "nexus_plugin_rokid_inbox_secure",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", it)
            context.getSharedPreferences("nexus_plugin_rokid_inbox", Context.MODE_PRIVATE)
        }

    companion object {
        private const val TAG = "InboxConfigStore"
        private const val KEY_BOXES = "boxes.v1"
        private const val KEY_OPENAI = "openai.key"
        private const val KEY_QUICK = "quick.v1"
        private const val KEY_STT_ENABLED = "stt.enabled"
        private const val KEY_STT_LANG = "stt.language"
        private const val KEY_STT_MODEL = "stt.model"
        private const val KEY_DAV_SERVER = "carddav.server"
        private const val KEY_DAV_USER = "carddav.user"
        private const val KEY_DAV_PASS = "carddav.pass"
        private const val KEY_DAV_COLLECTION = "carddav.collection"
        private const val KEY_DAV_TOKEN = "carddav.token"
        private const val KEY_DAV_LAST_SYNC = "carddav.lastSync"

        private val DEFAULT_QUICK = listOf(
            QuickMessage("Estou chegando", "Oi! Estou chegando, ja te encontro."),
            QuickMessage("Estou atrasado", "Desculpa, estou atrasado. Chego assim que puder."),
            QuickMessage("Te ligo ja", "Agora nao consigo falar, te ligo em instantes."),
            QuickMessage("Ok", "Ok!"),
        )

        fun newBoxId(kind: ChannelKind): String =
            "${kind.name.lowercase()}-${System.currentTimeMillis().toString(36)}${(0..9999).random().toString(36)}"
    }
}
