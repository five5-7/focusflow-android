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
 */
internal object BootRecovery {
    /** 本次启动是否属于"开机后系统没有把广播送给我们"的情况。 */
    var deferredThisBoot: Boolean = false
        private set

    /** 应用启动时调用一次（`MainActivity.onCreate`）。 */
    fun noteLaunch(context: Context) {
        val current = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
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
}

/**
 * 纯判定：本次开机是否"系统没把开机广播送给我们"。
 * - [seenBootCount] < 0：应用第一次启动（刚装完），无法判断，不提示；
 * - 本次开机编号没有变新：不是重启，不提示；
 * - 收到过本次开机的广播：系统已自动恢复，不提示。
 */
internal fun bootRecoveryDeferred(currentBootCount: Int, seenBootCount: Int, restoredBootCount: Int): Boolean =
    seenBootCount >= 0 && currentBootCount > seenBootCount && restoredBootCount != currentBootCount

