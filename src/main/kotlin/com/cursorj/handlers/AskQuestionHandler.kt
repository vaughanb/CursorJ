package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.AskQuestionParams
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** AskQuestionAnswer is the user's selection for a single question in a `cursor/ask_question` request. */
data class AskQuestionAnswer(
    val questionId: String,
    val selectedOptionIds: List<String>,
)

/** AskQuestionOutcome is the resolved response to a `cursor/ask_question` extension request. */
sealed interface AskQuestionOutcome {
    data class Answered(val answers: List<AskQuestionAnswer>) : AskQuestionOutcome
    data class Skipped(val reason: String?) : AskQuestionOutcome
    object Cancelled : AskQuestionOutcome
}

/**
 * AskQuestionHandler answers the agent's `cursor/ask_question` ACP extension request, the structured
 * channel a Cursor-aware client uses to render interactive multiple-choice questions.
 *
 * When no UI resolver is registered (or the request is malformed/empty) the handler declines by
 * returning `null`, so the agent falls back to its `session/request_permission` flow handled by
 * [PermissionHandler].
 */
class AskQuestionHandler(
    private val promptTimeoutMinutes: Long = DEFAULT_PROMPT_TIMEOUT_MINUTES,
) {
    private val log = Logger.getInstance(AskQuestionHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var resolver: ((AskQuestionParams) -> CompletableFuture<AskQuestionOutcome>)? = null

    fun setResolver(resolver: ((AskQuestionParams) -> CompletableFuture<AskQuestionOutcome>)?) {
        this.resolver = resolver
    }

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                EXT_METHOD -> handleAskQuestion(params)
                else -> null
            }
        }
    }

    internal fun handleAskQuestion(params: JsonElement): JsonElement? {
        val activeResolver = resolver ?: return null
        val request = try {
            json.decodeFromJsonElement<AskQuestionParams>(params)
        } catch (e: Exception) {
            log.warn("Failed to decode cursor/ask_question params; declining so the agent can fall back", e)
            return null
        }
        if (request.questions.isEmpty()) return null

        val outcome = try {
            activeResolver(request).get(promptTimeoutMinutes, TimeUnit.MINUTES)
        } catch (e: Exception) {
            log.warn("ask_question resolver failed or timed out; cancelling", e)
            AskQuestionOutcome.Cancelled
        }
        return buildResult(outcome)
    }

    private fun buildResult(outcome: AskQuestionOutcome): JsonElement = buildJsonObject {
        putJsonObject("outcome") {
            when (outcome) {
                is AskQuestionOutcome.Answered -> {
                    put("outcome", "answered")
                    putJsonArray("answers") {
                        for (answer in outcome.answers) {
                            addJsonObject {
                                put("questionId", answer.questionId)
                                putJsonArray("selectedOptionIds") {
                                    for (id in answer.selectedOptionIds) add(id)
                                }
                            }
                        }
                    }
                }
                is AskQuestionOutcome.Skipped -> {
                    put("outcome", "skipped")
                    outcome.reason?.let { put("reason", it) }
                }
                AskQuestionOutcome.Cancelled -> put("outcome", "cancelled")
            }
        }
    }

    companion object {
        const val EXT_METHOD = "cursor/ask_question"
        private const val DEFAULT_PROMPT_TIMEOUT_MINUTES = 10L
    }
}
