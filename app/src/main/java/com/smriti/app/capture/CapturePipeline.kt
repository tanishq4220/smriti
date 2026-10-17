package com.smriti.app.capture

import android.content.Context
import android.util.Log
import com.smriti.app.ai.Enricher
import com.smriti.app.data.RecordDao
import com.smriti.app.data.RecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface CaptureStage {
    data object Photo : CaptureStage
    // Retained for compatibility; no longer emitted.
    data object Reading : CaptureStage
    data object Listening : CaptureStage
    /** Speech has ended and Whisper is decoding it on the device: 7-11 s on the Realme. */
    data object Transcribing : CaptureStage
    // Retained for compatibility; no longer emitted.
    data object Thinking : CaptureStage
    data class Done(val recordId: Long) : CaptureStage
    data class Failed(val reason: String) : CaptureStage
}

class CapturePipeline(
    private val context: Context,
    private val dao: RecordDao,
    private val camera: CameraController,
    private val asr: Asr
) {

    fun run(withVoice: Boolean): Flow<CaptureStage> = flow {
        try {
            emit(CaptureStage.Photo)
            if (withVoice) {
                emit(CaptureStage.Listening)
            }

            val started = System.currentTimeMillis()
            val recordId = coroutineScope {
                val asrDeferred = async { if (withVoice) asr.transcribe() else "" }
                val photoFile = camera.capture()
                Log.i(TAG, "photo saved in ${System.currentTimeMillis() - started} ms: ${photoFile.name}")

                if (!withVoice) return@coroutineScope saveRecord(photoFile.absolutePath, "")

                // The row exists from the moment the photo does. Waiting for Whisper first meant
                // 10-20 s in which the capture lived only in memory: on the Realme the app went
                // to the background in that window, ColorOS killed it, and the photo was left
                // on disk with no record. CAPTURING keeps it out of the enricher until the
                // speech arrives, and is recovered on the next launch if the process dies.
                val id = saveRecord(photoFile.absolutePath, "", state = "CAPTURING")
                val transcript = try {
                    asrDeferred.await()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "transcription failed, keeping the photo: ${t.message}")
                    ""
                }
                Log.i(TAG, "record $id transcript: ${transcript.length} chars after " +
                    "${System.currentTimeMillis() - started} ms")
                dao.completeCapture(
                    id = id,
                    transcript = transcript,
                    title = titleFor("", transcript, ""),
                    embedding = embedFor("", "", "", transcript)
                )
                Enricher.request(context)
                id
            }
            Log.i(TAG, "capture done: record $recordId")
            emit(CaptureStage.Done(recordId))
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed: ${t.message}", t)
            emit(CaptureStage.Failed(t.message ?: "Capture pipeline failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun runVoiceOnly(): Flow<CaptureStage> = flow {
        try {
            emit(CaptureStage.Listening)
            val started = System.currentTimeMillis()
            val transcript = asr.transcribe()
            Log.i(TAG, "voice-only transcript: ${transcript.length} chars after ${System.currentTimeMillis() - started} ms")
            if (!isWorthSaving(transcript)) {
                emit(CaptureStage.Failed("Nothing heard"))
                return@flow
            }
            val recordId = saveRecord("", transcript)
            emit(CaptureStage.Done(recordId))
        } catch (t: Throwable) {
            emit(CaptureStage.Failed(t.message ?: "Capture pipeline failed"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun saveRecord(photoPath: String, transcript: String, state: String = "PENDING"): Long {
        val ocrText = ""
        val now = System.currentTimeMillis()
        val record = RecordEntity(
            createdAt = now,
            photoPath = photoPath,
            ocrText = ocrText,
            transcript = transcript,
            title = titleFor("", transcript, ""),
            summary = "",
            peopleJson = "[]",
            amountsJson = "[]",
            tagsJson = "[]",
            embedding = embedFor("", "", "", transcript),
            enrichmentState = state
        )
        val recordId = dao.insertRecord(record)
        if (state == "PENDING") Enricher.request(context)
        return recordId
    }

    /**
     * Returns the little-endian float32 blob for this record, or null when the embedder asset
     * is unavailable. A missing embedding is not an error: [com.smriti.app.ai.Recall] degrades
     * to keyword scoring, so a capture is still worth keeping.
     */
    private suspend fun embedFor(
        title: String,
        summary: String,
        ocrText: String,
        transcript: String
    ): ByteArray? = Enricher.embedFor(context, title, summary, ocrText, transcript)

    /**
     * Titles reach the timeline verbatim, so they have to survive raw OCR.
     *
     * Observed on a Redmi Note 10S, 2026-09-01: a photograph of a laptop screen produced a
     * title containing embedded newlines, which rendered as a three-line timeline card. And a
     * photograph of a blank surface produced an empty title and a card with no text at all.
     *
     * So: collapse all whitespace, trim, cut on a word boundary, and never return blank.
     */
    private fun titleFor(modelTitle: String, transcript: String, ocrText: String): String =
        Enricher.titleFor(modelTitle, transcript, ocrText)

    companion object {
        private const val TAG = "SmritiCapture"

        internal fun isWorthSaving(transcript: String): Boolean =
            transcript.trim().isNotEmpty()
    }
}