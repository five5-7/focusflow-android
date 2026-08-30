package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ImprovementNoteCodecTest {
    @Test fun roundtrip_preservesFields() {
        val note = ImprovementNote(5L, "先做最小版本", 456L)
        val decoded = ImprovementNoteCodec.decode(ImprovementNoteCodec.encode(listOf(note))).single()
        assertEquals(5L, decoded.id)
        assertEquals("先做最小版本", decoded.text)
        assertEquals(456L, decoded.createdAt)
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, ImprovementNoteCodec.decode("not-json{{{").size)
    }
}
