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

    enum class View { INBOX, CHAT, MSG_ACTIONS, QUICK, REACT, INFO, IMAGE, LISTENING, CONFIRM_SEND, SEARCH_RESULTS }
    enum class Filter { ALL, UNREAD }

    /** Why the mic is being captured: to dictate a reply, or to search by name. */
    enum class ListenPurpose { REPLY, SEARCH }

    /** What a SELECT on the focused row means; the runtime executes it. */
    sealed interface NavAction {
        data object None : NavAction
        data object CycleFilter : NavAction
        data object Refresh : NavAction
        /** Dictate a contact name and search chats across all boxes. */
        data object VoiceSearch : NavAction
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
        /** Start voice dictation of a reply (optionally quoting a message). */
        data class Dictate(val quoting: Message?) : NavAction
        /** Stop capturing and transcribe what was said. */
        data object StopListening : NavAction
        /** Send the confirmed transcript as a reply. */
        data object SendTranscript : NavAction
        /** Discard the transcript and dictate again. */
        data object Redictate : NavAction
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
    private var sttEnabled: Boolean = false
    private var transcript: String = ""
    private var searchResults: List<Chat> = emptyList()
    private var searchQuery: String = ""

    /** Whether the current LISTENING session is for a reply or a name search. */
    var listenPurpose: ListenPurpose = ListenPurpose.REPLY
        private set

    /** The transcript awaiting confirmation in the CONFIRM_SEND view. */
    val currentTranscript: String get() = transcript

    // Per-view selection (kept so BACK restores the parent's focus)
    private var inboxIndex = 0
    private var chatIndex = 0
    private var actionsIndex = 0
    private var quickIndex = 0
    private var reactIndex = 0
    private var confirmIndex = 0
    private var searchIndex = 0

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
    fun setSttEnabled(value: Boolean) { sttEnabled = value }

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
            View.INBOX -> {
                val headers = inboxHeaderRows()
                if (inboxIndex < headers.size) {
                    when (inboxIndex) {
                        0 -> NavAction.CycleFilter
                        1 -> NavAction.Refresh
                        else -> NavAction.VoiceSearch
                    }
                } else {
                    visibleChats().getOrNull(inboxIndex - headers.size)?.let { NavAction.OpenChat(it) } ?: NavAction.None
                }
            }
            View.CHAT -> {
                val msgs = messages
                if (chatIndex < msgs.size) {
                    NavAction.OpenMessage(msgs[chatIndex])
                } else {
                    when (chatActionRows().getOrNull(chatIndex - msgs.size)) {
                        ROW_REPLY -> NavAction.ReplyToChat
                        ROW_DICTATE -> NavAction.Dictate(null)
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
                    ROW_DICTATE_QUOTE -> NavAction.Dictate(m)
                    else -> NavAction.None
                }
            }
            View.QUICK -> quickMessages.getOrNull(quickIndex)?.let { NavAction.SendQuick(it) } ?: NavAction.None
            View.REACT -> {
                val m = selectedMessage ?: return NavAction.None
                reactions.getOrNull(reactIndex)?.let { NavAction.SendReaction(m, it.first) } ?: NavAction.None
            }
            View.LISTENING -> NavAction.StopListening
            View.CONFIRM_SEND -> when (confirmIndex) {
                0 -> NavAction.SendTranscript
                else -> NavAction.Redictate
            }
            View.SEARCH_RESULTS -> searchResults.getOrNull(searchIndex)?.let { NavAction.OpenChat(it) } ?: NavAction.None
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

    /** Begin voice dictation of a reply (quoting [quoting] when non-null). */
    fun enterListening(quoting: Message?) {
        this.quoting = quoting
        listenPurpose = ListenPurpose.REPLY
        transcript = ""
        statusLine = null
        view = View.LISTENING
    }

    /** Begin voice capture to search chats by contact name across all boxes. */
    fun enterVoiceSearch() {
        quoting = null
        listenPurpose = ListenPurpose.SEARCH
        transcript = ""
        statusLine = null
        view = View.LISTENING
    }

    /** Show the chats matching a spoken name search. */
    fun showSearchResults(query: String, results: List<Chat>) {
        searchQuery = query
        searchResults = results
        searchIndex = 0
        statusLine = null
        view = View.SEARCH_RESULTS
    }

    /** Show the transcribed text for confirmation before sending. */
    fun showTranscript(text: String) {
        transcript = text
        confirmIndex = 0
        statusLine = null
        view = View.CONFIRM_SEND
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
            View.LISTENING -> view = when {
                listenPurpose == ListenPurpose.SEARCH -> View.INBOX
                quoting != null -> View.MSG_ACTIONS
                else -> View.CHAT
            }
            View.CONFIRM_SEND -> view = if (quoting != null) View.MSG_ACTIONS else View.CHAT
            View.SEARCH_RESULTS -> view = View.INBOX
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
        View.LISTENING -> listeningScreen()
        View.CONFIRM_SEND -> confirmSendScreen()
        View.SEARCH_RESULTS -> searchResultsScreen()
        View.INFO -> infoScreen()
        View.IMAGE -> Screen("Foto", listOf("Exibindo imagem..."), "voltar", "image")
    }

    private fun inboxScreen(): Screen {
        val chats = visibleChats()
        val headers = inboxHeaderRows()
        val rows = ArrayList<String>()
        headers.forEachIndexed { i, h -> rows += row(inboxIndex == i, h) }
        chats.forEachIndexed { i, c ->
            rows += row(inboxIndex == i + headers.size, chatLabel(c))
        }
        val body = windowed(rows, inboxIndex).toMutableList()
        if (chats.isEmpty() && statusLine == null) body += "  (sem conversas)"
        statusLine?.let { body += "  $it" }
        return Screen(
            title = "Inbox · ${if (filter == Filter.ALL) "Todos" else "Nao lidos"}",
            lines = body,
            footer = "girar mover · toque abrir · duplo sair",
            keySeed = "inbox|${filter}|${inboxIndex}|${chats.size}|${sttEnabled}|${statusLine ?: ""}",
        )
    }

    /** Inbox header actions in traversal order (voice search only when STT is on). */
    private fun inboxHeaderRows(): List<String> {
        val rows = ArrayList<String>()
        rows += "Filtro: ${if (filter == Filter.ALL) "Todos" else "Nao lidos"}"
        rows += "Atualizar"
        if (sttEnabled) rows += "Buscar por voz"
        return rows
    }

    private fun chatScreen(): Screen {
        val rows = ArrayList<String>()
        messages.forEachIndexed { i, m ->
            rows += row(chatIndex == i, messageLabel(m))
        }
        val actions = chatActionRows()
        actions.forEachIndexed { i, a ->
            rows += row(chatIndex == messages.size + i, a)
        }
        val body = windowed(rows, chatIndex).toMutableList()
        if (rows.isEmpty() && statusLine == null) body += "  (sem mensagens)"
        statusLine?.let { body += "  $it" }
        return Screen(
            title = openChat?.let { "${it.boxLabel} ${it.name}".trim() }?.take(60) ?: "Conversa",
            lines = body,
            footer = "girar mover · toque abrir · duplo voltar",
            keySeed = "chat|${openChat?.boxId}:${openChat?.id}|${chatIndex}|${messages.size}|${statusLine ?: ""}",
        )
    }

    private fun actionsScreen(): Screen {
        val m = selectedMessage
        val header = m?.let { messageFull(it).take(100) } ?: ""
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
            footer = "girar mover · toque acao · duplo voltar",
            keySeed = "acts|${m?.id}|${actionsIndex}|${statusLine ?: ""}",
        )
    }

    private fun quickScreen(): Screen {
        val rows = ArrayList<String>()
        quickMessages.forEachIndexed { i, q ->
            rows += row(quickIndex == i, "${q.title} — ${q.body}".take(80))
        }
        val body = windowed(rows, quickIndex).toMutableList()
        if (quickMessages.isEmpty()) body += "  (configure respostas rapidas no celular)"
        statusLine?.let { body += "  $it" }
        return Screen(
            title = if (quoting != null) "Responder citando" else "Responder",
            lines = body,
            footer = "girar mover · toque enviar · duplo voltar",
            keySeed = "quick|${quoting?.id ?: ""}|${quickIndex}|${statusLine ?: ""}",
        )
    }

    private fun reactScreen(): Screen {
        val rows = reactions.mapIndexed { i, r -> row(reactIndex == i, "${r.first} ${r.second}") }
        val body = windowed(rows, reactIndex).toMutableList()
        statusLine?.let { body += "  $it" }
        return Screen(
            title = "Reagir",
            lines = body,
            footer = "girar mover · toque reagir · duplo voltar",
            keySeed = "react|${selectedMessage?.id}|${reactIndex}|${statusLine ?: ""}",
        )
    }

    private fun listeningScreen(): Screen {
        val prompt = if (listenPurpose == ListenPurpose.SEARCH) "Fale o nome do contato." else "Fale sua resposta."
        return Screen(
            title = if (listenPurpose == ListenPurpose.SEARCH) "Buscar por voz" else "Ouvindo",
            lines = listOf(
                "  $prompt",
                "  ${statusLine ?: "Toque para parar."}",
            ),
            footer = "toque parar · duplo cancelar",
            keySeed = "listen|${listenPurpose}|${quoting?.id ?: ""}|${statusLine ?: ""}",
        )
    }

    private fun searchResultsScreen(): Screen {
        val rows = searchResults.mapIndexed { i, c -> row(searchIndex == i, chatLabel(c)) }
        val body = windowed(rows, searchIndex).toMutableList()
        if (searchResults.isEmpty()) body += "  (nada encontrado)"
        statusLine?.let { body += "  $it" }
        return Screen(
            title = "Busca: ${searchQuery.take(40)} (${searchResults.size})",
            lines = body,
            footer = "girar mover · toque abrir · duplo voltar",
            keySeed = "search|${searchQuery}|${searchIndex}|${searchResults.size}",
        )
    }

    private fun confirmSendScreen(): Screen {
        val head = transcript.take(240).ifBlank { "(nada reconhecido)" }
        val rows = listOf(
            row(confirmIndex == 0, "Enviar"),
            row(confirmIndex == 1, "Regravar"),
        )
        val extra = statusLine?.let { listOf("  $it") } ?: emptyList()
        return Screen(
            title = if (quoting != null) "Enviar (citando)" else "Enviar resposta",
            lines = listOf(head, "") + rows + extra,
            footer = "girar mover · toque ok · duplo cancelar",
            keySeed = "confirm|${confirmIndex}|${transcript.length}|${statusLine ?: ""}",
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
        val n = inboxHeaderRows().size + visibleChats().size
        if (inboxIndex >= n) inboxIndex = (n - 1).coerceAtLeast(0)
    }

    private fun chatActionRows(): List<String> {
        val rows = ArrayList<String>()
        if (canSendOpen) rows += ROW_REPLY
        if (canSendOpen && sttEnabled) rows += ROW_DICTATE
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
        if (canSendOpen && sttEnabled) rows += ROW_DICTATE_QUOTE
        return rows
    }

    private fun itemCount(): Int = when (view) {
        View.INBOX -> inboxHeaderRows().size + visibleChats().size
        View.CHAT -> messages.size + chatActionRows().size
        View.MSG_ACTIONS -> messageActionRows().size
        View.QUICK -> quickMessages.size
        View.REACT -> reactions.size
        View.CONFIRM_SEND -> 2
        View.SEARCH_RESULTS -> searchResults.size
        View.INFO, View.IMAGE, View.LISTENING -> 0
    }

    private fun currentIndex(): Int = when (view) {
        View.INBOX -> inboxIndex
        View.CHAT -> chatIndex
        View.MSG_ACTIONS -> actionsIndex
        View.QUICK -> quickIndex
        View.REACT -> reactIndex
        View.CONFIRM_SEND -> confirmIndex
        View.SEARCH_RESULTS -> searchIndex
        View.INFO, View.IMAGE, View.LISTENING -> 0
    }

    private fun setCurrentIndex(v: Int) {
        when (view) {
            View.INBOX -> inboxIndex = v
            View.CHAT -> chatIndex = v
            View.MSG_ACTIONS -> actionsIndex = v
            View.QUICK -> quickIndex = v
            View.REACT -> reactIndex = v
            View.CONFIRM_SEND -> confirmIndex = v
            View.SEARCH_RESULTS -> searchIndex = v
            View.INFO, View.IMAGE, View.LISTENING -> {}
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

    /**
     * The HUD card shows only a few rows and never auto-scrolls to a marked
     * row (there is no selection concept in the card model). So we paginate the
     * selectable rows into pages of [VISIBLE_ROWS] that always contain the
     * focused row, with "more above / more below" indicators — the same
     * approach the shipped Transit plugin uses for its boards.
     */
    private fun windowed(rows: List<String>, selected: Int): List<String> {
        if (rows.size <= VISIBLE_ROWS) return rows
        val page = selected.coerceAtLeast(0) / VISIBLE_ROWS
        val start = page * VISIBLE_ROWS
        val end = minOf(start + VISIBLE_ROWS, rows.size)
        val out = ArrayList<String>()
        if (start > 0) out += "  (+$start acima)"
        out += rows.subList(start, end)
        if (end < rows.size) out += "  (+${rows.size - end} abaixo)"
        return out
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
        const val VISIBLE_ROWS = 6 // rows that fit the HUD card at glance distance

        private const val ROW_REPLY = "Responder"
        private const val ROW_DICTATE = "Ditar por voz"
        private const val ROW_LOAD_OLDER = "Carregar mais"
        private const val ROW_VIEW_PHOTO = "Ver foto"
        private const val ROW_DESCRIBE = "Descrever (IA)"
        private const val ROW_REACT = "Reagir"
        private const val ROW_REPLY_QUOTE = "Responder citando"
        private const val ROW_DICTATE_QUOTE = "Ditar resposta (voz)"
    }
}
