package com.sakata.focusflow

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            ReminderScheduler.restoreActivityReminders(context)
            ReminderScheduler.restoreGameReminders(context)
            if (PrototypeStore(context).loadQuickCaptureEnabled()) QuickCaptureService.start(context)
            // 记下"这次开机广播确实送达了"，供设置页判断是否被厂商推迟（ColorOS 会推迟）。
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                if (bootCount >= 0) PrototypeStore(context).saveRestoredBootCount(bootCount)
            }
        }
    }
}
