package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.contacts.PhoneKey
import com.rokid.inbox.nexus.contacts.VCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-referencing core: BR-aware phone matching + vCard parsing. */
class ContactMatchingTest {

    private fun matches(a: String, b: String): Boolean =
        PhoneKey.candidates(a).intersect(PhoneKey.candidates(b)).isNotEmpty()

    @Test fun `BR mobile matches with or without the 9th digit, both directions`() {
        // WhatsApp exposes an 8-digit subscriber; the saved contact has the 9th digit.
        assertTrue(matches("558186623552", "+55 81 98662-3552"))
        assertTrue(matches("+55 81 98662-3552", "558186623552"))
        // And the reverse: WA has the 9, saved is legacy 8-digit.
        assertTrue(matches("5581986623552", "(81) 8662-3552"))
    }

    @Test fun `country code presence does not break matching`() {
        // Same subscriber written 3 ways: full intl, national, and the WA 8-digit form.
        assertTrue(matches("5581992658271", "81992658271"))
        assertTrue(matches("5581992658271", "558192658271"))
    }

    @Test fun `landline does not get a fake 9th digit`() {
        // 11 5128-0100 is a landline (subscriber starts with 5) — no 9-form is invented.
        val keys = PhoneKey.candidates("+55 11 5128-0100")
        assertTrue(keys.contains("551151280100"))
        assertFalse(keys.any { it.contains("5191") || it == "5511951280100" })
    }

    @Test fun `different numbers do not match`() {
        assertFalse(matches("558186623552", "558199265827"))
        assertFalse(matches("5511999990000", "5511888880000"))
    }

    @Test fun `too-short input yields no keys`() {
        assertTrue(PhoneKey.candidates("1234").isEmpty())
        assertNull(PhoneKey.primary("12"))
    }

    @Test fun `vCard parses FN and multiple phones, unfolding and tel URI`() {
        val raw = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Levi Nobr
             ega
            TEL;TYPE=CELL:+55 81 98662-3552
            TEL;TYPE=HOME:tel:+551151280100
            END:VCARD
        """.trimIndent()
        val list = VCard.parseAll(raw)
        assertEquals(1, list.size)
        assertEquals("Levi Nobrega", list[0].name) // folded continuation line joined
        assertEquals(2, list[0].phones.size)
    }

    @Test fun `vCard falls back to structured N when FN is absent`() {
        val raw = """
            BEGIN:VCARD
            VERSION:3.0
            N:Nobrega;Levi;;;
            TEL:+5581999990000
            END:VCARD
        """.trimIndent()
        val c = VCard.parseAll(raw).single()
        assertEquals("Levi Nobrega", c.name)
    }

    @Test fun `vCard reads several cards in one payload`() {
        val raw = """
            BEGIN:VCARD
            FN:A
            TEL:+5581999990001
            END:VCARD
            BEGIN:VCARD
            FN:B
            TEL:+5581999990002
            END:VCARD
        """.trimIndent()
        assertEquals(2, VCard.parseAll(raw).size)
    }
}
