package com.sakata.focusflow

import android.app.Application
import com.sakata.focusflow.data.CoreDataRuntimeRepositoryProvider

class FocusFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannelSettings.ensureManagedChannels(this)
        CoreDataRuntimeRepositoryProvider.legacyLocked(PrototypeStore(this))
            .ensureTaskHistoryMigrated()
    }
}
