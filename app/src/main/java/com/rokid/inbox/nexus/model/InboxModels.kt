package com.rokid.inbox.nexus.model

/**
 * Inbox domain types, ported from the original two-app project's
 * shared-contracts (`InboxContracts.kt`). The Bluetooth wire messages and voice
 * modes are intentionally dropped: on Nexus the glasses hub renders declarative
 * surfaces and the R08 ring drives navigation, so there is no phone<->glasses
 * wire protocol and no on-glasses mic capture.
 */

enum class ChannelKind {
    WHATSAPP,
    TELEGRAM,
    GMAIL,
    GITHUB,
}

enum class ChatType {
    USER,
    GROUP,
    CHANNEL,
}

/** A single conversation across any channel. `boxLabel` (e.g. "[W]" / "[W1]") is
 *  precomputed on the phone so the surfaces stay channel-agnostic. */
data class Chat(
    val channel: ChannelKind = ChannelKind.WHATSAPP,
    val boxId: String = "",
    val id: String = "",
    val name: String = "",
    val type: ChatType = ChatType.USER,
    val unreadCount: Int = 0,
    val lastMessageDate: String? = null,
    /** Short preview of the last message (first characters), for the chat list. */
    val lastMessagePreview: String = "",
    val boxLabel: String = "",
)

data class Message(
    val id: String = "",
    val text: String = "",
    val media: String? = null,
    val date: String? = null,
    val isOutgoing: Boolean = false,
    val senderName: String = "",
    /** Duration in seconds for voice/audio messages (0 otherwise). */
    val durationSec: Int = 0,
    /** Original file name for document messages (used for AI descriptions). */
    val fileName: String = "",
) {
    val isImageMedia: Boolean get() = media == "[photo]" || media == "[sticker]"
    val isDescribableFile: Boolean get() = media == "[file]"
    /** Can an AI text description be requested for this message? */
    val canDescribe: Boolean get() = isImageMedia || isDescribableFile
}

/** A canned reply configured on the phone and sent from the glasses. */
data class QuickMessage(
    val title: String = "",
    val body: String = "",
)
