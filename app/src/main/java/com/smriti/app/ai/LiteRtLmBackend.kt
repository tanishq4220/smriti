@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.smriti.app.ai

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtLmBackend(
    private val engine: Engine,
    override val label: String
) : LlmBackend {

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        jsonSchema: String?
    ): String = withContext(Dispatchers.IO) {
        val conv = engine.createConversation(ConversationConfig(enableResponseFormat = true))
        try {
            val reply = conv.sendMessage(
                message = Message(
                    role = Role.USER,
                    contents = Contents.of(Content.Text(prompt))
                ),
                responseFormat = jsonSchema?.let { ResponseFormat.json(it) }
            )
            // Real token counts and rates from the native runtime, not a word-count estimate.
            runCatching { conv.getBenchmarkInfo() }.getOrNull()?.let { b ->
                Log.i(TAG, "SPEED $label · prefill ${b.lastPrefillTokenCount} tok @ " +
                    "${"%.1f".format(b.lastPrefillTokensPerSecond)} tok/s · decode " +
                    "${b.lastDecodeTokenCount} tok @ ${"%.1f".format(b.lastDecodeTokensPerSecond)} tok/s · " +
                    "ttft ${"%.2f".format(b.timeToFirstTokenInSecond)} s")
            }
            reply.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } finally {
            conv.close()
        }
    }

    override fun close() {
        engine.close()
    }

    private companion object {
        const val TAG = "SmritiEngine"
    }
}
