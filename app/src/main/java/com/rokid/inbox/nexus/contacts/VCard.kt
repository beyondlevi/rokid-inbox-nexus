package com.rokid.inbox.nexus.contacts

/** A parsed contact: a display name plus every phone number found on the card. */
data class VCardContact(
    val name: String,
    val phones: List<String>,
)

/**
 * Minimal vCard reader — just the fields we need to map a phone number to a saved
 * name (FN, N as fallback, TEL). Handles RFC 6350 line folding and value escaping.
 * Deliberately tolerant: unknown properties and parameters are ignored, and a
 * single file may contain several `BEGIN:VCARD`/`END:VCARD` blocks.
 *
 * Pure Kotlin, unit-tested.
 */
object VCard {

    /** Parse one or more vCards from raw text. */
    fun parseAll(raw: String): List<VCardContact> {
        val out = ArrayList<VCardContact>()
        var current: MutableList<String>? = null
        for (line in unfold(raw)) {
            when {
                line.startsWith("BEGIN:VCARD", ignoreCase = true) -> current = ArrayList()
                line.startsWith("END:VCARD", ignoreCase = true) -> {
                    current?.let { parseOne(it)?.let(out::add) }
                    current = null
                }
                else -> current?.add(line)
            }
        }
        return out
    }

    /** Parse exactly one vCard block's already-unfolded property lines. */
    private fun parseOne(lines: List<String>): VCardContact? {
        var fn = ""
        var nName = ""
        val phones = LinkedHashSet<String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val prop = head.substringBefore(';').substringAfter('.').uppercase() // strip group. prefix + params
            when (prop) {
                "FN" -> if (fn.isBlank()) fn = unescape(value).trim()
                "N" -> if (nName.isBlank()) nName = nameFromStructured(value)
                "TEL" -> {
                    val v = value.substringAfter("tel:", value) // some cards use a tel: URI
                    val digits = v.filter { it.isDigit() || it == '+' }
                    if (digits.filter { it.isDigit() }.length >= 8) phones += digits
                }
            }
        }
        val name = fn.ifBlank { nName }.trim()
        if (name.isBlank() && phones.isEmpty()) return null
        return VCardContact(name = name, phones = phones.toList())
    }

    /** "Family;Given;Additional;Prefix;Suffix" -> "Given Family". */
    private fun nameFromStructured(value: String): String {
        val parts = value.split(';').map { unescape(it).trim() }
        val family = parts.getOrElse(0) { "" }
        val given = parts.getOrElse(1) { "" }
        return listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            parts.firstOrNull { it.isNotBlank() }.orEmpty()
        }
    }

    /**
     * Join folded continuation lines (a physical line starting with a space or tab
     * continues the previous one) into logical lines. Tolerant of CRLF and LF.
     */
    private fun unfold(raw: String): List<String> {
        val logical = ArrayList<String>()
        for (physical in raw.replace("\r\n", "\n").replace("\r", "\n").split('\n')) {
            if (physical.isEmpty()) continue
            if ((physical[0] == ' ' || physical[0] == '\t') && logical.isNotEmpty()) {
                logical[logical.lastIndex] = logical.last() + physical.substring(1)
            } else {
                logical.add(physical)
            }
        }
        return logical
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\N", "\n")
            .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
