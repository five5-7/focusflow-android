package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组③：**恢复覆盖即数据**的回归测试（纯 JVM，不碰通知、不碰闹钟）。
 *
 * 为什么需要它：统一恢复入口 `ReminderScheduler.restoreUnifiedReminders`（原名 `restoreActivityReminders`，
 * 2026-09-29 改名——原名只说"活动"，实际恢复的是 **10 类**）；其中游戏提醒已于同日并入统一入口，
 * 不再由 `BootReceiver` 单独恢复。
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
    fun `game reminders are restored by the unified entry and nowhere else`() {
        // 堵住盲区：两份清单都只是**声明**，调用点真的在不在没人守。这里读源码钉住**两端**：
        // ① BootReceiver 不得再单独调用；② 统一入口的 GAME 分支必须真的调用。
        // （同仓先例：LauncherIconResourceTest 用 File("src/main/...") 读真实源文件。）
        //
        // 已知强度上限（复核席 A/B 逐条指出，如实写下——**不要把它当语义守卫**）：
        //   · 它只证明"文件里出现过／没出现过这段文本"，不证明语义，也不证明该分支可达。
        //   · **误红方向**（更常见的运营成本）：① 存活行里写出该调用文本即红（行尾注释、日志字符串）；
        //     ② 断言 B 用**单行精确文本** ⇒ 分支体折行、写成块体 `{ }`、参数改名、`->` 前后空格变化都会误红。
        //   · **漏过方向**：负向断言可用间接调用绕过（包装函数——尤其定义在别的文件、方法引用
        //     `ReminderScheduler::restoreGameReminders`、反射）；正向断言只要文本还在（写进字符串、
        //     放进行尾注释、或让该分支不可达，例如遍历表达式里 `filterNot { it == GAME }`）就仍然绿。
        // 已剥掉行首注释（`//`、`*`、`/*` 开头的行）再匹配，故"把调用写进行首注释"过不了关。
        val bootReceiver = sourceFile("src/main/java/com/sakata/focusflow/BootReceiver.kt")
        val scheduler = sourceFile("src/main/java/com/sakata/focusflow/ReminderScheduler.kt")
        assertTrue("找不到 ${bootReceiver.path}", bootReceiver.isFile)
        assertTrue("找不到 ${scheduler.path}", scheduler.isFile)

        fun codeLines(file: java.io.File) = file.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        assertTrue(
            "BootReceiver 不应再单独恢复游戏提醒（已并入统一入口，重复调用会让\"一个入口\"的说法失真）",
            codeLines(bootReceiver).none { it.contains("restoreGameReminders(") }
        )
        assertTrue(
            "统一入口的 GAME 分支必须真的调用 restoreGameReminders —— 否则 GAME 这一类就没有任何恢复入口",
            codeLines(scheduler).any { it.contains("ReminderRestoreStep.GAME -> restoreGameReminders(context)") }
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
    fun `the unified restore order keeps the declared step sequence including game`() {
        assertEquals(
            listOf(
                ReminderRestoreStep.MISSED_DIGEST,
                ReminderRestoreStep.GAME,
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
    fun `game sits in the unified order and the outside list is empty`() {
        // 2026-09-29 第三步落地：GAME 并入统一入口，"别处单独恢复"清单清空。
        // 这条测试的作用是让那次行为变更在代码里可见：谁要把 GAME 挪回去，这里会红。
        assertTrue(REMINDER_RESTORE_ORDER.contains(ReminderRestoreStep.GAME))
        assertTrue(
            "并入之后不应再有任何类型挂在\"别处单独恢复\"清单上",
            REMINDER_RESTORED_OUTSIDE_UNIFIED_ENTRY.isEmpty()
        )
    }
}
