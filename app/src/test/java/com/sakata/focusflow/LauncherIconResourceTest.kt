package com.sakata.focusflow

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LauncherIconResourceTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/res/$path")).documentElement
    private fun paths(name: String): List<Element> {
        val nodes = xml("drawable/$name.xml").getElementsByTagName("path")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun adaptiveResourcesUseSameForegroundAndBackground() {
        val old = xml("mipmap-anydpi-v26/ic_launcher.xml")
        val themed = xml("mipmap-anydpi-v33/ic_launcher.xml")
        for (tag in listOf("background", "foreground")) {
            val a = old.getElementsByTagName(tag).item(0) as Element
            val b = themed.getElementsByTagName(tag).item(0) as Element
            assertEquals(a.getAttributeNS(android, "drawable"), b.getAttributeNS(android, "drawable"))
        }
        val mono = themed.getElementsByTagName("monochrome").item(0) as Element
        assertEquals("@drawable/ic_launcher_monochrome", mono.getAttributeNS(android, "drawable"))
    }

    @Test fun themedIconPreservesAllForegroundPaths() {
        assertEquals(paths("ic_launcher_foreground").map { it.getAttributeNS(android, "pathData") },
            paths("ic_launcher_monochrome").map { it.getAttributeNS(android, "pathData") })
        paths("ic_launcher_monochrome").forEach {
            assertEquals("#FFFFFF", it.getAttributeNS(android, "strokeColor"))
            assertEquals("#00000000", it.getAttributeNS(android, "fillColor"))
        }
    }

    @Test fun allControlPointsAndStrokesFitAdaptiveSafeCircle() {
        // These paths use only absolute M/L/Q commands. A Bezier stays inside its
        // control-point convex hull, so this also covers the complete curves.
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val root = xml("drawable/$name.xml")
            assertEquals("108", root.getAttributeNS(android, "viewportWidth"))
            assertEquals("108", root.getAttributeNS(android, "viewportHeight"))
            for (path in paths(name)) {
                val data = path.getAttributeNS(android, "pathData")
                assertTrue(data.filter(Char::isLetter).all { it in "MLQ" })
                val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(data).map { it.value.toDouble() }.toList()
                assertEquals(0, values.size % 2)
                val radius = path.getAttributeNS(android, "strokeWidth").toDouble() / 2
                values.chunked(2).forEach { p ->
                    assertTrue("$name: $p outside safe circle", hypot(p[0] - 54, p[1] - 54) + radius <= 33)
                }
            }
        }
    }

    @Test fun launcherAndRoundLauncherShareAdaptiveResource() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "icon"))
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "roundIcon"))
    }
}
