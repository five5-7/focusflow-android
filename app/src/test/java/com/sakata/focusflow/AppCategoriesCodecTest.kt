package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoriesCodecTest {
    @Test fun roundtrip_preservesEntries() {
        val categories = mapOf("com.taptap" to "游戏", "com.zhihu" to "阅读")
        assertEquals(categories, AppCategoriesCodec.decode(AppCategoriesCodec.encode(categories)))
    }

    @Test fun encode_skipsBlankCategory() {
        val raw = AppCategoriesCodec.encode(mapOf("com.a" to "游戏", "com.b" to "  "))
        assertEquals(mapOf("com.a" to "游戏"), AppCategoriesCodec.decode(raw))
    }

    @Test fun decode_filtersBlankValues() {
        val raw = """{"com.a":"游戏","com.b":""}"""
        assertEquals(mapOf("com.a" to "游戏"), AppCategoriesCodec.decode(raw))
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, AppCategoriesCodec.decode("not-json{{{").size)
    }
}
