package com.smriti.app.capture

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper small through sherpa-onnx. Since Parakeet became the default it is used only for
 * Hindi, with the language pinned to "hi": left on auto-detect, noise was "detected" as Hindi
 * and decoded into looping Devanagari for up to two minutes.
 */
class SherpaWhisperAsr(
    context: Context,
    private val language: String = ""
) : SherpaOfflineAsr(context) {

    override val engine: String get() = if (language.isEmpty()) "whisper" else "whisper-$language"

    override val decodeTimeoutMs: Long = DECODE_CAP_MS

    override suspend fun recognizer(): OfflineRecognizer = getOrLoadRecognizer(context, language)

    override fun clean(raw: String): String = cleanTranscript(raw)

    companion object {
        /** A Hindi note longer than this to decode is not worth the wait. */
        const val DECODE_CAP_MS = 20_000L

        @Volatile
        private var cachedRecognizer: OfflineRecognizer? = null
        @Volatile
        private var cachedLanguage: String? = null
        private val recognizerMutex = Mutex()

        /** One Whisper in memory at a time (~375 MB); a language change rebuilds it. */
        internal suspend fun getOrLoadRecognizer(context: Context, language: String = ""): OfflineRecognizer {
            cachedRecognizer?.takeIf { cachedLanguage == language }?.let { return it }
            return recognizerMutex.withLock {
                cachedRecognizer?.takeIf { cachedLanguage == language }?.let { return@withLock it }
                // Not released: a decode that outlived its cap may still be running on it.
                cachedRecognizer = null
                val paths = WhisperModelProvisioner.locate(context)
                    ?: throw AsrUnavailableException(WhisperModelProvisioner.missingMessage())
                try {
                    val cfg = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = paths.encoder.absolutePath,
                                decoder = paths.decoder.absolutePath,
                                language = language,    // "" = auto-detect
                                task = "transcribe"
                            ),
                            tokens = paths.tokens.absolutePath,
                            numThreads = 4,
                            debug = false
                        ),
                        decodingMethod = "greedy_search"
                    )
                    val r = OfflineRecognizer(config = cfg)
                    cachedRecognizer = r
                    cachedLanguage = language
                    r
                } catch (e: AsrUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    throw AsrUnavailableException(
                        "Failed to load Whisper model from ${paths.encoder.parent}: ${e.message}",
                        cause = e
                    )
                }
            }
        }

        internal fun readWavSamples(wav: File): FloatArray {
            val bytes = wav.readBytes()
            if (bytes.size <= 44) return FloatArray(0)
            val sampleCount = (bytes.size - 44) / 2
            if (sampleCount <= 0) return FloatArray(0)
            val buffer = ByteBuffer.wrap(bytes, 44, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                samples[i] = buffer.getShort() / 32768.0f
            }
            return samples
        }

        // Whisper's own special tokens / pseudo-tokens emitted when transcribing silence or non-speech audio.
        // In Whisper's training dataset (derived from subtitles), silent or inaudible segments
        // were tagged with bracketed or parenthesized markers like [INAUDIBLE], [BLANK_AUDIO], or [Silence].
        private val SILENCE_MARKERS = setOf(
            "inaudible",
            "blank_audio",
            "blank audio",
            "silence",
            "[inaudible]",
            "(inaudible)",
            "[blank_audio]",
            "(blank_audio)",
            "[blank audio]",
            "(blank audio)",
            "[silence]",
            "(silence)"
        )

        internal fun cleanTranscript(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""

            val isEnclosed = (trimmed.startsWith('[') && trimmed.endsWith(']')) ||
                (trimmed.startsWith('(') && trimmed.endsWith(')'))
            if (isEnclosed && trimmed.length >= 2) {
                val inner = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                if (inner in SILENCE_MARKERS || "[$inner]" in SILENCE_MARKERS || "($inner)" in SILENCE_MARKERS) {
                    return ""
                }
                // Whisper describes non-speech in a single bracketed tag and invents new ones
                // freely: "[Sounds of the machine]" came back for room noise on the Realme and
                // was saved as a voice note. Speech never arrives wholly bracketed, so any lone
                // tag is noise. "[a] b [c]" has brackets inside and is kept.
                if (inner.none { it == '[' || it == ']' || it == '(' || it == ')' }) return ""
            }
            return trimmed
        }

        internal fun stripSilenceMarkers(raw: String): String = cleanTranscript(raw)
    }
}
