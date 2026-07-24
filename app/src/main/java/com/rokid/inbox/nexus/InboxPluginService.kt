package com.rokid.inbox.nexus

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest

/**
 * Headless Nexus plugin service — the thin adapter between the hub and
 * [InboxRuntime]. It owns the surface session, maps the four R08 ring verbs to
 * runtime input, and renders the runtime's declarative screens as HUD card or
 * image surfaces. All domain logic lives in the runtime + [InboxNavState].
 */
class InboxPluginService : NexusPluginService(), InboxRuntime.SurfaceHost {

    private val runtime by lazy { InboxRuntime(applicationContext, this) }
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    // A surface (card OR image) has been shown this session: first send uses
    // show*, every later send uses update* — including card<->image kind changes.
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

    /** SPP link state can bring the image surface up/down; let the runtime flush a pending photo. */
    override fun onNexusLinkState(state: Int) {
        runtime.onLinkState(state)
    }

    /* ---------------- SurfaceHost ---------------- */

    override fun renderCard(screen: InboxNavState.Screen) {
        val s = surface ?: return
        val card = NexusCard(
            title = screen.title.take(120),
            lines = screen.lines.map { it.take(240) }.take(64),
            footer = screen.footer.take(240),
            contentKey = contentKey(screen.keySeed),
            handlesBack = true,
        )
        val result = if (surfaceShown) s.updateCard(card) else s.showCard(card)
        // SURFACE_BUSY: another plugin owns the HUD — give up quietly, no retry loop.
        if (result == NexusSdkResult.SENT) surfaceShown = true
    }

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
            footer = "duplo - voltar",
            handlesBack = true,
        )
        // First surface uses show; a card is usually already up, so transition via update.
        val result = if (surfaceShown) s.updateImage(image, jpeg) else s.showImage(image, jpeg)
        val sent = result == NexusSdkResult.SENT
        if (sent) surfaceShown = true
        return sent
    }

    override fun supportsImage(): Boolean = nexusClient?.supportsImageSurface == true

    override fun selfClose() {
        surface?.hide()
        surfaceShown = false
    }

    override fun startMic(): InboxRuntime.MicStart {
        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                runtime.onMicStarted(format.sampleRate)
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                runtime.onMicFrame(pcm)
            }

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

    /** Stable, bounded contentKey: hash the seed so it never exceeds 128 chars. */
    private fun contentKey(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "ri-" + hex.take(40)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
