package com.smriti.app.ai

/**
 * Abstraction over the on-device LLM. There is no cloud implementation: the app declares no
 * network permission, so a remote backend could not work even if one were written.
 */
interface LlmBackend {
    val label: String
    suspend fun generate(prompt: String, maxTokens: Int = 512, jsonSchema: String? = null): String

    /**
     * [generate], but reporting progress as it decodes, so a screen can show the model working
     * instead of a spinner. Both callbacks receive the full text so far, not a delta, so a
     * caller can drop it straight into a StateFlow and a backend can restart (e.g. a GPU-to-CPU
     * retry) without the UI showing a splice. [onThinking] only fires when [think] is true and
     * the backend supports a reasoning channel.
     *
     * The default does not stream: it answers in one go and reports the whole answer once.
     */
    suspend fun generateStreaming(
        prompt: String,
        maxTokens: Int = 512,
        jsonSchema: String? = null,
        think: Boolean = false,
        onThinking: (String) -> Unit = {},
        onAnswer: (String) -> Unit = {}
    ): String = generate(prompt, maxTokens, jsonSchema).also(onAnswer)

    fun close() {}
}
