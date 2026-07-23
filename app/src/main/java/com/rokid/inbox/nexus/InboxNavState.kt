package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.model.Chat
import com.rokid.inbox.nexus.model.Message
import com.rokid.inbox.nexus.model.QuickMessage

/**
 * The one-axis navigation state machine for the HUD, driven entirely by the
 * four R08 ring verbs (NEXT / PREV / SELECT / BACK). It is pure Kotlin (no
 * Android types) so it is unit-testable on the JVM — that test IS the proof of
 * ring-navigability, since the glasses hub renders the surface, not the plugin.
 *
 * The service/runtime owns all I/O; this class only tracks the ordered lists of
 * focusable rows per view, the selection index, and the view stack. `activate()`
 * reports the intent of a SELECT as a [NavAction] that the runtime dispatches;
 * data updates and view transitions come back in through the explicit
 * setter and enter methods.
 */
class InboxNavState {

    enum class View { INBOX, CHAT, MSG_ACTIONS, QUICK, REACT, INFO, IMAGE }
    enum class Filter { ALL, UNREAD }

    /** What a SELECT on the focused row means; the runtime executes it. */
    sealed interface NavAction {
        data object None : NavAction
        data object CycleFilter : NavAction
        data object Refresh : NavAction
        data class OpenChat(val chat: Chat) : NavAction
        data object LoadOlder : NavAction
        /** SELECT on a message row -> open its per-message action menu. */
        data class OpenMessage(val message: Message) : NavAction
        /** Chat-level reply (no quote) -> quick-message picker. */
        data object ReplyToChat : NavAction
        /** Reply quoting the currently selected message. */
        data class ReplyQuoting(val message: Message) : NavAction
        data class React(val message: Message) : NavAction
        data class ViewPhoto(val message: Message) : NavAction
        data class Describe(val message: Message) : NavAction
        data class SendQuick(val quick: QuickMessage) : NavAction
        data class SendReaction(val message: Message, val emoji: String) : NavAction
    }

    /** A ready-to-render card model; the service hashes [keySeed] into contentKey. */
    data class Screen(
        val title: String,
        val lines: List<String>,
        val footer: String,
        val keySeed: String,
    )

    var view: View = View.INBOX
        private set

    // Data
    private var allChats: List<Chat> = emptyList()
    var filter: Filter = Filter.ALL
        private set
    var openChat: Chat? = null
        private set
    private var messages: List<Message> = emptyList()
    private var atStart: Boolean = true
    private var canSendOpen: Boolean = false
    private var canReactOpen: Boolean = false
    private var aiConfigured: Boolean = false
    private var quickMessages: List<QuickMessage> = emptyList()
    private var selectedMessage: Message? = null
    private var quoting: Message? = null
    private var infoTitle: String = ""
    private var infoLines: List<String> = emptyList()
    private var statusLine: String? = null

    // Per-view selection (kept so BACK restores the parent's focus)
    private var inboxIndex = 0
    private var chatIndex = 0
    private var actionsIndex = 0
    private var quickIndex = 0
    private var reactIndex = 0

    val reactions: List<Pair<String, String>> = listOf(
        "👍" to "Curtir",
        "❤️" to "Amei",
        "😂" to "Haha",
        "😮" to "Uau",
        "😢" to "Triste",
        "🙏" to "Obrigado",
    )

    /* ---------------- data intake (from the runtime) ---------------- */

    fun setAiConfigured(value: Boolean) { aiConfigured = value }
    fun setQuickMessages(list: List<QuickMessage>) { quickMessages = list }

    fun setInbox(chats: List<Chat>) {
        allChats = chats
        clampInboxIndex()
    }

    /** Transient one-line status shown under the current view (e.g. "Carregando..."). */
    fun setStatus(line: String?) { statusLine = line }

    fun setConversation(chat: Chat, msgs: List<Message>, atStart: Boolean, canSend: Boolean, canReact: Boolean) {
        openChat = chat
        messages = msgs
        this.atStart = atStart
        canSendOpen = canSend
        canReactOpen = canReact
        statusLine = null
        view = View.CHAT
        chatIndex = (messages.size - 1).coerceAtLeast(0) // focus newest message
    }

    fun showInfo(title: String, lines: List<String>) {
        infoTitle = title
        infoLines = lines
        statusLine = null
        view = View.INFO
    }

    fun enterImage() {
        statusLine = null
        view = View.IMAGE
    }

    fun resetToInbox() {
        view = View.INBOX
        openChat = null
        messages = emptyList()
        selectedMessage = null
        quoting = null
        statusLine = null
    }

    /* ---------------- one-axis navigation ---------------- */

    fun move(delta: Int) {
        val n = itemCount()
        if (n <= 0) return
        val idx = currentIndex()
        setCurrentIndex(floorMod(idx + delta, n))
    }

    /** SELECT: report the intent of the focused row (runtime executes it). */
    fun activate(): NavAction {
        return when (view) {
            View.INBOX -> when (val i = inboxIndex) {
                0 -> NavAction.CycleFilter
                1 -> NavAction.Refresh
                else -> visibleChats().getOrNull(i - INBOX_HEADER_ROWS)?.let { NavAction.OpenChat(it) } ?: NavAction.None
            }
            View.CHAT -> {
                val msgs = messages
                if (chatIndex < msgs.size) {
                    NavAction.OpenMessage(msgs[chatIndex])
                } else {
                    when (chatActionRows().getOrNull(chatIndex - msgs.size)) {
                        ROW_REPLY -> NavAction.ReplyToChat
                        ROW_LOAD_OLDER -> NavAction.LoadOlder
                        else -> NavAction.None
                    }
                }
            }
            View.MSG_ACTIONS -> {
                val m = selectedMessage ?: return NavAction.None
                when (messageActionRows().getOrNull(actionsIndex)) {
                    ROW_VIEW_PHOTO -> NavAction.ViewPhoto(m)
                    ROW_DESCRIBE -> NavAction.Describe(m)
                    ROW_REACT -> NavAction.React(m)
                    ROW_REPLY_QUOTE -> NavAction.ReplyQuoting(m)
                    else -> NavAction.None
                }
            }
            View.QUICK -> quickMessages.getOrNull(quickIndex)?.let { NavAction.SendQuick(it) } ?: NavAction.None
            View.REACT -> {
                val m = selectedMessage ?: return NavAction.None
                reactions.getOrNull(reactIndex)?.let { NavAction.SendReaction(m, it.first) } ?: NavAction.None
            }
            View.INFO, View.IMAGE -> NavAction.None
        }
    }

    /** Local view transitions the runtime triggers after an [activate] intent. */
    fun enterMessageActions(message: Message) {
        selectedMessage = message
        actionsIndex = 0
        view = View.MSG_ACTIONS
    }

    fun enterQuick(quoting: Message?) {
        this.quoting = quoting
        quickIndex = 0
        view = View.QUICK
    }

    fun enterReact(message: Message) {
        selectedMessage = message
        reactIndex = 0
        view = View.REACT
    }

    fun quotingMessage(): Message? = quoting

    /** BACK: pop to the parent view; true means "self-close at root". */
    fun back(): Boolean {
        statusLine = null
        when (view) {
            View.INBOX -> return true
            View.CHAT -> resetToInbox()
            View.MSG_ACTIONS -> view = View.CHAT
            View.QUICK -> view = if (quoting != null) View.MSG_ACTIONS else View.CHAT
            View.REACT -> view = View.MSG_ACTIONS
            View.INFO -> view = if (openChat != null) View.CHAT else View.INBOX
            View.IMAGE -> view = View.CHAT
        }
        return false
    }

    /* ---------------- rendering ---------------- */

    fun screen(): Screen = when (view) {
        View.INBOX -> inboxScreen()
        View.CHAT -> chatScreen()
        View.MSG_ACTIONS -> actionsScreen()
        View.QUICK -> quickScreen()
        View.REACT -> reactScreen()
        View.INFO -> infoScreen()
        View.IMAGE -> Screen("Foto", listOf("Exibindo imagem..."), "voltar", "image")
    }

    private fun inboxScreen(): Screen {
        val chats = visibleChats()
        val rows = ArrayList<String>()
        rows += row(0 == inboxIndex, "Filtro: ${if (filter == Filter.ALL) "Todos" else "Nao lidos"}")
        rows += row(1 == inboxIndex, "Atualizar")
        if (chats.isEmpty()) {
            rows += "  (sem conversas)"
        } else {
            chats.forEachIndexed { i, c ->
                rows += row(inboxIndex == i + INBOX_HEADER_ROWS, chatLabel(c))
            }
        }
        statusLine?.let { rows += "  $it" }
        return Screen(
            title = "Inbox",
            lines = rows,
            footer = "girar - mover · toque - abrir · duplo - sair",
            keySeed = "inbox|${filter}|${inboxIndex}|${chats.size}|${statusLine ?: ""}",
        )
    }

    private fun chatScreen(): Screen {
        val rows = ArrayList<String>()
        if (messages.isEmpty()) rows += "  (sem mensagens)"
        messages.forEachIndexed { i, m ->
            rows += row(chatIndex == i, messageLabel(m))
        }
        val actions = chatActionRows()
        actions.forEachIndexed { i, a ->
            rows += row(chatIndex == messages.size + i, a)
        }
        statusLine?.let { rows += "  $it" }
        return Screen(
            title = openChat?.let { "${it.boxLabel} ${it.name}".trim() }?.take(60) ?: "Conversa",
            lines = rows,
            footer = "girar - mover · toque - abrir · duplo - voltar",
            keySeed = "chat|${openChat?.boxId}:${openChat?.id}|${chatIndex}|${messages.size}|${statusLine ?: ""}",
        )
    }

    private fun actionsScreen(): Screen {
        val m = selectedMessage
        val header = m?.let { messageFull(it) } ?: ""
        val rows = ArrayList<String>()
        val actions = messageActionRows()
        if (actions.isEmpty()) {
            rows += "  (sem acoes para esta mensagem)"
        } else {
            actions.forEachIndexed { i, a -> rows += row(actionsIndex == i, a) }
        }
        statusLine?.let { rows += "  $it" }
        val lines = if (header.isNotBlank()) listOf(header, "") + rows else rows
        return Screen(
            title = "Mensagem",
            lines = lines,
            footer = "girar - mover · toque - acao · duplo - voltar",
            keySeed = "acts|${m?.id}|${actionsIndex}|${statusLine ?: ""}",
        )
    }

    private fun quickScreen(): Screen {
        val rows = ArrayList<String>()
        if (quickMessages.isEmpty()) {
            rows += "  (configure respostas rapidas no celular)"
        } else {
            quickMessages.forEachIndexed { i, q ->
                rows += row(quickIndex == i, "${q.title} — ${q.body}".take(80))
            }
        }
        statusLine?.let { rows += "  $it" }
        return Screen(
            title = if (quoting != null) "Responder citando" else "Responder",
            lines = rows,
            footer = "girar - mover · toque - enviar · duplo - voltar",
            keySeed = "quick|${quoting?.id ?: ""}|${quickIndex}|${statusLine ?: ""}",
        )
    }

    private fun reactScreen(): Screen {
        val rows = reactions.mapIndexed { i, r -> row(reactIndex == i, "${r.first} ${r.second}") }
        val extra = statusLine?.let { listOf("  $it") } ?: emptyList()
        return Screen(
            title = "Reagir",
            lines = rows + extra,
            footer = "girar - mover · toque - reagir · duplo - voltar",
            keySeed = "react|${selectedMessage?.id}|${reactIndex}|${statusLine ?: ""}",
        )
    }

    private fun infoScreen(): Screen = Screen(
        title = infoTitle.ifBlank { "Info" },
        lines = wrap(infoLines),
        footer = "duplo - voltar",
        keySeed = "info|${infoTitle}|${infoLines.size}",
    )

    /* ---------------- row helpers ---------------- */

    private fun visibleChats(): List<Chat> = when (filter) {
        Filter.ALL -> allChats
        Filter.UNREAD -> allChats.filter { it.unreadCount > 0 }
    }

    fun cycleFilter() {
        filter = if (filter == Filter.ALL) Filter.UNREAD else Filter.ALL
        clampInboxIndex()
    }

    private fun clampInboxIndex() {
        val n = INBOX_HEADER_ROWS + visibleChats().size
        if (inboxIndex >= n) inboxIndex = (n - 1).coerceAtLeast(0)
    }

    private fun chatActionRows(): List<String> {
        val rows = ArrayList<String>()
        if (canSendOpen) rows += ROW_REPLY
        if (!atStart) rows += ROW_LOAD_OLDER
        return rows
    }

    private fun messageActionRows(): List<String> {
        val m = selectedMessage ?: return emptyList()
        val rows = ArrayList<String>()
        if (m.isImageMedia) rows += ROW_VIEW_PHOTO
        if (m.canDescribe && aiConfigured) rows += ROW_DESCRIBE
        if (canReactOpen) rows += ROW_REACT
        if (canSendOpen) rows += ROW_REPLY_QUOTE
        return rows
    }

    private fun itemCount(): Int = when (view) {
        View.INBOX -> INBOX_HEADER_ROWS + visibleChats().size
        View.CHAT -> messages.size + chatActionRows().size
        View.MSG_ACTIONS -> messageActionRows().size
        View.QUICK -> quickMessages.size
        View.REACT -> reactions.size
        View.INFO, View.IMAGE -> 0
    }

    private fun currentIndex(): Int = when (view) {
        View.INBOX -> inboxIndex
        View.CHAT -> chatIndex
        View.MSG_ACTIONS -> actionsIndex
        View.QUICK -> quickIndex
        View.REACT -> reactIndex
        View.INFO, View.IMAGE -> 0
    }

    private fun setCurrentIndex(v: Int) {
        when (view) {
            View.INBOX -> inboxIndex = v
            View.CHAT -> chatIndex = v
            View.MSG_ACTIONS -> actionsIndex = v
            View.QUICK -> quickIndex = v
            View.REACT -> reactIndex = v
            View.INFO, View.IMAGE -> {}
        }
    }

    private fun row(selected: Boolean, label: String): String =
        (if (selected) "> " else "  ") + label

    private fun chatLabel(c: Chat): String {
        val unread = if (c.unreadCount > 0) " (${c.unreadCount})" else ""
        val name = c.name.ifBlank { "?" }
        val prefix = if (c.boxLabel.isNotBlank()) "${c.boxLabel} " else ""
        val preview = c.lastMessagePreview.replace("\n", " ").trim()
        val base = "$prefix$name$unread"
        return if (preview.isNotBlank()) "$base · $preview".take(160) else base.take(160)
    }

    private fun messageLabel(m: Message): String {
        val who = if (m.isOutgoing) "Eu" else m.senderName.ifBlank { "?" }
        val body = m.text.ifBlank { mediaLabel(m) }.replace("\n", " ").trim()
        return "$who: $body".take(120)
    }

    private fun messageFull(m: Message): String {
        val who = if (m.isOutgoing) "Eu" else m.senderName.ifBlank { "?" }
        val body = m.text.ifBlank { mediaLabel(m) }.replace("\n", " ").trim()
        return "$who: $body".take(240)
    }

    private fun mediaLabel(m: Message): String = when (m.media) {
        "[photo]" -> "[foto]"
        "[sticker]" -> "[figurinha]"
        "[voice]" -> "[audio de voz]"
        "[audio]" -> "[audio]"
        "[video]" -> "[video]"
        "[file]" -> "[arquivo${if (m.fileName.isNotBlank()) ": " + m.fileName else ""}]"
        "[location]" -> "[localizacao]"
        "[contact]" -> "[contato]"
        "[poll]" -> "[enquete]"
        else -> "[midia]"
    }

    /** Split long info lines into HUD rows within the per-line 240-char limit. */
    private fun wrap(lines: List<String>, max: Int = 200): List<String> {
        val out = ArrayList<String>()
        for (raw in lines) {
            var s = raw.replace("\r", "")
            for (piece in s.split("\n")) {
                var p = piece
                while (p.length > max) {
                    out += p.substring(0, max)
                    p = p.substring(max)
                }
                out += p
            }
        }
        return out.take(60)
    }

    private fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b

    companion object {
        const val INBOX_HEADER_ROWS = 2 // filter toggle + refresh

        private const val ROW_REPLY = "Responder"
        private const val ROW_LOAD_OLDER = "Carregar mais"
        private const val ROW_VIEW_PHOTO = "Ver foto"
        private const val ROW_DESCRIBE = "Descrever (IA)"
        private const val ROW_REACT = "Reagir"
        private const val ROW_REPLY_QUOTE = "Responder citando"
    }
}
