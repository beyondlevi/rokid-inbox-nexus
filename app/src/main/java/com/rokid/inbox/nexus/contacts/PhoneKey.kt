package com.rokid.inbox.nexus.contacts

/**
 * Brazil-aware phone-number matching. WhatsApp JIDs and address-book numbers are
 * written inconsistently (with/without the +55 country code, and — the big one —
 * with or without the mobile 9th digit). To cross-reference a chat number against
 * a CardDAV contact we expand each number into a small set of canonical keys and
 * match on any intersection.
 *
 * Pure Kotlin, no Android deps — unit-tested. Index every contact number under
 * ALL its [candidates]; at lookup, expand the chat number the same way and probe
 * each key. Because both sides generate the same expanded forms (e.g. both a
 * saved `+55 81 8221-8886` and a WhatsApp `558182218886` yield `5581982218886`),
 * the 9th-digit ambiguity resolves symmetrically.
 */
object PhoneKey {

    /** Digits that a legacy 8-digit BR subscriber could start with to be a mobile. */
    private val MOBILE_LEAD = setOf('6', '7', '8', '9')

    /**
     * Expand a raw phone string (any punctuation) into the set of canonical match
     * keys. Returns an empty set for anything too short to be a phone number.
     */
    fun candidates(raw: String?): Set<String> {
        var d = (raw ?: "").filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)   // intl call prefix
        if (d.length < 8) return emptySet()

        val out = LinkedHashSet<String>()
        out += d   // exact-digits key (covers non-BR numbers saved identically)

        // National significant number: drop a leading 55 country code when present.
        val local = if (d.startsWith("55") && d.length >= 12) d.substring(2) else d
        if (local.length != 10 && local.length != 11) return out

        val dd = local.substring(0, 2)
        val sub = local.substring(2) // subscriber: 8 (legacy) or 9 (with 9th digit)

        val forms = LinkedHashSet<String>()
        forms += sub
        when {
            sub.length == 9 && sub[0] == '9' -> forms += sub.substring(1)     // also 8-digit form
            sub.length == 8 && sub[0] in MOBILE_LEAD -> forms += "9$sub"      // also 9-digit form
        }
        for (s in forms) {
            out += "55$dd$s"   // full international
            out += "$dd$s"     // national (no country code)
        }
        return out
    }

    /**
     * The single preferred key for a number (used when we want one deterministic
     * label). Prefers the full international form with the 9th digit.
     */
    fun primary(raw: String?): String? {
        val c = candidates(raw)
        return c.firstOrNull { it.length == 13 && it.startsWith("55") }
            ?: c.firstOrNull { it.startsWith("55") }
            ?: c.firstOrNull()
    }
}
