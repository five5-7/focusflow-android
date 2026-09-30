package com.sakata.focusflow

import java.time.ZoneId

/**
 * 阶段 7.4 · 重复规则删除的纯捕获与纯操作计划。
 *
 * 本文件只做**纯计算**：不读写存储、不排闹钟、不更新 UI、不追加事件到仓库。
 * 它产出「一笔拟提交的数据」，交给下一批的持久化层一次性写入。
 *
 * 关键约定：
 * - 成员集合**精确等于**真实 `RepeatActions.stop` 命中的实例，只用同一谓词推导，
 *   不从事件、标题、`detail` 或同名历史反推。
 * - 成员的 `postState` 一律取自**真实删除输出**，不靠手工构造。
 * - `deletedAt` 取墓碑的真实删除时间；`expiresAt` 由 `expiresAtFor` 产生。
 * - `groupId`/`operationId` 由调用方注入（测试可固定），创建后**保持稳定**，
 *   后续规划与编码不得重新生成。
 * - 不凭「标题相同」或「内容差不多」认定同一次操作：重复 `operationId` 只能靠 ID 匹配。
 */

sealed interface RepeatClosureCapture {
    data class Ok(val record: RepeatClosureRecord) : RepeatClosureCapture
    data class Rejected(val reason: String) : RepeatClosureCapture
}

sealed interface DeletePlanResult {
    /** 一笔拟提交数据：最终条目 + 待保存组记录 + 真实业务事件。 */
    data class Ok(
        val itemsToWrite: List<Item>,
        val groupToWrite: RepeatClosureRecord,
        val events: List<TaskEvent>,
    ) : DeletePlanResult

    data class Rejected(val reason: String) : DeletePlanResult
}

sealed interface RecoveryPlanResult {
    data class Ok(
        val itemsToWrite: List<Item>,
        val groupToWrite: RepeatClosureRecord,
        val events: List<TaskEvent>,
        val restoration: RepeatClosureRestoration,
    ) : RecoveryPlanResult

    data class Rejected(val reason: String) : RecoveryPlanResult
}

object RepeatClosureCaptureRequest {

    /**
     * 从「删除前快照」与真实 `deleteRule` 输出建立完整恢复组。
     *
     * @param currentItems 删除前的当前快照；模板必须真实存在于其中。
     * @param template 请求删除的模板（来自当前快照）。
     * @param deleted 真实 `RepeatActions.deleteRule` 的输出。
     * @param groupId／operationId 由调用方注入，创建后保持稳定。
     */
    internal fun capture(
        currentItems: List<Item>,
        template: Item,
        deleted: RepeatResult,
        groupId: String,
        operationId: String,
    ): RepeatClosureCapture {
        if (groupId.isBlank()) return RepeatClosureCapture.Rejected("groupId must not be blank")
        if (operationId.isBlank()) return RepeatClosureCapture.Rejected("operationId must not be blank")
        if (hasDuplicateItemIds(currentItems)) return RepeatClosureCapture.Rejected("current snapshot has duplicate item ids")
        // 外部删除输出自身必须身份唯一，否则无法证明它对应一次真实删除。
        if (hasDuplicateItemIds(deleted.items)) return RepeatClosureCapture.Rejected("deletion output has duplicate item ids")

        // 模板必须是当前快照里的那一个对象，不能挑同名对象代替。
        val liveTemplate = currentItems.firstOrNull { it.id == template.id }
            ?: return RepeatClosureCapture.Rejected("template ${template.id} is missing from the current snapshot")
        if (liveTemplate != template) return RepeatClosureCapture.Rejected("template request is stale")

        val suppliedTombstone = deleted.items.firstOrNull { it.id == template.id }
            ?: return RepeatClosureCapture.Rejected("deletion did not produce a tombstone for ${template.id}")
        if (suppliedTombstone.kind != "回收站") return RepeatClosureCapture.Rejected("deletion output is not a tombstone")
        if (suppliedTombstone.trashedAt == null) return RepeatClosureCapture.Rejected("tombstone has no deletion time")

        // 不信任外部输出：用真实 deleteRule 在同样输入上重跑一遍，删除时刻只取自
        // **真实墓碑**，然后核对完整 items 输出——成员、无关项、顺序、字段全部一致。
        // 只忽略随机生成的事件 ID。
        val authoritative = RepeatActions.deleteRule(currentItems, liveTemplate, at = suppliedTombstone.trashedAt)
        if (authoritative.events.isEmpty()) return RepeatClosureCapture.Rejected("deletion did not actually happen")
        if (authoritative.items.size != deleted.items.size) {
            return RepeatClosureCapture.Rejected("deletion output does not match a real deleteRule result")
        }
        authoritative.items.forEachIndexed { index, item ->
            if (item != deleted.items[index]) {
                return RepeatClosureCapture.Rejected("deletion output item $index disagrees with a real deleteRule result")
            }
        }
        // 核对通过后，删除时刻以真实重跑结果为准（伪造的墓碑走不到这里）。
        val realTombstone = authoritative.items.first { it.id == template.id }
        val deletedAt = realTombstone.trashedAt
            ?: return RepeatClosureCapture.Rejected("real deletion produced no deletion time")

        // 精确等于 stop 命中的集合：同一谓词，且这些实例必须真的在重跑输出里被改动。
        val affectedPre = currentItems.filter {
            it.repeatTemplateId == template.id && it.kind == "任务" && !it.done
        }
        val affectedIds = affectedPre.mapTo(mutableSetOf()) { it.id }
        val instances = affectedPre.map { pre ->
            val post = authoritative.items.firstOrNull { it.id == pre.id }
                ?: return RepeatClosureCapture.Rejected("instance ${pre.id} is missing from the deletion output")
            if (post == pre) return RepeatClosureCapture.Rejected("instance ${pre.id} was not changed by the deletion")
            RecoveryInstanceMember(
                itemId = pre.id,
                preState = pre,
                postState = post,
                // 处理原因取自真实删除输出，而不是重新推断。
                handlingReason = post.detail,
            )
        }
        if (instances.map { it.itemId }.toSet() != affectedIds) {
            return RepeatClosureCapture.Rejected("captured instance set does not match the deletion")
        }

        val expiry = RepeatClosureRecovery.expiresAtFor(deletedAt)
        val expiresAt = (expiry as? RecoveryExpiry.Ok)?.expiresAt
            ?: return RepeatClosureCapture.Rejected("deletion time cannot produce a legal expiry")

        val record = RepeatClosureRecord(
            groupId = groupId,
            operationId = operationId,
            template = RecoveryTemplateMember(template.id, realTombstone, template),
            instances = instances,
            deletedAt = deletedAt,
            expiresAt = expiresAt,
            status = RecoveryGroupStatus.ACTIVE,
            restoration = null,
        )
        // 复用统一的记录校验：不在这里另写一套结构判定。
        RepeatClosureCodec.recordRejection(record)?.let {
            return RepeatClosureCapture.Rejected("captured group is invalid: $it")
        }
        return RepeatClosureCapture.Ok(record)
    }
}

object RepeatClosureDeletePlan {

    /**
     * 纯删除操作计划：用真实 `deleteRule` 删除，捕获恢复组，再按既有业务链组合 `refresh`。
     *
     * @param existingGroups 必要的既有组记录，用于识别重复 `operationId`（不读写存储）。
     */
    fun plan(
        currentItems: List<Item>,
        template: Item,
        groupId: String,
        operationId: String,
        now: Long = System.currentTimeMillis(),
        courses: List<Course> = emptyList(),
        existingGroups: List<RepeatClosureRecord> = emptyList(),
    ): DeletePlanResult {
        // 重复请求只按 operationId 识别，绝不按标题或内容相似度。
        existingGroups.firstOrNull { it.operationId == operationId }?.let {
            return DeletePlanResult.Rejected("operation $operationId was already captured as group ${it.groupId}")
        }
        if (existingGroups.any { it.groupId == groupId }) {
            return DeletePlanResult.Rejected("groupId $groupId already exists")
        }
        if (currentItems.none { it.id == template.id && it == template }) {
            return DeletePlanResult.Rejected("template request is stale or missing from the current snapshot")
        }

        val deleted = RepeatActions.deleteRule(currentItems, template, at = now)
        if (deleted.events.isEmpty()) return DeletePlanResult.Rejected("deletion produced no events")

        val captured = when (val result = RepeatClosureCaptureRequest.capture(
            currentItems = currentItems,
            template = template,
            deleted = deleted,
            groupId = groupId,
            operationId = operationId,
        )) {
            is RepeatClosureCapture.Rejected -> return DeletePlanResult.Rejected(result.reason)
            is RepeatClosureCapture.Ok -> result.record
        }

        // 既有业务链：删除结果再过一次 refresh，与真实保存路径一致。
        val refreshed = RepeatActions.refresh(deleted.items, at = now, courses = courses)
        // refresh 可能为**其它存活模板**生成实例，那是正常变化；但它不得改写或移除闭包成员。
        // 成员的 postState 一律保留真实删除留下的状态，绝不被 refresh 的结果替换。
        captured.instances.forEach { member ->
            val afterRefresh = refreshed.items.firstOrNull { it.id == member.itemId }
                ?: return DeletePlanResult.Rejected("refresh removed closure member ${member.itemId}")
            if (afterRefresh != member.postState) {
                return DeletePlanResult.Rejected("refresh changed closure member ${member.itemId}")
            }
        }
        RepeatClosureCodec.recordRejection(captured)?.let { return DeletePlanResult.Rejected(it) }
        return DeletePlanResult.Ok(
            itemsToWrite = refreshed.items,
            groupToWrite = captured,
            events = deleted.events + refreshed.events,
        )
    }
}

object RepeatClosureRecoveryPlan {

    /**
     * 纯恢复与 `refresh` 的组合计划：复用既有 [RepeatClosureRecovery.recover]，不另写资格判定。
     *
     * 失败路径不返回任何可应用的部分修改；已 `RESTORED` 的组保持终态。
     */
    fun plan(
        record: RepeatClosureRecord,
        currentItems: List<Item>,
        now: Long,
        zoneId: ZoneId,
        courses: List<Course> = emptyList(),
        existingGroups: List<RepeatClosureRecord> = emptyList(),
    ): RecoveryPlanResult {
        // 计算前先校验记录与 now；无效输入不进入任何资格判定。
        if (now <= 0L) return RecoveryPlanResult.Rejected("now must be positive")
        if (zoneId != ZoneId.systemDefault()) {
            return RecoveryPlanResult.Rejected("zoneId must match the system zone used by refresh")
        }
        RepeatClosureCodec.recordRejection(record)?.let { return RecoveryPlanResult.Rejected(it) }

        // 组终态幂等：不再恢复、不再 refresh、不追加事件。
        if (record.status == RecoveryGroupStatus.RESTORED) {
            return RecoveryPlanResult.Rejected("group ${record.groupId} is already RESTORED")
        }
        existingGroups.firstOrNull { it.groupId == record.groupId && it.status == RecoveryGroupStatus.RESTORED }?.let {
            return RecoveryPlanResult.Rejected("group ${record.groupId} was already restored")
        }

        val closure = record.asClosure()
        val outcome = RepeatClosureRecovery.recover(closure, currentItems, now, zoneId, courses)
        if (outcome.status != RecoveryGroupStatus.RESTORED || outcome.restoredTemplate == null) {
            // 失败：不返回任何可应用的部分修改。
            val reason = outcome.rejection?.name ?: outcome.templateStatus.name
            return RecoveryPlanResult.Rejected("recovery rejected: $reason")
        }

        // 组装恢复后的条目视图：还原模板与被恢复的实例，其它项与跳过项保持原样。
        val restoredTemplate = outcome.restoredTemplate
        val restoredById = outcome.instanceOutcomes
            .mapNotNull { entry -> entry.restoredItem?.let { it.id to it } }
            .toMap()
        val restoredItems = currentItems.map { item ->
            when {
                item.id == record.template.templateId -> restoredTemplate
                restoredById.containsKey(item.id) -> restoredById.getValue(item.id)
                else -> item
            }
        }

        // 复用真实 refresh：按模板与发生日去重、不补回过去实例、暂停模板不生成。
        val refreshed = RepeatActions.refresh(restoredItems, at = now, courses = courses)

        val restoration = RepeatClosureRestoration(
            restoredAt = now,
            instances = outcome.instanceOutcomes.map { entry ->
                RepeatClosureRestoredInstance(
                    itemId = entry.itemId,
                    status = when (entry.status) {
                        RecoveryInstanceStatus.RESTORED -> RepeatClosureInstanceStatus.RESTORED
                        RecoveryInstanceStatus.SKIPPED_PAST -> RepeatClosureInstanceStatus.SKIPPED_PAST
                        RecoveryInstanceStatus.SKIPPED_COURSE -> RepeatClosureInstanceStatus.SKIPPED_COURSE
                    },
                )
            },
        )
        val groupToWrite = record.copy(status = RecoveryGroupStatus.RESTORED, restoration = restoration)
        // 每个成功返回的记录都必须能合法往返，且往返后字段一致；
        // 这里做的是断言而不是「先编解码一次再交出去」，不会掩盖数据变化。
        RepeatClosureCodec.assertRoundTrips(groupToWrite)?.let {
            return RecoveryPlanResult.Rejected("restored group does not round trip: $it")
        }
        return RecoveryPlanResult.Ok(
            itemsToWrite = refreshed.items,
            groupToWrite = groupToWrite,
            // 只为 refresh 真实生成的事件返回；skipped 实例不新增说明性事件。
            events = refreshed.events,
            restoration = restoration,
        )
    }
}
