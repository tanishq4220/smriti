@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.smriti.app.ai

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
    override val label: String,
    /** Called once, after the first generation returns. Clears the GPU crash sentinel. */
    private var onFirstGeneration: (() -> Unit)? = null
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
            onFirstGeneration?.let { it(); onFirstGeneration = null }
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
}
