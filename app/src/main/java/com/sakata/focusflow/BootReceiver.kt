package com.sakata.focusflow

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            ReminderScheduler.restoreActivityReminders(context)
            ReminderScheduler.restoreGameReminders(context)
            if (PrototypeStore(context).loadQuickCaptureEnabled()) QuickCaptureService.start(context)
        }
    }
}
