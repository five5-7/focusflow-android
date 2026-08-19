package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommuteLearningTest {
    @Test fun record_computesMedianCalibration() {
        var p = CommuteProfile()
        p = CommuteLearning.record(p, "k", 10)
        p = CommuteLearning.record(p, "k", 20)
        p = CommuteLearning.record(p, "k", 30)
        assertEquals(listOf(10, 20, 30), p.routeObservations["k"])
        assertEquals(20, p.routeCalibrations["k"]) // median(10,20,30)
    }

    @Test fun record_keepsLastTwelve() {
        var p = CommuteProfile()
        (1..15).forEach { p = CommuteLearning.record(p, "k", it) }
        assertEquals(12, p.routeObservations["k"]?.size)
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), p.routeObservations["k"])
    }

    @Test fun undoLatest() {
        var p = CommuteProfile()
        p = CommuteLearning.record(p, "k", 10)
        p = CommuteLearning.record(p, "k", 20)
        p = CommuteLearning.undoLatest(p, "k")
        assertEquals(listOf(10), p.routeObservations["k"])
        assertEquals(10, p.routeCalibrations["k"])
    }

    @Test fun clear() {
        var p = CommuteProfile()
        p = CommuteLearning.record(p, "k", 10)
        p = CommuteLearning.clear(p, "k")
        assertNull(p.routeObservations["k"])
        assertNull(p.routeCalibrations["k"])
    }
}
