package com.smriti.app.capture

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Push-to-talk recording plus one offline sherpa-onnx decode. Whisper and Parakeet differ only
 * in how the recognizer is built and how its text is cleaned, so that is all subclasses supply.
 */
abstract class SherpaOfflineAsr(protected val context: Context) : Asr, PushToTalk {

    /** Short name for logs and the benchmark, e.g. "parakeet". */
    abstract val engine: String

    protected abstract suspend fun recognizer(): OfflineRecognizer

    protected abstract fun clean(raw: String): String

    /** Give up on a decode after this long and return "". Null waits for ever. */
    protected open val decodeTimeoutMs: Long? = null

    private val lock = Any()

    @Volatile
    private var pending: CompletableDeferred<Unit>? = null
    private var recorder: AudioRecorder? = null

    override suspend fun transcribe(maxMillis: Long): String = withContext(Dispatchers.IO) {
        try {
            val recognizer = recognizer()

            val deferred = CompletableDeferred<Unit>()
            val rec = AudioRecorder(context)
            synchronized(lock) {
                pending = deferred
                recorder = rec
            }

            rec.start()

            val released = withTimeoutOrNull(maxMillis) { deferred.await() } != null
            if (released) {
                // Humans release the button as they finish the last word, so cutting the
                // microphone at that exact instant clips it.
                delay(TRAILING_PAD_MS)
            }

            val wav = try {
                rec.stop()
            } catch (_: Exception) {
                null
            } finally {
                synchronized(lock) {
                    pending = null
                    recorder = null
                }
            }

            if (wav == null || !wav.exists()) return@withContext ""
            try {
                decodeFile(recognizer, wav)
            } finally {
                runCatching { wav.delete() }
            }
        } catch (e: AsrUnavailableException) {
            clearPending()
            throw e
        } catch (e: Exception) {
            clearPending()
            throw AsrUnavailableException(e.message ?: "$engine transcription failed", cause = e)
        }
    }

    /** Decodes a 16 kHz mono WAV. Also the benchmark's entry point. */
    suspend fun transcribeFile(wav: File): String = withContext(Dispatchers.IO) {
        decodeFile(recognizer(), wav)
    }

    private suspend fun decodeFile(recognizer: OfflineRecognizer, wav: File): String {
        val samples = SherpaWhisperAsr.readWavSamples(wav)
        if (samples.isEmpty()) return ""
        val audioMs = samples.size * 1000L / SAMPLE_RATE
        val started = System.currentTimeMillis()

        // The native decode cannot be interrupted, so the cap is a race, not a cancel: past the
        // deadline the capture moves on with no transcript and the decode finishes on its own.
        // Whisper on noise is why this exists: it looped on Devanagari for 125 s on the Realme.
        val decode = decodeScope.async {
            synchronized(recognizer) {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    runCatching { stream.release() }
                }
            }
        }
        val raw = decodeTimeoutMs?.let { withTimeoutOrNull(it) { decode.await() } } ?: run {
            if (decodeTimeoutMs == null) decode.await() else null
        }
        val ms = System.currentTimeMillis() - started
        if (raw == null) {
            Log.w(TAG, "$engine gave up after $ms ms on $audioMs ms of audio")
            return ""
        }
        Log.i(TAG, "$engine decoded $audioMs ms of audio in $ms ms")
        return clean(raw)
    }

    private fun clearPending() {
        synchronized(lock) {
            pending = null
            recorder = null
        }
    }

    override fun stopListening() {
        synchronized(lock) {
            val d = pending
            if (d != null && !d.isCompleted) d.complete(Unit)
        }
    }

    override fun cancel() {
        synchronized(lock) {
            runCatching { recorder?.cancel() }
            val d = pending
            if (d != null && !d.isCompleted) d.complete(Unit)
            pending = null
            recorder = null
        }
    }

    protected companion object {
        const val TAG = "SmritiAsr"
        const val SAMPLE_RATE = 16000
        const val TRAILING_PAD_MS = 700L
        val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
