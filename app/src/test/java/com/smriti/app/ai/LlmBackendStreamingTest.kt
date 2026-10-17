package com.smriti.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmBackendStreamingTest {

    /** A backend that cannot stream must still answer, and report that answer exactly once. */
    @Test
    fun defaultStreamingFallsBackToGenerate() = runBlocking {
        var captured: Triple<String, Int, String?>? = null
        val backend = object : LlmBackend {
            override val label = "Fake"
            override suspend fun generate(prompt: String, maxTokens: Int, jsonSchema: String?): String {
                captured = Triple(prompt, maxTokens, jsonSchema)
                return "the answer"
            }
        }
        val thoughts = mutableListOf<String>()
        val answers = mutableListOf<String>()

        val result = backend.generateStreaming(
            "q",
            maxTokens = 64,
            think = true,
            onThinking = { thoughts += it },
            onAnswer = { answers += it }
        )

        assertEquals("the answer", result)
        assertEquals(Triple("q", 64, null), captured)
        assertEquals(listOf("the answer"), answers)
        assertEquals(emptyList<String>(), thoughts)
    }
}
