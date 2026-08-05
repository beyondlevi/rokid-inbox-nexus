package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.model.ChannelKind
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

    enum class View { LIST, THREAD, MSG_ACTIONS, QUICK, REACT, LISTENING, REVIEW, SEARCH_RESULTS, INFO, IMAGE, PHOTO_PREVIEW }
    /** Inbox filter: everything, unread-only, or a single channel. */
    sealed class Filter {
        data object All : Filter()
        data object Unread : Filter()
        data class Channel(val kind: ChannelKind) : Filter()
    }
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
        /** When set, render as a dense plain card body (up to 15 lines) instead of
         *  rich rows — used by the paged reader (AI description). */
        val bodyLines: List<String>? = null,
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
        data class ViewPhoto(val message: Message) : NavAction
        data class PlayAudio(val message: Message) : NavAction
        data class Describe(val message: Message) : NavAction
        data class SendQuick(val quick: QuickMessage) : NavAction
        data class SendReaction(val message: Message, val emoji: String) : NavAction
        data class Dictate(val quoting: Message?) : NavAction
        /** Capture a photo with the glasses camera to send as a reply. */
        data class CapturePhoto(val quoting: Message?) : NavAction
        data object SendPhoto : NavAction
        data object StopListening : NavAction
        data object SendReplyText : NavAction
        data object SendReplyAudio : NavAction
        data object Redictate : NavAction
    }

    var view: View = View.LIST
        private set

    // Data
    private var allChats: List<Chat> = emptyList()
    var filter: Filter = Filter.All
        private set
    var openChat: Chat? = null
        private set
    private var messages: List<Message> = emptyList()
    // Thread rendered as chunk rows so a long message reads in full across rows
    // (the hub caps a list row at 3 lines); every chunk maps back to its message.
    private var threadEntries: List<ThreadEntry> = emptyList()
    private var atStart = true
    private var canSendOpen = false
    private var canReactOpen = false
    private var canVoiceOpen = false
    private var canImageOpen = false
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
    // Transient "refreshing" flag: surfaced at the TOP of the list (subtitle + the
    // Atualizar row) so it stays visible even when the chat list scrolls off-screen.
    private var loading = false

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
    private var infoIndex = 0

    val reactions: List<Pair<String, String>> = listOf(
        "👍" to "Curtir", "❤️" to "Amei", "😂" to "Haha",
        "😮" to "Uau", "😢" to "Triste", "🙏" to "Obrigado",
    )

    /* ---------------- intake ---------------- */

    fun setAiConfigured(v: Boolean) { aiConfigured = v }
    fun setVoiceEnabled(v: Boolean) { voiceEnabled = v }
    fun setQuickMessages(list: List<QuickMessage>) { quickMessages = list }
    fun setStatus(line: String?) { statusLine = line }
    fun setLoading(v: Boolean) { loading = v }

    fun setInbox(chats: List<Chat>) {
        allChats = chats
        loading = false
        // If a channel filter is active but that channel no longer has chats, fall back to All.
        val f = filter
        if (f is Filter.Channel && f.kind !in availableChannels()) filter = Filter.All
        clampIndex(listIndex, listItemCount()) { listIndex = it }
    }

    fun setConversation(
        chat: Chat,
        msgs: List<Message>,
        atStart: Boolean,
        canSend: Boolean,
        canReact: Boolean,
        canVoice: Boolean,
        canImage: Boolean = false,
    ) {
        openChat = chat
        messages = msgs
        this.atStart = atStart
        canSendOpen = canSend
        canReactOpen = canReact
        canVoiceOpen = canVoice
        canImageOpen = canImage
        statusLine = null
        rebuildThread()
        view = View.THREAD
        // Focus the newest content (last message chunk); the hub scrolls to it.
        threadIndex = threadEntries.indexOfLast { it is ThreadEntry.MsgChunk }.coerceAtLeast(0)
    }

    private fun rebuildThread() {
        val soloVoice = messages.all {
            it.isOutgoing || it.senderName.isBlank() || it.senderName.equals(openChat?.name?.trim(), true)
        }
        val list = ArrayList<ThreadEntry>()
        messages.forEach { m ->
            val chunks = chunk(messageText(m))
            chunks.forEachIndexed { ci, c ->
                list += ThreadEntry.MsgChunk(m, c, if (ci == 0) speakerBadge(m, soloVoice) else null)
            }
        }
        if (canSendOpen) list += ThreadEntry.Action(ROW_REPLY)
        if (!atStart) list += ThreadEntry.Action(ROW_LOAD_OLDER)
        threadEntries = list
    }

    fun showInfo(title: String, lines: List<String>) {
        infoTitle = title; infoLines = lines; infoIndex = 0; statusLine = null; view = View.INFO
    }

    /**
     * Info/description paged for a dense plain-body reader: the whole text is
     * word-wrapped into narrow lines and grouped into pages that fill the card
     * body (which packs up to ~15 lines). Rotating pages; no per-line caret.
     */
    private fun infoPages(): List<List<String>> {
        val flat = infoLines.flatMap { chunk(it.replace("\n", " "), INFO_WRAP_CHARS) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }
        return flat.chunked(INFO_LINES_PER_PAGE)
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
        View.THREAD -> when (val e = threadEntries.getOrNull(threadIndex)) {
            is ThreadEntry.MsgChunk -> NavAction.OpenMessage(e.message)
            is ThreadEntry.Action -> when (e.name) {
                ROW_REPLY -> NavAction.ReplyToChat
                ROW_LOAD_OLDER -> NavAction.LoadOlder
                else -> NavAction.None
            }
            null -> NavAction.None
        }
        View.MSG_ACTIONS -> {
            val m = selectedMessage ?: return NavAction.None
            when (messageActionRows().getOrNull(actionsIndex)) {
                ROW_VIEW_PHOTO -> NavAction.ViewPhoto(m)
                ROW_PLAY_AUDIO -> NavAction.PlayAudio(m)
                ROW_REACT -> NavAction.React(m)
                ROW_DESCRIBE, ROW_TRANSCRIBE -> NavAction.Describe(m)
                ROW_REPLY_QUOTE -> NavAction.ReplyQuoting(m)
                else -> NavAction.None
            }
        }
        View.QUICK -> {
            val extras = quickExtras()
            if (quickIndex < extras.size) when (extras[quickIndex]) {
                ROW_DICTATE_VOICE -> NavAction.Dictate(quoting)
                ROW_TAKE_PHOTO -> NavAction.CapturePhoto(quoting)
                else -> NavAction.None
            } else quickMessages.getOrNull(quickIndex - extras.size)?.let { NavAction.SendQuick(it) } ?: NavAction.None
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
        View.PHOTO_PREVIEW -> NavAction.SendPhoto
        View.INFO, View.IMAGE -> NavAction.None
    }

    fun enterMessageActions(message: Message) { selectedMessage = message; actionsIndex = 0; view = View.MSG_ACTIONS }
    /** The image surface is on screen; keep the view so BACK returns to the thread. */
    fun enterImage() { statusLine = null; view = View.IMAGE }
    /** A freshly captured photo awaiting send confirmation (shown on the image surface). */
    fun enterPhotoPreview() { statusLine = null; view = View.PHOTO_PREVIEW }
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
            View.IMAGE -> view = View.THREAD
            View.PHOTO_PREVIEW -> view = View.QUICK // discard the capture, back to the reply picker
            View.INFO -> view = if (openChat != null) View.THREAD else View.LIST
        }
        return false
    }

    fun cycleFilter() {
        val cycle = filterCycle()
        val i = cycle.indexOf(filter).let { if (it < 0) 0 else it }
        filter = cycle[(i + 1) % cycle.size]
        clampIndex(listIndex, listItemCount()) { listIndex = it }
    }

    /** Channel kinds that currently have chats, in a stable display order. */
    private fun availableChannels(): List<ChannelKind> {
        val present = allChats.map { it.channel }.toSet()
        return CHANNEL_ORDER.filter { it in present }
    }

    /** Filter cycle: All -> Unread -> each connected channel -> back to All. */
    private fun filterCycle(): List<Filter> =
        listOf(Filter.All, Filter.Unread) + availableChannels().map { Filter.Channel(it) }

    private fun filterLabel(): String = when (val f = filter) {
        Filter.All -> "Todos"
        Filter.Unread -> "Nao lidos"
        is Filter.Channel -> channelName(f.kind)
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
        View.IMAGE -> Screen("Foto", null, listOf(Row(text = "Exibindo imagem...", tone = Tone.DIM)), "duplo volta", "image")
        View.PHOTO_PREVIEW -> Screen(
            title = "Foto capturada",
            subtitle = null,
            rows = listOf(Row(text = "Enviar", tone = Tone.ALERT, selected = true)),
            footer = "toque envia · duplo descarta",
            keySeed = "photo|${statusLine ?: ""}",
        )
    }

    private fun listScreen(): Screen {
        val headers = listHeaders()
        val chats = visibleChats()
        val rows = ArrayList<Row>()
        headers.forEachIndexed { i, h ->
            rows += Row(text = h, tone = if (listIndex == i) Tone.ALERT else Tone.NORMAL, selected = listIndex == i)
        }
        chats.forEachIndexed { i, c ->
            val idx = i + headers.size
            rows += Row(
                text = titleWithChannel(c),
                sub = c.lastMessagePreview.replace("\n", " ").trim().ifBlank { null },
                tone = if (c.unreadCount > 0) Tone.ALERT else Tone.NORMAL,
                selected = listIndex == idx,
            )
        }
        if (chats.isEmpty()) rows += Row(text = statusLine ?: if (loading) "Atualizando..." else "Nenhuma conversa.", tone = Tone.DIM)
        else statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = "Inbox",
            // Loading shown in the subtitle so it is visible at the top regardless of scroll.
            subtitle = if (loading) "Atualizando conversas..." else "${filterLabel()} · ${chats.size}",
            rows = rows,
            footer = if (chats.isEmpty()) "duplo sai" else "${(listIndex - headers.size + 1).coerceAtLeast(1)}/${chats.size} · girar · toque · duplo sai",
            keySeed = "list|$filter|$listIndex|${chats.size}|${loading}|${statusLine ?: ""}",
        )
    }

    private fun threadScreen(): Screen {
        val chat = openChat
        val rows = threadEntries.mapIndexed { i, e ->
            val on = threadIndex == i
            when (e) {
                is ThreadEntry.MsgChunk -> Row(
                    text = e.text,
                    badge = e.badge,
                    tone = if (on) Tone.ALERT else Tone.BODY,
                    selected = on,
                )
                is ThreadEntry.Action -> Row(text = e.name, tone = if (on) Tone.ALERT else Tone.NORMAL, selected = on)
            }
        }.toMutableList()
        if (rows.isEmpty()) rows += Row(text = "Sem mensagens.", tone = Tone.DIM)
        statusLine?.let { rows += Row(text = it, tone = Tone.DIM) }
        return Screen(
            title = chat?.let { "${it.boxLabel} ${it.name}".trim() }?.take(120) ?: "Conversa",
            subtitle = null,
            rows = rows,
            footer = "girar · toque · duplo volta",
            keySeed = "thread|${chat?.boxId}:${chat?.id}|$threadIndex|${threadEntries.size}|${statusLine ?: ""}",
        )
    }

    private fun actionsScreen(): Screen {
        val m = selectedMessage
        val rows = ArrayList<Row>()
        // Show the full message (chunked so nothing truncates) above the actions.
        m?.let { chunk(messageText(it)).forEach { c -> rows += Row(text = c, tone = Tone.BODY) } }
        val actions = messageActionRows()
        if (actions.isEmpty()) {
            rows += Row(text = "Sem acoes.", tone = Tone.DIM)
        } else {
            actions.forEachIndexed { i, a ->
                rows += Row(text = a, tone = if (actionsIndex == i) Tone.ALERT else Tone.NORMAL, selected = actionsIndex == i)
            }
        }
        return Screen(
            title = "Mensagem",
            subtitle = null,
            rows = rows,
            footer = "girar · toque · duplo volta",
            keySeed = "acts|${m?.id}|$actionsIndex|${messageActionRows().size}",
        )
    }

    private fun quickScreen(): Screen {
        val rows = ArrayList<Row>()
        val extras = quickExtras()
        extras.forEachIndexed { i, e ->
            rows += Row(text = e, tone = if (quickIndex == i) Tone.ALERT else Tone.NORMAL, selected = quickIndex == i)
        }
        quickMessages.forEachIndexed { i, q ->
            val idx = i + extras.size
            rows += Row(text = q.title, sub = q.body, tone = if (quickIndex == idx) Tone.ALERT else Tone.NORMAL, selected = quickIndex == idx)
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
        val rows = reactions.mapIndexed { i, r ->
            Row(text = "${r.first} ${r.second}", tone = if (reactIndex == i) Tone.ALERT else Tone.NORMAL, selected = reactIndex == i)
        }
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
            Row(text = titleWithChannel(c), sub = c.lastMessagePreview.replace("\n", " ").trim().ifBlank { null }, selected = searchIndex == i)
        }.ifEmpty { listOf(Row(text = "Nada encontrado.", tone = Tone.DIM)) }
        return Screen("Busca: ${searchQuery.take(40)}", "${searchResults.size}", rows, "girar · toque abre · duplo volta", "search|$searchQuery|$searchIndex|${searchResults.size}")
    }

    private fun infoScreen(): Screen {
        val pages = infoPages()
        val page = pages.getOrElse(infoIndex) { pages.first() }
        return Screen(
            title = infoTitle.ifBlank { "Info" },
            subtitle = if (pages.size > 1) "pagina ${infoIndex + 1}/${pages.size}" else null,
            rows = emptyList(),
            footer = if (pages.size > 1) "girar pagina · duplo volta" else "duplo volta",
            keySeed = "info|$infoTitle|${pages.size}|$infoIndex",
            bodyLines = page,
        )
    }

    /* ---------------- rows / helpers ---------------- */

    private fun visibleChats(): List<Chat> = when (val f = filter) {
        Filter.All -> allChats
        Filter.Unread -> allChats.filter { it.unreadCount > 0 }
        is Filter.Channel -> allChats.filter { it.channel == f.kind }
    }

    private fun listHeaders(): List<String> {
        val h = arrayListOf(
            "Filtro: ${filterLabel()}",
            if (loading) "Atualizando..." else "Atualizar",
        )
        if (voiceEnabled) h += "Buscar por voz"
        return h
    }

    private fun messageActionRows(): List<String> {
        val m = selectedMessage ?: return emptyList()
        val rows = ArrayList<String>()
        if (m.isImageMedia) rows += ROW_VIEW_PHOTO
        if (m.isPlayableAudio) rows += ROW_PLAY_AUDIO
        if (m.canDescribe && aiConfigured) rows += ROW_DESCRIBE // image / file
        if (m.isPlayableAudio && aiConfigured) rows += ROW_TRANSCRIBE // voice note -> text
        if (canReactOpen) rows += ROW_REACT
        if (canSendOpen) rows += ROW_REPLY_QUOTE
        return rows
    }

    /** Non-canned reply options shown above the quick messages, in order. */
    private fun quickExtras(): List<String> {
        val e = ArrayList<String>()
        if (voiceEnabled && canSendOpen) e += ROW_DICTATE_VOICE
        if (canSendOpen && canImageOpen) e += ROW_TAKE_PHOTO
        return e
    }
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
        View.THREAD -> threadEntries.size
        View.MSG_ACTIONS -> messageActionRows().size
        View.QUICK -> quickExtras().size + quickMessages.size
        View.REACT -> reactions.size
        View.REVIEW -> reviewChoices().size
        View.SEARCH_RESULTS -> searchResults.size
        View.INFO -> infoPages().size
        View.LISTENING, View.IMAGE, View.PHOTO_PREVIEW -> 0
    }

    private fun index(): Int = when (view) {
        View.LIST -> listIndex
        View.THREAD -> threadIndex
        View.MSG_ACTIONS -> actionsIndex
        View.QUICK -> quickIndex
        View.REACT -> reactIndex
        View.REVIEW -> reviewIndex
        View.SEARCH_RESULTS -> searchIndex
        View.INFO -> infoIndex
        View.LISTENING, View.IMAGE, View.PHOTO_PREVIEW -> 0
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
            View.INFO -> infoIndex = v
            View.LISTENING, View.IMAGE, View.PHOTO_PREVIEW -> {}
        }
    }

    private fun clampIndex(current: Int, count: Int, set: (Int) -> Unit) {
        if (current >= count) set((count - 1).coerceAtLeast(0))
    }

    private fun chatTitle(c: Chat): String = c.name.ifBlank { "?" }.take(120)

    /**
     * Chat title prefixed with the channel pseudo-icon. The emoji goes in the row
     * TEXT (not the badge field): the hub renders emoji in text — as proven by the
     * thread title — but does not surface the badge glyph on list/search rows.
     */
    private fun titleWithChannel(c: Chat): String {
        val g = c.boxLabel.trim()
        return if (g.isEmpty()) chatTitle(c) else "$g ${chatTitle(c)}".take(120)
    }

    // The sender is carried by the row badge ("Eu" / name), so the text is just
    // the message body — prefixing "Eu:" here duplicated the speaker column.
    private fun messageText(m: Message): String =
        m.text.ifBlank { mediaLabel(m) }.replace("\n", " ").trim().take(240)

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

    private fun floorMod(a: Int, b: Int) = ((a % b) + b) % b

    /**
     * Word-aware split so each piece fits a list row (the hub caps a list row at
     * 3 lines and ellipsizes past that). Splitting a long message into pieces
     * lets the whole thing read across rows, with the hub scrolling.
     */
    private fun chunk(text: String, max: Int = THREAD_CHUNK_CHARS): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word0 in text.split(" ")) {
            var word = word0
            while (word.length > max) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
                out += word.substring(0, max)
                word = word.substring(max)
            }
            if (word.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(word)
                sb.length + 1 + word.length <= max -> sb.append(' ').append(word)
                else -> { out += sb.toString(); sb.setLength(0); sb.append(word) }
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.ifEmpty { listOf("") }
    }

    /** A thread row: a chunk of a message (mapped back to it) or a trailing action. */
    private sealed interface ThreadEntry {
        data class MsgChunk(val message: Message, val text: String, val badge: String?) : ThreadEntry
        data class Action(val name: String) : ThreadEntry
    }

    companion object {
        /** Stable order used both for the filter cycle and any per-kind display. */
        private val CHANNEL_ORDER = listOf(
            ChannelKind.WHATSAPP, ChannelKind.TELEGRAM, ChannelKind.GMAIL, ChannelKind.GITHUB,
        )
        fun channelName(kind: ChannelKind): String = when (kind) {
            ChannelKind.WHATSAPP -> "WhatsApp"
            ChannelKind.TELEGRAM -> "Telegram"
            ChannelKind.GMAIL -> "Gmail"
            ChannelKind.GITHUB -> "GitHub"
        }
        private const val THREAD_CHUNK_CHARS = 52
        // Dense reader for the AI description: wrap narrow (<= plain-body width
        // ~27 cols) and pack a page under the body's ~15-line cap.
        private const val INFO_WRAP_CHARS = 22
        private const val INFO_LINES_PER_PAGE = 12
        private const val ROW_DICTATE_VOICE = "Ditar por voz"
        private const val ROW_TAKE_PHOTO = "Tirar foto"
        private const val ROW_REPLY = "Responder"
        private const val ROW_LOAD_OLDER = "Carregar mais"
        private const val ROW_VIEW_PHOTO = "Ver foto"
        private const val ROW_PLAY_AUDIO = "Reproduzir audio"
        private const val ROW_TRANSCRIBE = "Transcrever (IA)"
        private const val ROW_REACT = "Reagir"
        private const val ROW_DESCRIBE = "Descrever (IA)"
        private const val ROW_REPLY_QUOTE = "Responder citando"
        private const val CHOICE_SEND_TEXT = "Enviar texto"
        private const val CHOICE_SEND_AUDIO = "Enviar audio"
        private const val CHOICE_RETRY = "Regravar"
    }
}
