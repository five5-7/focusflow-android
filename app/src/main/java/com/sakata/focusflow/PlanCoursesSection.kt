package com.sakata.focusflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun PlanCoursesSection(
    awaitingCourses: List<Course>,
    confirmedCourses: List<Course>,
    courseImportRunning: Boolean,
    courseImportMessage: String?,
    tutorialSearch: TutorialSearchSettings,
    courseVision: CourseVisionSettings,
    onImportCourses: () -> Unit,
    onImportZju: () -> Unit,
    onAddCourse: () -> Unit,
    onClearAwaitingCourses: () -> Unit,
    onConfirmSafeCourses: () -> Unit,
    onConfirmCourse: (Course) -> Unit,
    onEditCourse: (Course) -> Unit,
    onIgnoreCourse: (Course) -> Unit,
    onToggleCourse: (Course) -> Unit,
    onDeleteCourses: (Set<Course>) -> Unit,
    onMergeCourses: (Set<Long>, Long, CourseEditPlans.CourseMergePlan.Applied) -> Boolean,
    onConfirmImportedGroup: (Set<Long>) -> Boolean,
    onLinkCourses: (Set<Long>) -> Boolean,
    onSeparateCourse: (Long) -> Boolean,
    onRenameCourse: (Long, String) -> Boolean,
    reminderSettings: CourseReminderSettings,
    reminderPeriodTable: CoursePeriodTable,
    onReminderGlobalChange: (Boolean) -> Unit,
    onReminderOverrideChange: (Course, Boolean?) -> Unit,
    restorableCourseCount: Int,
    onRestoreCourses: () -> Unit
) {
    val periodConfigured = PrototypeStore(LocalContext.current).hasCoursePeriodTable()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("课程提醒", fontWeight = FontWeight.Bold)
            Text("默认上课前 10 分钟；单条课次可覆盖总开关", style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = reminderSettings.enabled, onCheckedChange = onReminderGlobalChange)
    }
    if (!periodConfigured) Text("请先在日程 → 课表确认节次时间；确认前课程提醒不会发送。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("从教务网导入", fontWeight = FontWeight.Bold)
    FilledTonalButton(
        enabled = !courseImportRunning,
        onClick = onImportZju,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (courseImportRunning) "导入处理中…" else "浙江大学")
    }
    Text(
        "应用内填写统一身份认证账号和密码后自动获取；密码仅用于本次导入，不保存。新课程进入待确认，唯一匹配的已有课程直接更新。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("其他导入方式", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !courseImportRunning, onClick = onImportCourses) { Text("截图识别") }
        TextButton(enabled = !courseImportRunning, onClick = onAddCourse) { Text("手动新增") }
    }
    Text(
        if (courseVision.enabled && tutorialSearch.apiKey.isNotBlank()) {
            "截图识别：硅基流动视觉模型（${courseVision.model}）"
        } else {
            "截图识别未开启：可到设置开启并填写 key"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    courseImportMessage?.let { message ->
FocusCard(
    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
) {
            Text(message, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    PendingCourses(
        awaitingCourses,
        confirmedCourses,
        onClearAwaitingCourses,
        onConfirmSafeCourses,
        onConfirmCourse,
        onEditCourse,
        onIgnoreCourse,
        onConfirmImportedGroup
    )
    HorizontalDivider()
    ConfirmedCourses(confirmedCourses, onEditCourse, onToggleCourse, onDeleteCourses, onMergeCourses,
        onLinkCourses, onSeparateCourse, onRenameCourse,
        reminderSettings, reminderPeriodTable, periodConfigured, onReminderOverrideChange,
        restorableCourseCount, onRestoreCourses)
}

@Composable
private fun PendingCourses(
    awaiting: List<Course>,
    confirmed: List<Course>,
    onClear: () -> Unit,
    onConfirmSafe: () -> Unit,
    onConfirm: (Course) -> Unit,
    onEdit: (Course) -> Unit,
    onIgnore: (Course) -> Unit,
    onConfirmGroup: (Set<Long>) -> Boolean
) {
    if (awaiting.isEmpty()) {
        Text("没有待确认课程。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val allCourses = awaiting + confirmed
    val safeIds = remember(awaiting, confirmed) { CourseConfirmationSafety.safeBatchConfirmationIds(allCourses) }
    Column(Modifier.fillMaxWidth()) {
        Text("待确认课程", fontWeight = FontWeight.Bold)
        Row {
            TextButton(enabled = safeIds.isNotEmpty(), onClick = onConfirmSafe) { Text("一键确认 ${safeIds.size} 条") }
            TextButton(onClick = onClear) { Text("全部忽略") }
        }
    }
    if (safeIds.size < awaiting.size) {
        Text("有 ${awaiting.size - safeIds.size} 条重叠或停用记录需逐条核对。", style = MaterialTheme.typography.bodySmall)
    }
    val blockedDirectConfirmationIds = remember(awaiting, confirmed) {
        CourseConfirmationSafety.blockedDirectConfirmationIds(allCourses)
    }
    if (blockedDirectConfirmationIds.isNotEmpty()) {
        Text(
            "检测到多门课程占用完全相同的星期和节次。为避免错误课表生效，这些课程只能逐门“编辑并确认”。",
            color = CONFLICT_TEXT_COLOR,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    val candidates = awaiting.filter { it.externalSchoolYearCode.isNotBlank() &&
        it.externalTermCode.isNotBlank() && it.externalSelectionKeyCandidate.isNotBlank() }
        .groupBy { Triple(it.externalSchoolYearCode, it.externalTermCode,
            it.externalSelectionKeyCandidate) }
        .values.filter { it.size >= 2 && it.map(Course::title).distinct().size == 1 }
    var pendingGroup by remember { mutableStateOf<List<Course>?>(null) }
    candidates.forEach { group ->
        TextButton(onClick = { pendingGroup = group }) {
            Text("核对并归为《${group.first().title}》的 ${group.size} 个课次")
        }
    }
    pendingGroup?.let { group ->
        AppDialog(onDismissRequest = { pendingGroup = null },
            title = { Text("确认同属一门课程？") },
            text = { Text(group.sortedWith(courseMeetingOrder).joinToString("\n") {
                "${weekdayName(it.weekday)} 第 ${it.startPeriod}–${it.endPeriod} 节 · ${it.building}"
            } + "\n请逐项核对；教务选课号仅用于提示，确认后各课次仍保留独立提醒和地点。") },
            confirmButton = { Button(onClick = {
                onConfirmGroup(group.mapTo(mutableSetOf(), Course::id))
                pendingGroup = null
            }) { Text("确认并归组") } },
            dismissButton = { TextButton(onClick = { pendingGroup = null }) { Text("取消") } })
    }
    groupCourseMeetings(awaiting).forEach { group ->
        FocusCard(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(group.title + if (group.meetings.size > 1) " · 同名 ${group.meetings.size} 条记录" else "", fontWeight = FontWeight.SemiBold)
                connectedCourseSpans(group.meetings).forEachIndexed { index, span ->
                    val course = span.display
                    if (index > 0) HorizontalDivider()
                    val conflictWith = confirmed.firstOrNull { coursesOverlap(course, it) }
                    val directConfirmationBlocked = span.records.any { it.id in blockedDirectConfirmationIds }
                    val groupConfirmationBlocked = span.records.size > 1 && span.records.any { it.id !in safeIds }
                    CourseMeetingDetails(course)
                    if (span.records.size > 1) Text("相邻时段合并展示 · ${span.records.size} 条原记录", style = MaterialTheme.typography.labelSmall)
                    conflictWith?.let {
                        Text("⚠ 与已确认课程《${it.title}》时间冲突", color = CONFLICT_TEXT_COLOR, style = MaterialTheme.typography.labelSmall)
                    }
                    if (directConfirmationBlocked) {
                        Text("星期和节次需逐条核对，编辑后才能确认。", color = CONFLICT_TEXT_COLOR, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !directConfirmationBlocked && !groupConfirmationBlocked, onClick = { span.records.forEach(onConfirm) }) {
                            Text(if (directConfirmationBlocked || groupConfirmationBlocked) "需逐段核对" else "确认")
                        }
                        if (span.records.size == 1) TextButton(onClick = { onEdit(span.records.single()) }) { Text("编辑并确认") }
                        TextButton(onClick = { span.records.forEach(onIgnore) }) { Text("忽略") }
                    }
                    if (span.records.size > 1) span.records.forEach { original ->
                        TextButton(onClick = { onEdit(original) }) { Text("编辑第 ${original.startPeriod}–${original.endPeriod} 节") }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ConfirmedCourses(confirmed: List<Course>, onEdit: (Course) -> Unit, onToggle: (Course) -> Unit,
    onDelete: (Set<Course>) -> Unit,
    onMerge: (Set<Long>, Long, CourseEditPlans.CourseMergePlan.Applied) -> Boolean,
    onLink: (Set<Long>) -> Boolean,
    onSeparate: (Long) -> Boolean,
    onRename: (Long, String) -> Boolean,
    reminderSettings: CourseReminderSettings,
    reminderPeriodTable: CoursePeriodTable, periodConfigured: Boolean,
    onReminderOverrideChange: (Course, Boolean?) -> Unit,
    restorableCourseCount: Int, onRestoreCourses: () -> Unit) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Course>()) }
    var pendingDelete by remember { mutableStateOf<Set<Course>?>(null) }
    var preferredId by remember { mutableStateOf<Long?>(null) }
    var pendingMerge by remember { mutableStateOf<PendingCourseMerge?>(null) }
    var pendingLink by remember { mutableStateOf<Set<Long>?>(null) }
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var groupTitle by remember { mutableStateOf("") }
    var mergeError by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("已确认课程", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (restorableCourseCount > 0) {
                TextButton(onClick = onRestoreCourses) { Text("恢复删除（$restorableCourseCount）") }
            }
            if (confirmed.isNotEmpty()) TextButton(onClick = {
                selecting = !selecting
                if (!selecting) selected = emptySet()
            }) { Text(if (selecting) "完成" else "批量管理") }
        }
    }
    if (confirmed.isEmpty()) {
        Text("确认课程后，它们会用于周日程和空挡计算。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val conflicting = confirmed.filter { course ->
        course.enabled && confirmed.any { other -> other.enabled && other != course && coursesOverlap(course, other) }
    }
    if (selecting) {
        val allSelected = confirmed.all { it in selected }
        Column(Modifier.fillMaxWidth()) {
            Text("已选 ${selected.size}/${confirmed.size} 门", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    selected = if (allSelected) emptySet() else confirmed.toSet()
                    preferredId = selected.lastOrNull()?.id
                }) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                TextButton(enabled = selected.size >= 2, onClick = {
                    val chosen = confirmed.filter { it in selected }
                    val preferred = preferredId?.takeIf { id -> chosen.any { it.id == id } } ?: chosen.last().id
                    when (val plan = CourseMergeOperation.preview(context, chosen, preferred)) {
                        is CourseEditPlans.CourseMergePlan.Applied -> {
                            mergeError = null
                            pendingMerge = PendingCourseMerge(chosen.mapTo(mutableSetOf()) { it.id }, preferred, plan)
                        }
                        is CourseEditPlans.CourseMergePlan.Rejected -> mergeError = plan.reason
                    }
                }) { Text("合并所选") }
                TextButton(enabled = selected.map(Course::courseId).distinct().size >= 2,
                    onClick = { pendingLink = selected.mapTo(mutableSetOf(), Course::id) }) {
                    Text("归为同一门课")
                }
                TextButton(enabled = selected.isNotEmpty(), onClick = { pendingDelete = selected }) {
                    Text("删除所选", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    mergeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (conflicting.isNotEmpty()) {
        Text(
            "⚠ ${conflicting.size} 门课程时间冲突，请编辑修正",
            color = CONFLICT_TEXT_COLOR,
            fontWeight = FontWeight.SemiBold
        )
        conflicting.sortedWith(courseMeetingOrder).forEach { course ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CONFLICT_BLOCK_COLOR,
                border = BorderStroke(1.dp, CONFLICT_TEXT_COLOR)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(checked = course in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + course else selected - course
                            if (checked) preferredId = course.id
                        })
                        Column(Modifier.weight(1f)) {
                            CourseIdentity(course, CONFLICT_TEXT_COLOR)
                        }
                    }
                    val overlapped = confirmed.firstOrNull { other ->
                        other.enabled && other != course && coursesOverlap(course, other)
                    }
                    Text(
                        "与${overlapped?.let { "《${it.title}》" } ?: "另一门课"}重叠",
                        style = MaterialTheme.typography.labelSmall,
                        color = CONFLICT_TEXT_COLOR
                    )
                    if (!selecting) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onEdit(course) }) { Text("编辑") }
                            TextButton(onClick = { onToggle(course) }) { Text(if (course.enabled) "停用" else "启用") }
                            TextButton(onClick = { pendingDelete = setOf(course) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    groupCourseMeetings(confirmed.filterNot { it in conflicting }, byIdentity = true).forEach { group ->
        FocusCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            elevation = 1.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(group.title + if (group.meetings.size > 1) " · ${group.meetings.size} 个独立课次" else "", fontWeight = FontWeight.SemiBold)
                if (!selecting && group.meetings.size > 1)
                    TextButton(onClick = {
                        editingGroupId = group.meetings.first().courseId
                        groupTitle = group.title
                    }) { Text("编辑整门课程名称") }
                connectedCourseSpans(group.meetings).forEachIndexed { index, span ->
                    val course = span.display
                    if (index > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(checked = span.records.all { it in selected }, onCheckedChange = { checked ->
                            selected = if (checked) selected + span.records else selected - span.records.toSet()
                            if (checked) preferredId = span.records.last().id
                        })
                        Column(Modifier.weight(1f)) { CourseMeetingDetails(course) }
                    }
                    if (span.records.size > 1) Text("相邻时段合并展示 · ${span.records.size} 条原记录", style = MaterialTheme.typography.labelSmall)
                    if (!selecting) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (span.records.size == 1) TextButton(onClick = { onEdit(span.records.single()) }) { Text("编辑") }
                            if (span.records.size == 1 && confirmed.count { it.courseId == course.courseId } > 1)
                                TextButton(onClick = { onSeparate(course.id) }) { Text("分离课次") }
                            TextButton(onClick = { span.records.forEach(onToggle) }) { Text(if (course.enabled) "停用" else "启用") }
                            TextButton(onClick = { pendingDelete = span.records.toSet() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                        if (span.records.size > 1) span.records.forEach { original ->
                            TextButton(onClick = { onEdit(original) }) { Text("编辑第 ${original.startPeriod}–${original.endPeriod} 节") }
                            if (confirmed.count { it.courseId == original.courseId } > 1)
                                TextButton(onClick = { onSeparate(original.id) }) { Text("分离该课次") }
                        }
                    }
                }
            }
        }
    }
    if (confirmed.isNotEmpty()) {
        HorizontalDivider()
        Text("按课次设置提醒", fontWeight = FontWeight.SemiBold)
        confirmed.sortedWith(courseMeetingOrder).forEach { course ->
            Column(Modifier.fillMaxWidth()) {
                Text("${course.title} · ${weekdayName(course.weekday)} ${course.startPeriod}–${course.endPeriod} 节",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "跟随总开关", true to "提醒", false to "关闭").forEach { (value, label) ->
                        FilterChip(selected = reminderSettings.overrides[course.id] == value,
                            onClick = { onReminderOverrideChange(course, value) }, label = { Text(label) })
                    }
                }
                if (periodConfigured) NextCourseLocationEditor(course, reminderPeriodTable)
            }
        }
    }
    pendingDelete?.let { targets ->
        AppDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (targets.size == 1) "删除这门课程？" else "删除所选 ${targets.size} 门课程？") },
            text = { Text("课程将从课表、日程和空挡计算中移除；节次表、地点、任务和历史记录不会删除。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDelete(targets)
                        selected = emptySet()
                        selecting = false
                        pendingDelete = null
                    }
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
    pendingMerge?.let { pending ->
        AppDialog(onDismissRequest = { pendingMerge = null },
            title = { Text("确认合并所选课程？") },
            text = { Text(pending.plan.confirmationText) },
            confirmButton = { Button(onClick = {
                if (onMerge(pending.ids, pending.preferredId, pending.plan)) {
                    selected = emptySet()
                    selecting = false
                }
                pendingMerge = null
            }) { Text("确认合并") } },
            dismissButton = { TextButton(onClick = { pendingMerge = null }) { Text("取消") } })
    }
    pendingLink?.let { ids ->
        AppDialog(onDismissRequest = { pendingLink = null },
            title = { Text("归为同一门课程？") },
            text = { Text("将所选及其已有同组课次关联到同一课程。课次 ID、提醒和临时地点保持独立；不同课程名、冲突时段或不同学期会被拒绝。") },
            confirmButton = { Button(onClick = {
                if (onLink(ids)) { selected = emptySet(); selecting = false }
                pendingLink = null
            }) { Text("确认归组") } },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("取消") } })
    }
    editingGroupId?.let { id ->
        AppDialog(onDismissRequest = { editingGroupId = null },
            title = { Text("编辑整门课程名称") },
            text = { OutlinedTextField(value = groupTitle, onValueChange = { groupTitle = it },
                label = { Text("课程名称") }, singleLine = true) },
            confirmButton = { Button(enabled = groupTitle.isNotBlank(), onClick = {
                if (onRename(id, groupTitle.trim())) editingGroupId = null
            }) { Text("保存全部课次名称") } },
            dismissButton = { TextButton(onClick = { editingGroupId = null }) { Text("取消") } })
    }
}

private data class PendingCourseMerge(
    val ids: Set<Long>,
    val preferredId: Long,
    val plan: CourseEditPlans.CourseMergePlan.Applied
)

@Composable
private fun NextCourseLocationEditor(course: Course, table: CoursePeriodTable) {
    val context = LocalContext.current
    // Offer today's room until class starts, even if the ten-minute alert has already fired.
    val next = CourseReminderPolicy.nextTrigger(course, table,
        System.currentTimeMillis() - CourseReminderPolicy.ADVANCE_MINUTES * 60_000L, ZoneId.systemDefault())
        ?: return
    val day = Instant.ofEpochMilli(next + CourseReminderPolicy.ADVANCE_MINUTES * 60_000L)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    var temporary by remember(course.id, day, context) {
        mutableStateOf(CourseLocationOverrides.get(context, course.id, day.toEpochDay()))
    }
    var editing by remember(course.id, day) { mutableStateOf(false) }
    var draft by remember(course.id, day) { mutableStateOf("") }
    val label = day.toString()
    TextButton(onClick = { draft = temporary ?: course.building; editing = true }) {
        Text("$label 本次地点：${temporary ?: course.building.ifBlank { "待确认" }} · 修改")
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("仅调整 $label 的上课地点") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(100) },
            label = { Text("本次地点") }, singleLine = true) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
            if (CourseLocationOverrides.set(context, course.id, day.toEpochDay(), draft)) {
                temporary = draft.trim(); editing = false
            }
        }) { Text("保存") } },
        dismissButton = {
            Row {
                if (temporary != null) TextButton(onClick = {
                    if (CourseLocationOverrides.set(context, course.id, day.toEpochDay(), null)) {
                        temporary = null; editing = false
                    }
                }) { Text("恢复固定地点") }
                TextButton(onClick = { editing = false }) { Text("取消") }
            }
        }
    )
}

@Composable
private fun CourseIdentity(course: Course, titleColor: androidx.compose.ui.graphics.Color? = null) {
    Text(
        course.title,
        fontWeight = FontWeight.SemiBold,
        color = titleColor ?: LocalContentColor.current
    )
    CourseMeetingDetails(course)
}

@Composable
private fun CourseMeetingDetails(course: Course) {
    Text(
        "${weekdayName(course.weekday)} · 第 ${course.startPeriod}–${course.endPeriod} 节" +
            (if (!course.enabled) " · 已停用" else courseDateRangeText(course)),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        if (course.building.isBlank()) "地点待确认" else course.building,
        style = MaterialTheme.typography.bodySmall
    )
}

private fun courseDateRangeText(course: Course): String = when {
    course.effectiveFromEpochDay != null && course.effectiveUntilEpochDay != null -> " · ${java.time.LocalDate.ofEpochDay(course.effectiveFromEpochDay)} 至 ${java.time.LocalDate.ofEpochDay(course.effectiveUntilEpochDay)}"
    course.effectiveFromEpochDay != null -> " · ${java.time.LocalDate.ofEpochDay(course.effectiveFromEpochDay)} 起"
    course.effectiveUntilEpochDay != null -> " · 至 ${java.time.LocalDate.ofEpochDay(course.effectiveUntilEpochDay)}"
    else -> ""
}
