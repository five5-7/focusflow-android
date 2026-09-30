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
                val tombstone = byId[member.itemId]
                require(tombstone?.kind == "回收站" && tombstone.trashedAt == group.deletedAt &&
                    tombstone.trashSnapshot == member.snapshot) { "active trash member changed" }
            }
        } }
    }
}

/** Strict decoding: invalid persisted data must block writes, never be treated as an empty journal. */
internal object TrashJournalCodec {
    fun encode(groups: List<TrashGroupRecord>): String = JSONArray().also { array ->
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
