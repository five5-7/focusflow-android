package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.*
import java.util.UUID

internal data class CoursePreferenceSnapshot(
    val overrides: Map<Long,Boolean?>,
    val watermarks: Map<Long,Long?>,
    val locations: Map<Long,Map<Long,String>>
) {
    fun encode(): String = StrictJson.write(StrictJson.Value.Arr(overrides.keys.sorted().map { id ->
        S7Json.obj("id" to StrictJson.Value.Num(id),
            "override" to (overrides[id]?.let { StrictJson.Value.Bool(it) } ?: StrictJson.Value.Null),
            "delivered" to (watermarks[id]?.let { StrictJson.Value.Num(it) } ?: StrictJson.Value.Null),
            "locations" to StrictJson.Value.Arr(locations[id].orEmpty().toSortedMap().map { (day,place) ->
                StrictJson.Value.Arr(listOf(StrictJson.Value.Num(day),StrictJson.Value.Str(place))) }))
    }))
    companion object {
        fun capture(context: Context, ids: Set<Long>): CoursePreferenceSnapshot {
            val r = CourseRecoveryPreferences.capture(context,ids)
            require(r is CourseRecoveryPreferenceCapture.Ready) { "course preferences are unreadable" }
            return CoursePreferenceSnapshot(ids.associateWith { r.overrides[it] },ids.associateWith { r.delivered[it] },ids.associateWith { r.locations[it].orEmpty() })
        }
        fun decode(raw: String): CoursePreferenceSnapshot {
            val values = (S7Json.parse(raw) as StrictJson.Value.Arr).items.map { v ->
                val o = S7Json.fields(v,setOf("id","override","delivered","locations"))
                val id = S7Json.long(o,"id"); require(id > 0)
                val flag = when(val x=o.getValue("override")) { StrictJson.Value.Null -> null; is StrictJson.Value.Bool -> x.value; else -> error("invalid override") }
                val at = when(val x=o.getValue("delivered")) { StrictJson.Value.Null -> null; is StrictJson.Value.Num -> x.value.also { require(it>0) }; else -> error("invalid watermark") }
                val places = S7Json.array(o,"locations").map { entry ->
                    val a=(entry as StrictJson.Value.Arr).items; require(a.size==2)
                    val day=(a[0] as StrictJson.Value.Num).value; val place=(a[1] as StrictJson.Value.Str).value
                    require(day>=0 && place.isNotBlank() && place.length<=100); day to place
                }
                require(places.map { it.first }.distinct().size==places.size)
                Triple(id,flag,at) to places.toMap()
            }
            require(values.map { it.first.first }.distinct().size==values.size)
            return CoursePreferenceSnapshot(values.associate { it.first.first to it.first.second },values.associate { it.first.first to it.first.third },values.associate { it.first.first to it.second })
        }
    }
}

internal data class InverseOperation(
    val id: String, val kind: String, val at: Long, val expiresAt: Long, val state: String,
    val beforeItems: List<Item>, val afterItems: List<Item>,
    val beforeCourses: List<Course>, val afterCourses: List<Course>,
    val beforePreferences: CoursePreferenceSnapshot?, val afterPreferences: CoursePreferenceSnapshot?,
    val taskAction: String = ""
) {
    fun record() = OperationRecord(id,kind,at,state,encode())
    fun encode(): String = StrictJson.write(S7Json.obj(
        "taskAction" to StrictJson.Value.Str(taskAction),
        "version" to StrictJson.Value.Num(1), "id" to StrictJson.Value.Str(id), "kind" to StrictJson.Value.Str(kind),
        "at" to StrictJson.Value.Num(at), "expiresAt" to StrictJson.Value.Num(expiresAt),"state" to StrictJson.Value.Str(state),
        "beforeItems" to StrictJson.Value.Arr(beforeItems.map { StrictJson.Value.Str(RepeatClosureCodec.encodeItem(it)) }),
        "afterItems" to StrictJson.Value.Arr(afterItems.map { StrictJson.Value.Str(RepeatClosureCodec.encodeItem(it)) }),
        "beforeCourses" to StrictJson.Value.Arr(beforeCourses.map { StrictJson.Value.Str(CourseSnapshotCodec.encode(it)) }),
        "afterCourses" to StrictJson.Value.Arr(afterCourses.map { StrictJson.Value.Str(CourseSnapshotCodec.encode(it)) }),
        "beforePreferences" to (beforePreferences?.let { StrictJson.Value.Str(it.encode()) } ?: StrictJson.Value.Null),
        "afterPreferences" to (afterPreferences?.let { StrictJson.Value.Str(it.encode()) } ?: StrictJson.Value.Null)
    ))
    companion object {
        fun decode(raw: String): InverseOperation {
            val o=S7Json.fields(S7Json.parse(raw),setOf("version","id","kind","at","expiresAt","state","beforeItems","afterItems","beforeCourses","afterCourses","beforePreferences","afterPreferences","taskAction"))
            require(S7Json.long(o,"version")==1L)
            fun items(k:String)=S7Json.array(o,k).map { RepeatClosureCodec.decodeItem((it as StrictJson.Value.Str).value) }
            fun courses(k:String)=S7Json.array(o,k).map { requireNotNull(CourseSnapshotCodec.decode((it as StrictJson.Value.Str).value)) }
            fun prefs(k:String)=when(val x=o.getValue(k)) { StrictJson.Value.Null->null; is StrictJson.Value.Str->CoursePreferenceSnapshot.decode(x.value); else->error("invalid preference snapshot") }
            val c=InverseOperation(S7Json.text(o,"id"),S7Json.text(o,"kind"),S7Json.long(o,"at"),S7Json.long(o,"expiresAt"),S7Json.text(o,"state"),items("beforeItems"),items("afterItems"),courses("beforeCourses"),courses("afterCourses"),prefs("beforePreferences"),prefs("afterPreferences"),S7Json.text(o,"taskAction"))
            require(c.id.isNotBlank() && c.expiresAt==Stage7Recovery.expiry(c.at) && c.state in setOf("active","restoring","restored"))
            listOf(c.beforeItems.map { it.id },c.afterItems.map { it.id },c.beforeCourses.map { it.id },c.afterCourses.map { it.id }).forEach { ids -> require(ids.all { it>0 } && ids.distinct().size==ids.size) }
            if(c.kind=="task_batch_inverse") {
                require(c.taskAction in setOf("COMPLETE","MOVE_DATE","CLEAR_TIME"))
                require(c.beforeItems.isNotEmpty() && c.beforeItems.map { it.id }==c.afterItems.map { it.id } && c.beforeCourses.isEmpty() && c.afterCourses.isEmpty() && c.beforePreferences==null && c.afterPreferences==null && c.state!="restoring")
            } else {
                require(c.taskAction.isEmpty())
                require(c.kind in setOf("course_merge_inverse","course_split_inverse") && c.beforeItems.isEmpty() && c.afterItems.isEmpty() && c.beforeCourses.isNotEmpty() && c.afterCourses.isNotEmpty())
                val ids=(c.beforeCourses+c.afterCourses).mapTo(mutableSetOf()) { it.id }
                require(c.beforePreferences?.overrides?.keys==ids && c.afterPreferences?.overrides?.keys==ids)
            }
            return c
        }
    }
}

internal object Stage7Inverse {
    fun applyTaskBatch(repo: CoreDataRepository, result: TodoBatchResult, now: Long): Pair<CoreDataWriteResult,String?> {
        var id: String?=null
        val outcome=Stage7Recovery.execute(repo) { before ->
            require(before.items==result.before && result.events.isNotEmpty() && result.action!=TodoBatchAction.DELETE)
            val next=RepeatActions.refresh(result.items,now,before.courses)
            val ids=result.affectedBefore.mapTo(mutableSetOf()) { it.id }
            val c=InverseOperation(UUID.randomUUID().toString(),"task_batch_inverse",now,Stage7Recovery.expiry(now),"active",result.affectedBefore,next.items.filter { it.id in ids },emptyList(),emptyList(),null,null,result.action.name)
            InverseOperation.decode(c.encode()); id=c.id
            before.copy(items=next.items,taskEvents=(result.events+next.events).fold(before.taskEvents,TaskHistory::append),operationRecords=before.operationRecords+c.record())
        }
        return outcome to id
    }
    fun commitCourseEdit(context: Context,repo: CoreDataRepository,current: List<Course>,updated: List<Course>,kind:String,
                         beforePrefs: CoursePreferenceSnapshot,afterPrefs: CoursePreferenceSnapshot,now:Long=System.currentTimeMillis()): CoreDataWriteResult = Stage7Recovery.execute(repo) { before ->
        require(before.courses==current)
        val ids=beforePrefs.overrides.keys
        require(ids.isNotEmpty() && beforePrefs.overrides.keys==afterPrefs.overrides.keys)
        val c=InverseOperation(UUID.randomUUID().toString(),kind,now,Stage7Recovery.expiry(now),"active",emptyList(),emptyList(),current.filter { it.id in ids },updated.filter { it.id in ids },beforePrefs,afterPrefs)
        InverseOperation.decode(c.encode())
        before.copy(courses=updated,operationRecords=before.operationRecords+c.record())
    }
    fun undo(context: Context,repo:CoreDataRepository,id:String,now:Long=System.currentTimeMillis()): CoreDataWriteResult = repo.withCourseWriteLock {
        if(now<=0) return@withCourseWriteLock CoreDataWriteResult(CoreDataWriteStatus.INVALID_INPUT,"invalid undo time")
        var courseUndo=false
        val result=Stage7Recovery.execute(repo) { before ->
            val r=before.operationRecords.single { it.operationId==id }; val c=InverseOperation.decode(r.payload)
            require(c.state=="active" && now<c.expiresAt) { "operation expired or already undone" }
            if(c.kind=="task_batch_inverse") {
                require(c.afterItems.all { after -> before.items.singleOrNull { it.id==after.id } == after }) { "batch member changed" }
                val originals=c.beforeItems.associateBy { it.id }
                val restored=before.items.map { originals[it.id] ?: it }
                val refreshed=RepeatActions.refresh(restored,now,before.courses)
                val events=c.beforeItems.map { TaskRecorder.event(if(c.taskAction=="COMPLETE") TaskEventType.TASK_UNCOMPLETED else if(it.scheduledAt==null) TaskEventType.TASK_UNSCHEDULED else TaskEventType.TASK_SCHEDULED,it.id,it.title,scheduledAt=it.scheduledAt ?: 0L,extra="撤回整批操作",at=now) }
                before.copy(items=refreshed.items,taskEvents=(events+refreshed.events).fold(before.taskEvents,TaskHistory::append),operationRecords=before.operationRecords.map { if(it.operationId==id) c.copy(state="restored").record() else it })
            } else {
                val union=(c.beforeCourses+c.afterCourses).mapTo(mutableSetOf()) { it.id }
                require(before.courses.filter { it.id in union }==c.afterCourses) { "course post-state changed" }
                require(!PrototypeStore(context).hasPendingCourseEditJournal()) { "course edit is still pending" }
                val actual=CoursePreferenceSnapshot.capture(context,union)
                require(actual.overrides==c.afterPreferences!!.overrides && actual.locations==c.afterPreferences.locations) { "course preferences changed" }
                val candidate=before.courses.filterNot { it.id in union }+c.beforeCourses
                candidate.toCourseParentEntities() // Validate restored identity/parent ownership before the commit.
                courseUndo=true
                before.copy(courses=candidate,operationRecords=before.operationRecords.map { if(it.operationId==id) c.copy(state="restoring").record() else it })
            }
        }
        if(result.applied && courseUndo) resume(context,repo,id,now) else result
    }
    fun resume(context:Context,repo:CoreDataRepository,id:String,now:Long=System.currentTimeMillis()):CoreDataWriteResult = repo.withCourseWriteLock {
        if(StorageProtection.readOnly || Stage7CommitGuard.uncertain || CourseRecoveryWriteGuard.uncertainReason()!=null) return@withCourseWriteLock CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED,"previous disk write unconfirmed")
        try {
            val before=(repo.read() as CoreDataReadResult.Ready).snapshot
            val c=InverseOperation.decode(before.operationRecords.single { it.operationId==id }.payload)
            require(c.state=="restoring")
            val union=(c.beforeCourses+c.afterCourses).mapTo(mutableSetOf()) { it.id }
            require(before.courses.filter { it.id in union }==c.beforeCourses)
            val target=c.beforePreferences!!
            val actual=CoursePreferenceSnapshot.capture(context,union)
            require(actual.overrides==target.overrides || actual.overrides==c.afterPreferences!!.overrides) { "reminder preferences changed during undo" }
            require(actual.locations==target.locations || actual.locations==c.afterPreferences!!.locations) { "locations changed during undo" }
            CourseReminders.withDeliveryLock {
            val rem=context.getSharedPreferences("course_reminder_settings",Context.MODE_PRIVATE)
            val edit=rem.edit()
            union.forEach { meeting ->
                target.overrides[meeting]?.let { edit.putBoolean("meeting_$meeting",it) } ?: edit.remove("meeting_$meeting")
                val stored=CourseRecoveryPreferences.watermark(context,meeting)
                require(stored !is CourseRecoveryWatermarkRead.Corrupt)
                val at=maxOf((stored as? CourseRecoveryWatermarkRead.Value)?.at ?: 0,target.watermarks[meeting] ?: 0)
                if(at>0) edit.putLong("delivered_$meeting",at)
            }
            if(!edit.commit()) { Stage7CommitGuard.markUncertain(); error("reminder preference write unconfirmed") }
            }
            val loc=context.getSharedPreferences("course_location_overrides",Context.MODE_PRIVATE)
            val placeEdit=loc.edit()
            // Use the existing boundary's exact key format, and touch only recorded meeting ids.
            union.forEach { meeting ->
                loc.all.keys.filter { it.startsWith("${meeting}_") }.forEach(placeEdit::remove)
                target.locations[meeting].orEmpty().forEach { (day,place) -> placeEdit.putString("${meeting}_$day",place) }
            }
            if(!placeEdit.commit()) { Stage7CommitGuard.markUncertain(); error("location preference write unconfirmed") }
            val store=PrototypeStore(context)
            CourseReminders.sync(context,c.afterCourses,c.beforeCourses,store.loadCoursePeriodTable(),CourseReminders.load(context))
            repo.commitRecovery(before,before.copy(operationRecords=before.operationRecords.map { if(it.operationId==id)c.copy(state="restored").record() else it }))
        } catch(e:Exception) { CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED,e.message ?: "course undo follow-up pending") }
    }
}
