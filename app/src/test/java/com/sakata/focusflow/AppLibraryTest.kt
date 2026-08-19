package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLibraryTest {
    @Test fun autoCategoryByLabel_game() {
        assertEquals(AppCategory.GAME, autoCategoryByLabel("王者荣耀"))
        assertEquals(AppCategory.GAME, autoCategoryByLabel("原神"))
    }

    @Test fun autoCategoryByLabel_video() {
        assertEquals(AppCategory.VIDEO, autoCategoryByLabel("哔哩哔哩"))
        assertEquals(AppCategory.VIDEO, autoCategoryByLabel("抖音"))
    }

    @Test fun autoCategoryByLabel_social() {
        assertEquals(AppCategory.SOCIAL, autoCategoryByLabel("微信"))
        assertEquals(AppCategory.SOCIAL, autoCategoryByLabel("QQ"))
    }

    @Test fun autoCategoryByLabel_study() {
        assertEquals(AppCategory.STUDY, autoCategoryByLabel("背单词"))
        assertEquals(AppCategory.STUDY, autoCategoryByLabel("知乎"))
    }

    @Test fun autoCategoryByLabel_unknown() {
        assertNull(autoCategoryByLabel("计算器"))
    }
}
