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
import com.google.ai.edge.litertlm.ThinkingConfig
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
        val conv = engine.createConversation(
            ConversationConfig(
                enableResponseFormat = true,
                // Gemma 4 thinks before answering by default. On this phone every decode token
                // costs ~0.3 s, and the thought lands in a side channel rather than the text, so
                // a plain prompt came back empty after 44 tokens. Extraction and recall need the
                // answer, not the reasoning.
                thinkingConfig = ThinkingConfig(enableThinking = false),
                // Caller's budget, enforced by the runtime: a rambling model stops here instead
                // of decoding until the context window fills.
                maxOutputToken = maxTokens
            )
        )
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
            val text = reply.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            if (reply.channels.isNotEmpty()) {
                Log.i(TAG, "side channels: ${reply.channels.mapValues { it.value.length }} chars")
            }
            // Never hand back nothing when the model did answer, just on another channel.
            text.ifBlank { reply.channels.values.joinToString("\n").trim() }
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
