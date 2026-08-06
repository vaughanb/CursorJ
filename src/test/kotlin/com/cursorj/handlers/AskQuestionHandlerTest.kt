package com.cursorj.handlers

import com.cursorj.acp.messages.AskQuestionItem
import com.cursorj.acp.messages.AskQuestionOptionItem
import com.cursorj.acp.messages.AskQuestionParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AskQuestionHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun params(vararg questions: AskQuestionItem): JsonElement =
        json.encodeToJsonElement(
            AskQuestionParams.serializer(),
            AskQuestionParams(toolCallId = "call_1", title = "Pick", questions = questions.toList()),
        )

    private fun singleQuestion() = AskQuestionItem(
        id = "q1",
        prompt = "Which backend?",
        options = listOf(
            AskQuestionOptionItem(id = "redis", label = "Redis"),
            AskQuestionOptionItem(id = "mem", label = "In-memory"),
        ),
    )

    @Test
    fun `declines when no resolver is registered`() {
        val handler = AskQuestionHandler()
        assertNull(handler.handleAskQuestion(params(singleQuestion())))
    }

    @Test
    fun `declines empty or malformed requests so agent can fall back`() {
        val handler = AskQuestionHandler()
        handler.setResolver { CompletableFuture.completedFuture(AskQuestionOutcome.Cancelled) }

        assertNull(handler.handleAskQuestion(params()))
        assertNull(handler.handleAskQuestion(Json.parseToJsonElement("""{"questions":"not-a-list"}""")))
    }

    @Test
    fun `resolver failure resolves to cancelled`() {
        val handler = AskQuestionHandler()
        handler.setResolver {
            CompletableFuture<AskQuestionOutcome>().apply { completeExceptionally(RuntimeException("boom")) }
        }

        val result = handler.handleAskQuestion(params(singleQuestion()))

        assertEquals("cancelled", outcomeType(result))
    }

    @Test
    fun `skipped outcome includes reason`() {
        val handler = AskQuestionHandler()
        handler.setResolver {
            CompletableFuture.completedFuture(AskQuestionOutcome.Skipped("not now"))
        }

        val outcome = requireOutcome(handler.handleAskQuestion(params(singleQuestion())))

        assertEquals("skipped", outcome["outcome"]?.jsonPrimitive?.content)
        assertEquals("not now", outcome["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `answered outcome serializes questionId and selected option ids`() {
        val handler = AskQuestionHandler()
        handler.setResolver {
            CompletableFuture.completedFuture(
                AskQuestionOutcome.Answered(
                    listOf(
                        AskQuestionAnswer(questionId = "q1", selectedOptionIds = listOf("redis")),
                        AskQuestionAnswer(questionId = "q2", selectedOptionIds = listOf("a", "b")),
                    ),
                ),
            )
        }

        val outcome = requireOutcome(handler.handleAskQuestion(params(singleQuestion())))

        assertEquals("answered", outcome["outcome"]?.jsonPrimitive?.content)
        val answers = outcome["answers"]!!.jsonArray
        assertEquals(2, answers.size)
        assertEquals("q1", answers[0].jsonObject["questionId"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("redis"),
            answers[0].jsonObject["selectedOptionIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("a", "b"),
            answers[1].jsonObject["selectedOptionIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `decodes real agent payload shape`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "toolCallId": "call_abc",
              "title": "Clarifying Questions",
              "questions": [
                {
                  "id": "q1",
                  "prompt": "Which backend?",
                  "options": [
                    {"id": "redis", "label": "Redis"},
                    {"id": "mem", "label": "In-memory"}
                  ],
                  "allowMultiple": false
                }
              ]
            }
            """.trimIndent(),
        )
        val decoded = json.decodeFromJsonElement(AskQuestionParams.serializer(), payload)

        assertEquals("call_abc", decoded.toolCallId)
        assertEquals(1, decoded.questions.size)
        assertEquals("q1", decoded.questions[0].id)
        assertEquals(listOf("redis", "mem"), decoded.questions[0].options.map { it.id })
        assertEquals(false, decoded.questions[0].allowMultiple)
    }

    private fun outcomeType(result: JsonElement?): String? =
        result?.jsonObject?.get("outcome")?.jsonObject?.get("outcome")?.jsonPrimitive?.content

    private fun requireOutcome(result: JsonElement?) =
        result!!.jsonObject["outcome"]!!.jsonObject
}
