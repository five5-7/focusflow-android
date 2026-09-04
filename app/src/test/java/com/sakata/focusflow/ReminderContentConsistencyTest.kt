package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderContentConsistencyTest {
    @Test fun `all optional reminder features default off`() {
        assertFalse(ReminderFeatureDefaults.STATUS_CHECK_IN_ENABLED)
        assertFalse(ReminderFeatureDefaults.MEAL_REMINDER_ENABLED)
        assertFalse(ReminderFeatureDefaults.MEAL_DURATION_TRACKING_ENABLED)
        assertFalse(ReminderFeatureDefaults.FOREGROUND_DETECTION_ENABLED)
        assertFalse(StatusCheckInSettings().enabled)
        assertFalse(FeatureUsageSnapshot().mealReminderEnabled)
    }

    @Test fun `quick start explains every 72 reminder rule`() {
        val copy = quickStartChapters.flatMap { it.lines }.joinToString("\n")

        assertTrue(copy.contains("每日精力询问默认关闭"))
        assertTrue(copy.contains("不会自动改设置"))
        assertTrue(copy.contains("饭点提醒默认关闭"))
        assertTrue(copy.contains("用餐结束询问") && copy.contains("另一个默认关闭"))
        assertTrue(copy.contains("前台应用检测默认关闭"))
        assertTrue(copy.contains("绝不会自动结束活动"))
    }

    @Test fun `help uses the same conservative semantics`() {
        val status = HelpCatalog.settings.getValue(SettingsBlock.STATUS_CHECK_IN).lines.joinToString("\n")
        val meal = HelpCatalog.settings.getValue(SettingsBlock.MEAL_LEARNING).lines.joinToString("\n")
        val detection = HelpCatalog.settings.getValue(SettingsBlock.APP_DETECTION).lines.joinToString("\n")

        assertTrue(status.contains("默认关闭") && status.contains("不会自动修改"))
        assertTrue(meal.contains("饭点提醒默认关闭") && meal.contains("用餐结束询问是独立且默认关闭"))
        assertTrue(detection.contains("只") && detection.contains("增强收尾文案"))
        assertTrue(detection.contains("绝不会自动结束活动"))
    }

    @Test fun `baseline and activity setup do not imply automatic enablement or completion`() {
        assertTrue(ReminderRuleCopy.BASELINE_SAVED.contains("不会自动开启饭点提醒"))
        assertTrue(ReminderRuleCopy.SCHEDULED_ACTIVITY.contains("不会自动结束"))
        assertTrue(ReminderRuleCopy.SCHEDULED_ACTIVITY.contains("由你确认"))
    }
}
