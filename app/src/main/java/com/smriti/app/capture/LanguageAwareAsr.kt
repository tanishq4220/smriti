package com.smriti.app.capture

import android.content.Context

/** The speech language the user picked on the capture screen. English unless they chose Hindi. */
object SpeechLanguage {
    private const val PREFS = "smriti_speech"
    private const val KEY_HINDI = "hindi"

    fun isHindi(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HINDI, false)

    fun setHindi(context: Context, hindi: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HINDI, hindi).apply()
    }
}

/**
 * Picks the engine per recording, so flipping the language toggle takes effect on the next
 * capture without rebuilding the view model that owns this instance.
 *
 * English: Parakeet, else Whisper (auto), else Vosk, else the platform recogniser.
 * Hindi: Whisper pinned to "hi" with its 20 s cap, else the English chain.
 */
class LanguageAwareAsr(private val context: Context) : Asr, PushToTalk {

    private val english: Asr by lazy {
        when {
            ParakeetModelProvisioner.locate(context) != null -> ParakeetAsr(context)
            WhisperModelProvisioner.locate(context) != null -> SherpaWhisperAsr(context)
            VoskModelProvisioner.locate(context) != null -> VoskAsr(context)
            else -> PlatformAsr(context)
        }
    }

    private val hindi: Asr by lazy {
        if (WhisperModelProvisioner.locate(context) != null) SherpaWhisperAsr(context, language = "hi") else english
    }

    @Volatile
    private var active: Asr? = null

    override suspend fun transcribe(maxMillis: Long): String {
        val asr = if (SpeechLanguage.isHindi(context)) hindi else english
        active = asr
        return try {
            asr.transcribe(maxMillis)
        } finally {
            active = null
        }
    }

    override suspend fun warmUp() {
        (if (SpeechLanguage.isHindi(context)) hindi else english).warmUp()
    }

    override fun stopListening() {
        (active as? PushToTalk)?.stopListening()
    }

    override fun cancel() {
        active?.cancel()
    }
}
