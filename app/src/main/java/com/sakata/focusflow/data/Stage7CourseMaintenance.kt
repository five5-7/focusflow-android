package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.*

/** Orphan collection is derived on every pass so an interrupted preference sweep can be retried. */
internal object Stage7CourseMaintenance {
    fun collect(context:Context, repo:CoreDataRepository):CoreDataWriteResult = repo.withCourseWriteLock {
        if(StorageProtection.readOnly || Stage7CommitGuard.uncertain || CourseRecoveryWriteGuard.uncertainReason()!=null)
            return@withCourseWriteLock CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET,"storage write is unconfirmed or protected")
        try {
            require(!PrototypeStore(context).hasPendingCourseEditJournal())
            val snapshot=(repo.read() as CoreDataReadResult.Ready).snapshot
            val groups=(CourseRecoveryOperations.readGroups(repo) as? CourseRecoveryGroupsRead.Ready)?.groups ?: error("course recovery data unavailable")
            val retained=snapshot.courses.mapTo(mutableSetOf()) { it.id }
            groups.forEach { g -> retained.addAll(g.members.map { it.id }) }
            snapshot.operationRecords.filter { it.kind in setOf("course_merge_inverse","course_split_inverse") }.forEach {
                val c=InverseOperation.decode(it.payload); retained.addAll((c.beforeCourses+c.afterCourses).map { it.id })
            }
            val reminders=context.getSharedPreferences("course_reminder_settings",Context.MODE_PRIVATE)
            val locations=context.getSharedPreferences("course_location_overrides",Context.MODE_PRIVATE)
            val reminderKeys=reminders.all.keys.filter { key ->
                val suffix=when { key.startsWith("meeting_")->key.removePrefix("meeting_"); key.startsWith("delivered_")->key.removePrefix("delivered_"); else->null }
                suffix?.toLongOrNull()?.let { it>0 && suffix==it.toString() && it !in retained } == true
            }
            val locationKeys=locations.all.keys.filter { key ->
                val id=key.substringBefore('_').toLongOrNull(); val day=key.substringAfter('_',"").toLongOrNull()
                id!=null && id>0 && day!=null && day>=0 && key=="${id}_$day" && id !in retained
            }
            fun clear(prefs:android.content.SharedPreferences, keys:List<String>):Boolean {
                if(keys.isEmpty()) return true
                val editor=prefs.edit(); keys.forEach(editor::remove); return editor.commit()
            }
            if(!clear(reminders,reminderKeys) || !clear(locations,locationKeys)) {
                Stage7CommitGuard.markUncertain()
                CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED,"preference cleanup unconfirmed")
            } else CoreDataWriteResult(CoreDataWriteStatus.APPLIED)
        } catch(e:Exception) { CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET,e.message ?: "cleanup held") }
    }
}
