package com.sakata.focusflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 常驻快速记录通知：设置里开启后，前台服务在通知栏常驻一条通知，
 * 提供“快速记录”入口（直接打开收集箱）与“打开 FocusFlow”。
 * 通知为静音、不可滑动；关闭开关即停止服务、通知消失。
 */
class QuickCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ 要求前台服务声明并传入类型（specialUse），否则抛 MissingForegroundServiceTypeException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FocusFlow 快速记录", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val quickCapture = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ReminderReceiver.EXTRA_OPEN_QUICK_CAPTURE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("FocusFlow 快速记录")
            .setContentText("随时记下想法，稍后统一安排")
            .setContentIntent(openApp)
            .addAction(0, "快速记录", quickCapture)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "focusflow_quick_capture_v1"
        private const val NOTIFICATION_ID = 2_900_010

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, QuickCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickCaptureService::class.java))
        }
    }
}
