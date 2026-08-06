package com.cursorj.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class FileReferenceSupportTest {
    @Test
    fun `reference regex matches paths with extensions`() {
        val match = FileReferenceSupport.referenceRegex.find("@main.go")
        assertEquals("@main.go", match?.value)
        assertEquals("main.go", match?.let(FileReferenceSupport::extractPath))
    }

    @Test
    fun `reference regex matches nested paths`() {
        val match = FileReferenceSupport.referenceRegex.find("@src/main/kotlin/App.kt")
        assertEquals("@src/main/kotlin/App.kt", match?.value)
        assertEquals("src/main/kotlin/App.kt", match?.let(FileReferenceSupport::extractPath))
    }

    @Test
    fun `reference regex matches quoted paths with spaces`() {
        val match = FileReferenceSupport.referenceRegex.find("""@"my file.go"""")
        assertEquals("""@"my file.go"""", match?.value)
        assertEquals("my file.go", match?.let(FileReferenceSupport::extractPath))
    }

    @Test
    fun `normalizeReferencePath trims trailing punctuation`() {
        assertEquals("main.go", FileReferenceSupport.normalizeReferencePath("main.go,"))
        assertEquals("main.go", FileReferenceSupport.normalizeReferencePath("main.go."))
    }

    @Test
    fun `findValidSpans only includes existing references`() {
        val text = "@main.go I shouldn't be highlighted"
        val spans = FileReferenceSupport.findValidSpans(text) { path -> path == "main.go" }
        assertEquals(1, spans.size)
        assertEquals(0, spans.single().start)
        assertEquals(8, spans.single().end)
        assertEquals("main.go", spans.single().path)
    }

    @Test
    fun `spanContaining detects caret inside reference`() {
        val span = FileReferenceSupport.ReferenceSpan(0, 8, "main.go")
        val spans = listOf(span)
        assertEquals(span, FileReferenceSupport.spanContaining(spans, 4))
        assertEquals(null, FileReferenceSupport.spanContaining(spans, 8))
        assertEquals(span, FileReferenceSupport.spanContaining(spans, 8, includeEnd = true))
    }
}
