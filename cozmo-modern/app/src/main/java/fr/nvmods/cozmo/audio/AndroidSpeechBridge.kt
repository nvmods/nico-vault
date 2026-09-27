package fr.nvmods.cozmo.audio

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidSpeechBridge(context: Context) {
    private val appContext = context.applicationContext
    private val init = CompletableDeferred<Int>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (!init.isCompleted) init.complete(status)
        }
    }

    suspend fun synthesize(text: String, locale: Locale): ShortArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "Texte vide" }

        val status = withTimeout(8_000) { init.await() }
        check(status == TextToSpeech.SUCCESS) {
            "Initialisation TTS Android impossible (" + status + ")"
        }

        val engine = tts ?: error("Moteur TTS indisponible")
        val languageResult = engine.setLanguage(locale)
        check(languageResult >= TextToSpeech.LANG_AVAILABLE) {
            "Langue TTS non disponible: " + locale + " (" + languageResult + ")"
        }

        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        val utteranceId = "cozmo-modern-" + UUID.randomUUID().toString()
        val collector = Collector()
        engine.setOnUtteranceProgressListener(collector)

        val temp = File.createTempFile("cozmo_tts_", ".wav", appContext.cacheDir)
        try {
            val rc = engine.synthesizeToFile(text, Bundle(), temp, utteranceId)
            check(rc == TextToSpeech.SUCCESS) {
                "Le moteur TTS refuse la synthèse (" + rc + ")"
            }

            withTimeout(30_000) { collector.done.await() }
            check(!collector.failed) { "Le moteur TTS a signalé une erreur" }

            val raw = collector.bytes()
            check(raw.isNotEmpty()) { "Aucun PCM fourni par le moteur TTS" }

            val decoded = decodePcm(raw, collector.encoding)
            val mono = toMono(decoded, collector.channels)
            val resampled = resample(mono, collector.sampleRate, TARGET_RATE)
            addSilence(resampled, TARGET_RATE, 50)
        } finally {
            temp.delete()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun decodePcm(bytes: ByteArray, encoding: Int): ShortArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ShortArray(bytes.size) { i ->
                (((bytes[i].toInt() and 0xff) - 128) shl 8).toShort()
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = bytes.size / 4
                ShortArray(count) { i ->
                    val p = i * 4
                    val bits = (bytes[p].toInt() and 0xff) or
                        ((bytes[p + 1].toInt() and 0xff) shl 8) or
                        ((bytes[p + 2].toInt() and 0xff) shl 16) or
                        ((bytes[p + 3].toInt() and 0xff) shl 24)
                    val f = Float.fromBits(bits).coerceIn(-1f, 1f)
                    (f * 32767f).roundToInt().toShort()
                }
            }

            else -> {
                val count = bytes.size / 2
                ShortArray(count) { i ->
                    val p = i * 2
                    ((bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() shl 8)).toShort()
                }
            }
        }
    }

    private fun toMono(input: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return input
        val frames = input.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0L
            val base = frame * channels
            for (channel in 0 until channels) sum += input[base + channel]
            (sum / channels).toShort()
        }
    }

    private fun resample(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        if (sourceRate <= 0 || sourceRate == targetRate || input.size < 2) return input

        val outLength = max(1, (input.size.toDouble() * targetRate / sourceRate).roundToInt())
        val out = ShortArray(outLength)
        val step = sourceRate.toDouble() / targetRate

        for (i in out.indices) {
            val sourcePos = i * step
            val index = sourcePos.toInt()
            if (index >= input.lastIndex) {
                out[i] = input.last()
            } else {
                val fraction = sourcePos - index
                val value = input[index] + (input[index + 1] - input[index]) * fraction
                out[i] = value.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        return out
    }

    private fun addSilence(input: ShortArray, sampleRate: Int, silenceMs: Int): ShortArray {
        val count = max(0, sampleRate * silenceMs / 1000)
        val out = ShortArray(input.size + count * 2)
        input.copyInto(out, destinationOffset = count)
        return out
    }

    private class Collector : UtteranceProgressListener() {
        val done = CompletableDeferred<Unit>()
        private val audio = ByteArrayOutputStream()

        @Volatile
        var sampleRate: Int = TARGET_RATE

        @Volatile
        var encoding: Int = AudioFormat.ENCODING_PCM_16BIT

        @Volatile
        var channels: Int = 1

        @Volatile
        var failed: Boolean = false

        override fun onStart(utteranceId: String?) = Unit

        override fun onBeginSynthesis(
            utteranceId: String?,
            sampleRateInHz: Int,
            audioFormat: Int,
            channelCount: Int
        ) {
            sampleRate = sampleRateInHz
            encoding = audioFormat
            channels = max(1, channelCount)
        }

        override fun onAudioAvailable(utteranceId: String?, buffer: ByteArray?) {
            if (buffer != null) synchronized(audio) { audio.write(buffer) }
        }

        override fun onDone(utteranceId: String?) {
            if (!done.isCompleted) done.complete(Unit)
        }

        override fun onError(utteranceId: String?) {
            failed = true
            if (!done.isCompleted) done.complete(Unit)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            failed = true
            if (!done.isCompleted) done.complete(Unit)
        }

        fun bytes(): ByteArray = synchronized(audio) { audio.toByteArray() }
    }

    companion object {
        const val TARGET_RATE = 22050
    }
}
