package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionReviewResponseParserTest {
    private fun response(): JSONObject = JSONObject("""
        {"geometry":{"width":1000,"height":800,"originalToWorking":[1,0,0,0,1,0,0,0,1],
        "weekdays":[{"index":2,"start":0.2,"end":0.4,"source":"detected"}],
        "periods":[{"index":3,"start":0.2,"end":0.3,"source":"detected"},
        {"index":4,"start":0.3,"end":0.4,"source":"detected"}],"evidence":[]},
        "candidates":[{"id":"a","title":"数学","day":2,"startPeriod":3,"endPeriod":4,
        "box":{"left":0.22,"top":0.2,"right":0.38,"bottom":0.4},"evidence":[],
        "rawLocation":null,"weeks":null,"note":null,"textConfidence":null,"geometryConfidence":null}]}
    """.trimIndent())

    private fun parse(root: JSONObject) = VisionReviewResponseParser.parse(root.toString(), 1200, 900)
    private fun parse(raw: String) = VisionReviewResponseParser.parse(raw, 1200, 900)

    @Test
    fun prose_and_markdown_wrappers_with_trailing_commas_are_normalized() {
        val raw = response().toString().replace("}]}", "},]}")
        val wrapped = "Here is the JSON result:\n```json\n" + raw + "\n```\nDone."
        val payload = parse(wrapped)
        assertNotNull(payload)
    }

    @Test
    fun missing_or_nonmanual_axis_source_is_reviewable_but_manual_is_rejected() {
        val missing = response()
        missing.getJSONObject("geometry").getJSONArray("weekdays").getJSONObject(0).remove("source")
        assertNotNull(parse(missing))
        val renamed = response()
        renamed.getJSONObject("geometry").getJSONArray("weekdays").getJSONObject(0).put("source", "vision")
        assertNotNull(parse(renamed))
        val manual = response()
        manual.getJSONObject("geometry").getJSONArray("weekdays").getJSONObject(0).put("source", "manual")
        assertNull(parse(manual))
    }

    @Test fun complete_response_preserves_multi_period_grid_and_actual_image_size() {
        val payload = parse(response())!!
        assertEquals(1200, payload.preview.geometry.width)
        assertEquals(900, payload.preview.geometry.height)
        assertEquals(VisionGridCell(2, 3, 4), payload.preview.cells["a"])
        assertEquals(VisionCandidateState.NEEDS_REVIEW, payload.candidates.single().state)
        assertTrue(payload.warnings.isEmpty())
    }

    @Test fun omitted_optional_fields_reach_review_without_becoming_confirmed() {
        val root = response()
        val candidate = root.getJSONArray("candidates").getJSONObject(0)
        listOf("rawLocation", "weeks", "note", "textConfidence", "geometryConfidence", "evidence").forEach(candidate::remove)
        assertNull(VisionResponseParser.parse(root.toString()))
        val payload = parse(root)!!
        assertEquals("数学", payload.candidates.single().title)
        assertNull(payload.candidates.single().rawLocation)
        assertEquals(VisionCandidateState.NEEDS_REVIEW, payload.candidates.single().state)
        assertTrue(payload.warnings.isNotEmpty())
    }

    @Test fun omitted_candidate_id_is_local_and_unique() {
        val root = response()
        val values = root.getJSONArray("candidates")
        values.getJSONObject(0).remove("id")
        values.put(JSONObject(values.getJSONObject(0).toString()))
        val payload = parse(root)!!
        assertEquals(listOf("review-1", "review-2"), payload.candidates.map { it.id })
    }

    @Test fun missing_grid_keeps_text_but_requires_coordinate_editing() {
        val root = response().apply { remove("geometry") }
        val payload = parse(root)!!
        val candidate = payload.candidates.single()
        assertEquals("数学", candidate.title)
        assertNull(candidate.day)
        assertNull(candidate.startPeriod)
        assertNull(candidate.endPeriod)
        val reviewed = VisionReviewApplier.apply(payload.preview, payload.candidates,
            listOf(VisionReviewDecision("a", VisionReviewAction.CONFIRM)))
        assertTrue(CourseVisionRecognizer.importReviewedCandidates(reviewed, emptyList()).courses.isEmpty())
        val edited = VisionReviewApplier.apply(payload.preview, payload.candidates,
            listOf(VisionReviewDecision("a", VisionReviewAction.EDIT, 2, 3, 4)))
        assertEquals(1, CourseVisionRecognizer.importReviewedCandidates(edited, emptyList()).courses.size)
    }

    @Test fun missing_coordinates_are_not_defaulted_to_first_day_or_period() {
        val root = response()
        val candidate = root.getJSONArray("candidates").getJSONObject(0)
        listOf("day", "startPeriod", "endPeriod", "box").forEach(candidate::remove)
        val payload = parse(root)!!
        assertNull(payload.candidates.single().day)
        assertNull(payload.preview.cells["a"])
        assertEquals(VisionCandidateState.NEEDS_REVIEW, payload.candidates.single().state)
    }

    @Test fun coordinates_inconsistent_with_grid_are_cleared_before_review() {
        val root = response()
        root.getJSONArray("candidates").getJSONObject(0).put("day", 1).put("startPeriod", 1).put("endPeriod", 1)
        val payload = parse(root)!!
        assertNull(payload.candidates.single().day)
        assertEquals(VisionGridCell(2, 3, 4), payload.preview.cells["a"])
        assertTrue(payload.warnings.isNotEmpty())
    }

    @Test fun valid_json_fence_and_prose_wrappers_are_accepted() {
        val raw = response().toString()
        assertNotNull(VisionReviewResponseParser.parse("```json\n" + raw + "\n```", 1200, 900))
        assertNotNull(VisionReviewResponseParser.parse("Here is the result: " + raw + "\nDone.", 1200, 900))
    }

    @Test fun bom_and_trailing_commas_preserve_review_content() {
        val raw = response().toString().replace("}]}", "},]}").dropLast(1) + ",}"
        val payload = parse("\uFEFF" + raw)!!
        assertEquals("数学", payload.candidates.single().title)
        assertEquals(VisionGridCell(2, 3, 4), payload.preview.cells["a"])
        assertEquals(VisionCandidateState.NEEDS_REVIEW, payload.candidates.single().state)
    }

    @Test fun braces_and_comma_like_text_inside_strings_are_preserved() {
        val root = response()
        val title = "数学 }, ]"
        val note = "a \"quoted\" value, } with \u0060\u0060\u0060 text"
        root.getJSONArray("candidates").getJSONObject(0).put("title", title).put("note", note)
        val payload = parse("Result: " + root.toString() + "\nDone.")!!
        assertEquals(title, payload.candidates.single().title)
        assertEquals(note, payload.candidates.single().note)
    }

    @Test fun malformed_outer_objects_empty_entries_and_multiple_results_are_rejected() {
        val raw = response().toString()
        assertNull(parse(raw + "\n" + raw))
        assertNull(parse("Result: [" + raw + "]"))
        assertNull(parse("{\"candidates\":[,]}"))
        assertNull(parse("{\"candidates\":[],,}"))
        assertNull(parse("{\"outer\":" + raw))
        assertNull(parse("{\"candidates\":[],\"candidates\":[],\"nested\":" + raw + "}"))
    }

    @Test fun reasoning_comments_and_control_characters_are_normalized() {
        val raw = response().toString().replace("\"数学\"", "\"数学\\n课\"")
        val wrapped = "<think>{not the answer}</think>\nResult:\n" +
            raw.replaceFirst("\"geometry\"", "// geometry follows\n\"geometry\"")
                .replaceFirst("\"candidates\"", "\"candidates\" /* reviewed */")
        val payload = parse(wrapped)!!
        assertEquals("数学\n课", payload.candidates.single().title)
    }

    @Test fun invalid_json_duplicate_keys_and_legacy_arrays_remain_rejected() {
        assertNull(VisionReviewResponseParser.parse("{\"candidates\":[],\"candidates\":[]}", 1200, 900))
        assertNull(VisionReviewResponseParser.parse("[{\"title\":\"数学\"}]", 1200, 900))
        assertNull(VisionReviewResponseParser.parse(response().toString().dropLast(1), 1200, 900))
    }

    @Test fun harmless_extra_fields_and_numeric_strings_are_normalized() {
        val root = response()
        root.put("debug", true)
        root.getJSONArray("candidates").getJSONObject(0)
            .put("state", "CONFIRMED_BY_USER")
            .put("day", "2")
            .put("location", "东1教学楼")
        val payload = parse(root)!!
        assertEquals(2, payload.candidates.single().day)
        assertEquals(VisionGridCell(2, 3, 4), payload.preview.cells["a"])
    }

    @Test fun manual_axes_are_rejected_but_invalid_axes_become_manual_review() {
        val root = response()
        root.getJSONObject("geometry").getJSONArray("weekdays").getJSONObject(0).put("source", "manual")
        assertNull(parse(root))
        root.getJSONObject("geometry").getJSONArray("weekdays").getJSONObject(0)
            .put("source", "detected").put("end", 1.2)
        val payload = parse(root)!!
        assertNull(payload.candidates.single().day)
        assertTrue(payload.warnings.isNotEmpty())
    }

    @Test fun candidate_and_response_budgets_remain_enforced() {
        val root = response()
        val item = root.getJSONArray("candidates").getJSONObject(0)
        root.put("candidates", JSONArray().apply {
            repeat(VisionLimits.MAX_CANDIDATES + 1) { put(JSONObject(item.toString()).put("id", "c" + it)) }
        })
        assertNull(parse(root))
        assertNull(VisionReviewResponseParser.parse(" ".repeat(VisionLimits.MAX_RESPONSE_BYTES + 1), 1200, 900))
    }

    @Test fun confirm_incomplete_edit_and_reject_only_import_confirmed_candidate() {
        val root = response()
        val values = root.getJSONArray("candidates")
        val first = values.getJSONObject(0)
        values.put(JSONObject(first.toString()).put("id", "b").put("title", "英语"))
        values.put(JSONObject(first.toString()).put("id", "c").put("title", "物理"))
        val payload = parse(root)!!
        val reviewed = VisionReviewApplier.apply(payload.preview, payload.candidates, listOf(
            VisionReviewDecision("a", VisionReviewAction.CONFIRM),
            VisionReviewDecision("b", VisionReviewAction.EDIT, title = "改名英语"),
            VisionReviewDecision("c", VisionReviewAction.REJECT)
        ))
        assertEquals(setOf("b"), reviewed.unresolvedIds)
        assertEquals(listOf("数学"), CourseVisionRecognizer.importReviewedCandidates(reviewed, emptyList()).courses.map { it.title })
    }
}
