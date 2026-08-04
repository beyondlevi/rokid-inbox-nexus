package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.model.Chat
import com.rokid.inbox.nexus.model.Message
import com.rokid.inbox.nexus.model.QuickMessage

/**
 * One-axis (R08) navigation state for the HUD, rebuilt on the shipped Relay
 * plugin's model: the plugin emits rich rows ([Row] -> NexusCardLine with sub /
 * tone / selected) and the glasses hub owns the caret, scroll and type. So there
 * is no manual "> " marker and no manual windowing — we send the full ordered
 * list, mark the focused row, and let the hub scroll it.
 *
 * Pure Kotlin (no Android) so it is unit-testable on the JVM; all I/O
 * (channels, transcription, sending) lives in [InboxRuntime].
 */
class InboxNavState {

    enum class View { LIST, THREAD, MSG_ACTIONS, QUICK, REACT, LISTENING, REVIEW, SEARCH_RESULTS, INFO }
    enum class Filter { ALL, UNREAD }
    enum class Tone { NORMAL, DIM, BODY, ALERT }
    enum class ListenPurpose { REPLY, SEARCH }

    /** A HUD row; the service maps it to NexusCardLine and the hub draws it. */
    data class Row(
        val text: String,
        val sub: String? = null,
        val badge: String? = null,
        val tone: Tone = Tone.NORMAL,
        val selected: Boolean = false,
    )

    data class Screen(
        val title: String,
        val subtitle: String?,
        val rows: List<Row>,
        val footer: String,
        val keySeed: String,
    )

    sealed interface NavAction {
        data object None : NavAction
        data object CycleFilter : NavAction
        data object Refresh : NavAction
        data object VoiceSearch : NavAction
        data class OpenChat(val chat: Chat) : NavAction
        data object LoadOlder : NavAction
        data class OpenMessage(val message: Message) : NavAction
        data object ReplyToChat : NavAction
        data class ReplyQuoting(val message: Message) : NavAction
        data class React(val message: Message) : NavAction
        data class Describe(val message: Message) : NavAction
        data class SendQuick(val quick: QuickMessage) : NavAction
        data class SendReaction(val message: Message, val emoji: String) : NavAction
        data class Dictate(val quoting: Message?) : NavAction
        data object StopListening : NavAction
        data object SendReplyText : NavAction
        data object SendReplyAudio : NavAction
        data object Redictate : NavAction
    }

    var view: View = View.LIST
        private set

    // Data
    private var allChats: List<Chat> = emptyList()
    var filter: Filter = Filter.ALL
        private set
    var openChat: Chat? = null
        private set
    private var messages: List<Message> = emptyList()
    private var atStart = true
    private var canSendOpen = false
    private var canReactOpen = false
    private var canVoiceOpen = false
    private var aiConfigured = false
    private var voiceEnabled = false
    private var quickMessages: List<QuickMessage> = emptyList()
    private var selectedMessage: Message? = null
    private var quoting: Message? = null
    private var searchResults: List<Chat> = emptyList()
    private var searchQuery = ""
    private var transcript = ""
    private var hasAudio = false
    private var infoTitle = ""
    private var infoLines: List<String> = emptyList()
    private var statusLine: String? = null

    var listenPurpose: ListenPurpose = ListenPurpose.REPLY
        private set

    val currentTranscript: String get() = transcript

    // Selection per view
    private var listIndex = 0
    private var threadIndex = 0
    private var actionsIndex = 0
    private var quickIndex = 0
    private var reactIndex = 0
    private var reviewIndex = 0
    private var searchIndex = 0

    val reactions: List<Pair<String, String>> = listOf(
        "👍" to "Curtir", "❤️" to "Amei", "😂" to "Haha",
        "😮" to "Uau", "😢" to "Triste", "🙏" to "Obrigado",
    )

    /* ---------------- intake ---------------- */

    fun setAiConfigured(v: Boolean) { aiConfigured = v }
    fun setVoiceEnabled(v: Boolean) { voiceEnabled = v }
    fun setQuickMessages(list: List<QuickMessage>) { quickMessages = list }
    fun setStatus(line: String?) { statusLine = line }

    fun setInbox(chats: List<Chat>) {
        allChats = chats
        clampIndex(listIndex, listItemCount()) { listIndex = it }
    }

    fun setConversation(chat: Chat, msgs: List<Message>, atStart: Boolean, canSend: Boolean, canReact: Boolean, canVoice: Boolean) {
        openChat = chat
        messages = msgs
        this.atStart = atStart
        canSendOpen = canSend
        canReactOpen = canReact
        canVoiceOpen = canVoice
        statusLine = null
        view = View.THREAD
        threadIndex = (messages.size - 1).coerceAtLeast(0) // focus newest
    }

    fun showInfo(title: String, lines: List<String>) {
        infoTitle = title; infoLines = lines; statusLine = null; view = View.INFO
    }

    fun showSearchResults(query: String, results: List<Chat>) {
        searchQuery = query; searchResults = results; searchIndex = 0; statusLine = null; view = View.SEARCH_RESULTS
    }

    fun resetToInbox() {
        view = View.LIST; openChat = null; messages = emptyList()
        selectedMessage = null; quoting = null; statusLine = null
    }

    /* ---------------- navigation ---------------- */

    fun move(delta: Int) {
        val n = itemCount()
        if (n <= 0) return
        setIndex(floorMod(index() + delta, n))
    }

    fun activate(): NavAction = when (view) {
        View.LIST -> {
            val headers = listHeaders()
            if (listIndex < headers.size) when (listIndex) {
                0 -> NavAction.CycleFilter
                1 -> NavAction.Refresh
                else -> NavAction.VoiceSearch
            } else visibleChats().getOrNull(listIndex - headers.size)?.let { NavAction.OpenChat(it) } ?: NavAction.None
        }
        View.THREAD -> {
            if (threadIndex < messages.size) NavAction.OpenMessage(messages[threadIndex])
            else when (threadActionRows().getOrNull(threadIndex - messages.size)) {
                ROW_REPLY -> NavAction.ReplyToChat
                ROW_LOAD_OLDER -> NavAction.LoadOlder
                else -> NavAction.None
            }
        }
        View.MSG_ACTIONS -> {
            val m = selectedMessage ?: return NavAction.None
            when (messageActionRows().getOrNull(actionsIndex)) {
                ROW_REACT -> NavAction.React(m)
                ROW_DESCRIBE -> NavAction.Describe(m)
                ROW_REPLY_QUOTE -> NavAction.ReplyQuoting(m)
                else -> NavAction.None
            }
        }
        View.QUICK -> {
            val voice = quickHasVoiceRow()
            if (voice && quickIndex == 0) NavAction.Dictate(quoting)
            else quickMessages.getOrNull(quickIndex - if (voice) 1 else 0)?.let { NavAction.SendQuick(it) } ?: NavAction.None
        }
        View.REACT -> selectedMessage?.let { m ->
            reactions.getOrNull(reactIndex)?.let { NavAction.SendReaction(m, it.first) }
        } ?: NavAction.None
        View.REVIEW -> when (reviewChoices().getOrNull(reviewIndex)) {
            CHOICE_SEND_TEXT -> NavAction.SendReplyText
            CHOICE_SEND_AUDIO -> NavAction.SendReplyAudio
            CHOICE_RETRY -> NavAction.Redictate
            else -> NavAction.None
        }
        View.SEARCH_RESULTS -> searchResults.getOrNull(searchIndex)?.let { NavAction.OpenChat(it) } ?: NavAction.None
        View.LISTENING -> NavAction.StopListening
        View.INFO -> NavAction.None
    }

    fun enterMessageActions(message: Message) { selectedMessage = message; actionsIndex = 0; view = View.MSG_ACTIONS }
    fun enterQuick(quoting: Message?) { this.quoting = quoting; quickIndex = 0; view = View.QUICK }
    fun enterReact(message: Message) { selectedMessage = message; reactIndex = 0; view = View.REACT }

    fun enterListening(quoting: Message?) {
        this.quoting = quoting; listenPurpose = ListenPurpose.REPLY
        transcript = ""; hasAudio = false; statusLine = null; view = View.LISTENING
    }
    fun enterVoiceSearch() {
        quoting = null; listenPurpose = ListenPurpose.SEARCH
        transcript = ""; hasAudio = false; statusLine = null; view = View.LISTENING
    }

    /** Show the review after capture: [transcript] may be blank if STT is off. */
    fun showReview(transcript: String, hasAudio: Boolean) {
        this.transcript = transcript; this.hasAudio = hasAudio
        reviewIndex = 0; statusLine = null; view = View.REVIEW
    }

    fun quotingMessage(): Message? = quoting

    /** BACK: pop to parent; true means self-close at root. */
    fun back(): Boolean {
        statusLine = null
        when (view) {
            View.LIST -> return true
            View.THREAD -> resetToInbox()
            View.MSG_ACTIONS -> view = View.THREAD
            View.QUICK -> view = if (quoting != null) View.MSG_ACTIONS else View.THREAD
            View.REACT -> view = View.MSG_ACTIONS
            View.REVIEW -> view = if (quoting != null) View.MSG_ACTIONS else View.THREAD
            View.LISTENING -> view = when {
                listenPurpose == ListenPurpose.SEARCH -> View.LIST
                quoting != null -> View.MSG_ACTIONS
                else -> View.THREAD
            }
            View.SEARCH_RESULTS -> view = View.LIST
            View.INFO -> view = if (openChat != null) View.THREAD else View.LIST
        }
        return false
    }

    fun cycleFilter() {
        filter = if (filter == Filter.ALL) Filter.UNREAD else Filter.ALL
        clampIndex(listIndex, listItemCount()) { listIndex = it }
    }

    /* ---------------- rendering ---------------- */

    fun screen(): Screen = when (view) {
        View.LIST -> listScreen()
        View.THREAD -> threadScreen()
        View.MSG_ACTIONS -> actionsScreen()
        View.QUICK -> quickScreen()
        View.REACT -> reactScreen()
        View.LISTENING -> listeningScreen()
        View.REVIEW -> reviewScreen()
        View.SEARCH_RESULTS -> searchScreen()
        View.INFO -> infoScreen()
    }

    private fun listScreen(): Screen {
        val headers = listHeaders()
        val chats = visibleChats()
        val rows = ArrayList<Row>()
        headers.forEachIndexed { i, h -> rows += Row(text = h, tone = Tone.BODY, selected = listIndex == i) }
        chats.forEachIndexed { i, c ->
            val idx = i + headers.size
            rows += Row(
                text = chatTitle(c),
                sub = c.lastMessagePreview.replace("\n", " ").trim().ifBlank { null },
                badge = c.boxLabel.ifBlank { null },
                tone = if (c.unreadCount > 0) Tone.ALERT else Tone.NORMAL,
                selected = listIndex == idx,
            )
        }
        if (chats.isEmpty()) rows += Row(text = statusLine ?: "Nenhuma conversa.", tone = Tone.DIM)
        else statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = "Inbox",
            subtitle = "${if (filter == Filter.ALL) "Todos" else "Nao lidos"} · ${chats.size}",
            rows = rows,
            footer = if (chats.isEmpty()) "duplo sai" else "${(listIndex - headers.size + 1).coerceAtLeast(1)}/${chats.size} · girar · toque · duplo sai",
            keySeed = "list|$filter|$listIndex|${chats.size}|${statusLine ?: ""}",
        )
    }

    private fun threadScreen(): Screen {
        val chat = openChat
        val rows = ArrayList<Row>()
        val soloVoice = messages.all { it.isOutgoing || it.senderName.isBlank() || it.senderName.equals(chat?.name?.trim(), true) }
        messages.forEachIndexed { i, m ->
            rows += Row(
                text = messageText(m),
                badge = speakerBadge(m, soloVoice),
                tone = Tone.BODY,
                selected = threadIndex == i,
            )
        }
        threadActionRows().forEachIndexed { i, a ->
            rows += Row(text = a, tone = Tone.NORMAL, selected = threadIndex == messages.size + i)
        }
        if (messages.isEmpty() && threadActionRows().isEmpty()) rows += Row(text = "Sem mensagens.", tone = Tone.DIM)
        statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = chat?.let { "${it.boxLabel} ${it.name}".trim() }?.take(120) ?: "Conversa",
            subtitle = null,
            rows = rows,
            footer = "girar · toque · duplo volta",
            keySeed = "thread|${chat?.boxId}:${chat?.id}|$threadIndex|${messages.size}|${statusLine ?: ""}",
        )
    }

    private fun actionsScreen(): Screen {
        val m = selectedMessage
        val rows = messageActionRows().mapIndexed { i, a -> Row(text = a, selected = actionsIndex == i) }
            .ifEmpty { listOf(Row(text = "Sem acoes.", tone = Tone.DIM)) }
        return Screen(
            title = "Mensagem",
            subtitle = m?.let { messageText(it) }?.take(240),
            rows = rows,
            footer = "girar · toque · duplo volta",
            keySeed = "acts|${m?.id}|$actionsIndex",
        )
    }

    private fun quickScreen(): Screen {
        val rows = ArrayList<Row>()
        val voice = quickHasVoiceRow()
        if (voice) rows += Row(text = "Ditar por voz", tone = Tone.BODY, selected = quickIndex == 0)
        quickMessages.forEachIndexed { i, q ->
            val idx = i + if (voice) 1 else 0
            rows += Row(text = q.title, sub = q.body, selected = quickIndex == idx)
        }
        if (rows.isEmpty()) rows += Row(text = "Configure respostas rapidas no celular.", tone = Tone.DIM)
        statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = if (quoting != null) "Responder citando" else "Responder",
            subtitle = null,
            rows = rows,
            footer = "girar · toque envia · duplo volta",
            keySeed = "quick|${quoting?.id ?: ""}|$quickIndex|${statusLine ?: ""}",
        )
    }

    private fun reactScreen(): Screen {
        val rows = reactions.mapIndexed { i, r -> Row(text = "${r.first} ${r.second}", selected = reactIndex == i) }
        val extra = statusLine?.let { listOf(Row(text = it, tone = Tone.DIM)) } ?: emptyList()
        return Screen("Reagir", null, rows + extra, "girar · toque reage · duplo volta", "react|${selectedMessage?.id}|$reactIndex|${statusLine ?: ""}")
    }

    private fun listeningScreen(): Screen {
        val prompt = if (listenPurpose == ListenPurpose.SEARCH) "Fale o nome do contato." else "Fale sua resposta."
        return Screen(
            title = if (listenPurpose == ListenPurpose.SEARCH) "Buscar por voz" else "Ouvindo",
            subtitle = null,
            rows = listOf(Row(text = statusLine ?: prompt, tone = Tone.BODY)),
            footer = "toque para · duplo cancela",
            keySeed = "listen|$listenPurpose|${statusLine ?: ""}",
        )
    }

    private fun reviewScreen(): Screen {
        val rows = ArrayList<Row>()
        if (transcript.isNotBlank()) rows += Row(text = transcript, badge = "Voce", tone = Tone.BODY)
        else rows += Row(text = "Audio gravado (sem transcricao).", tone = Tone.DIM)
        reviewChoices().forEachIndexed { i, c ->
            rows += Row(text = c, tone = if (reviewIndex == i) Tone.ALERT else Tone.NORMAL, selected = reviewIndex == i)
        }
        statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = if (quoting != null) "Enviar (citando)" else "Enviar resposta",
            subtitle = null,
            rows = rows,
            footer = "girar · toque · duplo cancela",
            keySeed = "review|$reviewIndex|${transcript.length}|$hasAudio|${statusLine ?: ""}",
        )
    }

    private fun searchScreen(): Screen {
        val rows = searchResults.mapIndexed { i, c ->
            Row(text = chatTitle(c), sub = c.lastMessagePreview.replace("\n", " ").trim().ifBlank { null }, badge = c.boxLabel.ifBlank { null }, selected = searchIndex == i)
        }.ifEmpty { listOf(Row(text = "Nada encontrado.", tone = Tone.DIM)) }
        return Screen("Busca: ${searchQuery.take(40)}", "${searchResults.size}", rows, "girar · toque abre · duplo volta", "search|$searchQuery|$searchIndex|${searchResults.size}")
    }

    private fun infoScreen(): Screen = Screen(
        title = infoTitle.ifBlank { "Info" },
        subtitle = null,
        rows = wrap(infoLines).map { Row(text = it, tone = Tone.BODY) },
        footer = "duplo volta",
        keySeed = "info|$infoTitle|${infoLines.size}",
    )

    /* ---------------- rows / helpers ---------------- */

    private fun visibleChats(): List<Chat> = when (filter) {
        Filter.ALL -> allChats
        Filter.UNREAD -> allChats.filter { it.unreadCount > 0 }
    }

    private fun listHeaders(): List<String> {
        val h = arrayListOf("Filtro: ${if (filter == Filter.ALL) "Todos" else "Nao lidos"}", "Atualizar")
        if (voiceEnabled) h += "Buscar por voz"
        return h
    }

    private fun threadActionRows(): List<String> {
        val rows = ArrayList<String>()
        if (canSendOpen) rows += ROW_REPLY
        if (!atStart) rows += ROW_LOAD_OLDER
        return rows
    }

    private fun messageActionRows(): List<String> {
        val m = selectedMessage ?: return emptyList()
        val rows = ArrayList<String>()
        if (canReactOpen) rows += ROW_REACT
        if (m.canDescribe && aiConfigured) rows += ROW_DESCRIBE
        if (canSendOpen) rows += ROW_REPLY_QUOTE
        return rows
    }

    private fun quickHasVoiceRow(): Boolean = voiceEnabled && canSendOpen
    private fun reviewChoices(): List<String> {
        val c = ArrayList<String>()
        if (transcript.isNotBlank()) c += CHOICE_SEND_TEXT
        if (canVoiceOpen && hasAudio) c += CHOICE_SEND_AUDIO
        c += CHOICE_RETRY
        return c
    }

    private fun listItemCount(): Int = listHeaders().size + visibleChats().size

    private fun itemCount(): Int = when (view) {
        View.LIST -> listItemCount()
        View.THREAD -> messages.size + threadActionRows().size
        View.MSG_ACTIONS -> messageActionRows().size
        View.QUICK -> quickMessages.size + if (quickHasVoiceRow()) 1 else 0
        View.REACT -> reactions.size
        View.REVIEW -> reviewChoices().size
        View.SEARCH_RESULTS -> searchResults.size
        View.LISTENING, View.INFO -> 0
    }

    private fun index(): Int = when (view) {
        View.LIST -> listIndex
        View.THREAD -> threadIndex
        View.MSG_ACTIONS -> actionsIndex
        View.QUICK -> quickIndex
        View.REACT -> reactIndex
        View.REVIEW -> reviewIndex
        View.SEARCH_RESULTS -> searchIndex
        View.LISTENING, View.INFO -> 0
    }

    private fun setIndex(v: Int) {
        when (view) {
            View.LIST -> listIndex = v
            View.THREAD -> threadIndex = v
            View.MSG_ACTIONS -> actionsIndex = v
            View.QUICK -> quickIndex = v
            View.REACT -> reactIndex = v
            View.REVIEW -> reviewIndex = v
            View.SEARCH_RESULTS -> searchIndex = v
            View.LISTENING, View.INFO -> {}
        }
    }

    private fun clampIndex(current: Int, count: Int, set: (Int) -> Unit) {
        if (current >= count) set((count - 1).coerceAtLeast(0))
    }

    private fun chatTitle(c: Chat): String = c.name.ifBlank { "?" }.take(120)

    private fun messageText(m: Message): String {
        val body = m.text.ifBlank { mediaLabel(m) }.replace("\n", " ").trim()
        return if (m.isOutgoing) "Eu: $body".take(240) else body.take(240)
    }

    private fun speakerBadge(m: Message, soloVoice: Boolean): String? = when {
        m.isOutgoing -> "Eu"
        soloVoice -> null
        m.senderName.isNotBlank() -> m.senderName.take(20)
        else -> null
    }

    private fun mediaLabel(m: Message): String = when (m.media) {
        "[photo]" -> "[foto]"; "[sticker]" -> "[figurinha]"; "[voice]" -> "[audio de voz]"
        "[audio]" -> "[audio]"; "[video]" -> "[video]"
        "[file]" -> "[arquivo${if (m.fileName.isNotBlank()) ": " + m.fileName else ""}]"
        "[location]" -> "[localizacao]"; "[contact]" -> "[contato]"; "[poll]" -> "[enquete]"
        else -> "[midia]"
    }

    private fun wrap(lines: List<String>, max: Int = 220): List<String> {
        val out = ArrayList<String>()
        for (raw in lines) for (piece in raw.replace("\r", "").split("\n")) {
            var p = piece
            while (p.length > max) { out += p.substring(0, max); p = p.substring(max) }
            out += p
        }
        return out.take(64)
    }

    private fun floorMod(a: Int, b: Int) = ((a % b) + b) % b

    companion object {
        private const val ROW_REPLY = "Responder"
        private const val ROW_LOAD_OLDER = "Carregar mais"
        private const val ROW_REACT = "Reagir"
        private const val ROW_DESCRIBE = "Descrever (IA)"
        private const val ROW_REPLY_QUOTE = "Responder citando"
        private const val CHOICE_SEND_TEXT = "Enviar texto"
        private const val CHOICE_SEND_AUDIO = "Enviar audio"
        private const val CHOICE_RETRY = "Regravar"
    }
}
