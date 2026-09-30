package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/**
 * Strict JSON helpers. org.json is deliberately lenient - `getInt`/`getLong` accept numeric
 * strings, `getBoolean` accepts "true"/"false", `getString` stringifies numbers, `isNull` also
 * returns true for a missing key, and `JSONObject(String)` accepts trailing content after the
 * object. None of that is acceptable for a persisted recovery payload, so every field is read
 * through these helpers: the key must exist, the JSON type must match exactly, and the document
 * must not carry trailing bytes.
 */
private fun strictJsonObject(raw: String): JSONObject {
    require(raw.isNotBlank()) { "blank payload" }
    val tokener = JSONTokener(raw)
    val value = tokener.nextValue()
    require(value is JSONObject) { "payload is not a JSON object" }
    require(tokener.nextClean() == '\u0000') { "payload has trailing content" }
    return value
}

private fun JSONObject.requireArray(key: String): JSONArray {
    require(has(key)) { "missing field $key" }
    val value = opt(key)
    require(value is JSONArray) { "field $key is not an array" }
    return value
}

private fun JSONObject.strictString(key: String): String {
    require(has(key)) { "missing field $key" }
    val value = opt(key)
    require(value is String) { "field $key is not a string" }
    return value
}

private fun JSONObject.strictBoolean(key: String): Boolean {
    require(has(key)) { "missing field $key" }
    val value = opt(key)
    require(value is Boolean) { "field $key is not a boolean" }
    return value
}

/**
 * Exact integer decoding. `Number` alone is not enough: a Double/Float (1.9, 1.0), a numeric string
 * ("1") or an out-of-range value must all be rejected *before* any conversion, because
 * `Number.toInt()` would silently truncate 1.9 to 1 and 4294967297 to 1. Only JSON integers that
 * org.json already materialised as Int/Long/BigInteger are accepted, and BigInteger is range-checked
 * with its bit length so nothing is narrowed first.
 */
private fun exactLongOrNull(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is BigInteger -> if (value.bitLength() < 64) value.toLong() else null
    else -> null // Double, Float, BigDecimal, String, Boolean, null and containers are not integers
}

private fun JSONObject.strictLong(key: String): Long {
    require(has(key)) { "missing field $key" }
    return exactLongOrNull(opt(key)) ?: throw IllegalArgumentException("field $key is not an exact integer")
}

private fun JSONObject.strictInt(key: String): Int {
    val value = strictLong(key)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "field $key is out of Int range" }
    return value.toInt()
}

/** A nullable number still has to be present; JSON null is the only accepted null spelling. */
private fun JSONObject.nullableStrictLong(key: String): Long? {
    require(has(key)) { "missing field $key" }
    return if (isNull(key)) null else strictLong(key)
}

/** A nullable boolean still has to be present; JSON null is the only accepted null spelling. */
private fun JSONObject.nullableStrictBoolean(key: String): Boolean? {
    require(has(key)) { "missing field $key" }
    return if (isNull(key)) null else strictBoolean(key)
}

/**
 * Stage 7.5, batch 1: the pure course recovery model.
 *
 * Scope of this file: model, strict codec, structural validation and the pure capture / restore /
 * purge decisions. It is deliberately storage-free: [Course] lists, the current time and the
 * pending journal flag are caller parameters, and nothing here reads or writes SharedPreferences,
 * Room, alarms, notifications or UI state. The cross-storage coordinator, the preference fill-in
 * protocol and the persistence interfaces stay in a later, separately approved batch.
 *
 * Adopted batch rules (see the stage 7.5 proposal, section 0.5):
 * 1. a course group is its own type; `ordinary_items` member validation is never reused;
 * 2. structural validity is separate from restore eligibility: structure checks version, fields,
 *    ids, members, retention and snapshot self-consistency only, so ordinary course edits can
 *    always be saved even when a historical group no longer matches the live list;
 * 3. the lifecycle is independent of the current course content: `restored` is the terminal state
 *    of a finished operation and never falls back to `active`/conflict because courses were edited;
 * 4. id occupancy and interruption handling are separate: while a group is `active`, any member id
 *    that exists again is an occupancy conflict even when the content is identical - only the
 *    persisted `restoring` state proves that the core step already started;
 * 5. only `active` groups can be restored, and only while `now < expiresAt`; any occupied member,
 *    an incompatible parent title or a pending merge/split journal rejects the whole group;
 * 6. reminder switch semantics are untouched (`overrides[id] ?: enabled`), no new global gate;
 * 7. purge only removes group records - never courses and never preference keys;
 * 8. payload digests exclude themselves and use a fixed field order.
 */

internal enum class CourseRecoveryScope(val wire: String) {
    MEETING("meeting"),
    PARENT("parent"),
    BATCH("batch");

    companion object {
        fun fromWire(value: String): CourseRecoveryScope? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class CourseRecoveryState(val wire: String) {
    ACTIVE("active"),
    RESTORING("restoring"),
    RESTORED("restored"),
    PURGING("purging");

    companion object {
        fun fromWire(value: String): CourseRecoveryState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One removed meeting. The full [Course] is kept as a snapshot, plus the reminder override /
 * delivery watermark / dated temporary locations that belong to this meeting id.
 *
 * [reminderOverride] is deliberately nullable: `null` means "the key was absent at deletion time"
 * (follow the global switch), `false`/`true` mean an explicit stored override. That three-state
 * value must survive encode/decode unchanged.
 */
internal data class CourseRecoveryMember(
    val sourceOrder: Int,
    val id: Long,
    val courseId: Long,
    val courseJson: String,
    val reminderOverride: Boolean? = null,
    val deliveredAt: Long? = null,
    val temporaryLocations: Map<Long, String> = emptyMap()
)

internal data class CourseRecoveryGroup(
    val groupId: String,
    val scope: CourseRecoveryScope,
    val state: CourseRecoveryState,
    val operationId: String,
    val deletedAt: Long,
    val expiresAt: Long,
    val parentIds: List<Long>,
    val sourceFingerprint: String,
    val members: List<CourseRecoveryMember>,
    val restoredAt: Long? = null,
    val schemaVersion: Int = CourseRecoveryJournal.SCHEMA_VERSION,
    val kind: String = CourseRecoveryJournal.KIND
)

/** Strict result of decoding the persisted group payload; corruption is never an empty list. */
internal sealed interface CourseRecoveryLoad {
    data class Ready(val groups: List<CourseRecoveryGroup>) : CourseRecoveryLoad
    data class Invalid(val reason: String) : CourseRecoveryLoad
}

/** The delete request as the UI intended it; the caller also passes the real pre-commit records. */
internal data class CourseRecoveryRequest(
    val requestedIds: Set<Long>,
    val scope: CourseRecoveryScope,
    val operationId: String,
    val now: Long,
    val reminderOverrides: Map<Long, Boolean> = emptyMap(),
    val deliveredWatermarks: Map<Long, Long> = emptyMap(),
    val temporaryLocations: Map<Long, Map<Long, String>> = emptyMap()
)

internal sealed interface CourseRecoveryCapture {
    data class Captured(val group: CourseRecoveryGroup) : CourseRecoveryCapture
    data class Rejected(val reason: String) : CourseRecoveryCapture
}

/** Parent-level fields that must agree between the group snapshot and the live meetings. */
internal enum class CourseParentField(val wire: String) {
    TITLE("title"),
    NEEDS_CONFIRMATION("needsConfirmation")
}

/**
 * Which side of the projection rule a parent-id ownership conflict came from. The rule itself is
 * `CourseRoomProjection.toCourseParentEntities`: if a parent id is also a meeting id, that meeting
 * must belong to the same parent.
 */
internal enum class CourseParentOwnership(val wire: String) {
    /** A parent id of the group is already used as a meeting id by a live course of another parent. */
    PARENT_ID_USED_AS_MEETING("parentIdUsedAsMeeting"),

    /** A meeting id the group is about to restore is already used as a parent id by a live course. */
    MEETING_ID_USED_AS_PARENT("meetingIdUsedAsParent"),

    /** The candidate list disagrees with the projection rule for reasons outside this group. */
    LIVE_LIST_INCONSISTENT("liveListInconsistent")
}

internal sealed interface CourseRestoreDecision {
    data class Eligible(val coursesToAdd: List<Course>) : CourseRestoreDecision
    data object AlreadyRestored : CourseRestoreDecision
    data object ResumeRestoration : CourseRestoreDecision
    data object ResumePurge : CourseRestoreDecision
    data class Occupied(val meetingId: Long) : CourseRestoreDecision
    data class ParentConflict(
        val parentId: Long,
        val field: CourseParentField,
        val actual: String
    ) : CourseRestoreDecision
    data class ParentOwnershipConflict(
        val parentId: Long,
        val conflictingMeetingId: Long,
        val ownership: CourseParentOwnership
    ) : CourseRestoreDecision
    data object Expired : CourseRestoreDecision
    data object PendingCourseEditJournal : CourseRestoreDecision
    data class Invalid(val reason: String) : CourseRestoreDecision
}

/**
 * Which group records a purge may remove. The type carries group ids only: by construction a purge
 * can never select a live course, a notification override, a temporary location or a watermark.
 * Preference garbage collection is a separate design and is not implemented here.
 */
internal data class CourseRecoveryPurgePlan(val groupIds: Set<String>)

/**
 * Snapshot codec for a single [Course]. Field names and value semantics follow the live course
 * codec in `PrototypeStore.loadCourses` / `encodeCourses`; this codec only ever writes inside a
 * recovery group payload and never touches the production `courses` key. Unlike the tolerant live
 * decode it is strict: every field must be present, nullable dates must be explicit, and any
 * illegal value makes the snapshot unreadable instead of silently defaulting.
 */
internal object CourseSnapshotCodec {
    fun encode(course: Course): String = JSONObject().apply {
        put("title", course.title)
        put("weekday", course.weekday)
        put("startPeriod", course.startPeriod)
        put("endPeriod", course.endPeriod)
        put("building", course.building)
        put("zone", course.zone.name)
        put("needsConfirmation", course.needsConfirmation)
        put("enabled", course.enabled)
        put("effectiveFromEpochDay", course.effectiveFromEpochDay ?: JSONObject.NULL)
        put("effectiveUntilEpochDay", course.effectiveUntilEpochDay ?: JSONObject.NULL)
        put("id", course.id)
        put("courseId", course.courseId)
        put("externalSchoolYearCode", course.externalSchoolYearCode)
        put("externalTermCode", course.externalTermCode)
        put("externalSelectionKeyCandidate", course.externalSelectionKeyCandidate)
    }.toString()

    fun decode(raw: String): Course? = runCatching { decodeStrict(strictJsonObject(raw)) }.getOrNull()

    private fun decodeStrict(value: JSONObject): Course {
        listOf(
            "title", "weekday", "startPeriod", "endPeriod", "building", "zone",
            "needsConfirmation", "enabled", "effectiveFromEpochDay", "effectiveUntilEpochDay",
            "id", "courseId", "externalSchoolYearCode", "externalTermCode",
            "externalSelectionKeyCandidate"
        ).forEach { require(value.has(it)) { "missing snapshot field $it" } }

        val id = value.strictLong("id")
        require(id > 0L) { "non-positive meeting id" }
        val courseId = value.strictLong("courseId")
        require(courseId > 0L) { "non-positive parent id" }
        val title = value.strictString("title")
        require(title.isNotBlank()) { "blank title" }
        val weekday = value.strictInt("weekday")
        require(weekday in 1..7) { "invalid weekday" }
        val startPeriod = value.strictInt("startPeriod")
        val endPeriod = value.strictInt("endPeriod")
        require(startPeriod in 1..20 && endPeriod in startPeriod..20) { "invalid period range" }
        val building = value.strictString("building")
        require(building.isNotBlank()) { "blank building" }
        val zone = CampusZone.valueOf(value.strictString("zone"))
        val from = value.nullableStrictLong("effectiveFromEpochDay")
        val until = value.nullableStrictLong("effectiveUntilEpochDay")
        require(from == null || until == null || from <= until) { "inverted effective range" }

        return Course(
            title = title,
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            building = building,
            zone = zone,
            needsConfirmation = value.strictBoolean("needsConfirmation"),
            enabled = value.strictBoolean("enabled"),
            effectiveFromEpochDay = from,
            effectiveUntilEpochDay = until,
            id = id,
            courseId = courseId,
            externalSchoolYearCode = value.strictString("externalSchoolYearCode"),
            externalTermCode = value.strictString("externalTermCode"),
            externalSelectionKeyCandidate = value.strictString("externalSelectionKeyCandidate")
        )
    }
}

internal object CourseRecoveryJournal {
    const val SCHEMA_VERSION = 1
    const val KIND = "course_closure"
    const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    const val MAX_PLACE_LENGTH = 100

    /** `deletedAt + 30 x 24h`, or null when the deletion time would overflow. */
    fun expiresAtFor(deletedAt: Long): Long? =
        if (deletedAt <= 0L || deletedAt > Long.MAX_VALUE - RETENTION_MS) null else deletedAt + RETENTION_MS

    /**
     * Structural validation only (rule 2): version, kind, ids, member order, retention window,
     * snapshot self-consistency and the payload fingerprint. It never looks at the live course list,
     * so an ordinary course edit can never make a stored group structurally invalid.
     */
    fun structuralError(group: CourseRecoveryGroup): String? {
        if (group.schemaVersion != SCHEMA_VERSION) return "unsupported schema version"
        if (group.kind != KIND) return "unsupported group kind"
        if (group.groupId.isBlank()) return "blank group id"
        if (group.operationId.isBlank()) return "blank operation id"
        if (group.deletedAt <= 0L) return "non-positive deletion time"
        if (group.deletedAt > Long.MAX_VALUE - RETENTION_MS) return "deletion time overflows retention"
        if (group.expiresAt != group.deletedAt + RETENTION_MS) return "unexpected retention window"
        if (group.members.isEmpty()) return "empty member list"

        val ids = group.members.map(CourseRecoveryMember::id)
        if (ids.any { it <= 0L }) return "non-positive member id"
        if (ids.distinct().size != ids.size) return "duplicate member id"
        if (group.members.any { it.courseId <= 0L }) return "non-positive member parent id"
        val orders = group.members.map(CourseRecoveryMember::sourceOrder)
        if (orders.any { it < 0 }) return "negative source order"
        if (orders.zipWithNext().any { (previous, next) -> previous >= next }) {
            return "member order is not strictly increasing"
        }

        if (group.parentIds.isEmpty()) return "empty parent list"
        if (group.parentIds.any { it <= 0L }) return "non-positive parent id"
        if (group.parentIds.distinct().size != group.parentIds.size) return "duplicate parent id"
        if (group.parentIds != group.parentIds.sorted()) return "parent list is not sorted"
        if (group.members.map(CourseRecoveryMember::courseId).distinct().sorted() != group.parentIds) {
            return "parent list does not match members"
        }

        val decoded = group.members.map { member ->
            val course = CourseSnapshotCodec.decode(member.courseJson) ?: return "member snapshot is unreadable"
            if (course.id != member.id) return "member snapshot disagrees with the member id"
            if (course.courseId != member.courseId) return "member snapshot disagrees with the parent id"
            member.deliveredAt?.let { if (it <= 0L) return "non-positive delivery watermark" }
            if (member.temporaryLocations.keys.any { it < 0L }) return "negative temporary location day"
            if (member.temporaryLocations.values.any { it.isBlank() || it.length > MAX_PLACE_LENGTH }) {
                return "invalid temporary location"
            }
            course
        }

        // One parent is one row in the live projection: `CourseEntity` keeps the title and the
        // confirmation state for the whole parent, and `CourseRoomProjection` rejects a parent whose
        // meetings disagree. A snapshot that could not be projected back must be rejected up front.
        decoded.groupBy(Course::courseId).forEach { (parentId, meetings) ->
            if (meetings.map(Course::title).distinct().size > 1) {
                return "parent $parentId has meetings with different titles"
            }
            if (meetings.map(Course::needsConfirmation).distinct().size > 1) {
                return "parent $parentId has meetings with different confirmation states"
            }
        }

        // `toCourseParentEntities` also requires that a parent id, when it is also a meeting id,
        // belongs to that same parent ("parent id belongs to another course"). Meeting fields such as
        // periods, building or enabled are deliberately not compared across a parent.
        ownershipViolation(decoded)?.let { (parentId, anchorId) ->
            return "parent id $parentId belongs to another course (meeting $anchorId)"
        }

        if (group.state == CourseRecoveryState.RESTORED) {
            if (group.restoredAt == null || group.restoredAt <= 0L) return "restored group has no completion time"
        } else if (group.restoredAt != null) {
            return "unfinished group carries a completion time"
        }

        if (group.sourceFingerprint.isBlank()) return "blank payload fingerprint"
        if (group.sourceFingerprint != payloadFingerprint(group)) return "payload fingerprint disagrees"
        return null
    }

    /**
     * Deterministic digest of the payload with a fixed field order. The digest field itself is never
     * part of the hashed text (rule 8), so encode -> decode -> recompute always agrees.
     *
     * Every variable-length component is length-prefixed and every list/map is size-prefixed, so a
     * value that happens to contain a separator can never be confused with a separator between two
     * values (for example a place named `a;2=b` versus two dated places `a` and `b`).
     */
    fun payloadFingerprint(group: CourseRecoveryGroup): String {
        val canonical = buildString {
            append(group.schemaVersion).append('|')
            appendString(group.groupId); append('|')
            appendString(group.kind); append('|')
            appendString(group.scope.wire); append('|')
            appendString(group.state.wire); append('|')
            appendString(group.operationId); append('|')
            append(group.deletedAt).append('|')
            append(group.expiresAt).append('|')
            append(group.parentIds.size).append('|')
            group.parentIds.forEach { append(it).append(',') }
            append('|')
            append(group.restoredAt ?: -1L).append('|')
            append(group.members.size).append('|')
            group.members.forEach { member ->
                append(member.sourceOrder).append('|')
                append(member.id).append('|')
                append(member.courseId).append('|')
                appendString(member.courseJson); append('|')
                append(member.reminderOverride?.toString() ?: "-").append('|')
                append(member.deliveredAt?.toString() ?: "-").append('|')
                append(member.temporaryLocations.size).append('|')
                member.temporaryLocations.toSortedMap().forEach { (day, place) ->
                    append(day).append('|')
                    appendString(place)
                    append('|')
                }
            }
        }
        return sha256(canonical)
    }

    /** Length-prefixed so a separator inside the value cannot be read as a field boundary. */
    private fun StringBuilder.appendString(value: String) {
        append(value.length).append(':').append(value)
    }

    /**
     * Audit-only digest of a full course list. Rule 8: it may be recorded for evidence, but a restore
     * must never require the live list to be unchanged, so this value gates nothing.
     */
    fun courseListFingerprint(courses: List<Course>): String =
        sha256(courses.joinToString("\u0002") { CourseSnapshotCodec.encode(it) })

    /**
     * Builds the recovery group for one user delete (rule 8): the snapshot comes from the real
     * pre-commit records, and the request must match the actual diff exactly. A stale UI object, a
     * partially present request or a scope that no longer matches is rejected for a refresh instead
     * of quietly shrinking or expanding the set.
     */
    fun captureDeletion(
        before: List<Course>,
        after: List<Course>,
        request: CourseRecoveryRequest,
        existing: List<CourseRecoveryGroup>
    ): CourseRecoveryCapture {
        if (request.requestedIds.isEmpty()) return CourseRecoveryCapture.Rejected("empty course delete request")
        if (request.requestedIds.any { it <= 0L }) return CourseRecoveryCapture.Rejected("invalid course delete id")
        if (request.operationId.isBlank()) return CourseRecoveryCapture.Rejected("blank operation id")
        if (before.map(Course::id).distinct().size != before.size) {
            return CourseRecoveryCapture.Rejected("course list has duplicate ids")
        }
        if (request.now <= 0L) return CourseRecoveryCapture.Rejected("invalid deletion time")
        val expiresAt = expiresAtFor(request.now)
            ?: return CourseRecoveryCapture.Rejected("deletion time overflows the retention window")

        val beforeById = before.associateBy(Course::id)
        if (request.requestedIds.any { it !in beforeById }) {
            return CourseRecoveryCapture.Rejected("requested meeting is no longer present; refresh before deleting")
        }
        if (after != before.filterNot { it.id in request.requestedIds }) {
            return CourseRecoveryCapture.Rejected("course list changed during the delete; refresh before deleting")
        }

        when (request.scope) {
            CourseRecoveryScope.MEETING -> if (request.requestedIds.size != 1) {
                return CourseRecoveryCapture.Rejected("single-meeting delete needs exactly one meeting")
            }
            CourseRecoveryScope.PARENT -> {
                val parents = request.requestedIds.map { beforeById.getValue(it).courseId }.distinct()
                if (parents.size != 1) return CourseRecoveryCapture.Rejected("delete spans more than one parent")
                val parentId = parents.single()
                val allOfParent = before.filter { it.courseId == parentId }.mapTo(mutableSetOf(), Course::id)
                if (allOfParent != request.requestedIds) {
                    return CourseRecoveryCapture.Rejected("delete does not cover the whole parent; refresh")
                }
                if (after.any { it.courseId == parentId }) {
                    return CourseRecoveryCapture.Rejected("delete leaves meetings of the parent behind")
                }
            }
            CourseRecoveryScope.BATCH -> Unit
        }

        val blocking = existing.firstOrNull { group ->
            group.state != CourseRecoveryState.RESTORED && group.members.any { it.id in request.requestedIds }
        }
        if (blocking != null) {
            return CourseRecoveryCapture.Rejected("meeting already belongs to an unfinished recovery group")
        }

        request.requestedIds.forEach { id ->
            request.deliveredWatermarks[id]?.let {
                if (it <= 0L) return CourseRecoveryCapture.Rejected("corrupt delivery watermark")
            }
            val places = request.temporaryLocations[id].orEmpty()
            if (places.keys.any { it < 0L }) return CourseRecoveryCapture.Rejected("corrupt temporary location day")
            if (places.values.any { it.isBlank() || it.length > MAX_PLACE_LENGTH }) {
                return CourseRecoveryCapture.Rejected("corrupt temporary location")
            }
        }

        val members = before.withIndex()
            .filter { (_, course) -> course.id in request.requestedIds }
            .map { (index, course) ->
                CourseRecoveryMember(
                    sourceOrder = index,
                    id = course.id,
                    courseId = course.courseId,
                    courseJson = CourseSnapshotCodec.encode(course),
                    reminderOverride = request.reminderOverrides[course.id],
                    deliveredAt = request.deliveredWatermarks[course.id],
                    temporaryLocations = request.temporaryLocations[course.id].orEmpty().toSortedMap()
                )
            }
        if (members.isEmpty()) return CourseRecoveryCapture.Rejected("no meeting was actually removed")

        val draft = CourseRecoveryGroup(
            groupId = UUID.randomUUID().toString(),
            scope = request.scope,
            state = CourseRecoveryState.ACTIVE,
            operationId = request.operationId,
            deletedAt = request.now,
            expiresAt = expiresAt,
            parentIds = members.map(CourseRecoveryMember::courseId).distinct().sorted(),
            sourceFingerprint = "",
            members = members
        )
        val group = draft.copy(sourceFingerprint = payloadFingerprint(draft))
        structuralError(group)?.let {
            return CourseRecoveryCapture.Rejected("captured group is not self-consistent: $it")
        }
        return CourseRecoveryCapture.Captured(group)
    }

    /**
     * Pure restore eligibility (rules 3-5). `restoring`/`purging` groups are answered with their
     * resume state and are never treated as an ordinary expired `active` group.
     */
    fun restoreDecision(
        group: CourseRecoveryGroup,
        currentCourses: List<Course>,
        pendingCourseEditJournal: Boolean,
        now: Long
    ): CourseRestoreDecision {
        structuralError(group)?.let { return CourseRestoreDecision.Invalid(it) }
        when (group.state) {
            CourseRecoveryState.RESTORED -> return CourseRestoreDecision.AlreadyRestored
            CourseRecoveryState.RESTORING -> return CourseRestoreDecision.ResumeRestoration
            CourseRecoveryState.PURGING -> return CourseRestoreDecision.ResumePurge
            CourseRecoveryState.ACTIVE -> Unit
        }
        if (pendingCourseEditJournal) return CourseRestoreDecision.PendingCourseEditJournal
        if (now <= 0L) return CourseRestoreDecision.Invalid("invalid current time")
        if (now >= group.expiresAt) return CourseRestoreDecision.Expired

        val liveIds = currentCourses.mapTo(mutableSetOf(), Course::id)
        group.members.firstOrNull { it.id in liveIds }?.let {
            // Rule 4: an existing id is occupancy, even when the content happens to be identical.
            return CourseRestoreDecision.Occupied(it.id)
        }

        val memberIds = group.members.mapTo(mutableSetOf(), CourseRecoveryMember::id)
        val coursesToAdd = group.members.sortedBy(CourseRecoveryMember::sourceOrder)
            .map { CourseSnapshotCodec.decode(it.courseJson) }
        if (coursesToAdd.any { it == null }) return CourseRestoreDecision.Invalid("member snapshot is unreadable")
        val restored = coursesToAdd.filterNotNull()

        // Parent-id ownership is checked against the candidate merge list (live + restored), because
        // the conflict only exists once both sides are in the same list. Two directions:
        // a live meeting that already owns a parent id we are about to restore, and a live parent
        // that already owns a meeting id we are about to restore.
        ownershipViolation(currentCourses + restored)?.let { (parentId, anchorId) ->
            return CourseRestoreDecision.ParentOwnershipConflict(
                parentId,
                anchorId,
                ownershipDirection(group, memberIds, parentId)
            )
        }

        val liveUnderParents = currentCourses.filter { it.courseId in group.parentIds && it.id !in memberIds }
        group.parentIds.forEach { parentId ->
            // Structural validation already guarantees one title and one confirmation state per
            // parent inside the snapshot, so the live meetings must agree on both.
            val snapshot = group.members.filter { it.courseId == parentId }
                .mapNotNull { CourseSnapshotCodec.decode(it.courseJson) }
            val expectedTitle = snapshot.firstOrNull()?.title
                ?: return CourseRestoreDecision.Invalid("member snapshot is unreadable")
            val expectedConfirmation = snapshot.first().needsConfirmation
            liveUnderParents.firstOrNull { it.courseId == parentId && it.title != expectedTitle }?.let {
                return CourseRestoreDecision.ParentConflict(parentId, CourseParentField.TITLE, it.title)
            }
            liveUnderParents.firstOrNull {
                it.courseId == parentId && it.needsConfirmation != expectedConfirmation
            }?.let {
                return CourseRestoreDecision.ParentConflict(
                    parentId,
                    CourseParentField.NEEDS_CONFIRMATION,
                    it.needsConfirmation.toString()
                )
            }
        }

        return CourseRestoreDecision.Eligible(restored)
    }

    /**
     * The `toCourseParentEntities` ownership rule over a list of meetings: when a parent id is also a
     * meeting id in the same list, that meeting has to belong to the same parent. Returns
     * `parentId to anchorMeetingId`.
     */
    private fun ownershipViolation(courses: List<Course>): Pair<Long, Long>? {
        val byId = courses.associateBy(Course::id)
        courses.forEach { row ->
            val anchor = byId[row.courseId]
            if (anchor != null && anchor.courseId != row.courseId) return row.courseId to anchor.id
        }
        return null
    }

    private fun ownershipDirection(
        group: CourseRecoveryGroup,
        memberIds: Set<Long>,
        parentId: Long
    ): CourseParentOwnership = when {
        parentId in memberIds && parentId !in group.parentIds -> CourseParentOwnership.MEETING_ID_USED_AS_PARENT
        parentId in group.parentIds -> CourseParentOwnership.PARENT_ID_USED_AS_MEETING
        else -> CourseParentOwnership.LIVE_LIST_INCONSISTENT
    }

    /**
     * Purge selection (rule 7). Only group records are returned: an expired `active` group and a
     * `restored` record that reached its own retention. `restoring`/`purging` groups are excluded
     * because they still have a resume flow, and an unreadable record is never auto-removed.
     */
    fun purgePlan(groups: List<CourseRecoveryGroup>, now: Long): CourseRecoveryPurgePlan {
        val ids = groups.filter { group ->
            structuralError(group) == null &&
                (group.state == CourseRecoveryState.ACTIVE || group.state == CourseRecoveryState.RESTORED) &&
                now >= group.expiresAt
        }.mapTo(mutableSetOf(), CourseRecoveryGroup::groupId)
        return CourseRecoveryPurgePlan(ids)
    }

    /** Legal lifecycle transitions; the fingerprint is recomputed so the payload stays readable. */
    fun transition(
        group: CourseRecoveryGroup,
        next: CourseRecoveryState,
        at: Long? = null
    ): CourseRecoveryGroup? {
        if (structuralError(group) != null) return null
        val allowed = when (group.state) {
            CourseRecoveryState.ACTIVE -> next == CourseRecoveryState.RESTORING || next == CourseRecoveryState.PURGING
            CourseRecoveryState.RESTORING -> next == CourseRecoveryState.RESTORED
            CourseRecoveryState.RESTORED, CourseRecoveryState.PURGING -> false
        }
        if (!allowed) return null
        val restoredAt = if (next == CourseRecoveryState.RESTORED) {
            if (at == null || at <= 0L) return null
            at
        } else {
            null
        }
        val draft = group.copy(state = next, restoredAt = restoredAt, sourceFingerprint = "")
        return draft.copy(sourceFingerprint = payloadFingerprint(draft))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Strict group payload codec. `null` (a missing key) is legitimately empty; anything else that
 * cannot be read exactly - blank payload, invalid JSON, unknown version, unknown scope/state,
 * missing field, duplicate id, structural violation - is [CourseRecoveryLoad.Invalid] and is never
 * downgraded to an empty list or a partial group list.
 */
internal object CourseRecoveryCodec {
    fun encode(groups: List<CourseRecoveryGroup>): String {
        groups.forEach { group ->
            require(CourseRecoveryJournal.structuralError(group) == null) { "unreadable course recovery group" }
        }
        require(groups.map(CourseRecoveryGroup::groupId).distinct().size == groups.size) {
            "duplicate course recovery group id"
        }
        val array = JSONArray()
        groups.forEach { array.put(encodeGroup(it)) }
        return JSONObject().put("version", CourseRecoveryJournal.SCHEMA_VERSION).put("groups", array).toString()
    }

    fun decode(raw: String?): CourseRecoveryLoad {
        if (raw == null) return CourseRecoveryLoad.Ready(emptyList())
        if (raw.isBlank()) return CourseRecoveryLoad.Invalid("blank course recovery payload")
        // Strict parse: a truncated document or one with trailing bytes is rejected, not partially read.
        val root = runCatching { strictJsonObject(raw) }
            .getOrElse { return CourseRecoveryLoad.Invalid("course recovery payload is not a valid JSON object") }
        val version = runCatching { root.strictInt("version") }
            .getOrElse { return CourseRecoveryLoad.Invalid("missing or invalid payload version") }
        if (version != CourseRecoveryJournal.SCHEMA_VERSION) {
            return CourseRecoveryLoad.Invalid("unsupported payload version $version")
        }
        val array = runCatching { root.requireArray("groups") }
            .getOrElse { return CourseRecoveryLoad.Invalid("missing groups array") }
        val groups = mutableListOf<CourseRecoveryGroup>()
        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index) ?: return CourseRecoveryLoad.Invalid("group $index is not an object")
            // The concrete reason is kept: without it a tampered payload would only ever report the
            // generic fingerprint mismatch, and a strictness regression could hide behind it.
            val group = try {
                decodeGroup(value)
            } catch (error: Exception) {
                return CourseRecoveryLoad.Invalid(
                    "group $index is unreadable: ${error.message ?: "invalid payload"}"
                )
            }
            CourseRecoveryJournal.structuralError(group)?.let {
                return CourseRecoveryLoad.Invalid("group $index: $it")
            }
            groups += group
        }
        if (groups.map(CourseRecoveryGroup::groupId).distinct().size != groups.size) {
            return CourseRecoveryLoad.Invalid("duplicate group id")
        }
        return CourseRecoveryLoad.Ready(groups)
    }

    private fun encodeGroup(group: CourseRecoveryGroup): JSONObject = JSONObject().apply {
        put("schemaVersion", group.schemaVersion)
        put("groupId", group.groupId)
        put("kind", group.kind)
        put("scope", group.scope.wire)
        put("state", group.state.wire)
        put("operationId", group.operationId)
        put("deletedAt", group.deletedAt)
        put("expiresAt", group.expiresAt)
        put("parentIds", JSONArray().also { array -> group.parentIds.forEach { array.put(it) } })
        put("sourceFingerprint", group.sourceFingerprint)
        put("restoredAt", group.restoredAt ?: JSONObject.NULL)
        put("members", JSONArray().also { array ->
            group.members.forEach { member ->
                array.put(JSONObject().apply {
                    put("sourceOrder", member.sourceOrder)
                    put("id", member.id)
                    put("courseId", member.courseId)
                    put("courseJson", member.courseJson)
                    put("reminderOverride", member.reminderOverride ?: JSONObject.NULL)
                    put("deliveredAt", member.deliveredAt ?: JSONObject.NULL)
                    put("temporaryLocations", JSONObject().also { locations ->
                        member.temporaryLocations.toSortedMap().forEach { (day, place) ->
                            locations.put(day.toString(), place)
                        }
                    })
                })
            }
        })
    }

    private fun decodeGroup(value: JSONObject): CourseRecoveryGroup {
        val scope = CourseRecoveryScope.fromWire(value.strictString("scope")) ?: error("unknown scope")
        val state = CourseRecoveryState.fromWire(value.strictString("state")) ?: error("unknown state")
        val membersArray = value.requireArray("members")
        val members = (0 until membersArray.length()).map { index ->
            val member = membersArray.opt(index)
            require(member is JSONObject) { "member $index is not an object" }
            require(member.has("temporaryLocations")) { "missing field temporaryLocations" }
            val locationObject = member.opt("temporaryLocations")
            require(locationObject is JSONObject) { "temporary locations are not an object" }
            val places = LinkedHashMap<Long, String>()
            locationObject.keys().forEach { key ->
                // A day key must be the canonical decimal spelling of its value: "01", "+1", "-0"
                // and friends are aliases, and two aliases for the same day must never be merged
                // into one entry (which `associate` would do silently, keeping the last one).
                val day = key.toLongOrNull() ?: error("location day is not a decimal integer")
                require(day.toString() == key) { "location day $key is not canonical" }
                require(places.put(day, locationObject.strictString(key)) == null) {
                    "duplicate location day $day"
                }
            }
            CourseRecoveryMember(
                sourceOrder = member.strictInt("sourceOrder"),
                id = member.strictLong("id"),
                courseId = member.strictLong("courseId"),
                courseJson = member.strictString("courseJson"),
                reminderOverride = member.nullableStrictBoolean("reminderOverride"),
                deliveredAt = member.nullableStrictLong("deliveredAt"),
                temporaryLocations = places
            )
        }
        val parentsArray = value.requireArray("parentIds")
        val parents = (0 until parentsArray.length()).map { index ->
            exactLongOrNull(parentsArray.opt(index)) ?: error("parent id is not an exact integer")
        }
        return CourseRecoveryGroup(
            groupId = value.strictString("groupId"),
            scope = scope,
            state = state,
            operationId = value.strictString("operationId"),
            deletedAt = value.strictLong("deletedAt"),
            expiresAt = value.strictLong("expiresAt"),
            parentIds = parents,
            sourceFingerprint = value.strictString("sourceFingerprint"),
            members = members,
            restoredAt = value.nullableStrictLong("restoredAt"),
            schemaVersion = value.strictInt("schemaVersion"),
            kind = value.strictString("kind")
        )
    }
}
