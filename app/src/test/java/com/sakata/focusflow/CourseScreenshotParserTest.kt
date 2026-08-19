package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseScreenshotParserTest {
    @Test fun buildingFromRoom_withRoom() {
        assertEquals("东1教学楼", CourseScreenshotParser.buildingFromRoom("东1A-302"))
    }

    @Test fun buildingFromRoom_blockOnly() {
        assertEquals("东1教学楼", CourseScreenshotParser.buildingFromRoom("东一B"))
    }

    @Test fun buildingFromRoom_trimsEllipsis() {
        assertEquals("东1教学楼", CourseScreenshotParser.buildingFromRoom("东1B-2..."))
    }

    @Test fun buildingFromRoom_unknown() {
        assertNull(CourseScreenshotParser.buildingFromRoom("随便"))
    }

    @Test fun toArabicDigits() {
        assertEquals("东1", CourseScreenshotParser.toArabicDigits("东一"))
    }

    @Test fun zoneByPrefix() {
        assertEquals(CampusZone.EAST_TEACHING, CourseScreenshotParser.zoneByPrefix("东1教学楼"))
        assertEquals(CampusZone.NORTH_TEACHING, CourseScreenshotParser.zoneByPrefix("北2"))
        assertEquals(CampusZone.CHEMISTRY_LABS, CourseScreenshotParser.zoneByPrefix("化学实验中心"))
        assertEquals(CampusZone.WEST_TEACHING, CourseScreenshotParser.zoneByPrefix("西1"))
    }

    @Test fun stripCampusPrefix() {
        assertEquals("东1A-213", CourseScreenshotParser.stripCampusPrefix("紫金港东1A-213"))
    }

    @Test fun normalize() {
        assertEquals("东1教学楼", CourseScreenshotParser.normalize("东 1 教学楼"))
    }

    @Test fun calculateSampleSize() {
        assertEquals(2, CourseScreenshotParser.calculateSampleSize(8000, 6000, 2048))
        assertEquals(1, CourseScreenshotParser.calculateSampleSize(1000, 1000, 2048))
    }
}
