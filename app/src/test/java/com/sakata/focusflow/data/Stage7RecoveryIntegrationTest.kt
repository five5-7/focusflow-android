package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class Stage7RecoveryIntegrationTest {
    private lateinit var context:Context
    private val databases=mutableListOf<FocusFlowDatabase>()
    private val now=1_800_000_000_000L
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        reset()
    }
    private fun reset() {
        listOf("focusflow","course_reminder_settings","course_location_overrides","course_merge_journal","course_split_journal").forEach {
            check(context.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit())
        }
        Stage7CommitGuard.confirm();CourseRecoveryWriteGuard.reset()
    }
    @After fun cleanup() { databases.forEach { it.close() };reset() }
    private fun legacy()=LegacyCoreDataRepository(PrototypeStore(context))
    private fun snapshot(r:CoreDataRepository)=(r.read() as CoreDataReadResult.Ready).snapshot
    private fun seed(items:List<Item> = emptyList(),goals:List<Goal> = emptyList(),courses:List<Course> = emptyList()) {
        val r=legacy();check(r.replaceTasks(items,emptyList()).applied);check(r.replacePlans(goals,emptyList()).applied);check(r.replaceCourses(courses,emptyList()).applied)
    }
    private fun room():CoreDataRepository {
        val db=Room.inMemoryDatabaseBuilder(context,FocusFlowDatabase::class.java).allowMainThreadQueries().build();databases+=db
        val report=LegacyDataImporter(LegacyPreferencesReader.fromContext(context),RoomLegacyMigrationStore(db),SharedPreferencesMigrationMarker(context)).importIfNeeded()
        check(report.status in setOf(MigrationStatus.IMPORTED,MigrationStatus.INITIALIZED_EMPTY)) {report.toString()}
        return RoomCoreDataRepository(RoomCoreDataReadRepository(DatabaseRoomCoreDataSource(db)),RoomCoreDataWriteRepository(DatabaseRoomCoreDataWriteStore(db)))
    }
    private fun both(seed:()->Unit,assertions:(CoreDataRepository)->Unit) {
        for(useRoom in listOf(false,true)) { reset();seed();assertions(if(useRoom)room() else legacy()) }
    }
    private fun task(id:Long=11)=Item(id=id,title="任务$id",kind="任务",detail="尚未安排具体时间",userNote="完整备注")
    private fun goal()=Goal(id=71,title="计划",weeklyTarget=2,durationMinutes=30,sourceNotes="完整来源")
    private fun course(id:Long=41,from:Long=100,to:Long=200)=Course("实验",1,1,2,"A",CampusZone.entries.first(),needsConfirmation=false,effectiveFromEpochDay=from,effectiveUntilEpochDay=to,id=id)

    @Test fun `repeat delete restore persists records in both sources and never rewrites history`() = both({
        val created=RepeatActions.create(emptyList(),"重复","daily",TaskHistory.dayStartOf(now),at=now)
        seed(created.items)
    }) { r ->
        val before=snapshot(r);val template=before.items.single {it.kind=="重复模板"}
        assertTrue(Stage7Recovery.deleteRepeat(r,template,now).applied)
        val deleted=snapshot(r);assertEquals("active",deleted.operationRecords.single().state)
        assertTrue(deleted.items.any {it.id==template.id && it.kind=="回收站"})
        val id=deleted.operationRecords.single().operationId
        assertTrue(Stage7Recovery.restore(r,id,now+1).applied)
        val restored=snapshot(r)
        assertEquals(template,restored.items.single {it.id==template.id})
        assertEquals("restored",restored.operationRecords.single().state)
        assertTrue(restored.taskEvents.containsAll(deleted.taskEvents))
        assertFalse(Stage7Recovery.restore(r,id,now+2).applied)
        assertEquals(restored,snapshot(r))
    }
    @Test fun `repeat member edits reject whole restore and expiry cleans only untouched tombstone`() = both({
        seed(RepeatActions.create(emptyList(),"规则","daily",TaskHistory.dayStartOf(now),at=now).items)
    }) { r ->
        val template=snapshot(r).items.single {it.kind=="重复模板"};assertTrue(Stage7Recovery.deleteRepeat(r,template,now).applied)
        val d=snapshot(r);val history=d.items.first {it.repeatTemplateId==template.id}
        assertTrue(r.replaceTasks(d.items.map {if(it.id==history.id) it.copy(userNote="用户修改") else it},d.items).applied)
        val current=snapshot(r);val id=current.operationRecords.single().operationId
        assertFalse(Stage7Recovery.restore(r,id,now+1).applied);assertEquals(current,snapshot(r))
        assertTrue(Stage7Recovery.purge(r,now=Stage7Recovery.expiry(now)).applied)
        assertTrue(snapshot(r).operationRecords.isEmpty());assertTrue(snapshot(r).items.none {it.id==template.id})
        assertEquals("用户修改",snapshot(r).items.single {it.id==history.id}.userNote)
        assertEquals(current.taskEvents,snapshot(r).taskEvents)
    }
    @Test fun `plan body edits survive restore while changed binding rejects it`() = both({seed(listOf(task().copy(goalId=71)),listOf(goal()))}) { r ->
        assertTrue(Stage7Recovery.deletePlan(r,snapshot(r).goals.single(),now=now).applied)
        val d=snapshot(r);assertTrue(r.replaceTasks(d.items.map {it.copy(userNote="后来写的备注")},d.items).applied)
        assertTrue(Stage7Recovery.restore(r,d.operationRecords.single().operationId,now+1).applied)
        assertEquals("后来写的备注",snapshot(r).items.single().userNote)
        assertEquals("完整来源",snapshot(r).goals.single().sourceNotes)
        assertTrue(Stage7Recovery.deletePlan(r,snapshot(r).goals.single(),now=now+2).applied)
        val d2=snapshot(r);assertTrue(r.replaceTasks(d2.items.map {it.copy(goalId=null)},d2.items).applied)
        val current=snapshot(r);assertFalse(Stage7Recovery.restore(r,current.operationRecords.last().operationId,now+3).applied);assertEquals(current,snapshot(r))
    }
    @Test fun `selected plan tasks restore atomically with plan and unselected task edits survive`() = both({seed(listOf(task(11).copy(goalId=71),task(12).copy(goalId=71)),listOf(goal()))}) { r ->
        val original=snapshot(r)
        assertTrue(Stage7Recovery.deletePlan(r,original.goals.single(),setOf(11),now).applied)
        val d=snapshot(r);assertEquals("回收站",d.items.single {it.id==11L}.kind);assertTrue(d.goals.isEmpty())
        assertEquals(setOf(11L),RecoveryEntries.ownedItemIds(d.operationRecords))
        assertTrue(r.replaceTasks(d.items.map {if(it.id==12L) it.copy(title="后续编辑") else it},d.items).applied)
        assertTrue(Stage7Recovery.restore(r,d.operationRecords.single().operationId,now+1).applied)
        assertEquals(original.items.single {it.id==11L},snapshot(r).items.single {it.id==11L})
        assertEquals("后续编辑",snapshot(r).items.single {it.id==12L}.title)
        assertEquals(original.goals,snapshot(r).goals)
        assertEquals("restored",snapshot(r).trashGroups.single().state)
    }
    @Test fun `plan occupied identity and selected tombstone mutation reject with no partial writes`() = both({seed(listOf(task().copy(goalId=71)),listOf(goal()))}) { r ->
        assertTrue(Stage7Recovery.deletePlan(r,snapshot(r).goals.single(),setOf(11),now).applied)
        val d=snapshot(r);assertTrue(r.replacePlans(listOf(goal().copy(title="新计划")),emptyList()).applied)
        val occupied=snapshot(r);assertFalse(Stage7Recovery.restore(r,d.operationRecords.single().operationId,now+1).applied);assertEquals(occupied,snapshot(r))
        assertTrue(r.replacePlans(emptyList(),occupied.goals).applied)
        val c=snapshot(r);assertTrue(r.replaceTasks(c.items.map {it.copy(userNote="删除后编辑")},c.items).applied)
        val changed=snapshot(r);assertFalse(Stage7Recovery.restore(r,d.operationRecords.single().operationId,now+2).applied);assertEquals(changed,snapshot(r))
    }
    @Test fun `batch complete reschedule and clear undo persists exact members and appropriate events`() {
        for(action in listOf(TodoBatchAction.COMPLETE,TodoBatchAction.MOVE_DATE,TodoBatchAction.CLEAR_TIME)) both({seed(listOf(task().copy(scheduledAt=now+86400000,dayOnly=true),task(12)))}) { r ->
            val before=snapshot(r);val result=TodoBatchActions.apply(before.items,setOf(11),action,now,targetDay=now+2*86400000)
            val (saved,id)=Stage7Inverse.applyTaskBatch(r,result,now);assertTrue(saved.toString(),saved.applied);assertNotNull(id)
            val after=snapshot(r);assertEquals(1,after.operationRecords.size)
            assertTrue(r.replaceTasks(after.items.map {if(it.id==12L) it.copy(title="不相关编辑") else it},after.items).applied)
            assertTrue(Stage7Inverse.undo(context,r,id!!,now+1).applied)
            assertEquals(before.items.single {it.id==11L},snapshot(r).items.single {it.id==11L})
            assertEquals("不相关编辑",snapshot(r).items.single {it.id==12L}.title)
            assertEquals(if(action==TodoBatchAction.COMPLETE)TaskEventType.TASK_UNCOMPLETED else TaskEventType.TASK_SCHEDULED,snapshot(r).taskEvents.last().type)
            assertEquals("restored",snapshot(r).operationRecords.single().state)
            assertFalse(Stage7Inverse.undo(context,r,id,now+2).applied)
        }
    }
    @Test fun `changed batch member and exact expiry refuse whole undo`() = both({seed(listOf(task(11),task(12)))}) {r->
        val before=snapshot(r);val (_,id)=Stage7Inverse.applyTaskBatch(r,TodoBatchActions.apply(before.items,setOf(11,12),TodoBatchAction.COMPLETE,now),now)
        val after=snapshot(r);assertTrue(r.replaceTasks(after.items.map {if(it.id==11L)it.copy(userNote="changed") else it},after.items).applied)
        val changed=snapshot(r);assertFalse(Stage7Inverse.undo(context,r,id!!,now+1).applied);assertEquals(changed,snapshot(r))
        assertFalse(Stage7Inverse.undo(context,r,id,Stage7Recovery.expiry(now)).applied);assertEquals(changed,snapshot(r))
    }
    @Test fun `stale commit cannot replace records or core snapshot`() = both({seed(listOf(task()),listOf(goal()))}) {r->
        val before=snapshot(r);assertTrue(r.replacePlans(listOf(goal().copy(title="changed")),before.goals).applied)
        val current=snapshot(r);assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET,r.commitRecovery(before,before.copy(goals=emptyList())).status);assertEquals(current,snapshot(r))
    }
    @Test fun `invalid recovery record is rejected before write and does not poison subsequent operations`() = both({seed(listOf(task()),listOf(goal()))}) {r->
        val before=snapshot(r);assertFalse(r.commitRecovery(before,before.copy(operationRecords=listOf(OperationRecord("x","unknown",now,"active","{}")))).applied)
        assertEquals(before,snapshot(r));assertFalse(Stage7CommitGuard.uncertain)
        assertTrue(Stage7Recovery.deletePlan(r,before.goals.single(),now=now).applied)
    }
    @Test fun `legacy recovery records migrate exactly and Room stays independent of original preferences`() {
        seed(listOf(task().copy(goalId=71)),listOf(goal()))
        assertTrue(Stage7Recovery.deletePlan(legacy(),goal(),now=now).applied)
        val raw=context.getSharedPreferences("focusflow",Context.MODE_PRIVATE).getString(Stage7RecordsCodec.KEY,null)
        val records=snapshot(legacy()).operationRecords
        val r=room();assertEquals(records,snapshot(r).operationRecords)
        assertTrue(Stage7Recovery.restore(r,records.single().operationId,now+1).applied)
        assertEquals(raw,context.getSharedPreferences("focusflow",Context.MODE_PRIVATE).getString(Stage7RecordsCodec.KEY,null))
        assertTrue(snapshot(legacy()).goals.isEmpty());assertEquals(goal(),snapshot(r).goals.single())
    }
    @Test fun `corrupt recovery payload blocks mutation and import without rewriting raw`() {
        seed(listOf(task()),listOf(goal()))
        val prefs=context.getSharedPreferences("focusflow",Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(Stage7RecordsCodec.KEY,"{truncated").commit())
        assertFalse(Stage7Recovery.deletePlan(legacy(),goal(),now=now).applied)
        assertEquals("{truncated",prefs.getString(Stage7RecordsCodec.KEY,null))
        val result=LegacyPreferencesReader.fromContext(context).read();assertTrue(result is LegacyReadResult.Failure)
        assertEquals(Stage7RecordsCodec.KEY,(result as LegacyReadResult.Failure).domain)
    }
    @Test fun `course recovery core and follow-up work in Room using existing schema`() = both({seed(courses=listOf(course()))}) {r->
        assertTrue(CourseReminders.setOverride(context,41,true));assertTrue(CourseLocationOverrides.set(context,41,150,"地点"));assertTrue(CourseReminders.markNotified(context,41,9000))
        val deleted=CourseRecoveryOperations.deleteCourses(context,r,CourseRecoveryScope.MEETING,setOf(41),"course-op",now)
        assertTrue(deleted.toString(),deleted is CourseDeletionOutcome.Applied)
        val group=(deleted as CourseDeletionOutcome.Applied).group
        assertTrue(snapshot(r).courses.isEmpty())
        assertTrue(CourseRecoveryOperations.restoreAndComplete(context,r,group.groupId,now+1) is CourseRestoreCompletionOutcome.Completed)
        assertEquals(listOf(course()),snapshot(r).courses)
        assertEquals(CourseRecoveryState.RESTORED,(CourseRecoveryOperations.readGroups(r) as CourseRecoveryGroupsRead.Ready).groups.single().state)
        assertEquals(true,CourseReminders.load(context).overrides[41L]);assertEquals("地点",CourseLocationOverrides.get(context,41,150))
        assertFalse(CourseReminders.markNotified(context,41,9000))
    }
    @Test fun `merge undo restores separate identities and preferences without regressing watermarks`() = both({seed(courses=listOf(course(),course(42,201,300)))}) {r->
        assertTrue(CourseReminders.setOverride(context,42,true));assertTrue(CourseLocationOverrides.set(context,41,150,"旧"));assertTrue(CourseLocationOverrides.set(context,42,250,"新"));assertTrue(CourseReminders.markNotified(context,42,9000))
        val before=snapshot(r).courses;val plan=CourseMergeOperation.preview(context,before,42) as CourseEditPlans.CourseMergePlan.Applied
        assertEquals(CourseMergeOperation.Outcome.APPLIED,CourseMergeOperation.apply(context,r,before,setOf(41,42),42,plan))
        val id=snapshot(r).operationRecords.single().operationId
        assertTrue(CourseReminders.markNotified(context,41,10000))
        assertTrue(Stage7Inverse.undo(context,r,id,snapshot(r).operationRecords.single().recordedAt+1).applied)
        assertEquals(before,snapshot(r).courses);assertNull(CourseReminders.load(context).overrides[41L]);assertEquals(true,CourseReminders.load(context).overrides[42L])
        assertEquals(mapOf(150L to "旧"),CourseLocationOverrides.snapshot(context,41));assertEquals(mapOf(250L to "新"),CourseLocationOverrides.snapshot(context,42))
        assertFalse(CourseReminders.markNotified(context,41,10000));assertFalse(CourseReminders.markNotified(context,42,9000))
        assertEquals("restored",snapshot(r).operationRecords.single().state)
    }
    @Test fun `split undo restores original and removes successor without losing dated overrides`() = both({seed(courses=listOf(course()))}) {r->
        assertTrue(CourseReminders.setOverride(context,41,false));assertTrue(CourseLocationOverrides.set(context,41,120,"过去"));assertTrue(CourseLocationOverrides.set(context,41,170,"未来"))
        val before=snapshot(r).courses;assertEquals(CourseSplitOperation.Outcome.APPLIED,CourseSplitOperation.apply(context,r,before,before.single(),before.single().copy(building="B"),150))
        val changed=snapshot(r);assertEquals(2,changed.courses.size)
        assertTrue(Stage7Inverse.undo(context,r,changed.operationRecords.single().operationId,changed.operationRecords.single().recordedAt+1).applied)
        assertEquals(before,snapshot(r).courses);assertEquals(mapOf(120L to "过去",170L to "未来"),CourseLocationOverrides.snapshot(context,41))
        val successor=changed.courses.single {it.id!=41L};assertTrue(CourseLocationOverrides.snapshot(context,successor.id).isEmpty());assertNull(CourseReminders.load(context).overrides[successor.id])
    }
    @Test fun `course inverse conflict leaves complete state unchanged`() = both({seed(courses=listOf(course(),course(42,201,300)))}) {r->
        val before=snapshot(r).courses;val p=CourseMergeOperation.preview(context,before,42) as CourseEditPlans.CourseMergePlan.Applied
        assertEquals(CourseMergeOperation.Outcome.APPLIED,CourseMergeOperation.apply(context,r,before,setOf(41,42),42,p))
        assertTrue(CourseLocationOverrides.set(context,41,150,"后改地点"))
        val changed=snapshot(r);assertFalse(Stage7Inverse.undo(context,r,changed.operationRecords.single().operationId,changed.operationRecords.single().recordedAt+1).applied)
        assertEquals(changed,snapshot(r));assertEquals("后改地点",CourseLocationOverrides.get(context,41,150))
    }
    @Test fun `manual course clear protects restoring groups and orphan collection respects retained records`() = both({seed(courses=listOf(course()))}) {r->
        assertTrue(CourseReminders.setOverride(context,41,true));assertTrue(CourseLocationOverrides.set(context,41,150,"保留"))
        val d=CourseRecoveryOperations.deleteCourses(context,r,CourseRecoveryScope.MEETING,setOf(41),"manual",now) as CourseDeletionOutcome.Applied
        assertTrue(Stage7CourseMaintenance.collect(context,r).applied);assertEquals("保留",CourseLocationOverrides.get(context,41,150))
        assertTrue(r.courseRecoveryStore.purgeSelectedGroups(setOf(d.group.groupId)) is CoursePurgeOutcome.Applied)
        assertTrue(Stage7CourseMaintenance.collect(context,r).applied);assertNull(CourseReminders.load(context).overrides[41L]);assertNull(CourseLocationOverrides.get(context,41,150))
    }
    @Test fun `uncertain write guard prevents recovery and preference side effects even on rebuilt store`() {
        seed(listOf(task()),listOf(goal()),listOf(course()))
        Stage7CommitGuard.markUncertain()
        val before=snapshot(legacy())
        assertFalse(Stage7Recovery.deletePlan(legacy(),goal(),now=now).applied)
        assertFalse(Stage7CourseMaintenance.collect(context,legacy()).applied)
        assertEquals(before,snapshot(legacy()));assertTrue(Stage7CommitGuard.uncertain)
    }
    @Test fun `strict record envelope refuses missing fields fractions duplicate identities and trailing input`() {
        val c=PlanBindingClosure("p",now,Stage7Recovery.expiry(now),"active",goal(),emptyMap())
        val valid=Stage7RecordsCodec.encode(listOf(c.record()));assertEquals(listOf(c.record()),Stage7RecordsCodec.decode(valid))
        listOf(valid+" true",valid.replace("\"version\":1","\"version\":1.5"),valid.replace("\"version\":1,",""),"[${valid.drop(1).dropLast(1)},${valid.drop(1).dropLast(1)}]").forEach {raw->assertTrue(runCatching { Stage7RecordsCodec.decode(raw) }.isFailure)}
    }
    @Test fun `Room operation insertion failure rolls back data events groups and recovery payload together`() {
        seed(listOf(task().copy(goalId=71)),listOf(goal()))
        val r=room();val before=snapshot(r)
        databases.last().openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_recovery BEFORE INSERT ON operation_records BEGIN SELECT RAISE(ABORT, 'injected record failure'); END")
        assertFalse(Stage7Recovery.deletePlan(r,before.goals.single(),setOf(11),now).applied)
        assertEquals(before,snapshot(r))
        assertTrue(databases.last().operationRecordDao().all().isEmpty())
        assertTrue(databases.last().trashGroupDao().all().isEmpty())
    }
    @Test fun `file Room database reopen retains complete inverse and supports later undo`() {
        seed(listOf(task(11),task(12)))
        val name="stage7-recovery-reopen.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,FocusFlowDatabase::class.java,name).allowMainThreadQueries().build().also {databases+=it}
        fun repository(db:FocusFlowDatabase)=RoomCoreDataRepository(RoomCoreDataReadRepository(DatabaseRoomCoreDataSource(db)),RoomCoreDataWriteRepository(DatabaseRoomCoreDataWriteStore(db)))
        try {
            val db=open()
            val report=LegacyDataImporter(LegacyPreferencesReader.fromContext(context),RoomLegacyMigrationStore(db),SharedPreferencesMigrationMarker(context)).importIfNeeded()
            assertEquals(MigrationStatus.IMPORTED,report.status)
            val r=repository(db);val before=snapshot(r)
            val (written,id)=Stage7Inverse.applyTaskBatch(r,TodoBatchActions.apply(before.items,setOf(11,12),TodoBatchAction.COMPLETE,now),now)
            assertTrue(written.applied);val after=snapshot(r);db.close()
            val reopened=repository(open());assertEquals(after,snapshot(reopened))
            assertTrue(Stage7Inverse.undo(context,reopened,id!!,now+1).applied);assertEquals(before.items,snapshot(reopened).items)
        } finally {databases.filter {it.isOpen}.forEach {it.close()};context.deleteDatabase(name)}
    }
    @Test fun `pending inverse resumes after expiry and cannot be cleared or have unrelated preferences overwritten`() = both({seed(courses=listOf(course(),course(42,201,300)))}) {r->
        val original=snapshot(r).courses
        assertTrue(CourseLocationOverrides.set(context,42,250,"原地点"))
        val plan=CourseMergeOperation.preview(context,original,42) as CourseEditPlans.CourseMergePlan.Applied
        assertEquals(CourseMergeOperation.Outcome.APPLIED,CourseMergeOperation.apply(context,r,original,setOf(41,42),42,plan))
        val merged=snapshot(r);val record=merged.operationRecords.single();val c=InverseOperation.decode(record.payload)
        // Persist the same core checkpoint that an interrupted inverse has, without replaying prefs.
        assertTrue(r.commitRecovery(merged,merged.copy(courses=original,operationRecords=listOf(c.copy(state="restoring").record()))).applied)
        val checkpoint=snapshot(r)
        assertFalse(Stage7Recovery.purge(r,setOf(record.operationId),c.expiresAt+1).applied)
        assertEquals(checkpoint,snapshot(r))
        assertTrue(CourseLocationOverrides.set(context,41,150,"用户在续办前新增"))
        assertFalse(Stage7Inverse.resume(context,r,record.operationId,c.expiresAt+1).applied)
        assertEquals(checkpoint,snapshot(r));assertEquals("用户在续办前新增",CourseLocationOverrides.get(context,41,150))
        assertTrue(CourseLocationOverrides.set(context,41,150,null))
        assertTrue(Stage7Inverse.resume(context,r,record.operationId,c.expiresAt+1).applied)
        assertEquals("restored",snapshot(r).operationRecords.single().state)
        assertEquals("原地点",CourseLocationOverrides.get(context,42,250))
    }
    @Test fun `manual purge refuses a course group whose preferences still need follow-up`() = both({seed(courses=listOf(course()))}) {r->
        val d=CourseRecoveryOperations.deleteCourses(context,r,CourseRecoveryScope.MEETING,setOf(41),"pending",now) as CourseDeletionOutcome.Applied
        assertTrue(CourseRecoveryOperations.restoreGroup(r,d.group.groupId,now+1) is CourseRestoreOutcome.CoreCommitted)
        assertTrue(r.courseRecoveryStore.purgeSelectedGroups(setOf(d.group.groupId)) is CoursePurgeOutcome.Rejected)
        assertEquals(CourseRecoveryState.RESTORING,(CourseRecoveryOperations.readGroups(r) as CourseRecoveryGroupsRead.Ready).groups.single().state)
    }
    @Test fun `shadow check detects changed recovery payload and imported course groups`() {
        seed(courses=listOf(course()))
        val d=CourseRecoveryOperations.deleteCourses(context,legacy(),CourseRecoveryScope.MEETING,setOf(41),"shadow",now) as CourseDeletionOutcome.Applied
        val before=snapshot(legacy());val r=room()
        assertEquals(CoreDataConsistencyStatus.CONSISTENT,CoreDataConsistencyChecker.compare(before,r.read()).status)
        assertTrue(CourseRecoveryOperations.restoreGroup(r,d.group.groupId,now+1) is CourseRestoreOutcome.CoreCommitted)
        val differences=CoreDataConsistencyChecker.compare(before,r.read()).differences
        assertTrue(differences.any {it.domain=="operation_records"})
    }

    @Test fun `clearing active plan closure removes only its tombstones and reconciles ordinary trash groups`() = both({seed(listOf(task().copy(goalId=71),task(12)),listOf(goal()))}) {r->
        val original=snapshot(r);assertTrue(Stage7Recovery.deletePlan(r,original.goals.single(),setOf(11),now).applied)
        val deleted=snapshot(r);assertEquals(1,deleted.trashGroups.size)
        assertTrue(Stage7Recovery.purge(r,setOf(deleted.operationRecords.single().operationId),now+1).applied)
        val cleared=snapshot(r);assertTrue(cleared.operationRecords.isEmpty());assertTrue(cleared.trashGroups.isEmpty())
        assertEquals(listOf(original.items.single {it.id==12L}),cleared.items);assertEquals(deleted.taskEvents,cleared.taskEvents)
    }
    @Test fun `watermark confirmation writes equal and newer values without moving backwards or coercing corruption`() {
        assertTrue(CourseReminders.markNotified(context,41,10000))
        assertTrue(CourseReminders.confirmWatermark(context,41,9000))
        assertFalse(CourseReminders.markNotified(context,41,10000))
        val prefs=context.getSharedPreferences("course_reminder_settings",Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("delivered_42","10000").commit())
        assertFalse(CourseReminders.confirmWatermark(context,42,20000));assertEquals("10000",prefs.getString("delivered_42",null))
    }

}
