package com.sakata.focusflow

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 8.1.0 检查更新（仅 GitHub 正式版）。
 * 正式版标识：GitHub Release 的 tag 形如 `vX.Y.Z`（无 -rc 后缀）且非 prerelease/draft，
 * 与 VERSIONING.md「正式 tag 用 vx.y.z、候选不建 tag」一致。
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
    val pageUrl: String,
    val apkUrl: String?
)

internal object UpdateChecker {
    const val REPO = "five5-7/focusflow-android"
    private val FORMAL_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

    /** 正式版判定：tag 为 vX.Y.Z 且非 prerelease/draft。 */
    fun isFormalTag(tag: String, prerelease: Boolean): Boolean = !prerelease && FORMAL_TAG.matches(tag)

    /** 当前版本是否应升级到该正式版：正式版基号更高，或同基号且当前是候选（-rc）。 */
    fun isNewer(currentVersionName: String, formalVersionName: String): Boolean {
        val currentBase = currentVersionName.substringBefore("-")
        val c = parseVersion(currentBase) ?: return false
        val r = parseVersion(formalVersionName) ?: return false
        val cmp = compareVersion(c, r)
        return cmp < 0 || (cmp == 0 && currentVersionName.contains("-rc"))
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

    /** 拉取 GitHub Releases（只信任本仓库来源），返回最新正式版；无正式版或网络失败返回 null。 */
    fun fetchLatestFormal(): FormalRelease? {
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
            val prerelease = rel.optBoolean("prerelease", false) || rel.optBoolean("draft", false)
            if (!isFormalTag(tag, prerelease)) continue
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
            return FormalRelease(versionName, tag, rel.optString("html_url", ""), apkUrl)
        }
        return null
    }
}
