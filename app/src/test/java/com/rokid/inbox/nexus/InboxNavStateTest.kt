package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.model.Chat
import com.rokid.inbox.nexus.model.Message
import com.rokid.inbox.nexus.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxNavStateTest {

    private fun chat(id: String, unread: Int = 0, box: String = "b1") =
        Chat(boxId = box, id = id, name = "Chat $id", unreadCount = unread, boxLabel = "[W]")

    private fun withInbox(vararg chats: Chat): InboxNavState =
        InboxNavState().apply { setInbox(chats.toList()) }

    private fun openThread(
        s: InboxNavState,
        c: Chat,
        msgs: List<Message>,
        canSend: Boolean = true,
        canReact: Boolean = true,
        canVoice: Boolean = true,
        canImage: Boolean = false,
    ) = s.setConversation(c, msgs, atStart = true, canSend = canSend, canReact = canReact, canVoice = canVoice, canImage = canImage)

    @Test
    fun `list headers precede chats and map to the right action`() {
        val s = withInbox(chat("a"), chat("b"))
        assertTrue(s.activate() is InboxNavState.NavAction.CycleFilter) // row 0
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Refresh) // row 1
        s.move(1)
        val open = s.activate() // row 2 = first chat (voice search hidden: voiceEnabled off)
        assertTrue(open is InboxNavState.NavAction.OpenChat)
        assertEquals("a", (open as InboxNavState.NavAction.OpenChat).chat.id)
    }

    @Test
    fun `voice search header shows only when voice is enabled`() {
        val s = withInbox(chat("a"))
        s.setVoiceEnabled(true)
        s.move(2)
        assertTrue(s.activate() is InboxNavState.NavAction.VoiceSearch)
        s.move(1)
        assertTrue(s.activate() is InboxNavState.NavAction.OpenChat) // chat now at row 3
    }

    @Test
    fun `filter toggle hides read chats`() {
        val s = withInbox(chat("a", unread = 0), chat("b", unread = 3))
        s.cycleFilter()
        assertEquals(InboxNavState.Filter.Unread, s.filter)
        s.move(2) // 2 headers + only chat "b"
        val open = s.activate()
        assertEquals("b", (open as InboxNavState.NavAction.OpenChat).chat.id)
    }

    @Test
    fun `filter cycles through each connected channel then back to All`() {
        val wa = chat("a").copy(channel = com.rokid.inbox.nexus.model.ChannelKind.WHATSAPP)
        val tg = chat("b").copy(channel = com.rokid.inbox.nexus.model.ChannelKind.TELEGRAM)
        val s = withInbox(wa, tg)
        assertEquals(InboxNavState.Filter.All, s.filter)
        s.cycleFilter(); assertEquals(InboxNavState.Filter.Unread, s.filter)
        // Then one step per connected channel, in canonical order (WhatsApp before Telegram).
        s.cycleFilter(); assertEquals(InboxNavState.Filter.Channel(com.rokid.inbox.nexus.model.ChannelKind.WHATSAPP), s.filter)
        s.cycleFilter(); assertEquals(InboxNavState.Filter.Channel(com.rokid.inbox.nexus.model.ChannelKind.TELEGRAM), s.filter)
        s.cycleFilter(); assertEquals(InboxNavState.Filter.All, s.filter) // wraps
        // Channel filter shows only that channel's chats.
        s.cycleFilter(); s.cycleFilter() // -> WhatsApp
        assertEquals(1, s.screen().rows.count { it.text == "Chat a" })
        assertEquals(0, s.screen().rows.count { it.text == "Chat b" })
    }

    @Test
    fun `loading is visible at the top of the list, not only as an off-screen row`() {
        val s = withInbox(chat("a"), chat("b"))
        s.setLoading(true)
        val scr = s.screen()
        assertTrue("subtitle shows refreshing", scr.subtitle!!.contains("Atualizando"))
        assertTrue("refresh header row reads Atualizando", scr.rows.any { it.text == "Atualizando..." })
        // Clearing loading (via setInbox) restores the normal header + subtitle.
        s.setInbox(listOf(chat("a")))
        val after = s.screen()
        assertTrue(after.rows.any { it.text == "Atualizar" })
        assertTrue(after.subtitle!!.contains("·"))
    }

    @Test
    fun `list rows are rich with exactly one selected and a preview sub`() {
        val s = withInbox(chat("a").copy(lastMessagePreview = "hi there", unreadCount = 2))
        s.move(2) // focus the chat
        val rows = s.screen().rows
        assertEquals(1, rows.count { it.selected })
        val chatRow = rows.first { it.selected }
        assertEquals("hi there", chatRow.sub)
        assertEquals("[W]", chatRow.badge)
        assertEquals(InboxNavState.Tone.ALERT, chatRow.tone) // unread
    }

    @Test
    fun `opening a conversation focuses the newest message`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1", text = "old"), Message(id = "m2", text = "new")))
        assertEquals(InboxNavState.View.THREAD, s.view)
        val act = s.activate()
        assertEquals("m2", (act as InboxNavState.NavAction.OpenMessage).message.id)
    }

    @Test
    fun `read-only conversation exposes no reply row`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1", text = "hi")), canSend = false, canReact = false, canVoice = false)
        s.move(1) // single message row wraps to itself
        assertTrue(s.activate() is InboxNavState.NavAction.OpenMessage)
    }

    @Test
    fun `message actions reflect capabilities`() {
        val s = withInbox(chat("a"))
        val photo = Message(id = "p1", media = "[photo]", senderName = "X")
        openThread(s, chat("a"), listOf(photo))
        s.setAiConfigured(true)
        s.enterMessageActions(photo)
        // Image message: media actions first (Ver foto, Descrever), then Reagir, Responder citando.
        assertTrue(s.activate() is InboxNavState.NavAction.ViewPhoto)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Describe)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.React)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.ReplyQuoting)
    }

    @Test
    fun `view photo returns to the thread on back`() {
        val s = withInbox(chat("a"))
        val photo = Message(id = "p1", media = "[photo]")
        openThread(s, chat("a"), listOf(photo))
        s.enterMessageActions(photo)
        s.enterImage()
        assertEquals(InboxNavState.View.IMAGE, s.view)
        assertFalse(s.back())
        assertEquals(InboxNavState.View.THREAD, s.view)
    }

    @Test
    fun `quick picker offers voice dictation then canned messages`() {
        val s = withInbox(chat("a"))
        s.setVoiceEnabled(true)
        s.setQuickMessages(listOf(QuickMessage("Oi", "Ola!")))
        openThread(s, chat("a"), listOf(Message(id = "m1")))
        s.enterQuick(null)
        assertTrue(s.activate() is InboxNavState.NavAction.Dictate) // row 0
        s.move(1)
        val q = s.activate()
        assertEquals("Ola!", (q as InboxNavState.NavAction.SendQuick).quick.body)
    }

    @Test
    fun `review offers text and audio when both are available`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canVoice = true)
        s.enterListening(null)
        s.showReview(transcript = "comprar leite", hasAudio = true)
        assertEquals(InboxNavState.View.REVIEW, s.view)
        assertTrue(s.activate() is InboxNavState.NavAction.SendReplyText) // choice 0
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.SendReplyAudio) // choice 1
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Redictate) // choice 2
    }

    @Test
    fun `review with no transcript offers audio only`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canVoice = true)
        s.enterListening(null)
        s.showReview(transcript = "", hasAudio = true)
        assertTrue(s.activate() is InboxNavState.NavAction.SendReplyAudio) // choice 0 (no text choice)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Redictate)
    }

    @Test
    fun `review hides audio when the channel cannot send voice`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canVoice = false)
        s.enterListening(null)
        s.showReview(transcript = "oi", hasAudio = true)
        assertTrue(s.activate() is InboxNavState.NavAction.SendReplyText)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Redictate) // no audio choice
    }

    @Test
    fun `back pops the view stack and self-closes at the root`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")))
        s.enterMessageActions(Message(id = "m1"))
        assertFalse(s.back()); assertEquals(InboxNavState.View.THREAD, s.view)
        assertFalse(s.back()); assertEquals(InboxNavState.View.LIST, s.view)
        assertTrue(s.back())
    }

    @Test
    fun `voice search yields results that open a chat and back returns to list`() {
        val s = withInbox(chat("a"))
        s.setVoiceEnabled(true)
        s.enterVoiceSearch()
        assertEquals(InboxNavState.ListenPurpose.SEARCH, s.listenPurpose)
        assertTrue(s.activate() is InboxNavState.NavAction.StopListening)
        s.showSearchResults("maria", listOf(chat("x"), chat("y")))
        assertEquals(InboxNavState.View.SEARCH_RESULTS, s.view)
        assertEquals("x", (s.activate() as InboxNavState.NavAction.OpenChat).chat.id)
        assertFalse(s.back()); assertEquals(InboxNavState.View.LIST, s.view)
    }

    @Test
    fun `long message splits into several readable rows all mapping to it`() {
        val s = withInbox(chat("a"))
        val long = "palavra ".repeat(20).trim() // ~139 chars, well over one 3-line row
        openThread(s, chat("a"), listOf(Message(id = "m1", text = long, senderName = "X")), canSend = false, canReact = false, canVoice = false)
        val rows = s.screen().rows
        assertTrue("expected several chunk rows, got ${rows.size}", rows.size >= 3)
        val joined = rows.joinToString(" ") { it.text }
        assertTrue(joined.contains("palavra palavra")) // full text preserved across chunks
        assertEquals("m1", (s.activate() as InboxNavState.NavAction.OpenMessage).message.id)
    }

    @Test
    fun `action menu renders the full message above the actions`() {
        val s = withInbox(chat("a"))
        val long = "linha ".repeat(30).trim()
        val m = Message(id = "m1", text = long)
        openThread(s, chat("a"), listOf(m))
        s.enterMessageActions(m)
        val rows = s.screen().rows
        val body = rows.filter { it.tone == InboxNavState.Tone.BODY }
        assertTrue("message should be chunked across body rows", body.size >= 3)
        assertTrue(body.joinToString(" ") { it.text }.contains("linha linha"))
    }

    @Test
    fun `AI description pages a long text into dense plain-body pages`() {
        val s = InboxNavState()
        s.showInfo("Descricao (IA)", listOf((1..120).joinToString(" ") { "tok$it" }))
        assertEquals(InboxNavState.View.INFO, s.view)
        val p0 = s.screen()
        assertTrue("plain body, not rich rows", p0.rows.isEmpty())
        assertTrue("has body lines", p0.bodyLines != null && p0.bodyLines!!.isNotEmpty())
        assertTrue("page fits the line budget", p0.bodyLines!!.size <= 12)
        assertTrue("shows page position", p0.subtitle!!.contains("pagina 1/"))
        s.move(1)
        val p1 = s.screen()
        assertTrue("rotates to next page", p1.subtitle!!.contains("pagina 2/"))
        assertTrue("next page has different content", p0.bodyLines != p1.bodyLines)
    }

    @Test
    fun `voice message offers playback then AI transcription`() {
        val s = withInbox(chat("a"))
        val voice = Message(id = "v1", media = "[voice]", senderName = "X")
        openThread(s, chat("a"), listOf(voice))
        s.setAiConfigured(true)
        s.enterMessageActions(voice)
        // Order: Reproduzir audio, Transcrever (IA), Reagir, Responder citando.
        assertTrue(s.activate() is InboxNavState.NavAction.PlayAudio)
        s.move(1)
        val t = s.activate()
        assertTrue(t is InboxNavState.NavAction.Describe)
        assertEquals("v1", (t as InboxNavState.NavAction.Describe).message.id)
    }

    @Test
    fun `voice message offers playback even without an AI key`() {
        val s = withInbox(chat("a"))
        val voice = Message(id = "v1", media = "[audio]")
        openThread(s, chat("a"), listOf(voice), canReact = false) // aiConfigured stays false
        s.enterMessageActions(voice)
        assertTrue(s.activate() is InboxNavState.NavAction.PlayAudio) // no Transcrever row
    }

    @Test
    fun `reply picker offers take photo on channels that can send images`() {
        val s = withInbox(chat("a"))
        s.setVoiceEnabled(false)
        s.setQuickMessages(listOf(QuickMessage("Oi", "Ola!")))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canImage = true)
        s.enterQuick(null)
        // extras: [Tirar foto] (voice off), then the quick message.
        assertTrue(s.activate() is InboxNavState.NavAction.CapturePhoto)
        s.move(1)
        assertTrue(s.activate() is InboxNavState.NavAction.SendQuick)
    }

    @Test
    fun `photo preview sends on tap and discards on back to the picker`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canImage = true)
        s.enterQuick(null)
        s.enterPhotoPreview()
        assertEquals(InboxNavState.View.PHOTO_PREVIEW, s.view)
        assertTrue(s.activate() is InboxNavState.NavAction.SendPhoto) // tap = send
        assertFalse(s.back())
        assertEquals(InboxNavState.View.QUICK, s.view) // back discards, returns to picker
    }

    @Test
    fun `read-only channel never offers take photo`() {
        val s = withInbox(chat("a"))
        openThread(s, chat("a"), listOf(Message(id = "m1")), canSend = false, canImage = false)
        // canSend false -> no reply picker path; messageActionRows has no photo either.
        s.enterMessageActions(Message(id = "m1"))
        repeat(4) {
            assertTrue(s.activate() !is InboxNavState.NavAction.CapturePhoto)
            s.move(1)
        }
    }

    @Test
    fun `reaction view sends the chosen emoji`() {
        val s = InboxNavState()
        val m = Message(id = "m9")
        s.enterReact(m)
        val act = s.activate()
        assertTrue(act is InboxNavState.NavAction.SendReaction)
        assertEquals("m9", (act as InboxNavState.NavAction.SendReaction).message.id)
        assertEquals(s.reactions.first().first, act.emoji)
        assertNull(null)
    }
}
