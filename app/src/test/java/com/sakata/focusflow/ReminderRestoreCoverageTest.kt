package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组③：**恢复覆盖即数据**的回归测试（纯 JVM，不碰通知、不碰闹钟）。
 *
 * 为什么需要它：统一恢复入口 `ReminderScheduler.restoreActivityReminders` 的名字只说"活动"，
 * 实际恢复的是 **9 类**（按分组 6 组）；游戏提醒由 `BootReceiver` 单独恢复。此前"某类提醒有没有恢复步骤"
 * 没有守卫，新增一类提醒时可能**静默漏掉恢复**（漏了不报错，只在"该响时没响"）。
 * 这里把类型清单钉成数据：新增枚举常量必须登记进两份清单之一，否则变红。
 *
 * **不要高估它**（复核席 B 逐条盘出的盲区，详见设计件 §4.1"这套数据不能保证什么"）：
 * 它只决定"跑哪些步骤、什么顺序"，**不检查每一步做了什么**——把 `COURSE ->` 的分支体改成空实现
 * 或调错函数，这几条测试仍然全绿。
 */
class ReminderRestoreCoverageTest {

    /** 源文本守卫用的文件路径：单测 JVM 的工作目录是模块目录（先例 `LauncherIconResourceTest`）。 */
    private fun sourceFile(relative: String) = java.io.File(relative)

    @Test
    fun `the bootstrap receiver still restores game reminders`() {
        // 堵住盲区：第二份清单只是**声明**"GAME 在别处恢复"，删掉 BootReceiver 的调用点不会有任何测试变红。
        // 这里直接读源码断言调用点仍在（同仓先例：LauncherIconResourceTest 用 File("src/main/...") 读真实文件）。
        //
        // 已知强度上限（复核席 A/B 指出）：源文本守卫只能证明"文本里还出现过这个调用"。
        // 这里**剥掉行首注释**（`//`、`*`、`/*` 开头的行）再匹配，所以"把调用写进行首注释"过不了关；
        // 但下列绕过仍然能过，不要把它当成语义守卫：
        //   ① 行尾注释（`val x = 0 // restoreGameReminders(appContext)`）；
        //   ② 块注释里不以 `*` 开头的裸行；
        //   ③ 字符串字面量里出现该词；
        //   ④ 把调用挪到别的函数里（本测试只看"文件里有没有这个词"）。
        // 真正的语义守卫要等 §4 第三步（并入统一入口）落地。
        val bootReceiver = sourceFile("src/main/java/com/sakata/focusflow/BootReceiver.kt")
        assertTrue("找不到 ${bootReceiver.path}", bootReceiver.isFile)

        val codeLines = bootReceiver.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        assertTrue(
            "BootReceiver 必须继续调用 restoreGameReminders —— 否则 GAME 这一类就没有任何恢复入口",
            codeLines.any { it.contains("restoreGameReminders(") }
        )
    }

    @Test
    fun `every reminder type is registered in exactly one restore place`() {
        val registered = REMINDER_RESTORE_ORDER + REMINDER_RESTORED_OUTSIDE_UNIFIED_ENTRY

        assertEquals(
            "每个提醒类型都必须登记（统一入口，或别处单独恢复）",
            ReminderRestoreStep.entries.toSet(),
            registered.toSet()
        )
        assertEquals("同一类型不得在两处重复登记", registered.size, registered.toSet().size)
    }

    @Test
    fun `the unified restore order keeps the original step sequence`() {
        assertEquals(
            listOf(
                ReminderRestoreStep.MISSED_DIGEST,
                ReminderRestoreStep.ACTIVITY,
                ReminderRestoreStep.DAILY_STATUS,
                ReminderRestoreStep.DAILY_MEAL,
                ReminderRestoreStep.DAILY_WIND_DOWN,
                ReminderRestoreStep.REPEAT_REFRESH,
                ReminderRestoreStep.TASK,
                ReminderRestoreStep.STANDALONE,
                ReminderRestoreStep.COURSE
            ),
            REMINDER_RESTORE_ORDER
        )
    }

    @Test
    fun `game reminders are still declared as restored outside the unified entry`() {
        // 把它并进统一入口是行为变更（未放行）；这条测试的作用是让"未放行"这件事在代码里可见，
        // 一旦有人合并了两份清单，这里会红，提醒他去看设计件的授权边界。
        assertTrue(REMINDER_RESTORED_OUTSIDE_UNIFIED_ENTRY.contains(ReminderRestoreStep.GAME))
        assertTrue(!REMINDER_RESTORE_ORDER.contains(ReminderRestoreStep.GAME))
    }
}
