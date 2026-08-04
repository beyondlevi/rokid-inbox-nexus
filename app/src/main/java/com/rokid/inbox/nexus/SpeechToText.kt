package com.rokid.inbox.nexus

import com.rokid.inbox.nexus.channels.Http
import com.rokid.inbox.nexus.channels.obj
import com.rokid.inbox.nexus.channels.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Phone-side speech-to-text, transposed from the maintainer's Rokid Relay
 * reference (`ApiCompletedAudioSpeechToTextEngine`, the OpenAI buffered path):
 * PCM16-mono -> WAV -> OpenAI `/v1/audio/transcriptions` (multipart) -> text.
 *
 * On Nexus the audio comes from the glasses microphone over the hub as raw
 * 16 kHz mono PCM (see [InboxRuntime]); this only turns a captured buffer into
 * text. Reuses the same OpenAI key already configured for AI descriptions.
 */
class SpeechToText(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** BCP-47/ISO code to force; blank = auto-detect. */
    private val language: String = "",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** @param pcm16leMono signed 16-bit little-endian mono PCM at [sampleRate]. */
    fun transcribe(pcm16leMono: ByteArray, sampleRate: Int): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        require(pcm16leMono.size >= MIN_AUDIO_BYTES) { "Audio muito curto" }
        val wav = Pcm16Wav.encode(pcm16leMono, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart(
                "prompt",
                "Transcreva uma resposta curta ditada em oculos Rokid. Preserve o idioma falado.",
            )
            .addFormDataPart("file", "reply.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("OpenAI STT ${res.code}: ${text.take(300).ifBlank { res.message }}")
            }
            val json = Http.parse(text).obj()
            json.optObj("error")?.let { throw RuntimeException(it.str("message").ifBlank { "OpenAI STT falhou" }) }
            return json.str("text").trim()
        }
    }

    /**
     * Transcribe an already-encoded audio clip (a WhatsApp/Telegram voice note is
     * OGG/Opus; OpenAI's transcriptions endpoint accepts ogg/m4a/mp3/wav/...).
     * @param fileName carries the extension so the API picks the right decoder.
     */
    fun transcribeFile(bytes: ByteArray, fileName: String): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        require(bytes.isNotEmpty()) { "Audio vazio" }
        val name = fileName.ifBlank { "audio.ogg" }.let { if (it.contains('.')) it else "$it.ogg" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", name, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("OpenAI STT ${res.code}: ${text.take(300).ifBlank { res.message }}")
            }
            val json = Http.parse(text).obj()
            json.optObj("error")?.let { throw RuntimeException(it.str("message").ifBlank { "OpenAI STT falhou" }) }
            return json.str("text").trim()
        }
    }

    private fun com.google.gson.JsonObject?.optObj(key: String): com.google.gson.JsonObject? =
        this?.get(key) as? com.google.gson.JsonObject

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini-transcribe"

        /** Buffered OpenAI transcription models (label -> id), as offered by Relay. */
        val MODELS = linkedMapOf(
            "GPT-4o mini Transcribe" to "gpt-4o-mini-transcribe",
            "GPT-4o Transcribe" to "gpt-4o-transcribe",
            "Whisper" to "whisper-1",
        )

        private const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MIN_AUDIO_BYTES = 3_200 // ~0.1 s at 16 kHz mono 16-bit
    }
}

/** Minimal PCM16-mono WAV container (transposed from Relay's `Pcm16Wav`). */
object Pcm16Wav {
    private const val CHANNEL_COUNT = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16

    fun encode(pcm16Mono: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm16Mono.size
        val byteRate = sampleRate * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream(44 + dataSize).apply {
            ascii("RIFF"); intLe(36 + dataSize); ascii("WAVE")
            ascii("fmt "); intLe(16); shortLe(1); shortLe(CHANNEL_COUNT)
            intLe(sampleRate); intLe(byteRate)
            shortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE); shortLe(BITS_PER_SAMPLE)
            ascii("data"); intLe(dataSize); write(pcm16Mono)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(v: String) = write(v.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.intLe(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }
    private fun ByteArrayOutputStream.shortLe(v: Int) { write(v and 0xff); write((v shr 8) and 0xff) }
}
