package com.cursorj.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillFrontmatterParserTest {

    @Test
    fun `parse returns defaults when no frontmatter`() {
        val r = SkillFrontmatterParser.parse("# Title\n\nBody")
        assertEquals(null, r.name)
        assertEquals(null, r.description)
        assertTrue(r.paths.isEmpty())
        assertFalse(r.disableModelInvocation)
    }

    @Test
    fun `parse reads name description paths string and disable flag`() {
        val md = """
            ---
            name: my-skill
            description: Use when deploying
            paths: src/**, *.kt
            disable-model-invocation: true
            ---

            # Body
        """.trimIndent()
        val r = SkillFrontmatterParser.parse(md)
        assertEquals("my-skill", r.name)
        assertEquals("Use when deploying", r.description)
        assertEquals(listOf("src/**", "*.kt"), r.paths)
        assertTrue(r.disableModelInvocation)
    }

    @Test
    fun `parse paths as yaml list`() {
        val md = """
            ---
            paths:
              - a/**/b
              - "c/d"
            ---
        """.trimIndent()
        val r = SkillFrontmatterParser.parse(md)
        assertEquals(listOf("a/**/b", "c/d"), r.paths)
    }

    @Test
    fun `parse disable snake case`() {
        val md = """
            ---
            disable_model_invocation: yes
            ---
        """.trimIndent()
        assertTrue(SkillFrontmatterParser.parse(md).disableModelInvocation)
    }
}
