package com.sakata.focusflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PlanCoursesSection(
    awaitingCourses: List<Course>,
    confirmedCourses: List<Course>,
    courseImportRunning: Boolean,
    courseImportMessage: String?,
    tutorialSearch: TutorialSearchSettings,
    courseVision: CourseVisionSettings,
    onImportCourses: () -> Unit,
    onAddCourse: () -> Unit,
    onClearAwaitingCourses: () -> Unit,
    onConfirmCourse: (Course) -> Unit,
    onEditCourse: (Course) -> Unit,
    onIgnoreCourse: (Course) -> Unit
) {
    Text("从课表截图开始", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(enabled = !courseImportRunning, onClick = onImportCourses) {
            Text(if (courseImportRunning) "正在识别…" else "选择课表截图")
        }
        TextButton(onClick = onAddCourse) { Text("手动新增") }
    }
    Text(
        if (courseVision.enabled && tutorialSearch.apiKey.isNotBlank()) {
            "识别方式：硅基流动视觉模型（${courseVision.model}）"
        } else {
            "未开启视觉模型：请到设置开启并填写 key 后导入"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    courseImportMessage?.let { message ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            )
        ) {
            Text(message, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    PendingCourses(
        awaitingCourses,
        confirmedCourses,
        onClearAwaitingCourses,
        onConfirmCourse,
        onEditCourse,
        onIgnoreCourse
    )
    HorizontalDivider()
    ConfirmedCourses(confirmedCourses, onEditCourse)
}

@Composable
private fun PendingCourses(
    awaiting: List<Course>,
    confirmed: List<Course>,
    onClear: () -> Unit,
    onConfirm: (Course) -> Unit,
    onEdit: (Course) -> Unit,
    onIgnore: (Course) -> Unit
) {
    if (awaiting.isEmpty()) {
        Text("没有待确认课程。", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("待确认课程", fontWeight = FontWeight.Bold)
        TextButton(onClick = onClear) { Text("全部忽略") }
    }
    awaiting.forEach { course ->
        val conflictWith = confirmed.firstOrNull { coursesOverlap(course, it) }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ),
            border = conflictWith?.let { BorderStroke(1.dp, CONFLICT_TEXT_COLOR) }
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CourseIdentity(course)
                conflictWith?.let {
                    Text(
                        "⚠ 与已确认课程《${it.title}》时间冲突，确认后会产生冲突警示",
                        color = CONFLICT_TEXT_COLOR,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onConfirm(course) }) { Text("确认") }
                    TextButton(onClick = { onEdit(course) }) { Text("编辑并确认") }
                    TextButton(onClick = { onIgnore(course) }) { Text("忽略") }
                }
            }
        }
    }
}

@Composable
private fun ConfirmedCourses(confirmed: List<Course>, onEdit: (Course) -> Unit) {
    Text("已确认课程", fontWeight = FontWeight.Bold)
    if (confirmed.isEmpty()) {
        Text("确认课程后，它们会用于周日程和空挡计算。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val conflicting = confirmed.filter { course ->
        confirmed.any { other -> other != course && coursesOverlap(course, other) }
    }
    if (conflicting.isNotEmpty()) {
        Text(
            "⚠ ${conflicting.size} 门课程时间冲突，请编辑修正",
            color = CONFLICT_TEXT_COLOR,
            fontWeight = FontWeight.SemiBold
        )
        conflicting.sortedWith(courseOrder).forEach { course ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CONFLICT_BLOCK_COLOR,
                border = BorderStroke(1.dp, CONFLICT_TEXT_COLOR)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        CourseIdentity(course, CONFLICT_TEXT_COLOR)
                        val overlapped = confirmed.firstOrNull { other ->
                            other != course && coursesOverlap(course, other)
                        }
                        Text(
                            "与${overlapped?.let { "《${it.title}》" } ?: "另一门课"}重叠",
                            style = MaterialTheme.typography.labelSmall,
                            color = CONFLICT_TEXT_COLOR
                        )
                    }
                    TextButton(onClick = { onEdit(course) }) { Text("编辑") }
                }
            }
        }
    }
    confirmed.filterNot { it in conflicting }.sortedWith(courseOrder).forEach { course ->
        ElevatedCard {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) { CourseIdentity(course) }
                TextButton(onClick = { onEdit(course) }) { Text("编辑") }
            }
        }
    }
}

@Composable
private fun CourseIdentity(course: Course, titleColor: androidx.compose.ui.graphics.Color? = null) {
    Text(
        "${weekdayName(course.weekday)} · ${course.title}",
        fontWeight = FontWeight.SemiBold,
        color = titleColor ?: LocalContentColor.current
    )
    Text(
        "第 ${course.startPeriod}–${course.endPeriod} 节 · ${course.building}",
        style = MaterialTheme.typography.bodySmall
    )
}

private val courseOrder = compareBy<Course> { it.weekday }.thenBy { it.startPeriod }
