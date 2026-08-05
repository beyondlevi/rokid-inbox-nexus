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
        /** Whether the glasses image surface (SPP binary plane) is available now. */
        fun supportsImage(): Boolean
        /** @return true if the image was accepted by the hub. */
        fun renderImage(contentKey: String, title: String, caption: String, jpeg: ByteArray, width: Int, height: Int): Boolean
        /** Capture a still from the glasses camera; result arrives via onSnapshot*. */
        fun startCapture(): MicStart
    }

    enum class MicStart { SENT, NOT_GRANTED, NOT_READY, UNAVAILABLE }

    val nav = InboxNavState()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val config = InboxConfigStore(appContext)
    // Local CardDAV-synced address book; resolves saved names for WhatsApp chats.
    private val contacts = com.rokid.inbox.nexus.contacts.ContactDirectory(appContext)
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

    // Photo awaiting the image-surface (SPP) window.
    private var pendingPhoto: PendingPhoto? = null
    // Plays a fetched voice note on the phone; the sound comes out on the glasses
    // (they are the phone's Bluetooth audio sink — same route the Nexus TTS uses).
    private var mediaPlayer: android.media.MediaPlayer? = null

    private data class PendingPhoto(
        val contentKey: String,
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val caption: String,
        val fallback: String,
        val previewView: InboxNavState.View, // where to land once the image shows
    )

    // A just-captured photo (full bytes) awaiting the user's send confirmation.
    private var capturedPhoto: ByteArray? = null
    private var captureQuoting: Message? = null

    /* ---------------- lifecycle ---------------- */

    fun open() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        resetVoiceBuffers()
        pendingPhoto = null
        capturedPhoto = null
        captureQuoting = null
        releasePlayer()
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
        pendingPhoto = null
        capturedPhoto = null
        captureQuoting = null
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    /** Called by the service on link-state changes so a pending photo flushes promptly. */
    fun onLinkState(@Suppress("UNUSED_PARAMETER") state: Int) { flushPhoto() }

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
            is InboxNavState.NavAction.ViewPhoto -> viewPhoto(action.message)
            is InboxNavState.NavAction.PlayAudio -> playAudio(action.message)
            is InboxNavState.NavAction.Describe -> describe(action.message)
            is InboxNavState.NavAction.SendQuick -> sendText(action.quick.body, nav.quotingMessage())
            is InboxNavState.NavAction.SendReaction -> react(action.message, action.emoji)
            is InboxNavState.NavAction.Dictate -> startDictation(action.quoting)
            is InboxNavState.NavAction.CapturePhoto -> startCapture(action.quoting)
            InboxNavState.NavAction.SendPhoto -> sendPhoto()
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
                nav.setConversation(chat, msgs, msgs.size < limit, svc.canSend, svc.canReact, svc.canSendVoice, svc.canSendImage)
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
        if (!ai.isConfigured) { nav.showInfo("IA", listOf("Configure a chave OpenAI no celular.")); render(); return }
        val audio = message.isPlayableAudio
        val title = if (audio) "Transcricao (IA)" else "Descricao (IA)"
        // Switch to a processing screen right away: gives feedback and, since INFO
        // has no actionable rows, stops repeated taps from firing another request.
        nav.showInfo(title, listOf(if (audio) "Transcrevendo com IA..." else "Processando com IA..."))
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) { runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) { nav.showInfo(title, listOf("Midia indisponivel.")); render(); return@launch }
            val lang = java.util.Locale.getDefault().language
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        audio -> stt.transcribeFile(bytes, message.fileName)
                        message.isImageMedia -> ai.describeImage(bytes, lang)
                        else -> ai.describeFile(bytes, message.fileName, lang)
                    }
                }
            }
            text.onSuccess { nav.showInfo(title, listOf(it.ifBlank { "(sem texto)" })) }
                .onFailure { nav.showInfo(title, listOf("Falha: ${it.message?.take(180).orEmpty()}")) }
            render()
        }
    }

    private fun playAudio(message: Message) {
        val chat = nav.openChat ?: return
        nav.showInfo("Audio", listOf("Carregando audio..."))
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) { runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) { nav.showInfo("Audio", listOf("Audio indisponivel.")); render(); return@launch }
            val file = withContext(Dispatchers.IO) {
                runCatching { java.io.File.createTempFile("voice", ".ogg", appContext.cacheDir).apply { writeBytes(bytes) } }.getOrNull()
            }
            val ok = file != null && withContext(Dispatchers.Main) { startPlayback(file) }
            if (ok) nav.showInfo("Audio", listOf("Reproduzindo no oculos...", "", "Duplo-toque volta (o audio continua)."))
            else nav.showInfo("Audio", listOf("Nao foi possivel reproduzir este audio."))
            render()
        }
    }

    /** Plays on the phone; the sound reaches the glasses (phone's Bluetooth audio sink). */
    private fun startPlayback(file: java.io.File): Boolean = runCatching {
        releasePlayer()
        mediaPlayer = android.media.MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnCompletionListener { mp -> runCatching { mp.release() }; if (mediaPlayer === mp) mediaPlayer = null; runCatching { file.delete() } }
            setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; if (mediaPlayer === mp) mediaPlayer = null; runCatching { file.delete() }; true }
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        true
    }.getOrDefault(false)

    /* ---------------- photo view (image surface) ---------------- */

    private fun viewPhoto(message: Message) {
        val chat = nav.openChat ?: return
        nav.showInfo("Foto", listOf("Carregando foto..."))
        render()
        scope.launch {
            val svc = serviceFor(chat.boxId)
            val bytes = withContext(Dispatchers.IO) { runCatching { svc?.fetchMedia(chat.id, message) }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) { nav.showInfo("Foto", listOf("Imagem indisponivel.")); render(); return@launch }
            val img = withContext(Dispatchers.Default) { preprocessImage(bytes) }
            if (img == null) { nav.showInfo("Foto", listOf("Nao foi possivel decodificar a imagem.")); render(); return@launch }
            pendingPhoto = PendingPhoto(
                contentKey = "photo-${chat.boxId}-${message.id}",
                jpeg = img.bytes, width = img.width, height = img.height,
                caption = if (message.senderName.isNotBlank()) message.senderName else "",
                fallback = message.text.ifBlank { message.senderName.ifBlank { "Foto" } }.take(160),
                previewView = InboxNavState.View.IMAGE,
            )
            // The image surface needs the SPP binary plane, which can be transiently
            // down; wait for it and retry rather than giving up (matches the shipped
            // Feeds/Sample image path). Fall back to text only if it never comes up.
            if (flushPhoto()) return@launch
            nav.showInfo("Foto", listOf("Carregando foto (aguardando canal de imagem)..."))
            render()
            val deadline = android.os.SystemClock.elapsedRealtime() + PHOTO_WAIT_MS
            while (pendingPhoto != null && android.os.SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.delay(700)
                if (flushPhoto()) return@launch
            }
            pendingPhoto?.let {
                pendingPhoto = null
                nav.showInfo("Foto", listOf(it.fallback, "", "Canal de imagem inativo — nao consegui exibir. Tente com os oculos conectados e vestidos."))
                render()
            }
        }
    }

    private fun flushPhoto(): Boolean {
        val p = pendingPhoto ?: return false
        if (!host.supportsImage()) return false
        if (!host.renderImage(p.contentKey, "Foto", p.caption, p.jpeg, p.width, p.height)) return false
        pendingPhoto = null
        // Image surface is on screen; land on its view and do NOT re-render a card over it.
        when (p.previewView) {
            InboxNavState.View.PHOTO_PREVIEW -> nav.enterPhotoPreview()
            else -> nav.enterImage()
        }
        return true
    }

    /* ---------------- capture photo (glasses camera) ---------------- */

    private fun startCapture(quoting: Message?) {
        val chat = nav.openChat ?: return
        val svc = serviceFor(chat.boxId)
        if (svc == null || !svc.canSendImage) { nav.showInfo("Camera", listOf("Este canal nao aceita imagem.")); render(); return }
        captureQuoting = quoting
        capturedPhoto = null
        pendingPhoto = null
        nav.showInfo("Camera", listOf("Capturando foto..."))
        render()
        when (host.startCapture()) {
            MicStart.SENT -> Unit // wait for onSnapshot / onSnapshotError
            MicStart.NOT_GRANTED -> { nav.showInfo("Camera", listOf("Ative a camera para este plugin em Plugin access.")); render() }
            MicStart.NOT_READY -> { nav.showInfo("Camera", listOf("Hub nao conectado. Tente de novo.")); render() }
            MicStart.UNAVAILABLE -> { nav.showInfo("Camera", listOf("Camera indisponivel.")); render() }
        }
    }

    /** Snapshot callbacks, forwarded by the service (main thread). */
    fun onSnapshot(jpeg: ByteArray) {
        capturedPhoto = jpeg
        nav.enterPhotoPreview()
        // Preview on the image surface (downscaled); the fallback confirm card
        // shows meanwhile / if the SPP image channel never comes up.
        scope.launch {
            val img = withContext(Dispatchers.Default) { preprocessImage(jpeg) }
            if (img == null) { render(); return@launch } // stay on the confirm card
            pendingPhoto = PendingPhoto(
                contentKey = "capture-${System.currentTimeMillis()}",
                jpeg = img.bytes, width = img.width, height = img.height,
                caption = "toque envia",
                fallback = "Foto capturada",
                previewView = InboxNavState.View.PHOTO_PREVIEW,
            )
            if (flushPhoto()) return@launch
            render() // confirm card while waiting for the image channel
            val deadline = android.os.SystemClock.elapsedRealtime() + PHOTO_WAIT_MS
            while (pendingPhoto != null && nav.view == InboxNavState.View.PHOTO_PREVIEW &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                kotlinx.coroutines.delay(700)
                if (flushPhoto()) return@launch
            }
            pendingPhoto = null
        }
    }

    fun onSnapshotError(reason: String) {
        capturedPhoto = null
        nav.showInfo("Camera", listOf(snapshotErrorText(reason)))
        render()
    }

    private fun sendPhoto() {
        val chat = nav.openChat ?: return
        val jpeg = capturedPhoto
        if (jpeg == null) { nav.showInfo("Foto", listOf("Nenhuma foto capturada.")); render(); return }
        pendingPhoto = null
        nav.showInfo("Foto", listOf("Enviando foto..."))
        render()
        val quoting = captureQuoting
        scope.launch {
            val svc = serviceFor(chat.boxId)
            if (svc == null || !svc.canSendImage) { nav.showInfo("Foto", listOf("Este canal nao aceita imagem.")); render(); return@launch }
            val res = withContext(Dispatchers.IO) {
                runCatching { svc.sendImage(chat.id, jpeg, "", quoting?.id.orEmpty(), quoting?.isOutgoing ?: false) }
            }
            capturedPhoto = null
            nav.showInfo("Foto", listOf(if (res.isSuccess) "Foto enviada." else "Falha: ${res.exceptionOrNull()?.message?.take(160).orEmpty()}"))
            render()
        }
    }

    private fun snapshotErrorText(reason: String): String = when (reason) {
        "BUSY" -> "Camera em uso por outro app."
        "LINK_DOWN" -> "Sem conexao com os oculos."
        "CAPTURE_FAILED" -> "Nao foi possivel capturar."
        "TIMEOUT" -> "A camera demorou demais."
        "CANCELLED" -> "Captura cancelada."
        else -> "Falha na camera."
    }

    private data class Img(val bytes: ByteArray, val width: Int, val height: Int)

    /** Decode, downscale to <= 512 px edges and JPEG-encode under 64 KiB for the image surface. */
    private fun preprocessImage(bytes: ByteArray, maxDim: Int = 480, quality: Int = 80): Img? {
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else src
        var q = quality
        var out = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
        while (out.size() > MAX_IMAGE_BYTES && q > 30) {
            q -= 10
            out = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
        }
        if (out.size() > MAX_IMAGE_BYTES) return null
        return Img(out.toByteArray(), scaled.width, scaled.height)
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
                    ChannelKind.WHATSAPP -> WhatsAppService(box.id, box.config["serverUrl"].orEmpty(), box.config["instance"].orEmpty(), box.config["apiKey"].orEmpty(), contacts)
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
        private const val MAX_IMAGE_BYTES = 60 * 1024
        private const val PHOTO_WAIT_MS = 12_000L

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
