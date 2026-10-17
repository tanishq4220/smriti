package com.smriti.app.ai

/**
 * Abstraction over the on-device LLM. There is no cloud implementation: the app declares no
 * network permission, so a remote backend could not work even if one were written.
 */
interface LlmBackend {
    val label: String
    suspend fun generate(prompt: String, maxTokens: Int = 512, jsonSchema: String? = null): String
    fun close() {}
}