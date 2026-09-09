package com.sakata.focusflow

import android.content.Context
import android.provider.Settings

/**
 * 8.1.0 重启恢复诊断。
 *
 * 实测（OPPO ColorOS 16）：系统会把 `BOOT_COMPLETED` **推迟**给第三方应用
 * （`dumpsys activity broadcasts` 里可见 `DEFER_BY_OPLUS SUB_REASON: PRESSURE` /
 * `DEFERRED OFFLOAD for manifest com.sakata.focusflow.BootReceiver`），
 * 于是"重启后自动恢复提醒"并不成立——提醒只在用户打开应用时才补登记。
 *
 * 这里用两次记录把这个事实判断出来：
 * - `restored_boot_count`：`BootReceiver` 收到开机广播时写入的 `BOOT_COUNT`；
 * - `seen_boot_count`：应用每次启动时写入的 `BOOT_COUNT`。
 * 若"本次开机的 BOOT_COUNT" 比上次见到的更新，且没有对应的开机广播记录，
 * 就说明本次开机系统没叫醒我们，提醒是这次打开应用才补上的。
 *
 * 已知边界（如实记录，不假装精确）：
 * - 安装后从未打开过应用就重启（`seen` 仍为 -1）不提示，避免把新装用户误报；
 * - `BOOT_COUNT` 是设备级全局值，而两个记录按用户存储（多用户下以主用户为准）；
 * - 同一进程内只判定一次；开机广播在进程存活期间才送达时，由 [noteBroadcastReceived] 撤销提示。
 */
internal object BootRecovery {
    private var notedThisProcess = false

    /** 本次启动是否属于"开机后系统没有把广播送给我们"的情况。 */
    var deferredThisBoot: Boolean = false
        private set

    /** 应用启动时调用一次（`MainActivity.onCreate`）。 */
    fun noteLaunch(context: Context) {
        if (notedThisProcess) return
        notedThisProcess = true
        val current = bootCount(context)
        if (current < 0) {
            deferredThisBoot = false
            return
        }
        val store = PrototypeStore(context)
        val seen = store.loadSeenBootCount()
        val restored = store.loadRestoredBootCount()
        deferredThisBoot = bootRecoveryDeferred(current, seen, restored)
        if (seen != current) store.saveSeenBootCount(current)
    }

    /** 开机广播真的送达时调用（[BootReceiver]）：记下这次开机，并撤销"被推迟"的提示。 */
    fun noteBroadcastReceived(context: Context) {
        val current = bootCount(context)
        if (current >= 0) PrototypeStore(context).saveRestoredBootCount(current)
        deferredThisBoot = false
    }

    private fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
}

/**
 * 纯判定：本次开机是否"系统没把开机广播送给我们"。
 * - [seenBootCount] < 0：应用第一次启动（刚装完），无法判断，不提示；
 * - 本次开机编号没有变新：不是重启，不提示；
 * - 收到过本次开机的广播：系统已自动恢复，不提示。
 */
internal fun bootRecoveryDeferred(currentBootCount: Int, seenBootCount: Int, restoredBootCount: Int): Boolean =
    seenBootCount >= 0 && currentBootCount > seenBootCount && restoredBootCount != currentBootCount
