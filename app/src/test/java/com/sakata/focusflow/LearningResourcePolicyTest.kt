package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningResourcePolicyTest {
    @Test
    fun `resource requires a title and confirmed material`() {
        assertFalse(LearningResourcePolicy.canSave("概率论", "", ""))
        assertFalse(LearningResourcePolicy.canSave("", "https://example.com", ""))
        assertTrue(LearningResourcePolicy.canSave("概率论", "https://example.com", ""))
        assertTrue(LearningResourcePolicy.canSave("概率论", "", "我的章节笔记"))
    }

    @Test
    fun `search suggestion becomes an action rather than a resource`() {
        assertEquals(
            "在B站搜索“线性代数入门”，确认一份真实资料",
            LearningResourcePolicy.candidateFirstAction("B站", "线性代数入门")
        )
    }
}
