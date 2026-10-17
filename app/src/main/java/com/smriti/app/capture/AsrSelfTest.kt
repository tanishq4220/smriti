package com.smriti.app.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Headless ASR benchmark: every WAV in /data/local/tmp/asr/test_wavs through each provisioned
 * engine, same clips, one after another so they never compete for the CPU.
 *
 *     adb shell am start -n com.smriti.app/.MainActivity --ez smriti_asrtest true
 *     adb logcat -s SmritiAsr:V
 *
 * Lines are "BENCH <engine> <clip> audio=<ms> decode=<ms> text=<...>", greppable into a table.
 */
object AsrSelfTest {

    const val EXTRA = "smriti_asrtest"
    private const val TAG = "SmritiAsr"

    fun run(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "=== ASR BENCHMARK START ===")
            try {
                val wavs = File("/data/local/tmp/asr/test_wavs")
                    .listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                    ?.sortedBy { it.name }.orEmpty()
                if (wavs.isEmpty()) {
                    Log.e(TAG, "no clips in /data/local/tmp/asr/test_wavs")
                    return@launch
                }

                val engines = buildList<SherpaOfflineAsr> {
                    if (ParakeetModelProvisioner.locate(context) != null) add(ParakeetAsr(context))
                    if (WhisperModelProvisioner.locate(context) != null) {
                        add(SherpaWhisperAsr(context))                    // the old default: auto-detect
                        add(SherpaWhisperAsr(context, language = "hi"))   // the Hindi path
                    }
                }

                for (asr in engines) {
                    // First call loads the model; time it apart from decoding.
                    val loadStart = System.currentTimeMillis()
                    try {
                        asr.transcribeFile(wavs.first())
                    } catch (t: Throwable) {
                        Log.e(TAG, "BENCH ${asr.engine} load FAILED: ${t.message}")
                        continue
                    }
                    Log.i(TAG, "BENCH ${asr.engine} warmup (load + first clip) ${System.currentTimeMillis() - loadStart} ms")

                    for (wav in wavs) {
                        val audioMs = (wav.length() - 44) / 32
                        val start = System.currentTimeMillis()
                        val text = try {
                            asr.transcribeFile(wav)
                        } catch (t: Throwable) {
                            "FAILED ${t.message}"
                        }
                        Log.i(TAG, "BENCH ${asr.engine} ${wav.name} audio=$audioMs decode=${System.currentTimeMillis() - start} text=$text")
                    }
                }
            } finally {
                Log.i(TAG, "=== ASR BENCHMARK END ===")
            }
        }
    }
}
