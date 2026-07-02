package com.jauschua.ironlogv2.ui.today

import com.jauschua.ironlogv2.ui.screens.today.GenerateOutcomeKind
import com.jauschua.ironlogv2.ui.screens.today.classifyGenerate
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayLogicTest {
    @Test fun nonexhausted_generate_with_preview_is_reviewable() {
        assertEquals(GenerateOutcomeKind.REVIEWABLE, classifyGenerate(exhausted = false, hasPreview = true))
    }
    @Test fun exhausted_generate_is_error() {
        assertEquals(GenerateOutcomeKind.ERROR, classifyGenerate(exhausted = true, hasPreview = false))
    }
    @Test fun nonexhausted_but_null_preview_is_error() {
        assertEquals(GenerateOutcomeKind.ERROR, classifyGenerate(exhausted = false, hasPreview = false))
    }
}
