package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-1 regression tests for the pure course recovery model: full-field snapshots, strict
 * encoding/decoding, structural validation and the capture (delete-set) decisions.
 */
class CourseRecoveryJournalTest {

    private fun course(
        id: Long,
        courseId: Long = id,
        title: String = "课程$id",
        weekday: Int = 1,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        building: String = "东一",
        zone: CampusZone = CampusZone.EAST_TEACHING,
        needsConfirmation: Boolean = false,
        enabled: Boolean = true,
        from: Long? = null,
        until: Long? = null,
        schoolYear: String = "",
        term: String = "",
        selectionKey: String = ""
    ) = Course(
        title = title,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        building = building,
        zone = zone,
        needsConfirmation = needsConfirmation,
        enabled = enabled,
        effectiveFromEpochDay = from,
        effectiveUntilEpochDay = until,
        id = id,
        courseId = courseId,
        externalSchoolYearCode = schoolYear,
        externalTermCode = term,
        externalSelectionKeyCandidate = selectionKey
    )

    private fun capture(
        before: List<Course>,
        ids: Set<Long>,
        scope: CourseRecoveryScope = CourseRecoveryScope.MEETING,
        now: Long = 1_000L,
        overrides: Map<Long, Boolean> = emptyMap(),
        delivered: Map<Long, Long> = emptyMap(),
        locations: Map<Long, Map<Long, String>> = emptyMap(),
        existing: List<CourseRecoveryGroup> = emptyList()
    ): CourseRecoveryCapture = CourseRecoveryJournal.captureDeletion(
        before = before,
        after = before.filterNot { it.id in ids },
        request = CourseRecoveryRequest(ids, scope, "op-1", now, overrides, delivered, locations),
        existing = existing
    )

    private fun captured(
        before: List<Course>,
        ids: Set<Long>,
        scope: CourseRecoveryScope = CourseRecoveryScope.MEETING,
        now: Long = 1_000L,
        overrides: Map<Long, Boolean> = emptyMap(),
        delivered: Map<Long, Long> = emptyMap(),
        locations: Map<Long, Map<Long, String>> = emptyMap(),
        existing: List<CourseRecoveryGroup> = emptyList()
    ): CourseRecoveryGroup {
        val result = capture(before, ids, scope, now, overrides, delivered, locations, existing)
        assertTrue("expected a captured group but got $result", result is CourseRecoveryCapture.Captured)
        return (result as CourseRecoveryCapture.Captured).group
    }

    private fun reject(
        before: List<Course>,
        ids: Set<Long>,
        scope: CourseRecoveryScope = CourseRecoveryScope.MEETING,
        now: Long = 1_000L,
        overrides: Map<Long, Boolean> = emptyMap(),
        delivered: Map<Long, Long> = emptyMap(),
        locations: Map<Long, Map<Long, String>> = emptyMap(),
        existing: List<CourseRecoveryGroup> = emptyList(),
        after: List<Course>? = null
    ): CourseRecoveryCapture.Rejected {
        val result = if (after == null) {
            capture(before, ids, scope, now, overrides, delivered, locations, existing)
        } else {
            CourseRecoveryJournal.captureDeletion(
                before, after,
                CourseRecoveryRequest(ids, scope, "op-1", now, overrides, delivered, locations),
                existing
            )
        }
        assertTrue("expected a rejection but got $result", result is CourseRecoveryCapture.Rejected)
        return result as CourseRecoveryCapture.Rejected
    }

    private fun ready(raw: String): List<CourseRecoveryGroup> {
        val load = CourseRecoveryCodec.decode(raw)
        assertTrue("expected Ready but got $load", load is CourseRecoveryLoad.Ready)
        return (load as CourseRecoveryLoad.Ready).groups
    }

    private fun revalidated(group: CourseRecoveryGroup): CourseRecoveryGroup =
        group.copy(sourceFingerprint = CourseRecoveryJournal.payloadFingerprint(group))

    // ------------------------------------------------------------------ snapshots and round trips

    @Test
    fun `captured snapshot round trips every course field including parent provenance and states`() {
        val rich = course(
            id = 11, courseId = 10, title = "大学物理", weekday = 3, startPeriod = 3, endPeriod = 4,
            building = "东二", zone = CampusZone.CHEMISTRY_LABS, needsConfirmation = true, enabled = false,
            from = 20_000L, until = 20_100L, schoolYear = "2026-2027", term = "1", selectionKey = "KEY-A"
        )
        val plain = course(
            id = 22, weekday = 7, startPeriod = 1, endPeriod = 1, building = "西一",
            zone = CampusZone.WEST_TEACHING, needsConfirmation = false, enabled = true
        )
        val group = captured(listOf(rich, plain), setOf(11L, 22L), CourseRecoveryScope.BATCH)

        assertEquals(listOf(11L, 22L), group.members.map { it.id })
        assertEquals(listOf(0, 1), group.members.map { it.sourceOrder })
        assertEquals(listOf(10L, 22L), group.parentIds)
        assertEquals(CourseRecoveryState.ACTIVE, group.state)
        assertEquals(1_000L + CourseRecoveryJournal.RETENTION_MS, group.expiresAt)

        val reloaded = ready(CourseRecoveryCodec.encode(listOf(group))).single()
        assertEquals(group, reloaded)
        assertEquals(rich, CourseSnapshotCodec.decode(reloaded.members[0].courseJson))
        assertEquals(plain, CourseSnapshotCodec.decode(reloaded.members[1].courseJson))

        val fields = JSONObject(reloaded.members[0].courseJson).keys().asSequence().toSet()
        assertEquals(
            setOf(
                "title", "weekday", "startPeriod", "endPeriod", "building", "zone",
                "needsConfirmation", "enabled", "effectiveFromEpochDay", "effectiveUntilEpochDay",
                "id", "courseId", "externalSchoolYearCode", "externalTermCode",
                "externalSelectionKeyCandidate"
            ),
            fields
        )
        // A snapshot without an explicit nullable range must round trip as null, not as a sentinel.
        assertNull(CourseSnapshotCodec.decode(reloaded.members[1].courseJson)!!.effectiveFromEpochDay)
    }

    @Test
    fun `reminder override keeps its three states and dated locations stay per meeting`() {
        val first = course(id = 11)
        val second = course(id = 22)
        val third = course(id = 33)
        val group = captured(
            before = listOf(first, second, third),
            ids = setOf(11L, 22L, 33L),
            scope = CourseRecoveryScope.BATCH,
            overrides = mapOf(11L to true, 22L to false),
            delivered = mapOf(11L to 5_000L),
            locations = mapOf(11L to mapOf(20_000L to "临时教室 3", 20_001L to "机房 5"), 22L to mapOf(20_002L to "实验室"))
        )

        val reloaded = ready(CourseRecoveryCodec.encode(listOf(group))).single()
        assertEquals(true, reloaded.members[0].reminderOverride)
        assertEquals(false, reloaded.members[1].reminderOverride)
        assertNull("an absent override key must stay absent", reloaded.members[2].reminderOverride)
        assertEquals(5_000L, reloaded.members[0].deliveredAt)
        assertNull(reloaded.members[1].deliveredAt)
        assertEquals(mapOf(20_000L to "临时教室 3", 20_001L to "机房 5"), reloaded.members[0].temporaryLocations)
        assertEquals(mapOf(20_002L to "实验室"), reloaded.members[1].temporaryLocations)
        assertTrue(reloaded.members[2].temporaryLocations.isEmpty())
        // Meeting 11 has no entry for meeting 22's day, so a location can never leak across meetings.
        assertNull(reloaded.members[0].temporaryLocations[20_002L])
    }

    // ------------------------------------------------------------------ structural validation

    @Test
    fun `structural validation rejects empty duplicate and illegal member payloads`() {
        val base = captured(listOf(course(id = 11), course(id = 22)), setOf(11L, 22L), CourseRecoveryScope.BATCH)

        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(base.copy(members = emptyList())))!!
                .contains("empty member list")
        )
        val duplicate = base.copy(
            members = listOf(base.members[0], base.members[1].copy(id = 11, courseId = base.members[0].courseId))
        )
        assertTrue(CourseRecoveryJournal.structuralError(revalidated(duplicate))!!.contains("duplicate member id"))

        val illegalJson = JSONObject(CourseSnapshotCodec.encode(course(id = 11))).put("weekday", 9).toString()
        val illegal = base.copy(
            members = listOf(base.members[0].copy(courseJson = illegalJson), base.members[1])
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(illegal))!!.contains("member snapshot is unreadable")
        )

        val unordered = base.copy(
            members = listOf(base.members[1].copy(sourceOrder = 0), base.members[0].copy(sourceOrder = 0))
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(unordered))!!
                .contains("member order is not strictly increasing")
        )

        val orphanParent = base.copy(parentIds = listOf(999L))
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(orphanParent))!!
                .contains("parent list does not match members")
        )
    }

    @Test
    fun `structural validation rejects retention lifecycle and fingerprint violations`() {
        val group = captured(listOf(course(id = 11)), setOf(11L))

        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(group.copy(expiresAt = group.expiresAt + 1)))!!
                .contains("unexpected retention window")
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(
                revalidated(group.copy(deletedAt = Long.MAX_VALUE - CourseRecoveryJournal.RETENTION_MS + 1L))
            )!!.contains("overflows retention")
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(group.copy(restoredAt = 9_000L)))!!
                .contains("unfinished group carries a completion time")
        )
        val restored = CourseRecoveryJournal.transition(
            CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORING)!!,
            CourseRecoveryState.RESTORED,
            9_000L
        )!!
        assertNull(CourseRecoveryJournal.structuralError(restored))
        assertTrue(
            CourseRecoveryJournal.structuralError(restored.copy(restoredAt = null))!!
                .contains("restored group has no completion time")
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(group.copy(sourceFingerprint = "tampered"))!!
                .contains("payload fingerprint disagrees")
        )
    }

    @Test
    fun `payload fingerprint never depends on itself and stays stable across reloads`() {
        val group = captured(listOf(course(id = 11)), setOf(11L))
        val recomputed = CourseRecoveryJournal.payloadFingerprint(group)
        assertEquals(group.sourceFingerprint, recomputed)
        assertEquals(recomputed, CourseRecoveryJournal.payloadFingerprint(group.copy(sourceFingerprint = "x")))

        val restoring = CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORING)!!
        val restored = CourseRecoveryJournal.transition(restoring, CourseRecoveryState.RESTORED, 5_000L)!!
        assertNotEquals(group.sourceFingerprint, restoring.sourceFingerprint)
        assertNotEquals(restoring.sourceFingerprint, restored.sourceFingerprint)
        assertEquals(listOf(restored), ready(CourseRecoveryCodec.encode(listOf(restored))))

        assertNull(CourseRecoveryJournal.transition(restored, CourseRecoveryState.ACTIVE))
        assertNull(CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORED, 5_000L))
        assertNull(CourseRecoveryJournal.transition(restored, CourseRecoveryState.RESTORING))
    }

    // ------------------------------------------------------------------ strict codec

    @Test
    fun `strict codec reports invalid for unknown version corrupt payload and missing fields`() {
        assertTrue(CourseRecoveryCodec.decode(null) is CourseRecoveryLoad.Ready)
        assertTrue((CourseRecoveryCodec.decode(null) as CourseRecoveryLoad.Ready).groups.isEmpty())

        listOf(
            "",
            "   ",
            "{not json",
            "[]",
            """{"groups":[]}""",
            """{"version":2,"groups":[]}""",
            """{"version":"1","groups":[]}""",
            """{"version":1}"""
        ).forEach { raw ->
            assertTrue("expected Invalid for <$raw>", CourseRecoveryCodec.decode(raw) is CourseRecoveryLoad.Invalid)
        }

        val group = captured(listOf(course(id = 11)), setOf(11L))
        val encoded = CourseRecoveryCodec.encode(listOf(group))
        assertTrue(CourseRecoveryCodec.decode(encoded) is CourseRecoveryLoad.Ready)

        val unknownState = encoded.replace("\"state\":\"active\"", "\"state\":\"paused\"")
        assertTrue(CourseRecoveryCodec.decode(unknownState) is CourseRecoveryLoad.Invalid)
        val unknownScope = encoded.replace("\"scope\":\"meeting\"", "\"scope\":\"semester\"")
        assertTrue(CourseRecoveryCodec.decode(unknownScope) is CourseRecoveryLoad.Invalid)

        // Truncated and trailing-content documents must not be read as a prefix of a valid payload.
        assertTrue(CourseRecoveryCodec.decode(encoded + " trailing") is CourseRecoveryLoad.Invalid)
        assertTrue(CourseRecoveryCodec.decode(encoded.dropLast(2)) is CourseRecoveryLoad.Invalid)
        assertTrue(CourseRecoveryCodec.decode("$encoded{}") is CourseRecoveryLoad.Invalid)

        // org.json would coerce numeric strings and stringified booleans; the codec must not.
        listOf(
            encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            encoded.replace("\"deletedAt\":1000", "\"deletedAt\":\"1000\""),
            encoded.replace("\"sourceOrder\":0", "\"sourceOrder\":\"0\""),
            encoded.replace("\"id\":11", "\"id\":\"11\""),
            encoded.replace("\"restoredAt\":null", "\"restoredAt\":\"0\"")
        ).forEach { raw ->
            assertTrue("expected Invalid for a type-coerced payload", CourseRecoveryCodec.decode(raw) is CourseRecoveryLoad.Invalid)
        }
        val numericSnapshot = JSONObject(encoded).getJSONArray("groups").getJSONObject(0)
        numericSnapshot.getJSONArray("members").getJSONObject(0).put("courseJson", 12345)
        assertTrue(
            CourseRecoveryCodec.decode(
                JSONObject().put("version", 1).put("groups", JSONArray().put(numericSnapshot)).toString()
            ) is CourseRecoveryLoad.Invalid
        )

        // A nullable member field must be present as null, not omitted.
        listOf("reminderOverride", "deliveredAt", "restoredAt").forEach { field ->
            val rawGroup = JSONObject(encoded).getJSONArray("groups").getJSONObject(0)
            if (field == "restoredAt") rawGroup.remove(field) else rawGroup.getJSONArray("members").getJSONObject(0).remove(field)
            val payload = JSONObject().put("version", 1).put("groups", JSONArray().put(rawGroup))
            assertTrue(
                "expected Invalid when $field is missing",
                CourseRecoveryCodec.decode(payload.toString()) is CourseRecoveryLoad.Invalid
            )
        }

        // A payload field cannot be dropped through encode (encode refuses unreadable groups), so the
        // raw payload is edited directly to prove the decoder rejects a missing snapshot field.
        val missingField = JSONObject(CourseSnapshotCodec.encode(course(id = 11))).apply { remove("enabled") }
        val rawGroup = JSONObject(encoded).getJSONArray("groups").getJSONObject(0)
        rawGroup.getJSONArray("members").getJSONObject(0).put("courseJson", missingField.toString())
        val missingFieldPayload = JSONObject().put("version", 1).put("groups", JSONArray().put(rawGroup))
        assertTrue(CourseRecoveryCodec.decode(missingFieldPayload.toString()) is CourseRecoveryLoad.Invalid)
    }

    @Test
    fun `strict codec rejects a duplicated group id inside the payload`() {
        val group = captured(listOf(course(id = 11)), setOf(11L))
        val one = JSONObject(CourseRecoveryCodec.encode(listOf(group)))
        val duplicated = JSONObject()
            .put("version", 1)
            .put("groups", JSONArray().put(one.getJSONArray("groups").getJSONObject(0)).put(one.getJSONArray("groups").getJSONObject(0)))
        assertTrue(CourseRecoveryCodec.decode(duplicated.toString()) is CourseRecoveryLoad.Invalid)
    }

    // ------------------------------------------------------------------ delete request handling

    @Test
    fun `capture rejects a stale or partial request instead of shrinking the delete set`() {
        val before = listOf(course(id = 11), course(id = 22), course(id = 33))

        assertTrue(reject(listOf(course(id = 11)), setOf(11L, 22L)).reason.contains("no longer present"))
        assertTrue(
            reject(before, setOf(11L), after = listOf(before[0].copy(title = "改过的课"), before[2]))
                .reason.contains("changed during the delete")
        )
        assertTrue(reject(before, setOf(11L, 22L), CourseRecoveryScope.MEETING).reason.contains("exactly one"))
        assertTrue(
            reject(
                listOf(course(id = 11, courseId = 10), course(id = 12, courseId = 10), course(id = 22)),
                setOf(11L),
                CourseRecoveryScope.PARENT
            ).reason.contains("does not cover the whole parent")
        )
        assertTrue(
            reject(before, setOf(11L), now = Long.MAX_VALUE - CourseRecoveryJournal.RETENTION_MS + 1L)
                .reason.contains("overflows")
        )
        val existing = listOf(captured(before, setOf(22L), existing = emptyList()))
        assertTrue(
            reject(before, setOf(22L), existing = existing).reason.contains("unfinished recovery group")
        )
    }

    @Test
    fun `capture rejects malformed preference snapshots instead of dropping them`() {
        val before = listOf(course(id = 11))
        assertTrue(
            reject(before, setOf(11L), locations = mapOf(11L to mapOf(20_000L to "   ")))
                .reason.contains("corrupt temporary location")
        )
        assertTrue(
            reject(before, setOf(11L), locations = mapOf(11L to mapOf(20_000L to "教".repeat(101))))
                .reason.contains("corrupt temporary location")
        )
        assertTrue(
            reject(before, setOf(11L), delivered = mapOf(11L to 0L)).reason.contains("corrupt delivery watermark")
        )
    }

    @Test
    fun `same title different ids and cross parent batches never widen the captured set`() {
        val first = course(id = 11, courseId = 10, title = "高等数学")
        val twin = course(id = 22, courseId = 20, title = "高等数学")
        val third = course(id = 33, courseId = 30, title = "大学英语")
        val before = listOf(first, twin, third)

        val single = captured(before, setOf(11L))
        assertEquals(listOf(11L), single.members.map { it.id })
        assertEquals(listOf(10L), single.parentIds)

        val batch = captured(before, setOf(11L, 22L), CourseRecoveryScope.BATCH)
        assertEquals(listOf(11L, 22L), batch.members.map { it.id })
        assertEquals(listOf(10L, 20L), batch.parentIds)

        val parent = captured(
            listOf(course(id = 11, courseId = 10, title = "高等数学"), course(id = 12, courseId = 10, title = "高等数学"), third),
            setOf(11L, 12L),
            CourseRecoveryScope.PARENT
        )
        assertEquals(listOf(11L, 12L), parent.members.map { it.id })
        assertEquals(listOf(10L), parent.parentIds)
        assertEquals(2, parent.members.size)
    }

    @Test
    fun `a parent whose meetings disagree on title or confirmation state is rejected`() {
        val mixedTitles = capture(
            listOf(course(id = 11, courseId = 10, title = "高等数学"), course(id = 12, courseId = 10, title = "大学物理")),
            setOf(11L, 12L),
            CourseRecoveryScope.PARENT
        )
        assertTrue(mixedTitles is CourseRecoveryCapture.Rejected)
        assertTrue((mixedTitles as CourseRecoveryCapture.Rejected).reason.contains("different titles"))

        val mixedConfirmation = capture(
            listOf(
                course(id = 11, courseId = 10, title = "高等数学"),
                course(id = 12, courseId = 10, title = "高等数学", needsConfirmation = true)
            ),
            setOf(11L, 12L),
            CourseRecoveryScope.PARENT
        )
        assertTrue(mixedConfirmation is CourseRecoveryCapture.Rejected)
        assertTrue(
            (mixedConfirmation as CourseRecoveryCapture.Rejected).reason.contains("different confirmation states")
        )
    }

    @Test
    fun `structural validation rejects a parent with mixed snapshot fields`() {
        val base = captured(
            listOf(
                course(id = 11, courseId = 10, title = "高等数学"),
                course(id = 12, courseId = 10, title = "高等数学")
            ),
            setOf(11L, 12L),
            CourseRecoveryScope.PARENT
        )
        val renamed = JSONObject(CourseSnapshotCodec.encode(course(id = 12, courseId = 10, title = "别的课")))
        val mixed = base.copy(
            members = listOf(base.members[0], base.members[1].copy(courseJson = renamed.toString()))
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(mixed))!!.contains("different titles")
        )

        val pending = JSONObject(
            CourseSnapshotCodec.encode(course(id = 12, courseId = 10, title = "高等数学", needsConfirmation = true))
        )
        val mixedState = base.copy(
            members = listOf(base.members[0], base.members[1].copy(courseJson = pending.toString()))
        )
        assertTrue(
            CourseRecoveryJournal.structuralError(revalidated(mixedState))!!
                .contains("different confirmation states")
        )
    }

    @Test
    fun `location digest cannot be confused by separator characters inside a place`() {
        val single = captured(
            listOf(course(id = 11)),
            setOf(11L),
            locations = mapOf(11L to mapOf(20_000L to "a;20001=b"))
        ).copy(groupId = "g", operationId = "op")
        val split = captured(
            listOf(course(id = 11)),
            setOf(11L),
            locations = mapOf(11L to mapOf(20_000L to "a", 20_001L to "b"))
        ).copy(groupId = "g", operationId = "op")

        assertNotEquals(
            CourseRecoveryJournal.payloadFingerprint(single),
            CourseRecoveryJournal.payloadFingerprint(split)
        )
        // Both payloads stay structurally valid, so the difference is the digest, not a rejection.
        assertNull(
            CourseRecoveryJournal.structuralError(
                single.copy(sourceFingerprint = CourseRecoveryJournal.payloadFingerprint(single))
            )
        )
        assertNull(
            CourseRecoveryJournal.structuralError(
                split.copy(sourceFingerprint = CourseRecoveryJournal.payloadFingerprint(split))
            )
        )
    }

    @Test
    fun `course list digest is audit only and changes with the list`() {
        val one = listOf(course(id = 11))
        val two = listOf(course(id = 11), course(id = 22))
        assertNotEquals(
            CourseRecoveryJournal.courseListFingerprint(one),
            CourseRecoveryJournal.courseListFingerprint(two)
        )
        assertEquals(
            CourseRecoveryJournal.courseListFingerprint(two),
            CourseRecoveryJournal.courseListFingerprint(listOf(course(id = 11), course(id = 22)))
        )
    }

    @Test
    fun `snapshot codec rejects illegal field values instead of defaulting them`() {
        val base = JSONObject(CourseSnapshotCodec.encode(course(id = 11)))
        fun mutated(block: JSONObject.() -> Unit): String =
            JSONObject(base.toString()).apply(block).toString()

        assertNull(CourseSnapshotCodec.decode(mutated { put("title", "  ") }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("startPeriod", 5); put("endPeriod", 4) }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("id", 0) }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("zone", "NOT_A_ZONE") }))
        assertNull(
            CourseSnapshotCodec.decode(
                mutated { put("effectiveFromEpochDay", 100); put("effectiveUntilEpochDay", 99) }
            )
        )
        assertNull(CourseSnapshotCodec.decode(mutated { remove("needsConfirmation") }))
        // Type coercion is rejected as well: a stringified number or boolean is not the same field.
        assertNull(CourseSnapshotCodec.decode(mutated { put("weekday", "3") }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("id", "11") }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("enabled", "true") }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("effectiveFromEpochDay", "20000") }))
        assertNull(CourseSnapshotCodec.decode(mutated { put("title", 123) }))
        // A truncated snapshot or one with trailing bytes must not be read as a valid prefix.
        assertNull(CourseSnapshotCodec.decode(base.toString().dropLast(1)))
        assertNull(CourseSnapshotCodec.decode("${base} trailing"))
        assertNotNull(CourseSnapshotCodec.decode(base.toString()))
    }
}
