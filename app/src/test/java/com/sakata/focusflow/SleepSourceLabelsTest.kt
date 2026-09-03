package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepSourceLabelsTest {
    @Test
    fun knownCompanionPackagesHaveReadableFallbacks() {
        assertEquals("小米运动健康", SleepSourceLabels.fallback("com.xiaomi.wearable"))
        assertEquals("Zepp Life", SleepSourceLabels.fallback("com.xiaomi.hm.health"))
        assertEquals("华为运动健康", SleepSourceLabels.fallback("com.huawei.health"))
    }

    @Test
    fun unknownSourceKeepsItsPackageName() {
        assertEquals("example.sleep", SleepSourceLabels.fallback("example.sleep"))
    }
}
