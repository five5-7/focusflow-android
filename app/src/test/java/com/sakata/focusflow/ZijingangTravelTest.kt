package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ZijingangTravelTest {
    private val profile = CommuteProfile(enabled = true) // 步行, 缓冲 3

    @Test fun sameZone_usesNearTierPlusBuffer() {
        assertEquals(11, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.WEST_TEACHING, profile))
    }

    @Test fun westEast_walk() {
        assertEquals(31, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, profile))
    }

    @Test fun bikeMode_scalesDown() {
        val bike = profile.copy(campusMode = "自行车")
        assertEquals(21, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, bike))
    }

    @Test fun transportReserves_areIndependentAndAffectPlanning() {
        val configured = profile.copy(
            walkingReserveMinutes = 20,
            bicycleReserveMinutes = 8,
            eBikeReserveMinutes = 4
        )
        assertEquals(20, configured.reserveMinutesFor("步行"))
        assertEquals(8, configured.reserveMinutesFor("自行车"))
        assertEquals(4, configured.reserveMinutesFor("电动车"))
        assertEquals(16, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, configured.copy(campusMode = "自行车")))
        assertEquals(11, ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, configured.copy(campusMode = "电动车")))
    }

    @Test fun legacyProfile_keepsTheFormerTransportDefaults() {
        assertEquals(10, profile.reserveMinutesFor("步行"))
        assertEquals(6, profile.reserveMinutesFor("自行车"))
        assertEquals(5, profile.reserveMinutesFor("电动车"))
    }

    @Test fun routeKey_symmetric() {
        val k1 = ZijingangTravel.routeKey(CampusZone.WEST_TEACHING, CampusZone.EAST_TEACHING, "步行")
        val k2 = ZijingangTravel.routeKey(CampusZone.EAST_TEACHING, CampusZone.WEST_TEACHING, "步行")
        assertEquals(k1, k2)
    }
}
