package com.sakata.focusflow

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 用户可定制的主题色。一套全局配色：
 * 主色/副色/强调色/中性色/文字色五个全局色，共同影响除课程色块（schedule）与提醒警示（warning）外的所有界面区域；
 * schedule 与 warning 保持各自语义，不接受定制。
 */
data class FocusFlowThemeColors(
    val primaryAction: Color,
    val secondary: Color,
    val accent: Color,
    val schedule: Color,
    val neutral: Color,
    val warning: Color,
    val text: Color
)

/** 内置主题的警示色统一用 Material 3 默认错误红，保证警示语义一致。 */
private val DEFAULT_WARNING = Color(0xFFB3261E)

enum class FocusFlowThemeOption(
    val storageKey: String,
    val label: String,
    val description: String,
    val colors: FocusFlowThemeColors
) {
    OCEAN(
        "ocean", "海盐蓝", "清爽、安静，延续 FocusFlow 的默认色调",
        FocusFlowThemeColors(
            primaryAction = Color(0xFF0F6B7A), secondary = Color(0xFF5C4B9A),
            accent = Color(0xFF4062A8), schedule = Color(0xFF2474B5),
            neutral = Color(0xFFF7FAFC), warning = DEFAULT_WARNING,
            text = Color(0xFF182124)
        )
    ),
    MINT(
        "mint", "薄荷绿", "柔和自然，适合长时间查看",
        FocusFlowThemeColors(
            primaryAction = Color(0xFF2C6D5A), secondary = Color(0xFF54706A),
            accent = Color(0xFF4C7A9C), schedule = Color(0xFF397A72),
            neutral = Color(0xFFF6FAF7), warning = DEFAULT_WARNING,
            text = Color(0xFF1A211E)
        )
    ),
    APRICOT(
        "apricot", "暖杏", "温暖明亮，降低界面的冷感",
        FocusFlowThemeColors(
            primaryAction = Color(0xFFA44F34), secondary = Color(0xFF785746),
            accent = Color(0xFF4F7D7A), schedule = Color(0xFF517BA5),
            neutral = Color(0xFFFFF8F4), warning = DEFAULT_WARNING,
            text = Color(0xFF241D1A)
        )
    ),
    TWILIGHT(
        "twilight", "暮紫", "低饱和紫色，更有专注感",
        FocusFlowThemeColors(
            primaryAction = Color(0xFF65558F), secondary = Color(0xFF675A70),
            accent = Color(0xFF7E9663), schedule = Color(0xFF5D72A8),
            neutral = Color(0xFFFAF7FC), warning = DEFAULT_WARNING,
            text = Color(0xFF211E24)
        )
    ),
    // 自定义主题：种子色 = OCEAN，进入编辑后从预设色板调整。
    CUSTOM(
        "custom", "自定义", "从预设色板自由搭配",
        FocusFlowThemeColors(
            primaryAction = Color(0xFF0F6B7A), secondary = Color(0xFF5C4B9A),
            accent = Color(0xFF4062A8), schedule = Color(0xFF2474B5),
            neutral = Color(0xFFF7FAFC), warning = DEFAULT_WARNING,
            text = Color(0xFF182124)
        )
    );

    companion object {
        fun fromStorageKey(value: String?): FocusFlowThemeOption =
            entries.firstOrNull { it.storageKey == value } ?: OCEAN

        fun builtInEntries(): List<FocusFlowThemeOption> = entries.filter { it != CUSTOM }
    }
}

data class FocusFlowSchedulePalette(
    val course: Color,
    val learning: Color,
    val exercise: Color,
    val entertainment: Color,
    val rest: Color,
    val task: Color,
    val completed: Color
)

data class FocusFlowThemeSpec(
    val colorScheme: ColorScheme,
    val schedulePalette: FocusFlowSchedulePalette
)

val LocalFocusFlowSchedulePalette = staticCompositionLocalOf {
    focusFlowThemeSpec(FocusFlowThemeOption.OCEAN).schedulePalette
}

fun focusFlowThemeSpec(option: FocusFlowThemeOption, customColors: FocusFlowThemeColors? = null, darkMode: Boolean = false): FocusFlowThemeSpec {
    val spec = when (option) {
    FocusFlowThemeOption.OCEAN -> builtInSpec(FocusFlowThemeOption.OCEAN)
    FocusFlowThemeOption.MINT -> builtInSpec(FocusFlowThemeOption.MINT)
    FocusFlowThemeOption.APRICOT -> builtInSpec(FocusFlowThemeOption.APRICOT)
    FocusFlowThemeOption.TWILIGHT -> builtInSpec(FocusFlowThemeOption.TWILIGHT)
    FocusFlowThemeOption.CUSTOM -> {
        val c = customColors ?: FocusFlowThemeOption.CUSTOM.colors
        val neutralBackground = lerp(c.neutral, Color.White, 0.85f)
        val neutralVariant = lerp(c.neutral, Color.White, 0.6f)
        FocusFlowThemeSpec(
            colorScheme = lightColorScheme(
                primary = c.primaryAction,
                onPrimary = onOf(c.primaryAction),
                primaryContainer = containerOf(c.primaryAction),
                onPrimaryContainer = onContainerOf(c.primaryAction),
                // 副色由"副色"槽位定制；次级容器沿用稳定灰紫保证可读。
                secondary = c.secondary,
                onSecondary = onOf(c.secondary),
                secondaryContainer = containerOf(c.secondary),
                onSecondaryContainer = onContainerOf(c.secondary),
                tertiary = c.accent,
                onTertiary = onOf(c.accent),
                tertiaryContainer = containerOf(c.accent),
                onTertiaryContainer = onContainerOf(c.accent),
                // 中性壳由中性色槽位派生：背景微调浅、卡片纯白、描边加深。
                background = neutralBackground,
                onBackground = c.text,
                surface = Color.White,
                onSurface = c.text,
                surfaceVariant = neutralVariant,
                onSurfaceVariant = lerp(c.text, Color.White, 0.45f),
                outline = lerp(c.neutral, Color.Black, 0.35f),
                // 警示色槽位直接映射系统错误色：提醒与警示全部跟随。
                error = c.warning,
                onError = onOf(c.warning),
                errorContainer = containerOf(c.warning),
                onErrorContainer = onContainerOf(c.warning)
            ),
            schedulePalette = FocusFlowSchedulePalette(
                course = c.schedule,
                learning = Color(0xFF7654A8),
                exercise = Color(0xFF2F8F5B),
                entertainment = Color(0xFFC95878),
                rest = Color(0xFF667885),
                task = Color(0xFFB5661D),
                completed = Color(0xFF94A3B8)
            )
        )
    }
    }
    return if (darkMode) spec.copy(colorScheme = darkenScheme(spec.colorScheme)) else spec
}

/** 内置主题：5 个种子色取自 option.colors，其余 scheme 字段与日程非定制色保持既有字面量。 */
private fun builtInSpec(option: FocusFlowThemeOption): FocusFlowThemeSpec {
    val c = option.colors
    return when (option) {
        FocusFlowThemeOption.OCEAN -> FocusFlowThemeSpec(
            colorScheme = lightColorScheme(
                primary = c.primaryAction,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFC9F3F6),
                onPrimaryContainer = Color(0xFF123E46),
                secondary = c.secondary,
                onSecondary = onOf(c.secondary),
                secondaryContainer = containerOf(c.secondary),
                onSecondaryContainer = onContainerOf(c.secondary),
                tertiary = c.accent,
                onTertiary = onOf(c.accent),
                tertiaryContainer = containerOf(c.accent),
                onTertiaryContainer = onContainerOf(c.accent),
                background = Color(0xFFF7FAFC),
                onBackground = Color(0xFF182124),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF182124),
                surfaceVariant = Color(0xFFE2ECEF),
                onSurfaceVariant = Color(0xFF44565B),
                outline = Color(0xFF8CA1A7)
            ),
            schedulePalette = FocusFlowSchedulePalette(
                course = c.schedule,
                learning = Color(0xFF7654A8),
                exercise = Color(0xFF2F8F5B),
                entertainment = Color(0xFFC95878),
                rest = Color(0xFF667885),
                task = Color(0xFFB5661D),
                completed = Color(0xFF94A3B8)
            )
        )

        FocusFlowThemeOption.MINT -> FocusFlowThemeSpec(
            colorScheme = lightColorScheme(
                primary = c.primaryAction,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD3F2E5),
                onPrimaryContainer = Color(0xFF173F34),
                secondary = c.secondary,
                onSecondary = onOf(c.secondary),
                secondaryContainer = containerOf(c.secondary),
                onSecondaryContainer = onContainerOf(c.secondary),
                tertiary = c.accent,
                onTertiary = onOf(c.accent),
                tertiaryContainer = containerOf(c.accent),
                onTertiaryContainer = onContainerOf(c.accent),
                background = Color(0xFFF6FAF7),
                onBackground = Color(0xFF1A211E),
                surface = Color(0xFFFCFFFC),
                onSurface = Color(0xFF1A211E),
                surfaceVariant = Color(0xFFE1ECE6),
                onSurfaceVariant = Color(0xFF46564F),
                outline = Color(0xFF8B9E95)
            ),
            schedulePalette = FocusFlowSchedulePalette(
                course = c.schedule,
                learning = Color(0xFF6671A8),
                exercise = Color(0xFF328153),
                entertainment = Color(0xFFB65C73),
                rest = Color(0xFF687B78),
                task = Color(0xFF9A6B27),
                completed = Color(0xFF91A29B)
            )
        )

        FocusFlowThemeOption.APRICOT -> FocusFlowThemeSpec(
            colorScheme = lightColorScheme(
                primary = c.primaryAction,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFDCCE),
                onPrimaryContainer = Color(0xFF5D2819),
                secondary = c.secondary,
                onSecondary = onOf(c.secondary),
                secondaryContainer = containerOf(c.secondary),
                onSecondaryContainer = onContainerOf(c.secondary),
                tertiary = c.accent,
                onTertiary = onOf(c.accent),
                tertiaryContainer = containerOf(c.accent),
                onTertiaryContainer = onContainerOf(c.accent),
                background = Color(0xFFFFF8F4),
                onBackground = Color(0xFF241D1A),
                surface = Color(0xFFFFFCFA),
                onSurface = Color(0xFF241D1A),
                surfaceVariant = Color(0xFFF2E5DE),
                onSurfaceVariant = Color(0xFF5B4D47),
                outline = Color(0xFFA18D84)
            ),
            schedulePalette = FocusFlowSchedulePalette(
                course = c.schedule,
                learning = Color(0xFF8A5F91),
                exercise = Color(0xFF55845A),
                entertainment = Color(0xFFC85F66),
                rest = Color(0xFF7B706D),
                task = Color(0xFFB7652B),
                completed = Color(0xFFA99A93)
            )
        )

        FocusFlowThemeOption.TWILIGHT -> FocusFlowThemeSpec(
            colorScheme = lightColorScheme(
                primary = c.primaryAction,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE9DFFF),
                onPrimaryContainer = Color(0xFF3C2F64),
                secondary = c.secondary,
                onSecondary = onOf(c.secondary),
                secondaryContainer = containerOf(c.secondary),
                onSecondaryContainer = onContainerOf(c.secondary),
                tertiary = c.accent,
                onTertiary = onOf(c.accent),
                tertiaryContainer = containerOf(c.accent),
                onTertiaryContainer = onContainerOf(c.accent),
                background = Color(0xFFFAF7FC),
                onBackground = Color(0xFF211E24),
                surface = Color(0xFFFFFBFF),
                onSurface = Color(0xFF211E24),
                surfaceVariant = Color(0xFFEAE4ED),
                onSurfaceVariant = Color(0xFF514B56),
                outline = Color(0xFF9B929F)
            ),
            schedulePalette = FocusFlowSchedulePalette(
                course = c.schedule,
                learning = Color(0xFF7A58A3),
                exercise = Color(0xFF4E8068),
                entertainment = Color(0xFFB85A83),
                rest = Color(0xFF726B82),
                task = Color(0xFFA66C35),
                completed = Color(0xFF9B94A3)
            )
        )

        FocusFlowThemeOption.CUSTOM -> throw IllegalStateException("CUSTOM 由 focusFlowThemeSpec 主分支处理")
    }
}

/** 深色模式：在当前浅色主题基础上调暗背景/表面、调亮文字，保留主/副/强调色并适度提亮。 */
private fun darkenScheme(base: ColorScheme): ColorScheme {
    fun brighten(c: Color) = lerp(c, Color.White, 0.20f)
    fun darkContainer(c: Color) = lerp(c, Color.Black, 0.55f)
    val darkBackground = Color(0xFF121417)
    val darkSurface = Color(0xFF1A1E21)
    val darkSurfaceVariant = Color(0xFF24282D)
    val lightText = Color(0xFFE4E8EC)
    val lightTextVariant = Color(0xFFB4BEC6)
    return base.copy(
        primary = brighten(base.primary),
        onPrimary = onOf(brighten(base.primary)),
        primaryContainer = darkContainer(base.primary),
        onPrimaryContainer = brighten(base.primary),
        secondary = brighten(base.secondary),
        onSecondary = onOf(brighten(base.secondary)),
        secondaryContainer = darkContainer(base.secondary),
        onSecondaryContainer = brighten(base.secondary),
        tertiary = brighten(base.tertiary),
        onTertiary = onOf(brighten(base.tertiary)),
        tertiaryContainer = darkContainer(base.tertiary),
        onTertiaryContainer = brighten(base.tertiary),
        background = darkBackground,
        onBackground = lightText,
        surface = darkSurface,
        onSurface = lightText,
        surfaceVariant = darkSurfaceVariant,
        onSurfaceVariant = lightTextVariant,
        outline = Color(0xFF6E7A82),
        outlineVariant = Color(0xFF3A4046),
        // Material3 的 Card/ElevatedCard 默认用 surfaceContainer* 色调（不是 surface），需一并调暗，否则卡片仍发亮。
        surfaceDim = Color(0xFF111417),
        surfaceBright = Color(0xFF2A2F34),
        surfaceContainerLowest = Color(0xFF0D0F11),
        surfaceContainerLow = Color(0xFF15181B),
        surfaceContainer = Color(0xFF1A1E21),
        surfaceContainerHigh = Color(0xFF202428),
        surfaceContainerHighest = Color(0xFF262A2F),
        inverseSurface = lightText,
        inverseOnSurface = Color(0xFF2A2F34),
        inversePrimary = darkContainer(base.primary),
        scrim = Color(0xFF000000),
        error = brighten(base.error),
        onError = onOf(brighten(base.error)),
        errorContainer = darkContainer(base.error),
        onErrorContainer = brighten(base.error)
    )
}

// 派生辅助：由主色自动生成文字/容器色（自定义主题与内置 tertiary 共用）。
private fun onOf(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White
private fun containerOf(color: Color): Color = lerp(color, Color.White, 0.82f)
private fun onContainerOf(color: Color): Color = lerp(color, Color.Black, 0.35f)
