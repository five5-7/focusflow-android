package com.sakata.focusflow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate

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
    val availability = availabilitySlots(gaps, freeWindows)
    var selectedView by remember { mutableStateOf(if (tableExpanded) "空挡图" else "建议") }
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("查看空挡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "建议" to recommendations.size,
                    "课间" to gaps.count { it.minutesFree >= 10 },
                    "自由时段" to freeWindows.size,
                    "空挡图" to availability.size
                ).forEach { (label, count) ->
                    FilterChip(
                        selected = selectedView == label,
                        onClick = {
                            selectedView = label
                            onTableExpandedChange(label == "空挡图")
                        },
                        label = { Text("$label $count") }
                    )
                }
            }
        }
    }
    when (selectedView) {
        "建议" -> if (recommendations.isEmpty()) {
            Text(
                gapSuggestionEmptyMessage(planningCourses, availability, goals, items),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else GapRecommendations(recommendations, checkIns, onScheduleGoal, onScheduleFlexible)
        "课间" -> CourseGaps(gaps, confirmedCourseCount)
        "自由时段" -> if (freeWindows.isEmpty()) Text("当前没有不少于 60 分钟的自由时段。", style = MaterialTheme.typography.bodySmall) else FreeWindows(freeWindows)
        else -> AvailabilityTimeline(availability)
    }
}

internal data class AvailabilitySlot(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val minutes: Int,
    val kind: String
)

internal fun availabilitySlots(gaps: List<CourseGap>, freeWindows: List<FreeWindow>): List<AvailabilitySlot> =
    (gaps.filter { it.minutesFree >= 10 }.map {
        AvailabilitySlot(it.from.weekday, it.suggestedStartMinute, it.suggestedStartMinute + it.minutesFree, it.minutesFree, "课间")
    } + freeWindows.map {
        AvailabilitySlot(it.weekday, it.startMinute, it.endMinute, it.minutes, it.kind)
    }).sortedWith(compareBy<AvailabilitySlot> { it.weekday }.thenBy { it.startMinute })

internal fun gapSuggestionEmptyMessage(
    planningCourses: List<Course>,
    availability: List<AvailabilitySlot>,
    goals: List<Goal>,
    items: List<Item>
): String {
    if (availability.isEmpty()) return if (planningCourses.isEmpty()) {
        "本周没有生效课程或可用时段数据，先检查校园生活、课程生效期和已有安排。"
    } else {
        "课程、已有安排和通勤时间扣除后，目前没有可安排的空挡。"
    }
    val unfinishedGoals = goals.filter { GoalPlanner.completedThisWeek(it) < it.weeklyTarget }
    val flexibleItems = items.filter { it.kind == "任务" && it.scheduledAt == null }
    if (unfinishedGoals.isEmpty() && flexibleItems.isEmpty()) {
        return "有 ${availability.size} 个可用时段，但没有待完成目标或未定时任务；可先查看课间、自由时段或新增目标。"
    }
    val longest = availability.maxOf { it.minutes }
    val shortestCandidate = (unfinishedGoals.map { it.durationMinutes } + flexibleItems.map { it.durationMinutes }).minOrNull()
    if (shortestCandidate != null && shortestCandidate > longest) {
        return "有 ${availability.size} 个可用时段，但现有内容最短需 $shortestCandidate 分钟，超过最长空挡 $longest 分钟。"
    }
    return "有 ${availability.size} 个可用时段，但当前筛选条件下没有生成内容建议；可到课间或自由时段查看具体时间。"
}

@Composable
private fun AvailabilityTimeline(slots: List<AvailabilitySlot>) {
    if (slots.isEmpty()) {
        Text("当前没有可绘制的净可用时段。", style = MaterialTheme.typography.bodySmall)
        return
    }
    var selectedDay by remember { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    // Expand the shared scale for supplied early/late intervals instead of clipping them.
    val start = minOf(8 * 60, slots.minOf { it.startMinute } / 60 * 60)
    val end = maxOf(22 * 60, (slots.maxOf { it.endMinute } + 59) / 60 * 60)
    val chartHeight = 336.dp
    val colors = MaterialTheme.colorScheme
    val daily = slots.filter { it.weekday == selectedDay }
    FocusCard(containerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("一周空挡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AvailabilityLegend("课间", true)
                AvailabilityLegend("自由时段", false)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(40.dp).height(chartHeight + 48.dp)) {
                    (start..end step 60).forEach { minute ->
                        if ((minute - start) % 120 == 0 || minute == end) {
                            Text(
                                formatMinute(minute),
                                Modifier.offset(y = 40.dp + chartHeight * ((minute - start).toFloat() / (end - start))),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
                (1..7).forEach { day ->
                    val selected = selectedDay == day
                    Column(Modifier.weight(1f)) {
                        Surface(
                            onClick = { selectedDay = day },
                            color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
                            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = "${weekdayName(day)}，查看空挡详情" }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1], style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(chartHeight).clickable { selectedDay = day }) {
                            (start..end step 60).forEach { minute ->
                                HorizontalDivider(
                                    Modifier.offset(y = chartHeight * ((minute - start).toFloat() / (end - start))),
                                    color = colors.outlineVariant
                                )
                            }
                            slots.filter { it.weekday == day && it.endMinute > it.startMinute }.forEach { slot ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                        .offset(y = chartHeight * ((slot.startMinute - start).toFloat() / (end - start)))
                                        .height(chartHeight * ((slot.endMinute - slot.startMinute).toFloat() / (end - start))),
                                    color = if (slot.kind == "课间") colors.primary else colors.tertiary,
                                    shape = RoundedCornerShape(4.dp)
                                ) {}
                            }
                        }
                    }
                }
            }
            Text("${weekdayName(selectedDay)} · ${daily.size} 个时段", style = MaterialTheme.typography.titleSmall)
            if (daily.isEmpty()) {
                Text("当天没有可显示的空挡。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            daily.forEach { slot ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${formatMinute(slot.startMinute)}–${formatMinute(slot.endMinute)}", style = MaterialTheme.typography.bodyLarge)
                        Text(slot.kind, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text("${slot.minutes} 分钟", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("点选星期查看明细；留白表示没有展示的可用时段，不代表已安排。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvailabilityLegend(label: String, betweenCourses: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            Modifier.size(8.dp, 16.dp),
            color = if (betweenCourses) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            shape = RoundedCornerShape(4.dp)
        ) {}
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
FocusCard(
    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
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
FocusCard(
    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
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
FocusCard(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        elevation = 1.dp
    ) {
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
FocusCard(
    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
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
