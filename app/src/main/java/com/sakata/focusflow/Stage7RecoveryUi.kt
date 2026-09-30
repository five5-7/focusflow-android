package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.sakata.focusflow.data.*

internal data class RecoveryEntry(val id:String,val title:String,val detail:String,val course:Boolean,val inverse:Boolean,val canRestore:Boolean,val canPurge:Boolean)
internal object RecoveryEntries {
    fun build(snapshot:CoreDataSnapshot, courseGroups:List<CourseRecoveryGroup>, now:Long):List<RecoveryEntry> {
        val records=snapshot.operationRecords.filter { it.kind!="course_recovery" }.map { r ->
            val (title,expiry)=when(r.kind) {
                "repeat_rule_closure" -> (RepeatClosureCodec.decode(r.payload) as RepeatClosureDecode.Ok).record.let { "重复规则 · ${it.template.preState.title}" to it.expiresAt }
                "plan_binding_closure" -> PlanBindingClosure.decode(r.payload).let { "计划 · ${it.plan.title}" to it.expiresAt }
                else -> InverseOperation.decode(r.payload).let { (when(r.kind) { "task_batch_inverse"->"整批待办 · ${it.beforeItems.size} 项"; "course_merge_inverse"->"课程合并"; else->"课程拆分" }) to it.expiresAt }
            }
            RecoveryEntry(r.operationId,title,when(r.state) { "restoring"->"撤回后续待完成"; "restored"->"已恢复 / 已撤回"; else->TrashViews.remainingLabel(expiry,now) },false,r.kind.endsWith("inverse"),r.state=="restoring" || r.state=="active" && now<expiry,r.state!="restoring")
        }
        return (records+courseGroups.map { g -> RecoveryEntry(g.groupId,"课程 · ${g.members.mapNotNull { CourseSnapshotCodec.decode(it.courseJson)?.title }.distinct().joinToString("、")}（${g.members.size} 个课次）",when(g.state) {
            CourseRecoveryState.RESTORING->"提醒与地点回填待完成"; CourseRecoveryState.RESTORED->"已恢复"; CourseRecoveryState.PURGING->"待清除"; else->TrashViews.remainingLabel(g.expiresAt,now)
        },true,false,g.state==CourseRecoveryState.RESTORING || g.state==CourseRecoveryState.ACTIVE && now<g.expiresAt,g.state!=CourseRecoveryState.RESTORING) }).reversed()
    }
    fun ownedItemIds(records:List<OperationRecord>):Set<Long> = records.filter { it.state=="active" }.flatMap { r -> when(r.kind) {
        "repeat_rule_closure" -> listOf((RepeatClosureCodec.decode(r.payload) as RepeatClosureDecode.Ok).record.template.templateId)
        "plan_binding_closure" -> PlanBindingClosure.decode(r.payload).afterItems.map { it.id }
        else -> emptyList()
    } }.toSet()
}

@Composable
internal fun RecoveryRows(entries:List<RecoveryEntry>,onRestore:(RecoveryEntry)->Unit,onPurge:(RecoveryEntry)->Unit) {
    entries.forEach { e ->
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(e.title); Text(e.detail,style=MaterialTheme.typography.labelSmall) }
            if(e.canRestore) TextButton(onClick={onRestore(e)}) { Text(if(e.inverse) "撤回" else "恢复") }
            if(e.canPurge) TextButton(onClick={onPurge(e)}) { Text("清除") }
        }
    }
}
