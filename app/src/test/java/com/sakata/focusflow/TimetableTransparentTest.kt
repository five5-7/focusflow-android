package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表／日程表底色的「透明」档（维护者 2026-09-10：「加入日程表的透明背景选项」）。
 *
 * 语义边界写在这里，免得以后被"顺手统一"掉：
 * - 透明**只对课表角色开放**。页面角色本身就是最底层，透明等于黑屏，所以
 *   [AppearanceSpec.offeredPageBackdrops] 里永远不许出现它。
 * - 透明与「跟随主题」不是一回事：跟随主题画的是主题 surface 纯色，
 *   透明是**这一层什么都不画**，把页面渐变／背景图／页面底色原样透出来。
 * - 它是零绘制成本的一档（不画东西），但仍然挂在「丰富效果」开关下，
 *   与 COLOR／IMAGE 的回落规则保持一致：关掉开关时课表底色回落成跟随主题。
 */
class TimetableTransparentTest {

    @Test
    fun storageKeyRoundTrips() {
        for (kind in BackdropKind.entries) {
            assertEquals(
                "storageKey 必须能往返：" + kind,
                kind,
                BackdropKind.fromKey(kind.storageKey)
            )
        }
    }

    @Test
    fun transparentKeyIsStableAndLegacyValuesStillRead() {
        // 这个字符串会落进 SharedPreferences 与主题预设 JSON，是数据契约，不许改。
        assertEquals("transparent", BackdropKind.TRANSPARENT.storageKey)
        assertEquals(BackdropKind.TRANSPARENT, BackdropKind.fromKey("transparent"))
        // 老装机与坏数据照旧：读不到／读坏了退回 THEME，不抛错
        assertEquals(BackdropKind.THEME, BackdropKind.fromKey(null))
        assertEquals(BackdropKind.THEME, BackdropKind.fromKey(""))
        assertEquals(BackdropKind.THEME, BackdropKind.fromKey("nonsense"))
    }

    @Test
    fun timetableCanBeTransparent() {
        val spec = AppearanceSpec(timetableBackdrop = BackdropKind.TRANSPARENT, richEffects = true)
        assertEquals(BackdropKind.TRANSPARENT, spec.effectiveTimetableBackdrop)
        // 透明不是"用了自选颜色"，也不是"有底图"——这两条会去画东西，必须保持 false
        assertFalse(spec.timetableUsesColor)
        assertFalse(spec.hasTimetableImage)
        // 页面角色不受影响
        assertEquals(BackdropKind.THEME, spec.effectivePageBackdrop)
    }

    @Test
    fun pageBackdropsNeverOfferTransparent() {
        assertFalse(
            "页面没有「下面」可以透出来的东西，透明档不许出现在页面选项里",
            AppearanceSpec(richEffects = true).offeredPageBackdrops.contains(BackdropKind.TRANSPARENT)
        )
        assertFalse(
            AppearanceSpec(richEffects = false).offeredPageBackdrops.contains(BackdropKind.TRANSPARENT)
        )
    }

    @Test
    fun richEffectsOffFallsBackToThemeLikeTheOtherTimetableKinds() {
        val plain = AppearanceSpec(
            timetableBackdrop = BackdropKind.TRANSPARENT,
            richEffects = false
        )
        assertEquals(BackdropKind.THEME, plain.effectiveTimetableBackdrop)
        // 回落只影响渲染，用户选过的东西不能被丢掉
        assertEquals(BackdropKind.TRANSPARENT, plain.timetableBackdrop)
    }

    @Test
    fun defaultAppearanceStaysFollowingTheme() {
        assertEquals(BackdropKind.THEME, AppearanceSpec.DEFAULT.timetableBackdrop)
        assertEquals(BackdropKind.THEME, AppearanceSpec.DEFAULT.effectiveTimetableBackdrop)
        // 老装机读不到键时也必须是跟随主题 —— 默认外观逐像素不变的前提
        val legacy = AppearanceSpec.fromKeys(
            null, null, 100, 100, 0, false, 0, 0, null, null, 0, null, 100, null
        )
        assertEquals(BackdropKind.THEME, legacy.effectiveTimetableBackdrop)
    }

    @Test
    fun readsBackFromStorageKeys() {
        val spec = AppearanceSpec.fromKeys(
            null, null, 100, 100, 0, false, 0, 0, null, "transparent", 0, null, 100, null
        )
        assertEquals(BackdropKind.TRANSPARENT, spec.timetableBackdrop)
    }
}
