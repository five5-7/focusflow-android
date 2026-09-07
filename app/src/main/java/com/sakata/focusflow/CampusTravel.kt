package com.sakata.focusflow

enum class CampusZone(val label: String) {
    WEST_TEACHING("西教学区"),
    EAST_TEACHING("东教学区"),
    NORTH_TEACHING("北教学区"),
    CHEMISTRY_LABS("化学实验中心"),
    LIBRARY("图书馆"),
    EAST_STADIUM("东田径场"),
    OTHER("其他")
}

/** lat/lng 可选：地点包与自定义地点可能带坐标（高德逆地理/POI 搜索所得），内置目录不带。 */
data class CampusPlace(val name: String, val zone: CampusZone, val kind: String, val lat: Double? = null, val lng: Double? = null)

/**
 * Initial, deliberately conservative estimates. They are planning buffers rather
 * than navigation directions and should be calibrated with the user's feedback.
 */
object ZijingangTravel {
    val places = listOf(
        CampusPlace("西1教学楼", CampusZone.WEST_TEACHING, "教学楼"),
        CampusPlace("西2教学楼", CampusZone.WEST_TEACHING, "教学楼"),
        CampusPlace("东4教学楼", CampusZone.EAST_TEACHING, "教学楼"),
        CampusPlace("东6教学楼", CampusZone.EAST_TEACHING, "教学楼"),
        CampusPlace("北2教学楼", CampusZone.NORTH_TEACHING, "教学楼"),
        CampusPlace("化学实验中心", CampusZone.CHEMISTRY_LABS, "实验"),
        CampusPlace("图书馆", CampusZone.LIBRARY, "学习"),
        CampusPlace("东田径场", CampusZone.EAST_STADIUM, "运动")
    )

    fun routeKey(from: CampusZone, to: CampusZone, mode: String): String {
        val zones = listOf(from.name, to.name).sorted()
        return "$mode|${zones[0]}|${zones[1]}"
    }

    fun calibratedMinutes(from: CampusZone, to: CampusZone, profile: CommuteProfile): Int? =
        profile.routeCalibrations[routeKey(from, to, profile.campusMode)]

    fun estimateMinutes(from: CampusZone, to: CampusZone, profile: CommuteProfile): Int {
        calibratedMinutes(from, to, profile)?.let { return it }
        val walkingMinutes = if (from == to) profile.nearMinutes else when (setOf(from, to)) {
            setOf(CampusZone.EAST_TEACHING, CampusZone.EAST_STADIUM),
            setOf(CampusZone.EAST_TEACHING, CampusZone.LIBRARY),
            setOf(CampusZone.LIBRARY, CampusZone.EAST_STADIUM) -> profile.fairlyNearMinutes
            setOf(CampusZone.WEST_TEACHING, CampusZone.NORTH_TEACHING),
            setOf(CampusZone.WEST_TEACHING, CampusZone.CHEMISTRY_LABS),
            setOf(CampusZone.EAST_TEACHING, CampusZone.NORTH_TEACHING) -> profile.fairlyFarMinutes
            setOf(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING) -> profile.farMinutes
            else -> if (profile.useDefaultForUnknown) profile.oneWayMinutes else 0
        }
        if (walkingMinutes <= 0) return 0
        // 档位只是可调整的规划缓冲；实测路线校准始终优先，不把校内分区当作导航距离。
        val travel = when (profile.campusMode) {
            "自行车" -> maxOf(3, (walkingMinutes * 0.6).toInt())
            "电动车" -> maxOf(3, (walkingMinutes * 0.5).toInt())
            else -> walkingMinutes
        }
        return travel + profile.buildingBufferMinutes * 2
    }
}
