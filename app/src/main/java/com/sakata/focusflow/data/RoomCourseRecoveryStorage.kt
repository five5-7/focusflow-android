package com.sakata.focusflow.data

import com.sakata.focusflow.Course

/** Course groups use versioned payload rows in the existing operation_records table. */
internal class RoomCourseRecoveryStorage(
    private val repo: RoomCoreDataRepository,
    private val writer: RoomCoreDataWriteRepository
) : CourseRecoveryStorage {
    override fun isStorageReadOnly() = false
    override fun <T> withCourseWriteLock(block: () -> T): T = writer.withRecoveryLock(block)
    private fun snapshot() = (repo.read() as? CoreDataReadResult.Ready)?.snapshot ?: error("Room data unavailable")
    override fun readCourses() = snapshot().courses
    override fun hasPendingCourseEditJournal() = repo.hasPendingCourseEdits()
    override fun loadCourseRecoveryGroups(): CourseRecoveryGroupsRead = try {
        CourseRecoveryGroupsRead.Ready(snapshot().operationRecords.filter { it.kind=="course_recovery" }.map {
            (CourseRecoveryCodec.decode(it.payload) as CourseRecoveryLoad.Ready).groups.single()
        })
    } catch(e:Exception) { CourseRecoveryGroupsRead.Invalid(e.message ?: "invalid Room course groups",null) }
    override fun commitCoursesAndRecoveryGroups(courses:List<Course>,groups:List<CourseRecoveryGroup>,expectedCourses:List<Course>,expectedGroups:List<CourseRecoveryGroup>):CourseRecoveryCommit {
        val before=snapshot()
        if(before.courses!=expectedCourses || loadCourseRecoveryGroups()!=CourseRecoveryGroupsRead.Ready(expectedGroups)) return CourseRecoveryCommit.Rejected("course/group snapshot changed")
        return commit(before,courses,groups)
    }
    override fun commitCourseRecoveryGroups(groups:List<CourseRecoveryGroup>,expectedGroups:List<CourseRecoveryGroup>):CourseRecoveryCommit {
        val before=snapshot()
        if(loadCourseRecoveryGroups()!=CourseRecoveryGroupsRead.Ready(expectedGroups)) return CourseRecoveryCommit.Rejected("group snapshot changed")
        return commit(before,before.courses,groups)
    }
    private fun commit(before:CoreDataSnapshot,courses:List<Course>,groups:List<CourseRecoveryGroup>):CourseRecoveryCommit {
        val records=before.operationRecords.filterNot { it.kind=="course_recovery" } + groups.map {
            OperationRecord(it.groupId,"course_recovery",it.deletedAt,it.state.name.lowercase(),CourseRecoveryCodec.encode(listOf(it)))
        }
        val result=repo.commitRecovery(before,before.copy(courses=courses,operationRecords=records))
        return if(result.applied) CourseRecoveryCommit.Committed
        else if(result.status==CoreDataWriteStatus.WRITE_FAILED) CourseRecoveryCommit.Failed(result.message)
        else CourseRecoveryCommit.Rejected(result.message)
    }
}
