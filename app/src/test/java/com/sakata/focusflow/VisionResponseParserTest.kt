package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class VisionResponseParserTest {
    private fun response(candidateId: String = "c1", extra: String = "") = """
        {
          "geometry":{
            "width":1000,
            "height":800,
            "originalToWorking":[1,0,0,0,1,0,0,0,1],
            "weekdays":[{"index":1,"start":0.0,"end":1.0,"source":"detected"}],
            "periods":[{"index":1,"start":0.0,"end":1.0,"source":"detected"}],
            "evidence":["header","row"]
          },
          "candidates":[{
            "id":"$candidateId",
            "title":"数学",
            "day":1,
            "startPeriod":1,
            "endPeriod":1,
            "box":{"left":0.1,"top":0.1,"right":0.3,"bottom":0.3},
            "evidence":["数学"],
            "rawLocation":"东1A-213",
            "weeks":[1,2],
            "note":null,
            "textConfidence":0.9,
            "geometryConfidence":0.8
          }]
          $extra
        }
    """.trimIndent()

    @Test fun parses_strict_structured_response() {
        val parsed = VisionResponseParser.parse(response())
        assertNotNull(parsed)
        assertEquals("c1", parsed!!.candidates.single().id)
        assertEquals(1, parsed.geometry.weekdays.single().index)
    }

    @Test fun legacy_array_is_left_for_legacy_parser() {
        assertNull(VisionResponseParser.parse("[{\"name\":\"数学\"}]"))
    }

    @Test fun unknown_top_level_key_is_rejected() {
        assertNull(VisionResponseParser.parse(response(extra = ",\"debug\":true")))
    }

    @Test fun duplicate_candidate_ids_are_rejected() {
        val first = response()
        val second = response("c1").substringAfter("\"candidates\":[{").substringBeforeLast("]}") 
        val duplicate = first.substringBeforeLast("]") + ",{" + second + "]}"+ "}"
        assertNull(VisionResponseParser.parse(duplicate))
    }

    @Test fun incomplete_candidate_coordinates_remain_parseable_for_review() {
        val parsed = VisionResponseParser.parse(response().replace("\"day\":1", "\"day\":null").replace("\"startPeriod\":1", "\"startPeriod\":null").replace("\"endPeriod\":1", "\"endPeriod\":null"))
        assertNotNull(parsed)
        assertNull(parsed!!.candidates.single().day)
    }
}
