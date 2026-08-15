package com.sakata.focusflow

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class FocusFlowThemeOption(
    val storageKey: String,
    val label: String,
    val description: String
) {
    OCEAN("ocean", "海盐蓝", "清爽、安静，延续 FocusFlow 的默认色调"),
    MINT("mint", "薄荷绿", "柔和自然，适合长时间查看"),
    APRICOT("apricot", "暖杏", "温暖明亮，降低界面的冷感"),
    TWILIGHT("twilight", "暮紫", "低饱和紫色，更有专注感");

    companion object {
        fun fromStorageKey(value: String?): FocusFlowThemeOption =
            entries.firstOrNull { it.storageKey == value } ?: OCEAN
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

fun focusFlowThemeSpec(option: FocusFlowThemeOption): FocusFlowThemeSpec = when (option) {
    FocusFlowThemeOption.OCEAN -> FocusFlowThemeSpec(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F6B7A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFC9F3F6),
            onPrimaryContainer = Color(0xFF123E46),
            secondary = Color(0xFF5C4B9A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEAE5FF),
            onSecondaryContainer = Color(0xFF332864),
            background = Color(0xFFF7FAFC),
            onBackground = Color(0xFF182124),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF182124),
            surfaceVariant = Color(0xFFE2ECEF),
            onSurfaceVariant = Color(0xFF44565B),
            outline = Color(0xFF8CA1A7)
        ),
        schedulePalette = FocusFlowSchedulePalette(
            course = Color(0xFF2474B5),
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
            primary = Color(0xFF2C6D5A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD3F2E5),
            onPrimaryContainer = Color(0xFF173F34),
            secondary = Color(0xFF54706A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCECE7),
            onSecondaryContainer = Color(0xFF273E39),
            background = Color(0xFFF6FAF7),
            onBackground = Color(0xFF1A211E),
            surface = Color(0xFFFCFFFC),
            onSurface = Color(0xFF1A211E),
            surfaceVariant = Color(0xFFE1ECE6),
            onSurfaceVariant = Color(0xFF46564F),
            outline = Color(0xFF8B9E95)
        ),
        schedulePalette = FocusFlowSchedulePalette(
            course = Color(0xFF397A72),
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
            primary = Color(0xFFA44F34),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDCCE),
            onPrimaryContainer = Color(0xFF5D2819),
            secondary = Color(0xFF785746),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFF5E1D6),
            onSecondaryContainer = Color(0xFF463126),
            background = Color(0xFFFFF8F4),
            onBackground = Color(0xFF241D1A),
            surface = Color(0xFFFFFCFA),
            onSurface = Color(0xFF241D1A),
            surfaceVariant = Color(0xFFF2E5DE),
            onSurfaceVariant = Color(0xFF5B4D47),
            outline = Color(0xFFA18D84)
        ),
        schedulePalette = FocusFlowSchedulePalette(
            course = Color(0xFF517BA5),
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
            primary = Color(0xFF65558F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE9DFFF),
            onPrimaryContainer = Color(0xFF3C2F64),
            secondary = Color(0xFF675A70),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEDE2F1),
            onSecondaryContainer = Color(0xFF403548),
            background = Color(0xFFFAF7FC),
            onBackground = Color(0xFF211E24),
            surface = Color(0xFFFFFBFF),
            onSurface = Color(0xFF211E24),
            surfaceVariant = Color(0xFFEAE4ED),
            onSurfaceVariant = Color(0xFF514B56),
            outline = Color(0xFF9B929F)
        ),
        schedulePalette = FocusFlowSchedulePalette(
            course = Color(0xFF5D72A8),
            learning = Color(0xFF7A58A3),
            exercise = Color(0xFF4E8068),
            entertainment = Color(0xFFB85A83),
            rest = Color(0xFF726B82),
            task = Color(0xFFA66C35),
            completed = Color(0xFF9B94A3)
        )
    )
}
