package com.sakata.focusflow

/**
 * “安排空闲活动”的类别与提醒策略唯一来源。
 *
 * storedValue 必须与既有 JSON 和界面值保持一致；别名只用于读取旧数据，不会改写用户数据。
 */
enum class ScheduledActivityKind(
    val storedValue: String,
    val startLabel: String,
    val detection: ForegroundDetection?
) {
    GAME("游戏", "游戏", ForegroundDetection.GAME),
    VIDEO("视频", "视频", ForegroundDetection.VIDEO),
    STUDY("学习", "学习", null),
    REST("休息", "休息", null),
    EXERCISE("运动", "运动", null),
    CUSTOM("自定义", "活动", null);

    companion object {
        val selectableValues: List<String> = entries.map { it.storedValue }

        fun fromStored(value: String?): ScheduledActivityKind = when (value?.trim()) {
            "游戏", "娱乐", "游戏／娱乐", "游戏/娱乐" -> GAME
            "视频" -> VIDEO
            "学习" -> STUDY
            "休息" -> REST
            "运动" -> EXERCISE
            else -> CUSTOM
        }
    }
}

enum class ForegroundDetection { GAME, VIDEO }

data class ScheduledActivityStartCopy(val title: String, val body: String)

object ScheduledActivityPolicy {
    /** 通知送达不等于用户已经开始，因此这里绝不自动写入状态签到。 */
    fun startCopy(session: GameSessionRecord): ScheduledActivityStartCopy {
        val kind = ScheduledActivityKind.fromStored(session.category)
        return ScheduledActivityStartCopy(
            title = "活动开始 · ${session.title}",
            body = "${kind.startLabel}安排时间到了；如果现在开始，到点会提醒你收尾。"
        )
    }

    fun detection(category: String?): ForegroundDetection? =
        ScheduledActivityKind.fromStored(category).detection

    /** 旧版广播没有时间字段（-1），为覆盖升级保留；新广播必须与当前计划完全一致。 */
    fun matchesCurrentPlan(broadcastPlannedAt: Long, currentPlannedAt: Long): Boolean =
        broadcastPlannedAt <= 0L || broadcastPlannedAt == currentPlannedAt
}

/** 空闲活动会话的纯数据变换；界面层只负责保存和撤销系统闹钟。 */
object ScheduledActivitySessions {
    fun remove(sessions: List<GameSessionRecord>, itemId: Long): List<GameSessionRecord> =
        sessions.filterNot { it.id == itemId }

    fun reschedule(
        sessions: List<GameSessionRecord>,
        itemId: Long,
        plannedStartAt: Long,
        durationMinutes: Int
    ): List<GameSessionRecord> = sessions.map { session ->
        if (session.id == itemId && session.isOpen()) session.copy(
            plannedStartAt = plannedStartAt,
            plannedEndAt = plannedStartAt + durationMinutes * 60_000L
        ) else session
    }
}
