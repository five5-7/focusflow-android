package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HealthConnectManifestContractTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun manifestDeclaresSleepPermissionAndRationaleEntrypoints() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val permissions = manifest.getElementsByTagName("uses-permission")
        assertTrue((0 until permissions.length).map { permissions.item(it) as Element }
            .any { it.getAttributeNS(android, "name") == "android.permission.health.READ_SLEEP" })

        val activities = manifest.getElementsByTagName("activity")
        assertTrue((0 until activities.length).map { activities.item(it) as Element }
            .any { it.getAttributeNS(android, "name") == ".HealthPermissionsRationaleActivity" })

        val aliases = manifest.getElementsByTagName("activity-alias")
        val alias = (0 until aliases.length).map { aliases.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".ViewHealthPermissionUsageActivity" }
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.getAttributeNS(android, "permission"))
        assertEquals(".HealthPermissionsRationaleActivity", alias.getAttributeNS(android, "targetActivity"))
    }
}
