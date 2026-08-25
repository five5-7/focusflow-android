package com.sakata.focusflow

internal enum class NotificationDeviceFamily { SAMSUNG, XIAOMI, HUAWEI_HONOR, COLOR_OS, VIVO, PIXEL_AOSP, GENERIC }

internal data class NotificationGuidance(
    val family: NotificationDeviceFamily,
    val title: String,
    val steps: List<String>,
    val limitation: String
)

/** Manual hints only: Android does not expose every vendor-specific banner switch. */
internal object NotificationGuidancePolicy {
    fun family(manufacturer: String?, brand: String?): NotificationDeviceFamily {
        val identity = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            identity.contains("samsung") -> NotificationDeviceFamily.SAMSUNG
            listOf("xiaomi", "redmi", "poco").any(identity::contains) -> NotificationDeviceFamily.XIAOMI
            listOf("huawei", "honor").any(identity::contains) -> NotificationDeviceFamily.HUAWEI_HONOR
            listOf("oppo", "realme", "oneplus").any(identity::contains) -> NotificationDeviceFamily.COLOR_OS
            listOf("vivo", "iqoo").any(identity::contains) -> NotificationDeviceFamily.VIVO
            listOf("google", "aosp").any(identity::contains) -> NotificationDeviceFamily.PIXEL_AOSP
            else -> NotificationDeviceFamily.GENERIC
        }
    }

    fun forDevice(manufacturer: String?, brand: String?): NotificationGuidance {
        val family = family(manufacturer, brand)
        val common = listOf(
            "长按 FocusFlow 图标 → 应用信息 → 通知（或通知管理），开启“允许通知”。",
            "进入“FocusFlow 任务提醒”和“饭点提醒”，选择提醒／默认或较高优先级。"
        )
        val vendorStep = when (family) {
            NotificationDeviceFamily.SAMSUNG -> "若看不到通知类别，先到设置 → 通知 → 高级设置，开启“管理每个应用的通知类别”；再为两个类别选择“提醒”并开启“显示为弹出窗口”。"
            NotificationDeviceFamily.XIAOMI -> "再到设置 → 通知与状态栏（部分版本为“通知与控制中心”）→ 悬浮通知，为 FocusFlow 开启悬浮通知。"
            NotificationDeviceFamily.HUAWEI_HONOR -> "在 FocusFlow 的通知页同时确认“允许通知”和“横幅”；部分机型入口位于“通知和状态栏”。"
            NotificationDeviceFamily.COLOR_OS -> "在 FocusFlow 的通知管理页确认“横幅／悬浮通知／在屏幕顶部显示”等同类选项；不同系统版本名称可能不同。"
            NotificationDeviceFamily.VIVO -> "在 FocusFlow 的通知页确认“悬浮通知／顶部预览／横幅”等同类选项；不同系统版本名称可能不同。"
            NotificationDeviceFamily.PIXEL_AOSP -> "为两个通知类别选择“提醒”或“默认”；若页面提供“在屏幕上弹出”，请一并开启。"
            NotificationDeviceFamily.GENERIC -> "如系统另有“横幅／悬浮／弹出／顶部预览”开关，请在 FocusFlow 的应用通知页开启。"
        }
        val title = when (family) {
            NotificationDeviceFamily.SAMSUNG -> "三星设备通知检查"
            NotificationDeviceFamily.XIAOMI -> "小米／Redmi／POCO 通知检查"
            NotificationDeviceFamily.HUAWEI_HONOR -> "华为／荣耀通知检查"
            NotificationDeviceFamily.COLOR_OS -> "OPPO／realme／OnePlus 通知检查"
            NotificationDeviceFamily.VIVO -> "vivo／iQOO 通知检查"
            NotificationDeviceFamily.PIXEL_AOSP -> "Pixel／原生 Android 通知检查"
            NotificationDeviceFamily.GENERIC -> "当前设备通知检查"
        }
        return NotificationGuidance(
            family,
            title,
            common + vendorStep,
            "FocusFlow 会在每次回到前台时检测系统公开的总通知和渠道状态；厂商额外的横幅、悬浮或弹出开关通常无法由应用读取。若这里显示已开启但仍没有横幅，请按上方路径手动确认。"
        )
    }
}
