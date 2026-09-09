package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.1.0 重启恢复判定：只覆盖"系统有没有把开机广播送给我们"这一条纯逻辑。
 * 真机证据见 docs/8.1.0-audit.md：ColorOS 会推迟开机广播（DEFER_BY_OPLUS）。
 */
class BootRecoveryTest {
    @Test fun `first launch cannot tell anything`() {
        assertFalse(bootRecoveryDeferred(currentBootCount = 42, seenBootCount = -1, restoredBootCount = -1))
    }

    @Test fun `no reboot since last launch is not deferred`() {
        assertFalse(bootRecoveryDeferred(currentBootCount = 42, seenBootCount = 42, restoredBootCount = -1))
    }

    @Test fun `reboot with boot broadcast restored alarms is not deferred`() {
        // 收到过本次开机的广播：系统已自动恢复，不该提示"推迟"。
        assertFalse(bootRecoveryDeferred(currentBootCount = 43, seenBootCount = 42, restoredBootCount = 43))
    }

    @Test fun `reboot without boot broadcast is deferred`() {
        // ColorOS 实测：BOOT_COUNT 变新，但没有本次开机的广播记录。
        assertTrue(bootRecoveryDeferred(currentBootCount = 43, seenBootCount = 42, restoredBootCount = -1))
        // 曾经收到过更早开机的广播，也不影响判断。
        assertTrue(bootRecoveryDeferred(currentBootCount = 43, seenBootCount = 42, restoredBootCount = 41))
    }
}
