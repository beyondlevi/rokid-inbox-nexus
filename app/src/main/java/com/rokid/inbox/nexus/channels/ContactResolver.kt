package com.rokid.inbox.nexus.channels

/**
 * Resolves a chat's saved (address-book) name from its WhatsApp JID, using a
 * locally-synced CardDAV directory. Kept as a channel-agnostic interface so the
 * channels stay decoupled from the contacts/CardDAV implementation and can be
 * unit-tested with a fake. All methods must be safe to call with a blank/unknown
 * JID (return null / no-op).
 */
interface ContactResolver {
    /** Saved name for this JID, or null when there is no matching contact. */
    fun nameForJid(jid: String, altJid: String?): String?

    /** The phone-number digits behind this JID (resolving @lid via [altJid]/cache), or null. */
    fun phoneForJid(jid: String, altJid: String?): String?

    /** Record a learned `@lid` -> phone-JID mapping (from a message's remoteJidAlt). */
    fun noteAlt(jid: String, altJid: String?)

    /** Persist any pending learned mappings; call once after a batch of lookups. */
    fun flush() {}

    /** Whether the directory has any contacts loaded. */
    val ready: Boolean
}
