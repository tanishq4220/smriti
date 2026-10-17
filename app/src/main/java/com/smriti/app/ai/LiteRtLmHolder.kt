@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.smriti.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.smriti.app.BuildConfig
import com.smriti.app.ModelProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One LiteRT-LM [Engine] for the process lifetime. Loading Gemma costs seconds, and doing it per
 * call is the single easiest way to make the app feel broken.
 *
 * GPU is tried first, then CPU. The GPU attempt is wrapped in [BackendPolicy]'s sentinel because
 * a GPU delegate failure on Mali is a native SIGSEGV, not an exception: it kills the process and
 * no fallback code runs. The sentinel stays set until the first full generation completes (see
 * [LiteRtLmBackend]), so a crash during init *or* during the first answer quarantines the GPU
 * on the next launch and the app comes back up on CPU.
 */
object LiteRtLmHolder {

    enum class Target { GPU, CPU }

    private const val TAG = "SmritiEngine"

    /**
     * Context window. Gemma E2B supports far more, but the KV cache is allocated up front and an
     * 8 GB phone shares that memory with the camera. Extraction needs well under 1k; recall with
     * four records of context stays under 3k.
     */
    private const val MAX_TOKENS = 4096

    @Volatile
    private var backend: LlmBackend? = null
    private val mutex = Mutex()

    suspend fun get(context: Context, forced: Target? = null): Result<LlmBackend> {
        backend?.let { return Result.success(it) }

        return mutex.withLock {
            backend?.let { return@withLock Result.success(it) }

            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val model = ModelProvisioner.locate(app)
                    ?: return@withContext Result.failure(
                        ModelMissingException(ModelProvisioner.missingMessage(app))
                    )

                // Debug builds record real prefill/decode rates; LiteRtLmBackend logs them as SPEED.
                ExperimentalFlags.enableBenchmark = BuildConfig.DEBUG

                val policy = BackendPolicy(app)
                val targets = when (forced) {
                    Target.GPU -> listOf(Target.GPU)
                    Target.CPU -> listOf(Target.CPU)
                    null -> if (policy.gpuAllowed()) listOf(Target.GPU, Target.CPU) else {
                        Log.i(TAG, "GPU quarantined on this device; CPU only")
                        listOf(Target.CPU)
                    }
                }

                var lastError: Throwable? = null
                for (target in targets) {
                    Log.i(TAG, "ATTEMPT $target · ${model.label}")
                    if (target == Target.GPU) policy.markGpuAttemptStarted()
                    var engine: Engine? = null
                    try {
                        engine = Engine(
                            EngineConfig(
                                modelPath = model.file.absolutePath,
                                backend = if (target == Target.GPU) Backend.GPU() else Backend.CPU(),
                                maxNumTokens = MAX_TOKENS,
                                // The model may sit in /data/local/tmp, which the app cannot write.
                                // Without this, LiteRT-LM tries to put its cache next to the model.
                                cacheDir = app.cacheDir.absolutePath
                            )
                        )
                        val initStart = System.currentTimeMillis()
                        engine.initialize()
                        Log.i(TAG, "RESULT $target: OK (${System.currentTimeMillis() - initStart} ms)")

                        val engineBackend = LiteRtLmBackend(engine, "$target · ${model.label}")
                        val ready: LlmBackend =
                            if (target == Target.GPU) GpuGuarded(engineBackend, app, policy) else engineBackend
                        backend = ready
                        return@withContext Result.success(ready)
                    } catch (t: Throwable) {
                        Log.w(TAG, "RESULT $target: FAILED ${t.javaClass.simpleName}: ${t.message}")
                        // A Kotlin-visible failure is not a crash: clear the sentinel so this
                        // ordinary refusal does not quarantine the GPU on the next launch.
                        if (target == Target.GPU) policy.clearGpuAttempt()
                        lastError = t
                        closeQuietly(engine, target)
                    }
                }

                Result.failure(lastError ?: IllegalStateException("All LiteRT-LM backends failed"))
            }
        }
    }

    private fun closeQuietly(engine: Engine?, target: Target) {
        try {
            engine?.close()
        } catch (closeEx: Throwable) {
            Log.w(TAG, "Failed to close engine for $target: ${closeEx.message}")
        }
    }

    /**
     * Holds the GPU sentinel open across the first generation, because init succeeding proves
     * little: on the Realme the GPU initialised and then failed the first answer. A crash there
     * kills the process and the sentinel quarantines the GPU on the next launch. An ordinary
     * exception means the GPU cannot run this model on this device: quarantine it now, drop the
     * engine, and answer this same prompt on CPU so the capture is not lost.
     */
    private class GpuGuarded(
        private val gpu: LlmBackend,
        private val context: Context,
        private val policy: BackendPolicy
    ) : LlmBackend {
        @Volatile private var proven = false
        override val label: String get() = gpu.label

        override suspend fun generate(prompt: String, maxTokens: Int, jsonSchema: String?): String =
            guarded { it.generate(prompt, maxTokens, jsonSchema) }

        // Callbacks carry the full text so far, so a CPU retry simply restarts what they show.
        override suspend fun generateStreaming(
            prompt: String,
            maxTokens: Int,
            jsonSchema: String?,
            think: Boolean,
            onThinking: (String) -> Unit,
            onAnswer: (String) -> Unit
        ): String = guarded {
            it.generateStreaming(prompt, maxTokens, jsonSchema, think, onThinking, onAnswer)
        }

        private suspend fun guarded(run: suspend (LlmBackend) -> String): String {
            if (proven) return run(gpu)
            return try {
                run(gpu).also {
                    proven = true
                    policy.markGpuAttemptSurvived()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "GPU failed its first generation (${t.message}); quarantining, retrying on CPU")
                policy.quarantineGpu()
                reset()
                run(get(context, Target.CPU).getOrThrow())
            }
        }

        override fun close() = gpu.close()
    }

    /** Drops the engine so the next [get] reloads, e.g. after a new model is imported. */
    fun reset() {
        val old = backend
        backend = null
        old?.close()
    }
}
