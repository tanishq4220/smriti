@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.smriti.app.ai

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
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
        // Gemma 4 thinks before answering by default. On this phone every decode token costs
        // ~0.2 s, and the thought lands in a side channel rather than the text, so a plain
        // prompt came back empty after 44 tokens. Extraction needs the answer, not the reasoning.
        val conv = conversation(maxTokens, think = false)
        try {
            val reply = conv.sendMessage(
                message = userMessage(prompt),
                responseFormat = jsonSchema?.let { ResponseFormat.json(it) }
            )
            logSpeed(conv)
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

    /**
     * Streams through `Conversation.sendMessageAsync`, whose Flow emits one [Message] per decode
     * step carrying only the new piece: answer text in `contents`, reasoning in `channels`
     * (Gemma 4 names its channel "thought"; any channel counts as reasoning, so a model with a
     * different name still shows its work instead of leaking it into the answer).
     */
    override suspend fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        jsonSchema: String?,
        think: Boolean,
        onThinking: (String) -> Unit,
        onAnswer: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Reasoning spends the same output budget as the answer; without the headroom a long
        // thought would leave nothing for the answer itself.
        val conv = conversation(if (think) maxTokens + THINK_BUDGET else maxTokens, think)
        val thought = StringBuilder()
        val answer = StringBuilder()
        try {
            conv.sendMessageAsync(
                message = userMessage(prompt),
                responseFormat = jsonSchema?.let { ResponseFormat.json(it) }
            ).collect { piece ->
                val text = piece.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                val reasoning = piece.channels.values.joinToString("")
                if (reasoning.isNotEmpty()) {
                    thought.append(reasoning)
                    onThinking(thought.toString())
                }
                if (text.isNotEmpty()) {
                    answer.append(text)
                    onAnswer(answer.toString())
                }
            }
            logSpeed(conv)
            if (thought.isNotEmpty()) Log.i(TAG, "thought ${thought.length} chars")
            answer.toString().ifBlank { thought.toString().trim() }
        } catch (c: CancellationException) {
            // The library's Flow does not stop native decoding when collection is cancelled,
            // and closing a conversation mid-decode invites a native crash.
            runCatching { conv.cancelProcess() }
            throw c
        } finally {
            conv.close()
        }
    }

    private fun conversation(maxTokens: Int, think: Boolean): Conversation =
        engine.createConversation(
            ConversationConfig(
                enableResponseFormat = true,
                thinkingConfig = if (think) {
                    ThinkingConfig(enableThinking = true, thinkingTokenBudget = THINK_BUDGET)
                } else {
                    ThinkingConfig(enableThinking = false)
                },
                // Caller's budget, enforced by the runtime: a rambling model stops here instead
                // of decoding until the context window fills.
                maxOutputToken = maxTokens
            )
        )

    private fun userMessage(prompt: String) =
        Message(role = Role.USER, contents = Contents.of(Content.Text(prompt)))

    /** Real token counts and rates from the native runtime, not a word-count estimate. */
    private fun logSpeed(conv: Conversation) {
        runCatching { conv.getBenchmarkInfo() }.getOrNull()?.let { b ->
            Log.i(TAG, "SPEED $label · prefill ${b.lastPrefillTokenCount} tok @ " +
                "${"%.1f".format(b.lastPrefillTokensPerSecond)} tok/s · decode " +
                "${b.lastDecodeTokenCount} tok @ ${"%.1f".format(b.lastDecodeTokensPerSecond)} tok/s · " +
                "ttft ${"%.2f".format(b.timeToFirstTokenInSecond)} s")
        }
    }

    override fun close() {
        engine.close()
    }

    private companion object {
        const val TAG = "SmritiEngine"

        /**
         * Cap on reasoning tokens. At ~4.7 tok/s on the Realme, 256 made an Ask take ~75 s;
         * 96 is about 20 s of visible thinking before the answer must start. Unbounded, Gemma
         * can reason for longer than anyone will wait.
         */
        const val THINK_BUDGET = 96
    }
}
