package com.sakata.focusflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PlanGapsSection(
    profile: CommuteProfile,
    gaps: List<CourseGap>,
    planningCourses: List<Course>,
    confirmedCourseCount: Int,
    goals: List<Goal>,
    items: List<Item>,
    checkIns: List<StatusCheckIn>,
    store: PrototypeStore,
    tableExpanded: Boolean,
    onTableExpandedChange: (Boolean) -> Unit,
    onScheduleGoal: (Goal, GoalSuggestion) -> Unit,
    onScheduleFlexible: (Item, Int, Int) -> Unit
) {
    ChargingGapNotice(profile, gaps)
    val occupied = occupiedByWeekday(items)
    val freeWindows = CourseGapPlanner.freeWindows(planningCourses, occupied = occupied)
    val recommendations = gapRecommendations(gaps, freeWindows, goals, items, store)
    if (recommendations.isNotEmpty()) {
        GapRecommendations(
            recommendations,
            checkIns,
            onScheduleGoal,
            onScheduleFlexible
        )
        HorizontalDivider()
    }
    GapTableToggle(tableExpanded) { onTableExpandedChange(!tableExpanded) }
    if (tableExpanded) GapTimelineContent(planningCourses, profile)
    CourseGaps(gaps, confirmedCourseCount)
    FreeWindows(freeWindows)
}

@Composable
private fun ChargingGapNotice(profile: CommuteProfile, gaps: List<CourseGap>) {
    if (profile.eBikeBattery != "偏低") return
    val chargeable = gaps.filter { it.minutesFree >= 60 }
        .sortedByDescending { it.minutesFree }
        .take(3)
    if (chargeable.isEmpty()) {
        Text(
            "电动车电量偏低，但本周暂无 ≥60 分钟的充电空档，可考虑在周末或晚上充电。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("电动车电量偏低", fontWeight = FontWeight.SemiBold)
            Text(
                "建议在长空档充电。本周可用充电空档：" +
                    chargeable.joinToString("；") {
                        "${weekdayName(it.from.weekday)} 第${it.from.endPeriod}–" +
                            "${it.to.startPeriod}节间（约 ${it.minutesFree} 分钟）"
                    },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun gapRecommendations(
    gaps: List<CourseGap>,
    freeWindows: List<FreeWindow>,
    goals: List<Goal>,
    items: List<Item>,
    store: PrototypeStore
): List<GapPlan> = buildList {
    val seen = mutableSetOf<Pair<Int, String>>()
    gaps.filter { it.minutesFree >= 30 }.forEach { gap ->
        recommendForWindow(
            goals,
            items,
            gap.minutesFree,
            store,
            gap.from.weekday,
            gap.suggestedStartMinute
        )?.let { recommendation ->
            if (seen.add(gap.from.weekday to recommendation.title)) {
                add(GapPlan(recommendation, gap.from.weekday, gap.suggestedStartMinute, gap.minutesFree))
            }
        }
    }
    freeWindows.filter { it.minutes >= 30 }.forEach { window ->
        recommendForWindow(
            goals,
            items,
            window.minutes,
            store,
            window.weekday,
            window.startMinute
        )?.let { recommendation ->
            if (seen.add(window.weekday to recommendation.title)) {
                add(GapPlan(recommendation, window.weekday, window.startMinute, window.minutes))
            }
        }
    }
}

@Composable
private fun GapRecommendations(
    plans: List<GapPlan>,
    checkIns: List<StatusCheckIn>,
    onScheduleGoal: (Goal, GoalSuggestion) -> Unit,
    onScheduleFlexible: (Item, Int, Int) -> Unit
) {
    Text("空挡适合做什么（内容建议）", fontWeight = FontWeight.SemiBold)
    plans.forEach { plan ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${weekdayName(plan.weekday)} ${GoalPlanner.displayTime(plan.startMinute)} · " +
                        "可用 ${plan.minutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("适合：${plan.recommendation.title}", fontWeight = FontWeight.SemiBold)
                val energyNote = when (CheckInInsights.slotEnergyFor(plan.startMinute, checkIns)) {
                    "偏低" -> " · 该时段你通常精力偏低，建议优先短任务或最低版本"
                    "充足" -> " · 该时段你通常精力充足，适合需要专注的任务"
                    else -> ""
                }
                val location = plan.recommendation.goal?.let {
                    locationHintFor("${it.title} ${it.desiredOutcome}")
                } ?: locationHintFor(plan.recommendation.title)
                Text(
                    plan.recommendation.reason + energyNote +
                        (location?.let { " · 建议地点：$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    plan.recommendation.goal?.let { goal ->
                        Button(
                            onClick = {
                                onScheduleGoal(
                                    goal,
                                    GoalSuggestion(plan.weekday, plan.startMinute, plan.minutes)
                                )
                            }
                        ) { Text("排入") }
                    }
                    plan.recommendation.flexibleItem?.let { item ->
                        Button(
                            onClick = { onScheduleFlexible(item, plan.weekday, plan.startMinute) }
                        ) { Text("排入") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapTableToggle(expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("空挡课表视图", fontWeight = FontWeight.SemiBold)
            Text(
                if (expanded) "收起 ▴" else "展开 ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CourseGaps(gaps: List<CourseGap>, confirmedCourseCount: Int) {
    if (gaps.isEmpty()) {
        Text(
            if (confirmedCourseCount == 0) "先确认课程后再计算空挡。"
            else "目前没有可显示的同日课程间空挡。"
        )
        return
    }
    val usable = gaps.filter { it.minutesFree >= 10 }
    val fragments = gaps.filter { it.minutesFree < 10 }
    if (usable.isEmpty()) {
        Text("没有可安排的空档。", style = MaterialTheme.typography.bodySmall)
    } else {
        usable.forEach { GapCard(it) }
    }
    if (fragments.isNotEmpty()) {
        Text("碎片时间（不足 10 分钟，仅够通行与缓冲）", fontWeight = FontWeight.SemiBold)
        fragments.forEach { gap ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    "${weekdayName(gap.from.weekday)} " +
                        "${formatMinute(CourseGapPlanner.periodStart(gap.from.endPeriod) + 45)}–" +
                        "${formatMinute(CourseGapPlanner.periodStart(gap.to.startPeriod))}：" +
                        "${gap.from.title} → ${gap.to.title} · 仅 ${gap.minutesFree} 分钟",
                    Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun GapCard(gap: CourseGap) {
    val fromEnd = CourseGapPlanner.periodStart(gap.from.endPeriod) + 45
    val toStart = CourseGapPlanner.periodStart(gap.to.startPeriod)
    ElevatedCard {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "${weekdayName(gap.from.weekday)} ${formatMinute(fromEnd)}–${formatMinute(toStart)}：" +
                    "${gap.from.title} → ${gap.to.title}",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "总 ${toStart - fromEnd} 分钟 · 路程约 ${gap.travelMinutes} 分钟 · " +
                    "净可用 ${gap.minutesFree} 分钟"
            )
            Text(
                if (gap.minutesFree >= 15) {
                    "可用约 ${gap.minutesFree} 分钟，可用于弹性安排。"
                } else {
                    "仅约 ${gap.minutesFree} 分钟，接近下限，暂不建议安排任务。"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FreeWindows(windows: List<FreeWindow>) {
    if (windows.isEmpty()) return
    Text("自由时段（非课间空挡，也可安排）", fontWeight = FontWeight.SemiBold)
    windows.forEach { window ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Text(
                "${weekdayName(window.weekday)} ${formatMinute(window.startMinute)}–" +
                    "${formatMinute(window.endMinute)} · ${window.kind} · 净 ${window.minutes} 分钟",
                Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
