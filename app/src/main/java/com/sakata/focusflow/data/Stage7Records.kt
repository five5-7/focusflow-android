package com.sakata.focusflow.data

import com.sakata.focusflow.*
import java.time.ZoneId
import java.util.UUID

/** A single strict envelope over the existing operation_records table. No new Room version. */
internal object Stage7RecordsCodec {
    const val KEY = "stage7_records_v1"
    fun encode(records: List<OperationRecord>): String {
        verify(records)
        return StrictJson.write(StrictJson.Value.Arr(records.map { r -> S7Json.obj(
            "version" to StrictJson.Value.Num(1), "id" to StrictJson.Value.Str(r.operationId),
            "kind" to StrictJson.Value.Str(r.kind), "at" to StrictJson.Value.Num(r.recordedAt),
            "state" to StrictJson.Value.Str(r.state), "payload" to StrictJson.Value.Str(r.payload)
        ) }))
    }
    fun decode(raw: String?): List<OperationRecord> {
        if (raw == null) return emptyList()
        val array = S7Json.parse(raw) as? StrictJson.Value.Arr ?: error("records must be an array")
        return array.items.map { value ->
            val o = S7Json.fields(value, setOf("version", "id", "kind", "at", "state", "payload"))
            require(S7Json.long(o, "version") == 1L)
            OperationRecord(S7Json.text(o,"id"), S7Json.text(o,"kind"), S7Json.long(o,"at"),
                S7Json.text(o,"state"), S7Json.text(o,"payload"))
        }.also(::verify)
    }
    fun verify(records: List<OperationRecord>) {
        require(records.map { it.operationId }.distinct().size == records.size) { "duplicate recovery identity" }
        records.forEach { r ->
            require(r.operationId.isNotBlank() && r.recordedAt > 0 && r.state in setOf("active","restored","restoring","purging"))
            when (r.kind) {
                "repeat_rule_closure" -> {
                    val c = (RepeatClosureCodec.decode(r.payload) as? RepeatClosureDecode.Ok)?.record
                        ?: error("invalid repeat closure")
                    require(c.groupId == r.operationId && c.deletedAt == r.recordedAt &&
                        c.status.name.lowercase() == r.state)
                }
                "plan_binding_closure" -> PlanBindingClosure.decode(r.payload).also {
                    require(it.id == r.operationId && it.at == r.recordedAt && it.state == r.state)
                }
                "task_batch_inverse", "course_merge_inverse", "course_split_inverse" -> InverseOperation.decode(r.payload).also {
                    require(it.id == r.operationId && it.at == r.recordedAt && it.state == r.state && it.kind == r.kind)
                }
                "course_recovery" -> {
                    val g = (CourseRecoveryCodec.decode(r.payload) as? CourseRecoveryLoad.Ready)?.groups?.singleOrNull()
                        ?: error("invalid course recovery")
                    require(g.groupId == r.operationId && g.deletedAt == r.recordedAt && g.state.name.lowercase() == r.state)
                }
                else -> error("unknown recovery kind ${r.kind}")
            }
        }
    }
}

internal object Stage7TrashTransitions {
    fun groups(before:CoreDataSnapshot,updated:CoreDataSnapshot):List<TrashGroupRecord> {
        val missing=before.items.mapTo(mutableSetOf()) { it.id }-updated.items.mapTo(mutableSetOf()) { it.id }
        val intermediate=before.items.filterNot { it.id in missing }
        val purged=TrashJournal.purge(before.items,intermediate,before.trashGroups,missing)
        val derived=TrashJournal.update(intermediate,updated.items,purged)
        val requested=updated.trashGroups.mapTo(mutableSetOf()) { it.groupId }
        val explicitlyRemoved=before.trashGroups.filter { it.groupId !in requested }
        require(explicitlyRemoved.all { it.state=="restored" }) { "active trash group cannot be discarded" }
        return derived.filterNot { group -> explicitlyRemoved.any { it.groupId==group.groupId } }
    }
}

internal object Stage7CommitGuard {
    @Volatile var uncertain = false; private set
    fun markUncertain() { uncertain = true }
    fun confirm() { uncertain = false }
}

/** Strict immutable snapshots: body edits are allowed because restore never writes a bound task. */
internal data class PlanBindingClosure(
    val id: String, val at: Long, val expiresAt: Long, val state: String,
    val plan: Goal, val bindings: Map<Long,Long>,
    val beforeItems: List<Item> = emptyList(), val afterItems: List<Item> = emptyList()
) {
    fun encode(): String = StrictJson.write(S7Json.obj(
        "version" to StrictJson.Value.Num(1), "id" to StrictJson.Value.Str(id),
        "at" to StrictJson.Value.Num(at), "expiresAt" to StrictJson.Value.Num(expiresAt),
        "state" to StrictJson.Value.Str(state), "plan" to StrictJson.Value.Str(StoredGoalsCodec.encodeGoals(listOf(plan))),
        "beforeItems" to StrictJson.Value.Arr(beforeItems.map { StrictJson.Value.Str(RepeatClosureCodec.encodeItem(it)) }),
        "afterItems" to StrictJson.Value.Arr(afterItems.map { StrictJson.Value.Str(RepeatClosureCodec.encodeItem(it)) }),
        "bindings" to StrictJson.Value.Arr(bindings.map { (itemId, goalId) ->
            StrictJson.Value.Arr(listOf(StrictJson.Value.Num(itemId),StrictJson.Value.Num(goalId))) })
    ))
    fun record() = OperationRecord(id,"plan_binding_closure",at,state,encode())
    companion object {
        fun decode(raw: String): PlanBindingClosure {
            val o = S7Json.fields(S7Json.parse(raw),setOf("version","id","at","expiresAt","state","plan","bindings","beforeItems","afterItems"))
            require(S7Json.long(o,"version") == 1L)
            val planRaw = S7Json.text(o,"plan")
            val plan = StoredGoalsCodec.decodeGoals(planRaw).single()
            require(StoredGoalsCodec.encodeGoals(listOf(plan)) == planRaw && plan.id > 0)
            val bindings = S7Json.array(o,"bindings").map { value ->
                val a = (value as StrictJson.Value.Arr).items
                require(a.size == 2)
                (a[0] as StrictJson.Value.Num).value to (a[1] as StrictJson.Value.Num).value
            }
            require(bindings.map { it.first }.distinct().size == bindings.size && bindings.all { it.first > 0 && it.second == plan.id })
            val c = PlanBindingClosure(S7Json.text(o,"id"),S7Json.long(o,"at"),S7Json.long(o,"expiresAt"),S7Json.text(o,"state"),plan,bindings.toMap(),S7Json.array(o,"beforeItems").map { RepeatClosureCodec.decodeItem((it as StrictJson.Value.Str).value) },S7Json.array(o,"afterItems").map { RepeatClosureCodec.decodeItem((it as StrictJson.Value.Str).value) })
            require(c.id.isNotBlank() && c.at > 0 && c.expiresAt == Stage7Recovery.expiry(c.at) && c.state in setOf("active","restored"))
            require(c.beforeItems.map { it.id } == c.afterItems.map { it.id } && c.beforeItems.map { it.id }.distinct().size == c.beforeItems.size)
            require(c.beforeItems.all { it.id > 0 && it.goalId == plan.id && it.kind == "任务" && it.repeatTemplateId == null && it.trashedAt == null })
            if(c.beforeItems.isNotEmpty()) require(TrashActions.trash(c.beforeItems,c.beforeItems.mapTo(mutableSetOf()) { it.id },c.at).items == c.afterItems)
            require(c.beforeItems.all { c.bindings[it.id] == plan.id })
            return c
        }
    }
}

internal object S7Json {
    fun parse(raw: String): StrictJson.Value = (StrictJson.parse(raw) as? StrictJson.ParseResult.Ok)?.value ?: error("invalid JSON")
    fun obj(vararg pairs: Pair<String,StrictJson.Value>) = StrictJson.Value.Obj(pairs.toList())
    fun fields(v: StrictJson.Value, keys: Set<String>): Map<String,StrictJson.Value> {
        val e = (v as StrictJson.Value.Obj).entries
        require(e.map { it.first }.toSet() == keys && e.size == keys.size)
        return e.toMap()
    }
    fun text(o: Map<String,StrictJson.Value>, k: String) = (o.getValue(k) as StrictJson.Value.Str).value
    fun long(o: Map<String,StrictJson.Value>, k: String) = (o.getValue(k) as StrictJson.Value.Num).value
    fun array(o: Map<String,StrictJson.Value>, k: String) = (o.getValue(k) as StrictJson.Value.Arr).items
}

internal object Stage7Recovery {
    fun expiry(at: Long): Long { require(at > 0 && at <= Long.MAX_VALUE - 30L*86400000); return at + 30L*86400000 }
    private fun snapshot(repo: CoreDataRepository) = (repo.read() as? CoreDataReadResult.Ready)?.snapshot ?: error("core data unavailable")
    private fun reject(message: String) = CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET,message)
    fun execute(repo: CoreDataRepository, operation: (CoreDataSnapshot) -> CoreDataSnapshot): CoreDataWriteResult = try {
        if (Stage7CommitGuard.uncertain || CourseRecoveryWriteGuard.uncertainReason()!=null) reject("previous disk write unconfirmed; restart before recovery")
        else snapshot(repo).let { before -> repo.commitRecovery(before,operation(before)) }
    } catch (e: Exception) { reject(e.message ?: "recovery rejected") }

    fun deleteRepeat(repo: CoreDataRepository, template: Item, now: Long = System.currentTimeMillis()): CoreDataWriteResult = execute(repo) { before ->
        val groups = before.operationRecords.filter { it.kind == "repeat_rule_closure" }.map { (RepeatClosureCodec.decode(it.payload) as RepeatClosureDecode.Ok).record }
        val planned = RepeatClosureDeletePlan.plan(before.items,template,UUID.randomUUID().toString(),UUID.randomUUID().toString(),now,before.courses,groups)
        require(planned is DeletePlanResult.Ok) { (planned as DeletePlanResult.Rejected).reason }
        before.copy(items=planned.itemsToWrite, taskEvents=planned.events.fold(before.taskEvents,TaskHistory::append),
            operationRecords=before.operationRecords + OperationRecord(planned.groupToWrite.groupId,"repeat_rule_closure",now,"active",RepeatClosureCodec.encode(planned.groupToWrite)))
    }
    fun deletePlan(repo: CoreDataRepository, goal: Goal, selectedIds: Set<Long> = emptySet(), now: Long = System.currentTimeMillis()): CoreDataWriteResult = execute(repo) { before ->
        require(before.goals.singleOrNull { it.id == goal.id } == goal) { "plan changed" }
        val selected = before.items.filter { it.id in selectedIds }
        require(selected.size == selectedIds.size && selected.all { it.goalId == goal.id && it.kind == "任务" && it.repeatTemplateId == null && it.trashedAt == null }) { "selected plan tasks changed or belong to a repeat rule" }
        val deleted = if(selectedIds.isEmpty()) TrashResult(before.items,emptyList()) else TrashActions.trash(before.items,selectedIds,now)
        require(selectedIds.isEmpty() || deleted.events.size == selectedIds.size)
        val c = PlanBindingClosure(UUID.randomUUID().toString(),now,expiry(now),"active",goal,
            before.items.filter { it.goalId == goal.id }.associate { it.id to goal.id },selected,deleted.items.filter { it.id in selectedIds })
        PlanBindingClosure.decode(c.encode())
        before.copy(items=deleted.items,taskEvents=deleted.events.fold(before.taskEvents,TaskHistory::append),goals=before.goals.filterNot { it.id == goal.id }, operationRecords=before.operationRecords+c.record())
    }
    fun restore(repo: CoreDataRepository, id: String, now: Long = System.currentTimeMillis()): CoreDataWriteResult = execute(repo) { before ->
        require(now>0) { "invalid recovery time" }
        val r = before.operationRecords.single { it.operationId == id }
        require(r.state == "active") { "already restored or follow-up pending" }
        when(r.kind) {
            "repeat_rule_closure" -> {
                val c = (RepeatClosureCodec.decode(r.payload) as RepeatClosureDecode.Ok).record
                val p = RepeatClosureRecoveryPlan.plan(c,before.items,now,ZoneId.systemDefault(),before.courses)
                require(p is RecoveryPlanResult.Ok) { (p as RecoveryPlanResult.Rejected).reason }
                before.copy(items=p.itemsToWrite,taskEvents=p.events.fold(before.taskEvents,TaskHistory::append),
                    operationRecords=before.operationRecords.map { if(it.operationId==id) r.copy(state="restored",payload=RepeatClosureCodec.encode(p.groupToWrite)) else it })
            }
            "plan_binding_closure" -> {
                val c = PlanBindingClosure.decode(r.payload)
                require(now < c.expiresAt && before.goals.none { it.id==c.plan.id }) { "plan expired or id occupied" }
                val live = before.items.filter { it.goalId==c.plan.id }.associate { it.id to c.plan.id }
                val selected = c.beforeItems.mapTo(mutableSetOf()) { it.id }
                require(live == c.bindings.filterKeys { it !in selected }) { "plan binding changed" }
                require(c.afterItems.all { post -> before.items.singleOrNull { it.id==post.id } == post }) { "selected plan task changed" }
                val originals = c.beforeItems.associateBy { it.id }
                val refreshed = RepeatActions.refresh(before.items.map { originals[it.id] ?: it },now,before.courses)
                val events = c.beforeItems.map { TaskRecorder.event(TaskEventType.TASK_RESTORED,it.id,it.title,at=now) }
                before.copy(items=refreshed.items,taskEvents=(events+refreshed.events).fold(before.taskEvents,TaskHistory::append),goals=before.goals+c.plan,operationRecords=before.operationRecords.map { if(it.operationId==id) c.copy(state="restored").record() else it })
            }
            else -> error("use inverse operation entry")
        }
    }
    fun purge(repo: CoreDataRepository, ids: Set<String>? = null, now: Long = System.currentTimeMillis()): CoreDataWriteResult = execute(repo) { before ->
        require(now>0) { "invalid purge time" }
        val selected = before.operationRecords.filter { r ->
            if (ids != null) r.operationId in ids && r.kind != "course_recovery" && r.state !in setOf("restoring","purging") else when(r.kind) {
                "repeat_rule_closure" -> (RepeatClosureCodec.decode(r.payload) as RepeatClosureDecode.Ok).record.expiresAt <= now
                "plan_binding_closure" -> PlanBindingClosure.decode(r.payload).expiresAt <= now
                "task_batch_inverse", "course_merge_inverse", "course_split_inverse" -> InverseOperation.decode(r.payload).expiresAt <= now && r.state != "restoring"
                else -> false
            }
        }
        if(ids!=null) require(selected.size==ids.size) { "recovery record is missing or follow-up is pending" }
        val removed = selected.mapTo(mutableSetOf()) { it.operationId }
        val planTombstones = selected.filter { it.kind=="plan_binding_closure" && it.state=="active" }.flatMap { PlanBindingClosure.decode(it.payload).afterItems }.filter { post -> before.items.singleOrNull { it.id==post.id } == post }.mapTo(mutableSetOf()) { it.id }
        val tombstones = selected.filter { it.kind=="repeat_rule_closure" && it.state=="active" }.mapNotNull {
            val c=(RepeatClosureCodec.decode(it.payload) as RepeatClosureDecode.Ok).record
            c.template.templateId.takeIf { id -> before.items.singleOrNull { it.id==id } == c.template.postState }
        }.toSet() + planTombstones
        // Active conflicts lose only the recovery payload; modified objects and history survive.
        before.copy(items=before.items.filterNot { it.id in tombstones },operationRecords=before.operationRecords.filterNot { it.operationId in removed },
            trashGroups=before.trashGroups.filterNot { it.state=="restored" && now>=it.expiresAt })
    }
}
