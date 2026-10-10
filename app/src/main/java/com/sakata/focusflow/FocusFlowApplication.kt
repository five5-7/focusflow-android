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
        // 开机广播/提醒可先于 Activity 启动，进程创建时就安装本地崩溃记录器。
        CrashReporter.init(this)
        coreDataRuntime = AndroidCoreDataRuntimeFactory.create(this)
        NotificationChannelSettings.ensureManagedChannels(this)
    }
}
