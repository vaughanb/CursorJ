package com.cursorj.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanQuestionParserTest {

    @Test
    fun `non-option prose yields no questions`() {
        val cases = mapOf(
            "empty" to "",
            "plain prose" to "I will add a caching layer and report back when done.",
            "single option only" to "Here is one idea:\n- A) Use Redis",
            "ordinary numbered steps" to buildString {
                appendLine("Here is the plan:")
                appendLine("1) First we read the files")
                appendLine("2) Then we write the cache")
                appendLine("3) Finally we test it")
            },
        )

        for ((name, input) in cases) {
            assertEquals(emptyList(), PlanQuestionParser.parse(input), "case: $name")
        }
    }

    @Test
    fun `lettered options without a cue still parse`() {
        val input = buildString {
            appendLine("Some neutral heading")
            appendLine("- A) Redis")
            appendLine("- B) In-memory")
            appendLine("- C) Memcached")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(1, questions.size)
        assertEquals(listOf("Redis", "In-memory", "Memcached"), questions[0].options.map { it.label })
        assertEquals(listOf("A", "B", "C"), questions[0].options.map { it.marker })
    }

    @Test
    fun `numbered options require a question prompt or cue`() {
        val withoutCue = buildString {
            appendLine("Neutral heading with no question")
            appendLine("1) In-memory")
            appendLine("2) Persistent on disk")
        }
        assertEquals(emptyList(), PlanQuestionParser.parse(withoutCue))

        val withCue = buildString {
            appendLine("What cache durability do you want?")
            appendLine("1) In-memory")
            appendLine("2) Persistent on disk")
        }
        val parsed = PlanQuestionParser.parse(withCue)
        assertEquals(1, parsed.size)
        assertEquals("What cache durability do you want?", parsed[0].prompt)
        assertEquals(listOf("In-memory", "Persistent on disk"), parsed[0].options.map { it.label })
    }

    @Test
    fun `parses single question from agent prose with reply cue`() {
        val input = buildString {
            appendLine("Which caching backend do you prefer for this work?")
            appendLine()
            appendLine("- A) Redis")
            appendLine("- B) In-memory")
            appendLine("- C) Memcached")
            appendLine()
            appendLine("Reply with **A**, **B**, or **C**.")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(1, questions.size)
        assertEquals("Which caching backend do you prefer for this work?", questions[0].prompt)
        assertEquals(listOf("Redis", "In-memory", "Memcached"), questions[0].options.map { it.label })
    }

    @Test
    fun `tolerates pros and cons sub-bullets between lettered options`() {
        val input = buildString {
            appendLine("In this codebase, add caching could mean a few things:")
            appendLine("- **A) Retrieval cache**")
            appendLine("  - **Pros:** fastest user-visible win")
            appendLine("  - **Cons:** invalidation complexity")
            appendLine("- **B) Skills discovery cache**")
            appendLine("  - **Pros:** low risk")
            appendLine("  - **Cons:** narrower impact")
            appendLine("- **C) Session cache**")
            appendLine("  - **Pros:** reduces parsing")
            appendLine("  - **Cons:** highest correctness risk")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(1, questions.size)
        assertEquals(
            listOf("Retrieval cache", "Skills discovery cache", "Session cache"),
            questions[0].options.map { it.label },
        )
    }

    @Test
    fun `parses multiple distinct questions in one message`() {
        val input = buildString {
            appendLine("Which caching backend do you prefer?")
            appendLine("- A) Redis")
            appendLine("- B) In-memory")
            appendLine()
            appendLine("What cache durability do you want for that target?")
            appendLine("- 1) In-memory only")
            appendLine("- 2) Persistent on disk")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(2, questions.size)
        assertEquals("Which caching backend do you prefer?", questions[0].prompt)
        assertEquals(listOf("Redis", "In-memory"), questions[0].options.map { it.label })
        assertEquals("What cache durability do you want for that target?", questions[1].prompt)
        assertEquals(listOf("In-memory only", "Persistent on disk"), questions[1].options.map { it.label })
    }

    @Test
    fun `does not merge two separate lettered lists that both restart at A`() {
        val input = buildString {
            appendLine("First choice?")
            appendLine("- A) One")
            appendLine("- B) Two")
            appendLine()
            appendLine("Second choice?")
            appendLine("- A) Three")
            appendLine("- B) Four")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(2, questions.size)
        assertTrue(questions.all { it.options.size == 2 })
        assertEquals(listOf("One", "Two"), questions[0].options.map { it.label })
        assertEquals(listOf("Three", "Four"), questions[1].options.map { it.label })
    }

    @Test
    fun `handles dotted markers and bold-wrapped markers`() {
        val input = buildString {
            appendLine("Pick one:")
            appendLine("**A.** First option")
            appendLine("**B.** Second option")
        }

        val questions = PlanQuestionParser.parse(input)

        assertEquals(1, questions.size)
        assertEquals(listOf("First option", "Second option"), questions[0].options.map { it.label })
        assertEquals(listOf("A", "B"), questions[0].options.map { it.marker })
    }
}
