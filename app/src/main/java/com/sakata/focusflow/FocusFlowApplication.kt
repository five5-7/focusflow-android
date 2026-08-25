package com.sakata.focusflow

import android.app.Application

class FocusFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannelSettings.ensureManagedChannels(this)
    }
}
