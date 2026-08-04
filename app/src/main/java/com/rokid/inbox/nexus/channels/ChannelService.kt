package com.rokid.inbox.nexus.channels

import com.rokid.inbox.nexus.model.ChannelKind
import com.rokid.inbox.nexus.model.Chat
import com.rokid.inbox.nexus.model.Message

/**
 * Common contract every channel (WhatsApp, Telegram, Gmail, GitHub) implements
 * so the unified inbox and the HUD surfaces stay channel-agnostic. Ported from
 * the original project's `ChannelService`; the voice-send method was dropped
 * because on-glasses mic capture is not available on Nexus.
 */
interface ChannelService {
    val kind: ChannelKind
    val boxId: String
    /** false = read-only (Gmail, GitHub): the surfaces hide the reply actions. */
    val canSend: Boolean
    /** Whether emoji reactions can be sent (WhatsApp, Telegram). */
    val canReact: Boolean get() = false
    /** Whether an original voice note can be sent (WhatsApp, Telegram). */
    val canSendVoice: Boolean get() = false
    /** Whether an image can be sent (WhatsApp, Telegram). */
    val canSendImage: Boolean get() = false

    /** Messages are returned oldest-first (chronological, oldest at top). */
    suspend fun listChats(limit: Int = 20): List<Chat>
    suspend fun listMessages(chatId: String, limit: Int = 20): List<Message>

    /** `replyToId` quotes a specific message when non-blank. */
    suspend fun sendText(chatId: String, text: String, replyToId: String = "", replyFromMe: Boolean = false)

    /** Send an original voice note (16 kHz mono PCM WAV). Only WhatsApp/Telegram support it. */
    suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String = "", replyFromMe: Boolean = false) {
        throw UnsupportedOperationException("Voice notes not supported")
    }

    /** Send a JPEG image. Only WhatsApp/Telegram support it. */
    suspend fun sendImage(chatId: String, jpeg: ByteArray, caption: String = "", replyToId: String = "", replyFromMe: Boolean = false) {
        throw UnsupportedOperationException("Images not supported")
    }
    suspend fun sendReaction(chatId: String, messageId: String, emoji: String, fromMe: Boolean) {
        throw UnsupportedOperationException("Reactions not supported")
    }
    suspend fun markAsRead(chatId: String, messages: List<Message>)

    /** Download the raw media bytes of a message (voice/audio or image), or null. */
    suspend fun fetchMedia(chatId: String, message: Message): ByteArray? = null

    /** Lightweight credential/connectivity probe used by the settings screen. */
    suspend fun ping()
    suspend fun disconnect() {}
}
