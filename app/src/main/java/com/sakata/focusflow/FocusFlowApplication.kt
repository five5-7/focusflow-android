package com.sakata.focusflow

import android.app.Application
import com.sakata.focusflow.data.AndroidCoreDataRuntimeFactory
import com.sakata.focusflow.data.CoreDataRuntimeCompositionRoot
import com.sakata.focusflow.data.CoreDataRuntimeOwner

class FocusFlowApplication : Application(), CoreDataRuntimeOwner {
    override lateinit var coreDataRuntime: CoreDataRuntimeCompositionRoot
        private set

    override fun onCreate() {
        super.onCreate()
        coreDataRuntime = AndroidCoreDataRuntimeFactory.create(this)
        NotificationChannelSettings.ensureManagedChannels(this)
    }
}
