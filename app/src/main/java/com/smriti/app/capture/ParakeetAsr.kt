package com.smriti.app.capture

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The default speech engine: NVIDIA Parakeet TDT 0.6B v2 through the same sherpa-onnx runtime
 * as Whisper. A transducer steps through the audio frames once rather than generating text
 * token by token over a padded 30 s window, so it cannot fall into Whisper's repetition loops
 * and it is several times faster. English only; Hindi stays on Whisper (see [LanguageAwareAsr]).
 */
class ParakeetAsr(context: Context) : SherpaOfflineAsr(context) {

    override val engine = "parakeet"

    // Generous: Parakeet decodes far faster than real time, so this only catches a hang.
    override val decodeTimeoutMs: Long = 20_000L

    override suspend fun recognizer(): OfflineRecognizer = getOrLoad(context)

    // Parakeet emits no bracketed sound tags, but the same blank-and-tag rules are harmless.
    override fun clean(raw: String): String = SherpaWhisperAsr.cleanTranscript(raw)

    companion object {
        @Volatile
        private var cached: OfflineRecognizer? = null
        private val mutex = Mutex()

        suspend fun getOrLoad(context: Context): OfflineRecognizer {
            cached?.let { return it }
            return mutex.withLock {
                cached?.let { return@withLock it }
                val paths = ParakeetModelProvisioner.locate(context)
                    ?: throw AsrUnavailableException(ParakeetModelProvisioner.missingMessage())
                try {
                    OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                transducer = OfflineTransducerModelConfig(
                                    encoder = paths.encoder.absolutePath,
                                    decoder = paths.decoder.absolutePath,
                                    joiner = paths.joiner.absolutePath
                                ),
                                tokens = paths.tokens.absolutePath,
                                modelType = "nemo_transducer",
                                numThreads = 4,
                                debug = false
                            ),
                            decodingMethod = "greedy_search"
                        )
                    ).also { cached = it }
                } catch (e: Exception) {
                    throw AsrUnavailableException(
                        "Failed to load Parakeet from ${paths.encoder.parent}: ${e.message}",
                        cause = e
                    )
                }
            }
        }
    }
}
