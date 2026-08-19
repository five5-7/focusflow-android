package com.sakata.focusflow

import kotlin.math.roundToInt

data class ActivityTimeSuggestion(
    val minutes: Int,
    val reason: String,
    val sampleCount: Int = 0,
    val cappedByCommitment: Boolean = false
)

object ActivityTimeAdvisor {
    fun suggest(
        category: String,
        name: String,
        history: List<ActivitySession>,
        nextCommitment: ActivityCommitment?,
        energyLevel: String,
        plannedMinutes: Int? = null,
        now: Long = System.currentTimeMillis()
    ): ActivityTimeSuggestion {
        val completed = history.filter {
            it.status == ActivitySession.STATUS_COMPLETED &&
                it.actualEndAt != null &&
                it.actualEndAt > it.actualStartAt
        }
        val exact = completed.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val sameCategory = completed.filter { it.category == category }
        val samples = when {
            exact.size >= 2 -> exact
            sameCategory.size >= 2 -> sameCategory
            exact.isNotEmpty() -> exact
            sameCategory.isNotEmpty() -> sameCategory
            else -> emptyList()
        }.take(8)
        val sampleMinutes = samples.mapNotNull { session ->
            session.actualEndAt?.let { end -> ((end - session.actualStartAt) / 60_000L).toInt().takeIf { it in 5..600 } }
        }
        val (baseMinutes, sourceReason) = when {
            sampleMinutes.isNotEmpty() -> {
                val reason = when {
                    exact.size >= 2 -> "参考最近 ${sampleMinutes.size} 次同名活动的实际用时中位数"
                    sameCategory.size >= 2 -> "参考最近 ${sampleMinutes.size} 次同类活动的实际用时中位数"
                    else -> "目前只有 1 次相近记录，先作为保守参考"
                }
                median(sampleMinutes) to reason
            }
            plannedMinutes != null -> plannedMinutes to "采用这项任务已填写的预计用时"
            else -> defaultMinutes(category) to "还没有足够历史，采用${category}的保守起始值"
        }
        var minutes = baseMinutes.coerceIn(5, 600)

        var reason = sourceReason
        if (energyLevel == "偏低" && plannedMinutes == null) {
            val reduced = roundToFive((minutes * 0.75).roundToInt()).coerceAtLeast(5)
            if (reduced < minutes) {
                minutes = reduced
                reason += "；当前精力偏低，先缩短到容易开始的版本"
            }
        }

        val available = nextCommitment?.let { ((it.startsAt - now) / 60_000L).toInt() - 15 }
        val capped = available != null && available < minutes
        if (capped && available <= 0) {
            minutes = 5
            reason += "；距离 ${nextCommitment?.title.orEmpty()} 已不足 15 分钟，建议先准备下一项或只做 5 分钟短版"
        } else if (capped) {
            minutes = roundDownToFive(available.coerceAtLeast(5))
            reason += "；为 ${nextCommitment?.title.orEmpty()} 保留 15 分钟缓冲"
        }
        return ActivityTimeSuggestion(
            minutes = roundToFive(minutes).coerceIn(5, 600),
            reason = reason,
            sampleCount = sampleMinutes.size,
            cappedByCommitment = capped
        )
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun defaultMinutes(category: String): Int = when (category) {
        "学习" -> 45
        "休息" -> 20
        "游戏／娱乐" -> 45
        else -> 30
    }

    private fun roundToFive(value: Int): Int = ((value + 2) / 5) * 5
    private fun roundDownToFive(value: Int): Int = (value / 5) * 5
}
