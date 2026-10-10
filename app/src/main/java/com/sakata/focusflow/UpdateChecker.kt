package com.sakata.focusflow

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 8.1.0 检查更新（仅 GitHub 来源）。
 * 正式版标识：GitHub Release 的 tag 形如 `vX.Y.Z`（无 -rc 后缀）且非 prerelease/draft，
 * 与 VERSIONING.md「正式 tag 用 vx.y.z、候选不建 tag」一致。
 * 勾选「接受候选版」后，prerelease（tag vX.Y.Z-rc.N）也可作为更新源；比较保持非对称：
 * 同基号时正式可覆盖候选，正式用户不接收同基号候选。
 */

/** 检查更新在设置页展示的状态。 */
internal data class UpdateCheckState(
    val checking: Boolean = false,
    val latestFormal: String? = null,
    val message: String? = null
)

internal data class FormalRelease(
    val versionName: String,
    val tag: String,
    val isFormal: Boolean,
    val pageUrl: String,
    val apkUrl: String?
)

internal object UpdateChecker {
    const val REPO = "five5-7/focusflow-android"
    private val VERSION_NAME = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$""")
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    private data class Version(val major: Int, val minor: Int, val patch: Int, val rc: Int?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val base = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
            if (base != 0) return base
            if (rc == other.rc) return 0
            if (rc == null) return 1
            if (other.rc == null) return -1
            return rc.compareTo(other.rc)
        }
    }

    /** tag 是否符合本仓库版本约定（正式或候选）。 */
    fun isKnownTag(tag: String): Boolean = tag.startsWith("v") && parseVersion(tag.drop(1)) != null

    /** 正式版判定：tag 为 vX.Y.Z 且非 prerelease/draft。 */
    fun isFormalTag(tag: String, prerelease: Boolean): Boolean =
        !prerelease && isKnownTag(tag) && !tag.contains("-rc.")

    /**
     * 按基号、候选序号、正式状态比较；同基号正式用户不接收候选降级。
     */
    fun isNewer(currentVersionName: String, candidateVersionName: String, candidateIsFormal: Boolean): Boolean {
        val current = parseVersion(currentVersionName) ?: return false
        val candidate = parseVersion(candidateVersionName) ?: return false
        if (candidateIsFormal != (candidate.rc == null)) return false
        return candidate > current
    }

    private fun parseVersion(value: String): Version? {
        val match = VERSION_NAME.matchEntire(value) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val rcText = match.groupValues[4]
        val rc = if (rcText.isEmpty()) null else rcText.toIntOrNull() ?: return null
        return Version(major, minor, patch, rc)
    }

    /**
     * 拉取 GitHub Releases（只信任本仓库来源），返回最新版本。
     * 草稿永不作为更新源；选择最高版本，不依赖 GitHub 按发布时间排列的顺序。
     */
    fun fetchLatest(includePrerelease: Boolean): FormalRelease? {
        val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=100").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FocusFlow")
        return try {
            val text = conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("release response exceeds limit")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            latestFromJson(text, includePrerelease)
        } finally {
            conn.disconnect()
        }
    }

    internal fun latestFromJson(text: String, includePrerelease: Boolean): FormalRelease? {
        val arr = JSONArray(text)
        var latest: FormalRelease? = null
        var latestVersion: Version? = null
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            val tag = rel.optString("tag_name", "")
            if (!isKnownTag(tag)) continue
            val versionName = tag.drop(1)
            val version = parseVersion(versionName) ?: continue
            val prerelease = rel.optBoolean("prerelease", false)
            val isFormal = isFormalTag(tag, prerelease)
            if (!isFormal && (!includePrerelease || version.rc == null)) continue
            if (latestVersion?.let { version <= it } == true) continue
            val assets = rel.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }
            latest = FormalRelease(versionName, tag, isFormal, rel.optString("html_url", ""), apkUrl)
            latestVersion = version
        }
        return latest
    }
}
