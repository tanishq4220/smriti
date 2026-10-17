package com.smriti.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainReasoningTest {

    @Test
    fun `strips emphasis and turns list stars into bullets`() {
        val raw = "1.  **Analyze the Request:**\n    *   **Date:** 2026-10-17.\n    - plain item"
        assertEquals(
            "1.  Analyze the Request:\n    •   Date: 2026-10-17.\n    • plain item",
            plainReasoning(raw)
        )
    }

    @Test
    fun `collapses runs of blank lines`() {
        assertEquals("a\n\nb", plainReasoning("a\n\n\n\nb\n"))
    }
}
