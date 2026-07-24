package com.rokid.inbox.nexus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The phone-side brain of the plugin. Owns the channel services, the OpenAI
 * describer and the config, executes the intents that [InboxNavState] reports,
 * and pushes the resulting surface to the HUD through a [SurfaceHost]. All I/O
 * runs on a scope that is cancelled on close; the plugin is dormant otherwise.
 */
class InboxRuntime(
    private val appContext: Context,
    private val host: SurfaceHost,
) {
    /** The service renders NavState screens/images and owns the mic session. */
    interface SurfaceHost {
        fun renderCard(screen: InboxNavState.Screen)
        /** @return true if the image was shown; false = image surface unavailable. */
        fun renderImage(contentKey: String, title: String, caption: String, jpeg: ByteArray, width: Int, height: Int): Boolean
        fun selfClose()
        /** Acquire the glasses-mic lease; audio is delivered back via onMic*. */
        fun startMic(): MicStart
        fun stopMic()
    }

    enum class MicStart { SENT, NOT_GRANTED, NOT_READY, UNAVAILABLE }

    val nav = InboxNavState()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val config = InboxConfigStore(appContext)
    private var services: List<ChannelService> = emptyList()
    private var ai: AiDescriber = AiDescriber("")
    private var stt: SpeechToText = SpeechToText("")
    private var chatLimit = INITIAL_LIMIT

    // Voice capture buffer (raw 16 kHz mono PCM16 from the glasses mic).
    private val micBuffer = ByteArrayOutputStream()
    private var micSampleRate = 16_000
    private var listening = false
    private var cancelDictation = false

    /* ---------------- lifecycle ---------------- */

    fun open() {
        // Re-entrant open: rebuild everything fresh (the process may be cold).
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val openAiKey = config.getOpenAiKey()
        ai = AiDescriber(openAiKey)
        stt = SpeechToText(openAiKey, config.getSttModel(), config.getSttLanguage())
        services = instantiateServices()
        nav.setAiConfigured(ai.isConfigured)
        nav.setSttEnabled(config.isSttEnabled() && stt.isConfigured)
        nav.setQuickMessages(config.getQuickMessages())
        nav.resetToInbox()
        nav.setStatus("Carregando inbox...")
        host.renderCard(nav.screen())
        fetchInbox()
    }

    fun close() {
        scope.cancel()
    }

    /* ---------------- input (from the service) ---------------- */

    fun onNext() { nav.move(1); render() }
    fun onPrev() { nav.move(-1); render() }
    fun onBack() {
        // BACK while listening cancels the dictation without transcribing.
        if (nav.view == InboxNavState.View.LISTENING && listening) {
            cancelDictation = true
            host.stopMic()
        }
        if (nav.back()) host.selfClose() else render()
    }

    fun onSelect() = dispatch(nav.activate())

    private fun render() = host.renderCard(nav.screen())

    /* ---------------- action dispatch ---------------- */

    private fun dispatch(action: InboxNavState.NavAction) {
        when (action) {
            InboxNavState.NavAction.None -> Unit
            InboxNavState.NavAction.CycleFilter -> { nav.cycleFilter(); render() }
            InboxNavState.NavAction.Refresh -> fetchInbox()
            is InboxNavState.NavAction.OpenChat -> openChat(action.chat, INITIAL_LIMIT)
            InboxNavState.NavAction.LoadOlder -> nav.openChat?.let { openChat(it, chatLimit + INITIAL_LIMIT) }
            is InboxNavState.NavAction.OpenMessage -> { nav.enterMessageActions(action.message); render() }
            InboxNavState.NavAction.ReplyToChat -> { nav.enterQuick(null); render() }
            is InboxNavState.NavAction.ReplyQuoting -> { nav.enterQuick(action.message); render() }
            is InboxNavState.NavAction.React -> { nav.enterReact(action.message); render() }
            is InboxNavState.NavAction.ViewPhoto -> viewPhoto(action.message)
            is InboxNavState.NavAction.Describe -> describe(action.message)
            is InboxNavState.NavAction.SendQuick -> sendText(action.quick.body, nav.quotingMessage())
            is InboxNavState.NavAction.SendReaction -> react(action.message, action.emoji)
            is InboxNavState.NavAction.Dictate -> startDictation(action.quoting)
            InboxNavState.NavAction.StopListening -> {
                nav.setStatus("Transcrevendo...")
                render()
                host.stopMic()
            }
            InboxNavState.NavAction.SendTranscript -> {
                val text = nav.currentTranscript.trim()
                if (text.isBlank()) { nav.showInfo("Voz", listOf("Nada para enviar.")); render() }
                else sendText(text, nav.quotingMessage())
            }
            InboxNavState.NavAction.Redictate -> startDictation(nav.quotingMessage())
        }
    }

    /* ---------------- voice dictation (mic -> STT) ---------------- */

    private fun startDictation(quoting: Message?) {
        if (!stt.isConfigured) {
            nav.showInfo("Voz", listOf("Configure a chave OpenAI e ative o STT nos ajustes do celular."))
            render(); return
        }
        micBuffer.reset()
        cancelDictation = false
        listening = false
        nav.enterListening(quoting)
        nav.setStatus("Solicitando microfone...")
        render()
        when (host.startMic()) {
            MicStart.SENT -> Unit // wait for onMicStarted / onMicStopped
            MicStart.NOT_GRANTED -> {
                nav.showInfo("Voz", listOf("Ative o microfone para este plugin em Plugin access (no app Nexus)."))
                render()
            }
            MicStart.NOT_READY -> {
                nav.showInfo("Voz", listOf("Hub ainda nao conectado. Tente de novo."))
                render()
            }
            MicStart.UNAVAILABLE -> {
                nav.showInfo("Voz", listOf("Microfone indisponivel."))
                render()
            }
        }
    }

    /** Mic callbacks, forwarded by the service (serialized on the main thread). */
    fun onMicStarted(sampleRate: Int) {
        micSampleRate = if (sampleRate > 0) sampleRate else 16_000
        micBuffer.reset()
        listening = true
        if (nav.view == InboxNavState.View.LISTENING) {
            nav.setStatus("Ouvindo... toque para parar.")
            render()
        }
    }

    fun onMicFrame(pcm: ByteArray) {
        if (listening) micBuffer.write(pcm)
    }

    fun onMicStopped(reason: String) {
        listening = false
        val audioBytes = micBuffer.toByteArray()
        micBuffer.reset()
        if (cancelDictation) { cancelDictation = false; return }
        if (reason != "RELEASED") {
            nav.showInfo("Voz", listOf(micErrorText(reason)))
            render(); return
        }
        transcribeBuffer(audioBytes)
    }

    private fun transcribeBuffer(audioBytes: ByteArray) {
        nav.setStatus("Transcrevendo...")
        render()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { stt.transcribe(audioBytes, micSampleRate) }
            }
            result.onSuccess { text ->
                if (text.isBlank()) nav.showInfo("Voz", listOf("Nao entendi. Tente de novo."))
                else nav.showTranscript(text)
            }.onFailure {
                nav.showInfo("Voz", listOf("Falha na transcricao: ${it.message?.take(160).orEmpty()}"))
            }
            render()
        }
    }

    private fun micErrorText(reason: String): String = when (reason) {
        "REVOKED" -> "Microfone perdido (link caiu ou outro app assumiu)."
        "DENIED_BUSY" -> "Microfone em uso por outro plugin."
        "DENIED_NO_LINK" -> "Sem conexao com os oculos."
        "DENIED_NOT_GRANTED" -> "Ative o microfone para este plugin em Plugin access."
        else -> "Falha ao capturar audio."
    }

    /* ---------------- operations ---------------- */

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
            if (svc == null) {
                nav.showInfo("Erro", listOf("Canal nao conectado."))
                render(); return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { svc.listMessages(chat.id, limit) }
            }
            result.onSuccess { msgs ->
                nav.setConversation(
                    chat = chat,
                    msgs = msgs,
                    atStart = msgs.size < limit,
                    canSend = svc.canSend,
                    canReact = svc.canReact,
                )
                render()
                if (chat.unreadCount > 0) {
                    withContext(Dispatchers.IO) { runCatching { svc.markAsRead(chat.id, msgs) } }
                }
            }.onFailure { e ->
                nav.showInfo("Erro", listOf("Falha ao carregar: ${e.message?.take(160).orEmpty()}"))
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
            if (svc == null || !svc.canSend) {
                nav.showInfo("Resposta", listOf("Este canal e somente leitura."))
                render(); return@launch
            }
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    svc.sendText(chat.id, text, quoting?.id.orEmpty(), quoting?.isOutgoing ?: false)
                }
            }
            if (res.isSuccess) nav.showInfo("Resposta", listOf("Mensagem enviada."))
            else nav.showInfo("Resposta", listOf("Falha ao enviar: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun react(message: Message, emoji: String) {
        val chat = nav.openChat ?: return
        nav.setStatus("Reagindo...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null || !svc.canReact) {
                nav.showInfo("Reacao", listOf("Reacoes nao suportadas neste canal."))
                render(); return@launch
            }
            val res = withContext(Dispatchers.IO) {
                runCatching { svc.sendReaction(chat.id, message.id, emoji, message.isOutgoing) }
            }
            if (res.isSuccess) nav.showInfo("Reacao", listOf("Reagiu com $emoji."))
            else nav.showInfo("Reacao", listOf("Falha: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun viewPhoto(message: Message) {
        val chat = nav.openChat ?: return
        nav.setStatus("Carregando foto...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) {
                runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                nav.showInfo("Foto", listOf("Imagem indisponivel."))
                render(); return@launch
            }
            val img = withContext(Dispatchers.Default) { preprocessImage(bytes) }
            if (img == null) {
                nav.showInfo("Foto", listOf("Nao foi possivel decodificar a imagem."))
                render(); return@launch
            }
            val shown = host.renderImage(
                contentKey = "photo-${chat.boxId}-${message.id}",
                title = "Foto",
                caption = if (message.senderName.isNotBlank()) message.senderName else "",
                jpeg = img.bytes,
                width = img.width,
                height = img.height,
            )
            if (shown) {
                nav.enterImage()
            } else {
                nav.showInfo("Foto", listOf("Pre-visualizacao de imagem indisponivel nestes oculos."))
                render()
            }
        }
    }

    private fun describe(message: Message) {
        val chat = nav.openChat ?: return
        if (!ai.isConfigured) {
            nav.showInfo("Descrever (IA)", listOf("Configure a chave OpenAI no celular."))
            render(); return
        }
        nav.setStatus("Descrevendo com IA...")
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) {
                runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                nav.showInfo("Descrever (IA)", listOf("Midia indisponivel."))
                render(); return@launch
            }
            val lang = Locale.getDefault().language
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    if (message.isImageMedia) {
                        val jpeg = preprocessImage(bytes, maxDim = 1024, quality = 85)?.bytes ?: bytes
                        ai.describeImage(jpeg, lang)
                    } else {
                        ai.describeFile(bytes, message.fileName, lang)
                    }
                }
            }
            text.onSuccess { nav.showInfo("Descricao (IA)", listOf(it)) }
                .onFailure { nav.showInfo("Descrever (IA)", listOf("Falha: ${it.message?.take(180).orEmpty()}")) }
            render()
        }
    }

    /* ---------------- image preprocessing ---------------- */

    private data class Img(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Correct-size a photo for the image surface: decode, downscale so both
     * edges are <= [maxDim] (image-surface cap is 512 px), JPEG-encode and step
     * the quality down until it fits under 64 KiB.
     */
    private fun preprocessImage(bytes: ByteArray, maxDim: Int = 480, quality: Int = 80): Img? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        var q = quality
        var out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
        while (out.size() > MAX_IMAGE_BYTES && q > 30) {
            q -= 10
            out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
        }
        if (out.size() > MAX_IMAGE_BYTES) return null
        return Img(out.toByteArray(), scaled.width, scaled.height)
    }

    /* ---------------- channel wiring ---------------- */

    private fun serviceFor(boxId: String): ChannelService? = services.firstOrNull { it.boxId == boxId }

    private fun instantiateServices(): List<ChannelService> =
        config.getBoxes().mapNotNull { box ->
            runCatching {
                when (box.kind) {
                    ChannelKind.WHATSAPP -> WhatsAppService(
                        boxId = box.id,
                        serverUrl = box.config["serverUrl"].orEmpty(),
                        instance = box.config["instance"].orEmpty(),
                        apiKey = box.config["apiKey"].orEmpty(),
                    )
                    ChannelKind.GITHUB -> GitHubService(
                        boxId = box.id,
                        token = box.config["token"].orEmpty(),
                        query = box.config["query"].orEmpty(),
                    )
                    ChannelKind.TELEGRAM -> TelegramService(
                        boxId = box.id,
                        serverUrl = box.config["serverUrl"].orEmpty(),
                        apiKey = box.config["apiKey"].orEmpty(),
                    )
                    ChannelKind.GMAIL -> GmailService(
                        boxId = box.id,
                        clientId = box.config["clientId"].orEmpty(),
                        clientSecret = box.config["clientSecret"].orEmpty(),
                        refreshToken = box.config["refreshToken"].orEmpty(),
                    )
                }
            }.getOrNull()
        }

    companion object {
        private const val MAX_CHATS = 40
        private const val INITIAL_LIMIT = 20
        private const val MAX_IMAGE_BYTES = 60 * 1024

        fun glyph(kind: ChannelKind): String = when (kind) {
            ChannelKind.WHATSAPP -> "W"
            ChannelKind.TELEGRAM -> "T"
            ChannelKind.GMAIL -> "E"
            ChannelKind.GITHUB -> "PR"
        }

        /** [W] when a type has one box; [W1]/[W2] when several. */
        fun computeBoxLabels(services: List<ChannelService>): Map<String, String> {
            val byKind = services.groupBy { it.kind }
            val out = HashMap<String, String>()
            for ((kind, list) in byKind) {
                val base = glyph(kind)
                list.forEachIndexed { i, svc ->
                    out[svc.boxId] = if (list.size > 1) "[$base${i + 1}]" else "[$base]"
                }
            }
            return out
        }
    }
}
