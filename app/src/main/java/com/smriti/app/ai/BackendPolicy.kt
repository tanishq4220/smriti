package com.smriti.app.ai

import android.content.Context

/**
 * Remembers whether the GPU backend has ever killed this process, and stops trying it if so.
 *
 * Why this is not just a try/catch:
 *
 * On-device LLM GPU paths are tuned for Adreno first. On a Mali-G76 (Redmi Note 10S, observed
 * 2026-09-01 with the MediaPipe engine this app used before LiteRT-LM) the engine initialised,
 * the model loaded in about 13 seconds, and then generation died with
 *
 *     Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
 *
 * The Realme RMX5106 this now targets is also Mali (Dimensity 7300, Mali-G615), so the guard
 * stays in front of LiteRT-LM's GPU backend too.
 *
 * A native segfault takes the whole process with it. No Kotlin catch block runs, no coroutine
 * exception handler fires, and nothing gets a chance to fall back. The failure is only
 * observable from *outside* the crashed run.
 *
 * So we leave a note before we try, and read it back on the next launch:
 *
 *   - before attempting GPU     -> set `gpu_attempt_in_flight = true`
 *   - after one full generation -> set it false, and mark GPU as proven
 *   - on startup, if it is still true, the previous run died mid-attempt -> quarantine the GPU
 *
 * The cost of being wrong in one direction is a slower demo. In the other, it is the app
 * vanishing in front of a jury. So the sentinel is deliberately pessimistic: one unexplained
 * death is enough to stop using the GPU on this device, permanently.
 */
class BackendPolicy(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("smriti_backend", Context.MODE_PRIVATE)

    fun gpuAllowed(): Boolean {
        if (prefs.getBoolean(KEY_QUARANTINED, false)) return false

        // A previous run set the flag and never cleared it, so it did not survive.
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            prefs.edit()
                .putBoolean(KEY_QUARANTINED, true)
                .putBoolean(KEY_IN_FLIGHT, false)
                .apply()
            return false
        }
        return true
    }

    fun markGpuAttemptStarted() {
        prefs.edit().putBoolean(KEY_IN_FLIGHT, true).commit() // commit, not apply: must survive a segfault
    }

    fun markGpuAttemptSurvived() {
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            prefs.edit()
                .putBoolean(KEY_IN_FLIGHT, false)
                .putBoolean(KEY_GPU_PROVEN, true)
                .commit()
        }
    }

    /** The GPU refused with an ordinary exception: not a crash, so not grounds for quarantine. */
    fun clearGpuAttempt() {
        prefs.edit().putBoolean(KEY_IN_FLIGHT, false).commit()
    }

    /** The GPU loaded but cannot generate on this device. Stop trying it. */
    fun quarantineGpu() {
        prefs.edit().putBoolean(KEY_QUARANTINED, true).putBoolean(KEY_IN_FLIGHT, false).commit()
    }

    fun isQuarantined(): Boolean = prefs.getBoolean(KEY_QUARANTINED, false)

    /** For the self-test, so a device can be re-evaluated deliberately. */
    fun reset() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_IN_FLIGHT = "gpu_attempt_in_flight"
        const val KEY_QUARANTINED = "gpu_quarantined"
        const val KEY_GPU_PROVEN = "gpu_proven"
    }
}
