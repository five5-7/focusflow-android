package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun laterRcOfSameVersionIsAnUpdate() {
        assertTrue(UpdateChecker.isNewer("9.0.0-rc.1", "9.0.0-rc.2", false))
        assertTrue(UpdateChecker.isNewer("9.0.0-rc.9", "9.0.0-rc.10", false))
        assertFalse(UpdateChecker.isNewer("9.0.0-rc.10", "9.0.0-rc.2", false))
        assertFalse(UpdateChecker.isNewer("9.0.0-rc.2", "9.0.0-rc.2", false))
    }

    @Test fun formalVersionSupersedesRcWithoutDowngradingFormalUsers() {
        assertTrue(UpdateChecker.isNewer("9.0.0-rc.4", "9.0.0", true))
        assertFalse(UpdateChecker.isNewer("9.0.0", "9.0.0-rc.99", false))
        assertTrue(UpdateChecker.isNewer("8.2.1", "9.0.0-rc.1", false))
        assertFalse(UpdateChecker.isNewer("9.0.0-rc.1", "8.2.2", true))
    }

    @Test fun invalidVersionsAndMismatchedReleaseStatusAreRejected() {
        for (invalid in listOf("9.0", "9.0.0.1", "9.0.0-beta.1", "9.0.0-rc.x", "2147483648.0.0")) {
            assertFalse(invalid, UpdateChecker.isNewer("8.2.1", invalid, true))
            assertFalse(invalid, UpdateChecker.isKnownTag("v$invalid"))
        }
        assertFalse(UpdateChecker.isNewer("8.2.1", "9.0.0-rc.1", true))
        assertFalse(UpdateChecker.isNewer("8.2.1", "9.0.0", false))
        assertFalse(UpdateChecker.isNewer("broken", "9.0.0", true))
    }

    @Test fun highestSemanticVersionWinsOverPublicationOrder() {
        val json = JSONArray().put(release("v8.2.2")).put(release("v9.0.0")).put(release("v8.2.1")).toString()
        assertEquals("9.0.0", UpdateChecker.latestFromJson(json, false)?.versionName)
    }

    @Test fun draftsAndInvalidPrereleaseTagsAreNeverOfferedEvenWhenRcIsEnabled() {
        val json = JSONArray()
            .put(release("v10.0.0", draft = true))
            .put(release("v9.1.0", prerelease = true))
            .put(release("v9.0.0-rc.9", prerelease = true))
            .put(release("v9.0.0-rc.10", prerelease = true))
            .put(release("v8.2.1")).toString()
        assertEquals("9.0.0-rc.10", UpdateChecker.latestFromJson(json, true)?.versionName)
        assertEquals("8.2.1", UpdateChecker.latestFromJson(json, false)?.versionName)
    }

    @Test fun formalWinsOverSameBaseRcAndMissingApkKeepsReleasePageFallback() {
        val json = JSONArray().put(release("v9.0.0-rc.10", prerelease = true)).put(release("v9.0.0")).toString()
        val latest = UpdateChecker.latestFromJson(json, true)!!
        assertTrue(latest.isFormal)
        assertEquals("9.0.0", latest.versionName)
        assertNull(latest.apkUrl)
        assertTrue(latest.pageUrl.endsWith("v9.0.0"))
    }

    @Test fun malformedEntriesAreSkippedAndApkAssetCanUseUppercaseExtension() {
        val json = JSONArray().put(JSONObject.NULL).put("invalid").put(
            release("v9.0.0").put("assets", JSONArray().put(JSONObject.NULL).put(
                JSONObject().put("name", "FocusFlow.APK").put("browser_download_url", "https://github.com/test/app.apk")
            ))
        ).toString()
        assertEquals("https://github.com/test/app.apk", UpdateChecker.latestFromJson(json, false)?.apkUrl)
        assertNull(UpdateChecker.latestFromJson(JSONArray().put(release("v9.0.0", draft = true)).toString(), true))
    }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("prerelease", prerelease).put("draft", draft)
        .put("html_url", "https://github.com/${UpdateChecker.REPO}/releases/tag/$tag")
}
