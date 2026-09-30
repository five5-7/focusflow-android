package com.sakata.focusflow.data

import com.sakata.focusflow.Item
import com.sakata.focusflow.ItemsCodec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only newly executed, fully snapshotted deletions may enter this journal. */
data class TrashMember(val itemId: Long, val sourceOrder: Int, val snapshot: String)

data class TrashGroupRecord(
    val groupId: String,
    val kind: String,
    val deletedAt: Long,
    val expiresAt: Long,
    val members: List<TrashMember>,
    val restoredIds: Set<Long> = emptySet()
) {
    val state: String get() = when (restoredIds.size) {
        0 -> "active"
        members.size -> "restored"
        else -> "partial"
    }
}

/** Schema only; operations are not written until their complete inverse payload exists. */
data class OperationRecord(
    val operationId: String,
    val kind: String,
    val recordedAt: Long,
    val state: String,
    val payload: String
)

internal object TrashJournal {
    private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private val ordinaryKinds = setOf("任务", "收集箱", "暂停")

    /** Called under the core writer's lock / transaction, after comparing the original task list. */
    fun update(before: List<Item>, after: List<Item>, existing: List<TrashGroupRecord>): List<TrashGroupRecord> {
        val beforeById = before.associateBy(Item::id)
        val afterById = after.associateBy(Item::id)
        val deleted = mutableListOf<Pair<Int, Item>>()
        val restored = mutableListOf<Long>()
        for ((index, original) in before.withIndex()) {
            val next = afterById[original.id] ?: continue
            if (original.kind in ordinaryKinds && original.trashedAt == null && next.kind == "回收站") {
                require(next.trashedAt != null && next.trashedAt > 0L &&
                    next.trashSnapshot == ItemsCodec.encode(listOf(original))) { "incomplete trash snapshot" }
                deleted += index to original
            } else if (original.kind == "回收站" && original.trashedAt != null &&
                next.kind in ordinaryKinds && next.trashedAt == null) {
                require(original.trashSnapshot == ItemsCodec.encode(listOf(next))) { "restored item differs from snapshot" }
                restored += original.id
            }
        }
        if (deleted.isEmpty() && restored.isEmpty()) {
            verifyActive(after, existing)
            return existing
        }
        val updated = existing.toMutableList()
        for (id in restored) {
            val matching = updated.withIndex().filter { (_, group) ->
                group.state != "restored" && group.members.any { it.itemId == id && it.itemId !in group.restoredIds }
            }
            require(matching.size <= 1) { "ambiguous trash group" }
            if (matching.isNotEmpty()) {
                val (position, group) = matching.single()
                val member = group.members.single { it.itemId == id }
                require(member.snapshot == beforeById.getValue(id).trashSnapshot) { "trash group and tombstone disagree" }
                updated[position] = group.copy(restoredIds = group.restoredIds + id)
            } // Pre-stage-7 tombstones legitimately have no group.
        }
        if (deleted.isNotEmpty()) {
            val times = deleted.map { (_, item) -> afterById.getValue(item.id).trashedAt }.toSet()
            require(times.size == 1) { "one deletion group needs one timestamp" }
            val at = requireNotNull(times.single())
            require(at <= Long.MAX_VALUE - RETENTION_MS) { "trash expiry overflows" }
            require(deleted.none { (_, item) -> updated.any { group ->
                group.state != "restored" && group.members.any { it.itemId == item.id }
            } }) { "item already has an active trash group" }
            updated += TrashGroupRecord(
                groupId = UUID.randomUUID().toString(), kind = "ordinary_items",
                deletedAt = at, expiresAt = at + RETENTION_MS,
                members = deleted.map { (index, item) ->
                    TrashMember(item.id, index, ItemsCodec.encode(listOf(item)))
                }
            )
        }
        verifyActive(after, updated)
        return updated.sortedWith(compareBy(TrashGroupRecord::deletedAt, TrashGroupRecord::groupId))
    }

    fun verifyActive(items: List<Item>, groups: List<TrashGroupRecord>) {
        val byId = items.associateBy(Item::id)
        val activeIds = mutableSetOf<Long>()
        groups.forEach { group -> group.members.forEach { member ->
            if (member.itemId !in group.restoredIds) {
                require(activeIds.add(member.itemId)) { "duplicate active trash member" }
                require(matchesTombstone(byId[member.itemId], group.deletedAt, member.snapshot)) {
                    "active trash member changed"
                }
            }
        } }
    }

    /**
     * Ids the user may permanently remove: active members whose tombstone is still intact, plus
     * tombstones that carry no group at all and whose snapshot is still readable. A restored member
     * is never included because its id now points at a live item; a repeat-generated occurrence is
     * excluded because its deletion contract belongs to stage 7.4 (`RepeatActions.refresh` would
     * regenerate the instance). Restored history is judged per group, never journal-wide, so an id
     * that was restored once and trashed again stays purgeable.
     */
    fun purgeableIds(items: List<Item>, groups: List<TrashGroupRecord>): Set<Long> {
        val byId = items.associateBy(Item::id)
        val groupedIds = mutableSetOf<Long>()
        val purgeable = mutableSetOf<Long>()
        groups.forEach { group -> group.members.forEach { member ->
            groupedIds += member.itemId
            if (member.itemId in group.restoredIds) return@forEach
            val tombstone = byId[member.itemId] ?: return@forEach
            if (!matchesTombstone(tombstone, group.deletedAt, member.snapshot)) return@forEach
            if (repeatGenerated(tombstone)) return@forEach
            purgeable += member.itemId
        } }
        items.forEach { item ->
            if (item.id in groupedIds || item.kind != "回收站") return@forEach
            if (item.trashedAt == null || repeatGenerated(item)) return@forEach
            // Any readable tombstone without a group is manually purgeable, including a deleted
            // repeat-rule template. `refresh` only regenerates instances from a LIVE template, so
            // removing a trashed template cannot resurrect anything.
            if (decodeSnapshot(item) == null) return@forEach
            purgeable += item.id
        }
        return purgeable
    }

    /**
     * Group members whose retention has elapsed at [now]. Boundary is frozen by the stage 7.3 data
     * contract: `now >= expiresAt` expires, `now == expiresAt - 1` does not. Tombstones without a
     * group are absent on purpose: they have no trustworthy `deletedAt` and must never be inferred.
     */
    fun expiredIds(items: List<Item>, groups: List<TrashGroupRecord>, now: Long): Set<Long> {
        val purgeable = purgeableIds(items, groups)
        return groups.filter { now >= it.expiresAt }
            // Retention belongs to the group that holds the id as an ACTIVE member. A historical
            // `restored` group can still list the same id, and its older expiry must never sweep an
            // item that was deleted again into a newer group.
            .flatMap { group ->
                group.members.filter { it.itemId in purgeable && it.itemId !in group.restoredIds }
                    .map { it.itemId }
            }
            .toSet()
    }

    /**
     * Applies an explicit permanent removal. Ids already absent from [before] are ignored so that a
     * retry, a restart or an interleaved expiry sweep stays idempotent; every id that is present
     * must be a matching tombstone, otherwise the whole call fails closed and nothing is written.
     */
    fun purge(
        before: List<Item>,
        after: List<Item>,
        existing: List<TrashGroupRecord>,
        purged: Set<Long>
    ): List<TrashGroupRecord> {
        if (purged.isEmpty()) return existing
        val beforeById = before.associateBy(Item::id)
        val effective = purged.filterTo(mutableSetOf()) { beforeById.containsKey(it) }
        if (effective.isEmpty()) {
            verifyActive(after, existing)
            return existing
        }
        val afterIds = after.mapTo(mutableSetOf(), Item::id)
        effective.forEach { id ->
            val tombstone = beforeById.getValue(id)
            // A live item can never be purged: a restored member is an ordinary item, not a tombstone.
            require(tombstone.kind == "回收站" && (tombstone.trashedAt ?: 0L) > 0L) {
                "only a trashed tombstone can be purged"
            }
            require(!repeatGenerated(tombstone)) { "repeat-generated tombstones belong to stage 7.4" }
            require(decodeSnapshot(tombstone) != null) { "purged tombstone snapshot is unreadable" }
            require(id !in afterIds) { "purged tombstone is still present" }
        }
        val updated = existing.mapNotNull { group ->
            // Only ACTIVE members take part in a purge. A restored member is live and must be left
            // untouched even when the same id was trashed again into a different group.
            val removed = group.members.filter { it.itemId in effective && it.itemId !in group.restoredIds }
                .mapTo(mutableSetOf()) { it.itemId }
            if (removed.isEmpty()) return@mapNotNull group
            group.members.filter { it.itemId in removed }.forEach { member ->
                require(matchesTombstone(beforeById[member.itemId], group.deletedAt, member.snapshot)) {
                    "purged member does not match its tombstone"
                }
            }
            // An empty member list is rejected by the codec, so a fully drained group disappears.
            group.members.filterNot { it.itemId in removed }
                .takeIf { it.isNotEmpty() }
                ?.let { group.copy(members = it, restoredIds = group.restoredIds - removed) }
        }
        verifyActive(after, updated)
        return updated.sortedWith(compareBy(TrashGroupRecord::deletedAt, TrashGroupRecord::groupId))
    }

    private fun matchesTombstone(tombstone: Item?, deletedAt: Long, snapshot: String): Boolean =
        tombstone?.kind == "回收站" && tombstone.trashedAt == deletedAt && tombstone.trashSnapshot == snapshot

    /** Strict: an unreadable snapshot is never treated as a valid original. */
    private fun decodeSnapshot(tombstone: Item): Item? = tombstone.trashSnapshot
        ?.let { raw -> runCatching { ItemsCodec.decode(raw).items.singleOrNull() }.getOrNull() }
        ?.takeIf { it.id == tombstone.id && it.trashedAt == null }

    private fun repeatGenerated(tombstone: Item): Boolean =
        decodeSnapshot(tombstone)?.repeatTemplateId != null
}

/** Strict decoding: invalid persisted data must block writes, never be treated as an empty journal. */
internal object TrashJournalCodec {
    fun encode(groups: List<TrashGroupRecord>): String {
        // `decode` rejects a restored id that has no member, so writing one would brick the next
        // read. Guard the same invariant on the way out instead of trusting every caller.
        groups.forEach { group ->
            require(group.restoredIds.all { id -> group.members.any { it.itemId == id } }) {
                "restored ids must reference existing members"
            }
        }
        return JSONArray().also { array ->
            groups.forEach { group -> array.put(JSONObject().apply {
                put("groupId", group.groupId); put("kind", group.kind)
                put("deletedAt", group.deletedAt); put("expiresAt", group.expiresAt)
                put("members", JSONArray().also { members -> group.members.forEach { member ->
                    members.put(JSONObject().apply {
                        put("itemId", member.itemId); put("sourceOrder", member.sourceOrder)
                        put("snapshot", member.snapshot)
                    })
                } })
                put("restoredIds", JSONArray().also { ids -> group.restoredIds.sorted().forEach { ids.put(it) } })
            }) }
        }.toString()
    }

    fun decode(raw: String?): List<TrashGroupRecord> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        val groups = (0 until array.length()).map { i ->
            val value = array.getJSONObject(i)
            val membersArray = value.getJSONArray("members")
            val members = (0 until membersArray.length()).map { j ->
                val member = membersArray.getJSONObject(j)
                TrashMember(member.getLong("itemId"), member.getInt("sourceOrder"), member.getString("snapshot"))
            }
            val restoredArray = value.getJSONArray("restoredIds")
            val restored = (0 until restoredArray.length()).map { restoredArray.getLong(it) }.toSet()
            TrashGroupRecord(value.getString("groupId"), value.getString("kind"),
                value.getLong("deletedAt"), value.getLong("expiresAt"), members, restored)
        }
        require(groups.map { it.groupId }.toSet().size == groups.size) { "duplicate trash group ID" }
        groups.forEach { group ->
            require(group.groupId.isNotBlank() && group.kind == "ordinary_items" &&
                group.deletedAt > 0 && group.expiresAt > group.deletedAt && group.members.isNotEmpty())
            val ids = group.members.map { it.itemId }
            require(ids.all { it > 0 } && ids.distinct().size == ids.size && group.restoredIds.all { it in ids })
            group.members.forEach { member ->
                require(member.sourceOrder >= 0 && ItemsCodec.decode(member.snapshot).items.singleOrNull()
                    ?.let { it.id == member.itemId && it.trashedAt == null && it.kind in setOf("任务", "收集箱", "暂停") } == true)
            }
        }
        return groups.sortedWith(compareBy(TrashGroupRecord::deletedAt, TrashGroupRecord::groupId))
    }
}
