package com.sakata.focusflow

/**
 * FocusFlowApp 的任务/调度动作纯变换。
 *
 * 每个动作只有数据变换：保存、事件记录、提醒调度、完成率学习等副作用
 * 由调用点（FocusFlowApp 胶水）执行。纯 Kotlin，无 Android 依赖，全部可单测；
 * 语义与搬家前的 FocusFlowApp 调用点逐字一致（含 extra 字符串）。
 */
object TaskActions {

    /** 一次任务动作的数据结果：更新后的任务列表 + 需要追加的一条历史事件（可能为空）。 */
    data class Result(
        val items: List<Item>,
        val event: TaskEvent? = null
    )

    /** 改期保存的结果：更新后的列表、改期后的任务副本、历史事件与基线事件载荷。 */
    data class DelayedPlan(
        val items: List<Item>,
        val delayedItem: Item,
        val event: TaskEvent,
        val baselinePayload: String
    )

    /** 完成任务（今日页/日程页直接完成）：置完成标记与「完成」事件。 */
    fun completeNow(items: List<Item>, item: Item, now: Long = System.currentTimeMillis()): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = now) else it },
            event = TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = "完成")
        )

    /** 目标卡片选择完成档位：置完成标记与档位事件（档位后的完成率学习/目标计数由调用点执行）。 */
    fun completeWithLevel(items: List<Item>, item: Item, level: String, now: Long = System.currentTimeMillis()): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(done = true, completionLevel = level, completedAt = now) else it },
            event = TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = level)
        )

    /** 缩为 15 分钟短版并放回收集箱；标题去掉「重新安排：」前缀。 */
    fun shrinkToInbox(items: List<Item>, item: Item): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "收集箱", detail = "短版：先做 15 分钟；准备好后再安排", recoverySourceScheduledAt = item.recoverySourceScheduledAt ?: item.scheduledAt, scheduledAt = null, dayOnly = false, durationMinutes = 15, windowStartAt = null, windowEndAt = null) else it },
            event = TaskRecorder.event(TaskEventType.TASK_TO_INBOX, item.id, item.title.removePrefix("重新安排："), extra = "缩为 15 分钟")
        )

    /** 放回收集箱：清掉时间与范围，保留原调度日记忆（三处共用：回收卡 / 快速改期建议 / 时间轴弹窗）。 */
    fun returnToInbox(items: List<Item>, item: Item): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(kind = "收集箱", recoverySourceScheduledAt = item.recoverySourceScheduledAt ?: item.scheduledAt, scheduledAt = null, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = "已放回收集箱；准备好后再安排") else it },
            event = TaskRecorder.event(TaskEventType.TASK_TO_INBOX, item.id, item.title.removePrefix("重新安排："))
        )

    /** 放弃任务：删除任务并记「放弃」事件。 */
    fun abandon(items: List<Item>, item: Item): Result =
        Result(
            items = items.filterNot { it.id == item.id },
            event = TaskRecorder.event(TaskEventType.TASK_DELETED, item.id, item.title, extra = "放弃")
        )

    /** 删除任务：删除任务并记删除事件（无 extra）。 */
    fun deleteItem(items: List<Item>, item: Item): Result =
        Result(
            items = items.filterNot { it.id == item.id },
            event = TaskRecorder.event(TaskEventType.TASK_DELETED, item.id, item.title)
        )

    /** 暂停任务：只保留暂停标记，无事件。 */
    fun pause(items: List<Item>, item: Item): Result =
        Result(items = items.map { if (it.id == item.id) it.copy(kind = "暂停", detail = "已暂停；随时可在计划中恢复") else it })

    /** 恢复任务：恢复为任务并记恢复事件（标题去掉「重新安排：」前缀）。 */
    fun resume(items: List<Item>, item: Item): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(kind = "任务", detail = "已恢复；今天有空时再做", scheduledAt = null) else it },
            event = TaskRecorder.event(TaskEventType.TASK_RESTORED, item.id, item.title.removePrefix("重新安排："))
        )

    /** 归入已有目标：按目标附加字段并记归入事件（scheduledAt 恒为 0：归入不产生日程计划）。 */
    fun attachToGoal(items: List<Item>, item: Item, goal: Goal): Result =
        Result(
            items = items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "任务", detail = "属于目标：${goal.title} · 尚未安排具体时间", goalId = goal.id, scheduledAt = null, dayOnly = false, windowStartAt = null, windowEndAt = null) else it },
            event = TaskRecorder.event(TaskEventType.TASK_ATTACHED_TO_PLAN, item.id, item.title.removePrefix("重新安排："), scheduledAt = 0, extra = goal.title)
        )

    /** 转为目标：移除原任务并记转换事件（extra=目标标题；scheduledAt 恒为 0）。 */
    fun convertToGoal(items: List<Item>, item: Item, goalTitle: String): Result =
        Result(
            items = items.filterNot { it.id == item.id },
            event = TaskRecorder.event(TaskEventType.TASK_CONVERTED, item.id, item.title, extra = goalTitle)
        )

    /** 改期保存的纯计算：改期任务副本、列表更新、历史事件与基线事件载荷。 */
    fun planDelayed(
        items: List<Item>,
        item: Item,
        scheduledAt: Long,
        duration: Int,
        @Suppress("UNUSED_PARAMETER") label: String,
        priority: String,
        now: Long = System.currentTimeMillis()
    ): DelayedPlan {
        val delayed = item.copy(
            // 空闲活动改期后仍须保留活动身份，才能继续使用对应的开始/收尾提醒链。
            kind = if (item.kind == "活动" || item.kind == "游戏") item.kind else "任务",
            detail = TaskScheduleText.rescheduledDetail(scheduledAt, duration),
            scheduledAt = scheduledAt,
            durationMinutes = duration,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
            priority = priority,
            rescheduleCount = item.rescheduleCount + 1,
            lastRescheduledAt = now
        )
        return DelayedPlan(
            items = items.map { if (it.id == item.id) delayed else it },
            delayedItem = delayed,
            event = TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, item.id, item.title.removePrefix("重新安排："), scheduledAt = scheduledAt, extra = formatDateTime(scheduledAt)),
            baselinePayload = "${item.title.removePrefix("重新安排：")} → ${formatDateTime(scheduledAt)}"
        )
    }

    /** 「安排到具体时刻」的任务外形（快速记录直接安排与收集箱改期共用）。 */
    fun scheduledShape(item: Item, startsAt: Long, duration: Int, @Suppress("UNUSED_PARAMETER") label: String, priority: String): Item =
        item.copy(
            title = item.title.removePrefix("重新安排："),
            kind = "任务",
            detail = TaskScheduleText.scheduledDetail(startsAt, duration),
            scheduledAt = startsAt,
            durationMinutes = duration,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
            priority = priority
        )

    /** 「保留弹性范围」的任务外形。 */
    fun flexibleShape(item: Item, start: Long, end: Long, duration: Int, @Suppress("UNUSED_PARAMETER") label: String, priority: String): Item =
        item.copy(
            title = item.title.removePrefix("重新安排："),
            kind = "任务",
            detail = TaskScheduleText.flexibleDetail(start, end, duration),
            scheduledAt = null,
            durationMinutes = duration,
            dayOnly = false,
            windowStartAt = start,
            windowEndAt = end,
            priority = priority
        )
}
