package com.sakata.focusflow

import org.json.JSONArray
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
    private val VERSION_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)(-rc\.\d+)?$""")

    /** tag 是否符合本仓库版本约定（正式或候选）。 */
    fun isKnownTag(tag: String): Boolean = VERSION_TAG.matches(tag)

    /** 正式版判定：tag 为 vX.Y.Z 且非 prerelease/draft。 */
    fun isFormalTag(tag: String, prerelease: Boolean): Boolean =
        !prerelease && Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matches(tag)

    /**
     * 当前版本是否应升级到该候选：基号更高则升级；同基号时仅「正式包覆盖候选包」。
     */
    fun isNewer(currentVersionName: String, candidateVersionName: String, candidateIsFormal: Boolean): Boolean {
        val curBase = parseVersion(currentVersionName.substringBefore("-")) ?: return false
        val candBase = parseVersion(candidateVersionName.substringBefore("-")) ?: return false
        val cmp = compareVersion(curBase, candBase)
        if (cmp != 0) return cmp < 0
        return candidateIsFormal && currentVersionName.contains("-rc")
    }

    private fun parseVersion(v: String): Triple<Int, Int, Int>? = runCatching {
        val parts = v.trim().split(".")
        Triple(parts[0].toInt(), parts[1].toInt(), parts.getOrElse(2) { "0" }.toInt())
    }.getOrNull()

    private fun compareVersion(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int = when {
        a.first != b.first -> a.first - b.first
        a.second != b.second -> a.second - b.second
        else -> a.third - b.third
    }

    /**
     * 拉取 GitHub Releases（只信任本仓库来源），返回最新版本。
     * includePrerelease=false 时跳过一切候选（含 draft）。
     */
    fun fetchLatest(includePrerelease: Boolean): FormalRelease? {
        val conn = URL("https://api.github.com/repos/$REPO/releases").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FocusFlow")
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val tag = rel.optString("tag_name", "")
            if (!isKnownTag(tag)) continue
            val prerelease = rel.optBoolean("prerelease", false) || rel.optBoolean("draft", false)
            val isFormal = isFormalTag(tag, prerelease)
            if (!includePrerelease && !isFormal) continue
            if (prerelease && !includePrerelease) continue
            val versionName = tag.removePrefix("v")
            val assets = rel.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name", "").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null)
                        break
                    }
                }
            }
            return FormalRelease(versionName, tag, isFormal, rel.optString("html_url", ""), apkUrl)
        }
        return null
    }
}
