package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ZijingangTravelTest {
    private val profile = CommuteProfile() // 步行, 缓冲 3

    @Test fun sameZone_isTwoPlusBuffer() {
        assertEquals(8, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.WEST_TEACHING, profile))
    }

    @Test fun westEast_walk() {
        assertEquals(20, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, profile))
    }

    @Test fun bikeMode_scalesDown() {
        val bike = profile.copy(campusMode = "自行车")
        assertEquals(14, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, bike))
    }

    @Test fun routeKey_symmetric() {
        val k1 = ZijingangTravel.routeKey(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, "步行")
        val k2 = ZijingangTravel.routeKey(CampusZone.EAST_TEACHING, CampusZone.WEST_TEACHING, "步行")
        assertEquals(k1, k2)
    }
}
