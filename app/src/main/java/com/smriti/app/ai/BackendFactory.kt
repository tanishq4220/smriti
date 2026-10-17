package com.smriti.app.ai

import android.content.Context

/** Thrown when no `.litertlm` model has been imported or pushed onto the device. */
class ModelMissingException(message: String) : Exception(message)

object BackendFactory {

    /**
     * @param backend "cpu" or "gpu" to force one, or null to let [BackendPolicy] decide.
     * @param resetPolicy clears any GPU quarantine and rebuilds the engine, so a device can be
     *   re-evaluated deliberately from the self-test.
     */
    suspend fun create(
        context: Context,
        backend: String? = null,
        resetPolicy: Boolean = false
    ): Result<LlmBackend> {
        if (resetPolicy || backend != null) {
            BackendPolicy(context).takeIf { resetPolicy }?.reset()
            LiteRtLmHolder.reset()
        }
        val forced = when (backend?.lowercase()) {
            "cpu" -> LiteRtLmHolder.Target.CPU
            "gpu" -> LiteRtLmHolder.Target.GPU
            else -> null
        }
        return LiteRtLmHolder.get(context, forced)
    }
}
