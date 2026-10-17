package com.smriti.app.capture

import android.content.Context

/**
 * Every option is on-device. [LanguageAwareAsr] chooses per recording: Parakeet for English
 * (fast, no hallucination loops), Whisper pinned to Hindi when the user picks Hindi, then Vosk
 * and the platform recogniser as fallbacks when no model is provisioned.
 */
object AsrFactory {
    fun create(context: Context): Asr = LanguageAwareAsr(context.applicationContext)
}
