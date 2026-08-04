package com.rokid.inbox.nexus

import android.content.Context
import com.rokid.inbox.nexus.ai.AiDescriber
import com.rokid.inbox.nexus.channels.ChannelService
import com.rokid.inbox.nexus.channels.GitHubService
import com.rokid.inbox.nexus.channels.GmailService
import com.rokid.inbox.nexus.channels.InboxAggregator
import com.rokid.inbox.nexus.channels.TelegramService
import com.rokid.inbox.nexus.channels.WhatsAppService
import com.rokid.inbox.nexus.model.ChannelKind
import com.rokid.inbox.nexus.model.Chat
import com.rokid.inbox.nexus.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Phone-side brain: owns the channel services, the OpenAI describer/transcriber
 * and the config; executes the intents [InboxNavState] reports; pushes rich-row
 * screens to the HUD through a [SurfaceHost]. Voice replies capture the glasses
 * mic (raw PCM), keep the WAV so the original audio can be sent, and use OpenAI
 * Whisper to offer a transcribed-text send too.
 */
class InboxRuntime(
    private val appContext: Context,
    private val host: SurfaceHost,
) {
    interface SurfaceHost {
        fun render(screen: InboxNavState.Screen)
        fun selfClose()
        fun startMic(): MicStart
        fun stopMic()
    }

    enum class MicStart { SENT, NOT_GRANTED, NOT_READY, UNAVAILABLE }

    val nav = InboxNavState()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val config = InboxConfigStore(appContext)
    private var services: List<ChannelService> = emptyList()
    private var ai = AiDescriber("")
    private var stt = SpeechToText("")
    private var chatLimit = INITIAL_LIMIT

    private val micBuffer = ByteArrayOutputStream()
    private var micSampleRate = 16_000
    private var listening = false
    private var cancelDictation = false
    private var lastWav: ByteArray? = null
    private var lastDurationSec = 0

    /* ---------------- lifecycle ---------------- */

    fun open() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        resetVoiceBuffers()
        val key = config.getOpenAiKey()
        ai = AiDescriber(key)
        stt = SpeechToText(key, config.getSttModel(), config.getSttLanguage())
        services = instantiateServices()
        nav.setAiConfigured(ai.isConfigured)
        nav.setVoiceEnabled(config.isSttEnabled())
        nav.setQuickMessages(config.getQuickMessages())
        nav.resetToInbox()
        nav.setStatus("Carregando inbox...")
        host.render(nav.screen())
        fetchInbox()
    }

    fun close() {
        scope.cancel()
        resetVoiceBuffers()
    }

    /* ---------------- input ---------------- */

    fun onNext() { nav.move(1); render() }
    fun onPrev() { nav.move(-1); render() }
    fun onSelect() = dispatch(nav.activate())
    fun onBack() {
        if (nav.view == InboxNavState.View.LISTENING && listening) {
            cancelDictation = true
            host.stopMic()
        }
        if (nav.back()) host.selfClose() else render()
    }

    private fun render() = host.render(nav.screen())

    /* ---------------- dispatch ---------------- */

    private fun dispatch(action: InboxNavState.NavAction) {
        when (action) {
            InboxNavState.NavAction.None -> Unit
            InboxNavState.NavAction.CycleFilter -> { nav.cycleFilter(); render() }
            InboxNavState.NavAction.Refresh -> fetchInbox()
            InboxNavState.NavAction.VoiceSearch -> startVoiceSearch()
            is InboxNavState.NavAction.OpenChat -> openChat(action.chat, INITIAL_LIMIT)
            InboxNavState.NavAction.LoadOlder -> nav.openChat?.let { openChat(it, chatLimit + INITIAL_LIMIT) }
            is InboxNavState.NavAction.OpenMessage -> { nav.enterMessageActions(action.message); render() }
            InboxNavState.NavAction.ReplyToChat -> { nav.enterQuick(null); render() }
            is InboxNavState.NavAction.ReplyQuoting -> { nav.enterQuick(action.message); render() }
            is InboxNavState.NavAction.React -> { nav.enterReact(action.message); render() }
            is InboxNavState.NavAction.Describe -> describe(action.message)
            is InboxNavState.NavAction.SendQuick -> sendText(action.quick.body, nav.quotingMessage())
            is InboxNavState.NavAction.SendReaction -> react(action.message, action.emoji)
            is InboxNavState.NavAction.Dictate -> startDictation(action.quoting)
            InboxNavState.NavAction.StopListening -> { nav.setStatus("Transcrevendo..."); render(); host.stopMic() }
            InboxNavState.NavAction.SendReplyText -> {
                val text = nav.currentTranscript.trim()
                if (text.isBlank()) { nav.showInfo("Resposta", listOf("Nada para enviar.")); render() }
                else sendText(text, nav.quotingMessage())
            }
            InboxNavState.NavAction.SendReplyAudio -> sendAudio(nav.quotingMessage())
            InboxNavState.NavAction.Redictate -> startDictation(nav.quotingMessage())
        }
    }

    /* ---------------- inbox / thread ---------------- */

    private fun fetchInbox() {
        nav.setStatus("Carregando inbox...")
        render()
        scope.launch {
            val labels = computeBoxLabels(services)
            val chats = withContext(Dispatchers.IO) {
                runCatching { InboxAggregator.fetchUnifiedInbox(services, MAX_CHATS) }.getOrDefault(emptyList())
            }.map { it.copy(boxLabel = labels[it.boxId].orEmpty()) }
            nav.setInbox(chats)
            nav.setStatus(if (services.isEmpty()) "Nenhum canal configurado (abra os ajustes no celular)." else null)
            render()
        }
    }

    private fun openChat(chat: Chat, limit: Int) {
        chatLimit = limit
        nav.setStatus("Abrindo conversa...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null) { nav.showInfo("Erro", listOf("Canal nao conectado.")); render(); return@launch }
            val result = withContext(Dispatchers.IO) { runCatching { svc.listMessages(chat.id, limit) } }
            result.onSuccess { msgs ->
                nav.setConversation(chat, msgs, msgs.size < limit, svc.canSend, svc.canReact, svc.canSendVoice)
                render()
                if (chat.unreadCount > 0) withContext(Dispatchers.IO) { runCatching { svc.markAsRead(chat.id, msgs) } }
            }.onFailure {
                nav.showInfo("Erro", listOf("Falha ao carregar: ${it.message?.take(160).orEmpty()}"))
                render()
            }
        }
    }

    private fun sendText(text: String, quoting: Message?) {
        val chat = nav.openChat ?: return
        nav.setStatus("Enviando...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null || !svc.canSend) { nav.showInfo("Resposta", listOf("Canal somente leitura.")); render(); return@launch }
            val res = withContext(Dispatchers.IO) {
                runCatching { svc.sendText(chat.id, text, quoting?.id.orEmpty(), quoting?.isOutgoing ?: false) }
            }
            nav.showInfo("Resposta", listOf(if (res.isSuccess) "Mensagem enviada." else "Falha: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun sendAudio(quoting: Message?) {
        val chat = nav.openChat ?: return
        val wav = lastWav
        if (wav == null) { nav.showInfo("Resposta", listOf("Nenhum audio gravado.")); render(); return }
        nav.setStatus("Enviando audio...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null || !svc.canSendVoice) { nav.showInfo("Resposta", listOf("Este canal nao aceita audio.")); render(); return@launch }
            val res = withContext(Dispatchers.IO) {
                runCatching { svc.sendVoice(chat.id, wav, lastDurationSec, quoting?.id.orEmpty(), quoting?.isOutgoing ?: false) }
            }
            nav.showInfo("Resposta", listOf(if (res.isSuccess) "Audio enviado." else "Falha: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun react(message: Message, emoji: String) {
        val chat = nav.openChat ?: return
        nav.setStatus("Reagindo...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null || !svc.canReact) { nav.showInfo("Reacao", listOf("Reacoes nao suportadas.")); render(); return@launch }
            val res = withContext(Dispatchers.IO) { runCatching { svc.sendReaction(chat.id, message.id, emoji, message.isOutgoing) } }
            nav.showInfo("Reacao", listOf(if (res.isSuccess) "Reagiu com $emoji." else "Falha: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun describe(message: Message) {
        val chat = nav.openChat ?: return
        if (!ai.isConfigured) { nav.showInfo("Descrever (IA)", listOf("Configure a chave OpenAI no celular.")); render(); return }
        nav.setStatus("Descrevendo com IA...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) { runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) { nav.showInfo("Descrever (IA)", listOf("Midia indisponivel.")); render(); return@launch }
            val lang = java.util.Locale.getDefault().language
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    if (message.isImageMedia) ai.describeImage(bytes, lang) else ai.describeFile(bytes, message.fileName, lang)
                }
            }
            text.onSuccess { nav.showInfo("Descricao (IA)", listOf(it)) }
                .onFailure { nav.showInfo("Descrever (IA)", listOf("Falha: ${it.message?.take(180).orEmpty()}")) }
            render()
        }
    }

    /* ---------------- voice ---------------- */

    private fun startDictation(quoting: Message?) = beginListen { nav.enterListening(quoting) }
    private fun startVoiceSearch() = beginListen { nav.enterVoiceSearch() }

    private fun beginListen(enter: () -> Unit) {
        resetVoiceBuffers()
        cancelDictation = false
        listening = false
        enter()
        nav.setStatus("Solicitando microfone...")
        render()
        when (host.startMic()) {
            MicStart.SENT -> Unit
            MicStart.NOT_GRANTED -> { nav.showInfo("Voz", listOf("Ative o microfone para este plugin em Plugin access.")); render() }
            MicStart.NOT_READY -> { nav.showInfo("Voz", listOf("Hub nao conectado. Tente de novo.")); render() }
            MicStart.UNAVAILABLE -> { nav.showInfo("Voz", listOf("Microfone indisponivel.")); render() }
        }
    }

    fun onMicStarted(sampleRate: Int) {
        micSampleRate = if (sampleRate > 0) sampleRate else 16_000
        micBuffer.reset()
        listening = true
        if (nav.view == InboxNavState.View.LISTENING) {
            nav.setStatus(if (nav.listenPurpose == InboxNavState.ListenPurpose.SEARCH) "Ouvindo o nome..." else "Ouvindo... toque para parar.")
            render()
        }
    }

    fun onMicFrame(pcm: ByteArray) { if (listening) micBuffer.write(pcm) }

    fun onMicStopped(reason: String) {
        listening = false
        val pcm = micBuffer.toByteArray()
        micBuffer.reset()
        if (cancelDictation) { cancelDictation = false; return }
        if (reason != "RELEASED") { nav.showInfo("Voz", listOf(micErrorText(reason))); render(); return }
        if (pcm.size < MIN_AUDIO_BYTES) { nav.showInfo("Voz", listOf("Nada capturado.")); render(); return }
        lastWav = Pcm16Wav.encode(pcm, micSampleRate)
        lastDurationSec = (pcm.size / (micSampleRate * 2)).coerceAtLeast(1)
        val search = nav.listenPurpose == InboxNavState.ListenPurpose.SEARCH
        nav.setStatus(if (search) "Buscando..." else "Transcrevendo...")
        render()
        scope.launch {
            val text = if (stt.isConfigured) {
                withContext(Dispatchers.IO) { runCatching { stt.transcribe(pcm, micSampleRate) }.getOrDefault("") }
            } else ""
            if (search) {
                if (text.isBlank()) { nav.showInfo("Busca", listOf("Nao entendi. Tente de novo.")); render() }
                else runSearch(text)
            } else {
                // Reply: offer the transcript (if any) and/or the original audio.
                nav.showReview(transcript = text, hasAudio = true)
                render()
            }
        }
    }

    private suspend fun runSearch(query: String) {
        val labels = computeBoxLabels(services)
        val matches = withContext(Dispatchers.IO) {
            runCatching { InboxAggregator.searchChatsByName(services, query, SEARCH_LIMIT) }.getOrDefault(emptyList())
        }.map { it.copy(boxLabel = labels[it.boxId].orEmpty()) }
        nav.showSearchResults(query, matches)
        render()
    }

    private fun resetVoiceBuffers() {
        micBuffer.reset()
        lastWav = null
        lastDurationSec = 0
    }

    private fun micErrorText(reason: String): String = when (reason) {
        "REVOKED" -> "Microfone perdido (link caiu ou outro app assumiu)."
        "DENIED_BUSY" -> "Microfone em uso por outro plugin."
        "DENIED_NO_LINK" -> "Sem conexao com os oculos."
        "DENIED_NOT_GRANTED" -> "Ative o microfone em Plugin access."
        else -> "Falha ao capturar audio."
    }

    /* ---------------- channels ---------------- */

    private fun serviceFor(boxId: String): ChannelService? = services.firstOrNull { it.boxId == boxId }

    private fun instantiateServices(): List<ChannelService> =
        config.getBoxes().mapNotNull { box ->
            runCatching {
                when (box.kind) {
                    ChannelKind.WHATSAPP -> WhatsAppService(box.id, box.config["serverUrl"].orEmpty(), box.config["instance"].orEmpty(), box.config["apiKey"].orEmpty())
                    ChannelKind.GITHUB -> GitHubService(box.id, box.config["token"].orEmpty(), box.config["query"].orEmpty())
                    ChannelKind.TELEGRAM -> TelegramService(box.id, box.config["serverUrl"].orEmpty(), box.config["apiKey"].orEmpty())
                    ChannelKind.GMAIL -> GmailService(box.id, box.config["clientId"].orEmpty(), box.config["clientSecret"].orEmpty(), box.config["refreshToken"].orEmpty())
                }
            }.getOrNull()
        }

    companion object {
        private const val MAX_CHATS = 40
        private const val INITIAL_LIMIT = 20
        private const val SEARCH_LIMIT = 200
        private const val MIN_AUDIO_BYTES = 3_200

        fun glyph(kind: ChannelKind): String = when (kind) {
            ChannelKind.WHATSAPP -> "W"; ChannelKind.TELEGRAM -> "T"; ChannelKind.GMAIL -> "E"; ChannelKind.GITHUB -> "PR"
        }

        fun computeBoxLabels(services: List<ChannelService>): Map<String, String> {
            val out = HashMap<String, String>()
            services.groupBy { it.kind }.forEach { (kind, list) ->
                val base = glyph(kind)
                list.forEachIndexed { i, svc -> out[svc.boxId] = if (list.size > 1) "[$base${i + 1}]" else "[$base]" }
            }
            return out
        }
    }
}
