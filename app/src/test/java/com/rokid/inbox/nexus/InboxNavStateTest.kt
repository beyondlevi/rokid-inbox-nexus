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

    private fun stateWithInbox(vararg chats: Chat): InboxNavState =
        InboxNavState().apply { setInbox(chats.toList()) }

    @Test
    fun `inbox header rows precede chats and select maps to the right action`() {
        val s = stateWithInbox(chat("a"), chat("b"))
        // Row 0 = filter, row 1 = refresh, row 2 = first chat.
        assertTrue(s.activate() is InboxNavState.NavAction.CycleFilter)
        s.move(1)
        assertTrue(s.activate() is InboxNavState.NavAction.Refresh)
        s.move(1)
        val open = s.activate()
        assertTrue(open is InboxNavState.NavAction.OpenChat)
        assertEquals("a", (open as InboxNavState.NavAction.OpenChat).chat.id)
    }

    @Test
    fun `NEXT and PREV wrap around the inbox`() {
        val s = stateWithInbox(chat("a"), chat("b")) // 2 headers + 2 chats = 4 rows
        s.move(-1) // PREV from row 0 wraps to last row (index 3)
        val last = s.activate()
        assertTrue(last is InboxNavState.NavAction.OpenChat)
        assertEquals("b", (last as InboxNavState.NavAction.OpenChat).chat.id)
        s.move(1) // NEXT wraps back to row 0
        assertTrue(s.activate() is InboxNavState.NavAction.CycleFilter)
    }

    @Test
    fun `filter toggle hides read chats`() {
        val s = stateWithInbox(chat("a", unread = 0), chat("b", unread = 3))
        s.cycleFilter() // ALL -> UNREAD
        assertEquals(InboxNavState.Filter.UNREAD, s.filter)
        // Only the unread chat "b" remains: row 2 opens it.
        s.move(2)
        val open = s.activate()
        assertTrue(open is InboxNavState.NavAction.OpenChat)
        assertEquals("b", (open as InboxNavState.NavAction.OpenChat).chat.id)
    }

    @Test
    fun `opening a conversation focuses the newest message and select opens its actions`() {
        val s = stateWithInbox(chat("a"))
        val msgs = listOf(
            Message(id = "m1", text = "old", senderName = "X"),
            Message(id = "m2", text = "new", senderName = "X"),
        )
        s.setConversation(chat("a"), msgs, atStart = true, canSend = true, canReact = true)
        assertEquals(InboxNavState.View.CHAT, s.view)
        val act = s.activate() // focus is on the newest message (m2)
        assertTrue(act is InboxNavState.NavAction.OpenMessage)
        assertEquals("m2", (act as InboxNavState.NavAction.OpenMessage).message.id)
    }

    @Test
    fun `read-only conversation exposes no reply row`() {
        val s = stateWithInbox(chat("a"))
        val msgs = listOf(Message(id = "m1", text = "hi"))
        s.setConversation(chat("a"), msgs, atStart = true, canSend = false, canReact = false)
        // Only the message row exists (no "Responder", atStart hides "Carregar mais").
        s.move(1) // wrap: single row -> stays / wraps to itself
        assertTrue(s.activate() is InboxNavState.NavAction.OpenMessage)
    }

    @Test
    fun `message actions reflect capabilities and media type`() {
        val s = stateWithInbox(chat("a"))
        val photo = Message(id = "p1", media = "[photo]", senderName = "X")
        s.setConversation(chat("a"), listOf(photo), atStart = true, canSend = true, canReact = true)
        s.setAiConfigured(true)
        s.enterMessageActions(photo)
        // Expected order: Ver foto, Descrever (IA), Reagir, Responder citando.
        assertTrue(s.activate() is InboxNavState.NavAction.ViewPhoto)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.Describe)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.React)
        s.move(1); assertTrue(s.activate() is InboxNavState.NavAction.ReplyQuoting)
    }

    @Test
    fun `quick reply picker sends the selected canned message`() {
        val s = stateWithInbox(chat("a"))
        s.setQuickMessages(listOf(QuickMessage("Oi", "Ola!"), QuickMessage("Tchau", "Ate mais")))
        s.setConversation(chat("a"), listOf(Message(id = "m1", text = "x")), atStart = true, canSend = true, canReact = false)
        s.enterQuick(null)
        s.move(1)
        val act = s.activate()
        assertTrue(act is InboxNavState.NavAction.SendQuick)
        assertEquals("Ate mais", (act as InboxNavState.NavAction.SendQuick).quick.body)
    }

    @Test
    fun `back pops the view stack and self-closes at the root`() {
        val s = stateWithInbox(chat("a"))
        s.setConversation(chat("a"), listOf(Message(id = "m1")), atStart = true, canSend = true, canReact = true)
        s.enterMessageActions(Message(id = "m1"))
        assertEquals(InboxNavState.View.MSG_ACTIONS, s.view)
        assertFalse(s.back()); assertEquals(InboxNavState.View.CHAT, s.view)
        assertFalse(s.back()); assertEquals(InboxNavState.View.INBOX, s.view)
        assertTrue(s.back()) // BACK at root = self-close
    }

    @Test
    fun `react view sends the chosen emoji for the selected message`() {
        val s = InboxNavState()
        val m = Message(id = "m9")
        s.enterReact(m)
        val act = s.activate()
        assertTrue(act is InboxNavState.NavAction.SendReaction)
        assertEquals("m9", (act as InboxNavState.NavAction.SendReaction).message.id)
        assertEquals(s.reactions.first().first, act.emoji)
    }

    @Test
    fun `inbox windows so the focused row is always rendered`() {
        val chats = (1..20).map { chat("c$it") }
        val s = InboxNavState().apply { setInbox(chats) }
        repeat(15) { s.move(1) } // walk deep past the first visible page
        val lines = s.screen().lines
        assertEquals(1, lines.count { it.startsWith("> ") }) // focus is rendered, exactly once
        assertTrue(lines.any { it.contains("acima") }) // "more above" indicator shown
        assertTrue(lines.size <= InboxNavState.VISIBLE_ROWS + 2) // window + up/down markers
    }

    @Test
    fun `opening a long conversation renders the newest focused message`() {
        val s = stateWithInbox(chat("a"))
        val msgs = (1..30).map { Message(id = "m$it", text = "msg $it", senderName = "X") }
        s.setConversation(chat("a"), msgs, atStart = true, canSend = false, canReact = false)
        val lines = s.screen().lines
        // The focused (newest) message must be inside the rendered window (finding #2).
        assertEquals(1, lines.count { it.startsWith("> ") })
        assertTrue(lines.any { it.startsWith("> ") && it.contains("msg 30") })
    }

    @Test
    fun `voice dictation row appears only when STT is enabled on a sendable chat`() {
        val s = stateWithInbox(chat("a"))
        s.setSttEnabled(true)
        s.setConversation(chat("a"), listOf(Message(id = "m1", text = "hi")), atStart = true, canSend = true, canReact = false)
        // Rows: [m1, "Responder", "Ditar por voz"]. Walk to the dictation row.
        s.move(1); s.move(1)
        val act = s.activate()
        assertTrue(act is InboxNavState.NavAction.Dictate)
        assertNull((act as InboxNavState.NavAction.Dictate).quoting)
    }

    @Test
    fun `read-only chat never offers voice dictation even with STT enabled`() {
        val s = stateWithInbox(chat("a"))
        s.setSttEnabled(true)
        s.setConversation(chat("a"), listOf(Message(id = "m1")), atStart = true, canSend = false, canReact = false)
        // Only the message row exists; SELECT can never yield a Dictate action.
        repeat(3) {
            assertTrue(s.activate() !is InboxNavState.NavAction.Dictate)
            s.move(1)
        }
    }

    @Test
    fun `dictation confirm flow sends the transcript or redictates`() {
        val s = InboxNavState()
        s.enterListening(null)
        assertEquals(InboxNavState.View.LISTENING, s.view)
        assertTrue(s.activate() is InboxNavState.NavAction.StopListening) // tap = stop+transcribe
        s.showTranscript("comprar leite")
        assertEquals(InboxNavState.View.CONFIRM_SEND, s.view)
        assertEquals("comprar leite", s.currentTranscript)
        assertTrue(s.activate() is InboxNavState.NavAction.SendTranscript) // row 0 = Enviar
        s.move(1)
        assertTrue(s.activate() is InboxNavState.NavAction.Redictate) // row 1 = Regravar
    }

    @Test
    fun `back while listening returns to the conversation`() {
        val s = stateWithInbox(chat("a"))
        s.setConversation(chat("a"), listOf(Message(id = "m1")), atStart = true, canSend = true, canReact = false)
        s.enterListening(null)
        assertFalse(s.back())
        assertEquals(InboxNavState.View.CHAT, s.view)
    }
}
