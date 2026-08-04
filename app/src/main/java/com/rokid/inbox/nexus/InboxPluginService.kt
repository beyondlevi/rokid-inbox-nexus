package com.rokid.inbox.nexus

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest

/**
 * Headless Nexus plugin service — the thin adapter between the hub and
 * [InboxRuntime]. Maps the four R08 ring verbs to runtime input and the
 * runtime's rich-row [InboxNavState.Screen] to a scrolling card surface (the
 * hub owns caret, scroll and type). Also owns the glasses-mic lease.
 */
class InboxPluginService : NexusPluginService(), InboxRuntime.SurfaceHost {

    private val runtime by lazy { InboxRuntime(applicationContext, this) }
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var snapshot: NexusSnapshotSession? = null
    private var surfaceShown = false

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        surfaceShown = false
        runtime.open()
    }

    override fun onNexusClose() {
        runtime.close()
        audio?.stop()
        audio = null
        snapshot?.cancel()
        snapshot = null
        surface?.hide()
        surface = null
        surfaceShown = false
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> runtime.onNext()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> runtime.onPrev()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> runtime.onSelect()
            KeyEvent.KEYCODE_BACK -> runtime.onBack()
            else -> Unit
        }
    }

    /** SPP link state can bring the image surface up/down; flush a pending photo. */
    override fun onNexusLinkState(state: Int) = runtime.onLinkState(state)

    /* ---------------- SurfaceHost ---------------- */

    override fun render(screen: InboxNavState.Screen) {
        val s = surface ?: return
        val body = screen.bodyLines
        val card = if (body != null) {
            // Dense plain card body (the hub packs ~15 lines) for the paged reader.
            NexusCard(
                title = screen.title.take(120).ifBlank { "Inbox" },
                lines = body.map { it.take(240) }.take(64).ifEmpty { listOf(" ") },
                subtitle = screen.subtitle?.take(240),
                footer = screen.footer.take(240),
                contentKey = contentKey(screen.keySeed),
                handlesBack = true,
            )
        } else {
            val rows = screen.rows.take(64).map { row ->
                NexusCardLine(
                    text = row.text.take(240),
                    sub = row.sub?.take(240),
                    badge = row.badge?.take(24),
                    tone = toneOf(row.tone),
                    selected = row.selected,
                )
            }
            NexusCard(
                title = screen.title.take(120).ifBlank { "Inbox" },
                lines = emptyList(),
                subtitle = screen.subtitle?.take(240),
                footer = screen.footer.take(240),
                contentKey = contentKey(screen.keySeed),
                richLines = rows.ifEmpty { listOf(NexusCardLine(text = " ")) },
                handlesBack = true,
            )
        }
        val result = if (surfaceShown) s.updateCard(card) else s.showCard(card)
        if (result == NexusSdkResult.SENT) surfaceShown = true
    }

    override fun selfClose() {
        surface?.hide()
        surfaceShown = false
    }

    override fun supportsImage(): Boolean = nexusClient?.supportsImageSurface == true

    override fun renderImage(
        contentKey: String,
        title: String,
        caption: String,
        jpeg: ByteArray,
        width: Int,
        height: Int,
    ): Boolean {
        val s = surface ?: return false
        if (nexusClient?.supportsImageSurface != true) return false
        val image = NexusImage(
            contentKey = contentKey(contentKey),
            mimeType = ImageSurfaceContract.MIME_JPEG,
            pixelWidth = width,
            pixelHeight = height,
            title = title.take(120),
            caption = caption.take(240),
            footer = "duplo volta",
            handlesBack = true,
        )
        // A card is usually already shown, so transition via updateImage.
        val result = if (surfaceShown) s.updateImage(image, jpeg) else s.showImage(image, jpeg)
        val sent = result == NexusSdkResult.SENT
        if (sent) surfaceShown = true
        return sent
    }

    override fun startMic(): InboxRuntime.MicStart {
        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) = runtime.onMicStarted(format.sampleRate)
            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) = runtime.onMicFrame(pcm)
            override fun onAudioStopped(reason: NexusAudioStopReason) {
                audio = null
                runtime.onMicStopped(reason.name)
            }
        }) ?: return InboxRuntime.MicStart.UNAVAILABLE
        audio = session
        return when (session.start()) {
            NexusSdkResult.SENT -> InboxRuntime.MicStart.SENT
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> InboxRuntime.MicStart.NOT_GRANTED
            NexusSdkResult.NOT_REGISTERED -> InboxRuntime.MicStart.NOT_READY
            else -> InboxRuntime.MicStart.UNAVAILABLE
        }
    }

    override fun stopMic() {
        audio?.stop()
    }

    override fun startCapture(): InboxRuntime.MicStart {
        val session = nexusSnapshotSession(object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) {
                snapshot = null
                runtime.onSnapshot(jpeg)
            }

            override fun onSnapshotError(error: NexusSnapshotError) {
                snapshot = null
                runtime.onSnapshotError(error.name)
            }
        }) ?: return InboxRuntime.MicStart.UNAVAILABLE
        snapshot = session
        return when (session.capture()) {
            NexusSdkResult.SENT -> InboxRuntime.MicStart.SENT
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> InboxRuntime.MicStart.NOT_GRANTED
            NexusSdkResult.NOT_REGISTERED -> InboxRuntime.MicStart.NOT_READY
            else -> InboxRuntime.MicStart.UNAVAILABLE
        }
    }

    private fun toneOf(tone: InboxNavState.Tone): NexusRowTone = when (tone) {
        InboxNavState.Tone.NORMAL -> NexusRowTone.NORMAL
        InboxNavState.Tone.DIM -> NexusRowTone.DIM
        InboxNavState.Tone.BODY -> NexusRowTone.BODY
        InboxNavState.Tone.ALERT -> NexusRowTone.ALERT
    }

    /** Stable, bounded contentKey: hash the seed so it never exceeds 128 chars. */
    private fun contentKey(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return "ri-" + digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
