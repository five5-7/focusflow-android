package com.sakata.focusflow

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings

/** 应用分类：前台应用检测与游戏自律统计使用。 */
enum class AppCategory(val label: String) {
    GAME("游戏"), VIDEO("视频"), SOCIAL("社交"), STUDY("学习"), OTHER("其他"), UNKNOWN("未分类");

    companion object {
        fun fromName(name: String?): AppCategory? = entries.firstOrNull { it.name == name }
    }
}

/** 内置应用分类清单（包名 → 分类）：只作起点，以用户确认为准。 */
val BUILTIN_APP_CATEGORIES: Map<String, AppCategory> = mapOf(
    // 游戏
    "com.miHoYo.Yuanshen" to AppCategory.GAME,          // 原神
    "com.miHoYo.hkrpg" to AppCategory.GAME,             // 崩坏：星穹铁道
    "com.miHoYo.bh3" to AppCategory.GAME,               // 崩坏3
    "com.tencent.tmgp.sgame" to AppCategory.GAME,       // 王者荣耀
    "com.tencent.tmgp.pubgmhd" to AppCategory.GAME,     // 和平精英
    "com.tencent.tmgp.chess" to AppCategory.GAME,       // 金铲铲之战
    "com.tencent.tmgp.cf" to AppCategory.GAME,          // 穿越火线
    "com.netease.nie.yys" to AppCategory.GAME,          // 阴阳师
    "com.tencent.tmgp.speedmobile" to AppCategory.GAME, // 天天酷跑
    "com.hypergryph.arknights" to AppCategory.GAME,     // 明日方舟
    "com.tencent.tmgp.cod" to AppCategory.GAME,         // 使命召唤手游
    // 视频
    "tv.danmaku.bili" to AppCategory.VIDEO,             // 哔哩哔哩
    "com.ss.android.ugc.aweme" to AppCategory.VIDEO,    // 抖音
    "com.smile.gifmaker" to AppCategory.VIDEO,          // 快手
    "com.tencent.qqlive" to AppCategory.VIDEO,          // 腾讯视频
    "com.qiyi.video" to AppCategory.VIDEO,              // 爱奇艺
    "com.youku.phone" to AppCategory.VIDEO,             // 优酷
    "com.kuaishou.nebula" to AppCategory.VIDEO,         // 快手极速版
    // 社交
    "com.tencent.mm" to AppCategory.SOCIAL,             // 微信
    "com.tencent.mobileqq" to AppCategory.SOCIAL,       // QQ
    "com.sina.weibo" to AppCategory.SOCIAL,             // 微博
    "com.baidu.tieba" to AppCategory.SOCIAL,            // 贴吧
    "com.immomo.momo" to AppCategory.SOCIAL,            // 陌陌
    // 学习
    "com.zhihu.android" to AppCategory.STUDY,           // 知乎
    "com.icourse163" to AppCategory.STUDY               // 中国大学MOOC
)

/** 应用名关键词 → 分类：检测到未分类应用时自动识别（以用户确认为准，不假装精确）。 */
fun autoCategoryByLabel(label: String): AppCategory? {
    val text = label.lowercase()
    return when {
        listOf("游戏", "王者", "原神", "和平精英", "金铲铲", "星穹", "崩坏", "蛋仔", "光遇", "我的世界", "斗地主", "麻将", "消消乐", "英雄联盟", "lolm", "阴阳师", "方舟", "永劫", "吃鸡", "模拟器").any { text.contains(it) } -> AppCategory.GAME
        listOf("b站", "哔哩", "视频", "抖音", "快手", "优酷", "爱奇艺", "腾讯视频", "芒果tv", "追剧", "漫画", "直播").any { text.contains(it) } -> AppCategory.VIDEO
        listOf("微信", "qq", "微博", "贴吧", "聊天", "社交", "陌陌", "脉脉").any { text.contains(it) } -> AppCategory.SOCIAL
        listOf("学习", "课程", "单词", "词典", "知乎", "慕课", "作业", "题库", "考研", "笔记", "听力").any { text.contains(it) } -> AppCategory.STUDY
        else -> null
    }
}

/** 应用库：分类解析（用户设置 → 内置清单 → 应用名自动识别）+ 使用情况访问 + 前台应用查询。 */
object AppLibrary {
    /** 是否已授予“使用情况访问”（AppOps 特殊权限，非运行时权限）。 */
    fun hasUsageAccess(context: Context): Boolean {
        val stats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 24 * 60 * 60 * 1000L
        return runCatching { stats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end).isNotEmpty() }
            .getOrDefault(false) || runCatching {
            // 部分设备 queryUsageStats 为空但实际有权限：再查一次事件流。
            val events = stats.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var found = false
            while (events.hasNextEvent()) { events.getNextEvent(event); found = true; if (found) break }
            found
        }.getOrDefault(false)
    }

    fun openUsageAccessSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    /** 当前前台应用包名（取最近一次前台切换事件；无权限或不可得返回 null）。 */
    fun foregroundPackage(context: Context, lookbackMillis: Long = 2 * 60 * 1000L): String? {
        if (!hasUsageAccess(context)) return null
        val stats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - lookbackMillis
        var lastPackage: String? = null
        var lastTime = 0L
        runCatching {
            val events = stats.queryEvents(begin, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= lastTime) {
                    lastTime = event.timeStamp
                    lastPackage = event.packageName
                }
            }
        }
        return lastPackage
    }

    /** 包名 → 分类：先用户设置，再内置清单，再应用名自动识别（未识别为 UNKNOWN）。 */
    fun categoryOf(context: Context, packageName: String, userCategories: Map<String, String>): AppCategory {
        userCategories[packageName]?.let { raw -> AppCategory.fromName(raw)?.let { return it } }
        BUILTIN_APP_CATEGORIES[packageName]?.let { return it }
        val label = runCatching {
            val info: ApplicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        return if (label != null) autoCategoryByLabel(label) ?: AppCategory.UNKNOWN else AppCategory.UNKNOWN
    }

    /** 应用显示名（获取不到返回包名）。 */
    fun appLabel(context: Context, packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { packageName }
}
