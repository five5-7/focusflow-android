package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class StringArrayCodecTest {
    @Test fun roundtrip_preservesOrder() {
        val values = listOf("西1教学楼", "东2", "图书馆")
        assertEquals(values, StringArrayCodec.decodeNonBlank(StringArrayCodec.encode(values)))
    }

    @Test fun decodeNonBlank_skipsBlankEntries() {
        val raw = StringArrayCodec.encode(listOf("  ", "有效", ""))
        assertEquals(listOf("有效"), StringArrayCodec.decodeNonBlank(raw))
    }

    @Test fun decodeStrict_keepsEveryEntryIncludingBlank() {
        val raw = StringArrayCodec.encode(listOf("2026-08-30:午餐", "2026-08-31:晚餐"))
        assertEquals(listOf("2026-08-30:午餐", "2026-08-31:晚餐"), StringArrayCodec.decodeStrict(raw))
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, StringArrayCodec.decodeNonBlank("not-json{{{").size)
        assertEquals(0, StringArrayCodec.decodeStrict("not-json{{{").size)
    }

    @Test fun encode_acceptsCollection() {
        assertEquals(2, StringArrayCodec.decodeNonBlank(StringArrayCodec.encode(setOf("a", "b"))).size)
    }
}
