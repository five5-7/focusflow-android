package com.sakata.focusflow

/**
 * 阶段 7.4 · 模板墓碑快照的**无损**自洽检查。
 *
 * 为什么不直接用 `ItemsCodec.decode` 比较：它会补默认值、归一化重复 ID、清理关联、
 * 迁移旧字段，用来做自洽检查会把「被改过的快照」洗成「合法的样子」。
 *
 * 这里改为比较 **JSON 内容**（忽略对象键顺序）：
 * - 墓碑里的 `trashSnapshot` 必须能解出**恰好一条**、`id` 与模板一致、且不是墓碑自身的记录；
 * - 该记录的无损内容必须等于 `ItemsCodec.encode(preState)` 的内容，**或**等于
 *   `ItemsCodec.encode(preState.preservingNote())` 的内容——后者是真实 `RepeatActions.stop`
 *   把 `userNote == null` 物化成 `editableNote()` 的合法结果。
 *
 * 不修改 `ItemsCodec` 的普通存档语义，也不放松身份校验。
 */
object RepeatClosureSnapshots {

    sealed interface Check {
        data object Consistent : Check
        data object Unreadable : Check
        data object Inconsistent : Check
    }

    fun checkTemplateSnapshot(template: RecoveryTemplateMember): Check {
        val raw = template.postState.trashSnapshot ?: return Check.Unreadable
        val parsed = when (val result = StrictJson.parse(raw)) {
            is StrictJson.ParseResult.Invalid -> return Check.Unreadable
            is StrictJson.ParseResult.Ok -> result.value
        }
        val element = (parsed as? StrictJson.Value.Arr)?.items?.singleOrNull() ?: return Check.Unreadable
        val snapshotId = ((element as? StrictJson.Value.Obj)?.entries?.firstOrNull { it.first == "id" }?.second
            as? StrictJson.Value.Num)?.value ?: return Check.Unreadable
        if (snapshotId != template.templateId) return Check.Inconsistent
        val snapshotTrashedAt = (element.entries.firstOrNull { it.first == "trashedAt" }?.second as? StrictJson.Value.Num)?.value
        if (snapshotTrashedAt != null && snapshotTrashedAt > 0L) return Check.Inconsistent
        val snapshotKind = (element.entries.firstOrNull { it.first == "kind" }?.second as? StrictJson.Value.Str)?.value
        if (snapshotKind == "回收站") return Check.Inconsistent

        // 无损内容比较：只经由 encode（不改语义），不经过 decode（会归一化）。
        return when {
            sameContent(element, encodeToJson(template.preState)) -> Check.Consistent
            sameContent(element, encodeToJson(template.preState.preservingNote())) -> Check.Consistent
            else -> Check.Inconsistent
        }
    }

    private fun encodeToJson(item: Item): StrictJson.Value = when (val parsed = StrictJson.parse(ItemsCodec.encode(listOf(item)))) {
        is StrictJson.ParseResult.Ok -> (parsed.value as StrictJson.Value.Arr).items.single()
        is StrictJson.ParseResult.Invalid -> StrictJson.Value.Null
    }

    /** 忽略对象键顺序的内容相等；数组顺序仍然有意义。 */
    private fun sameContent(left: StrictJson.Value, right: StrictJson.Value): Boolean = when {
        left is StrictJson.Value.Obj && right is StrictJson.Value.Obj -> {
            val leftKeys = left.entries.map { it.first }.toSet()
            val rightKeys = right.entries.map { it.first }.toSet()
            leftKeys == rightKeys && leftKeys.all { key ->
                sameContent(
                    left.entries.first { it.first == key }.second,
                    right.entries.first { it.first == key }.second,
                )
            }
        }
        left is StrictJson.Value.Arr && right is StrictJson.Value.Arr ->
            left.items.size == right.items.size && left.items.indices.all { sameContent(left.items[it], right.items[it]) }
        else -> left == right
    }
}
