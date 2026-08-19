package com.sakata.focusflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德 Web 服务 REST 客户端（可选的地图 API 优化）。
 * 需要"Web 服务"类型 key；手机端 SDK key 绑定应用，REST 调用会返回 INVALID_USER_KEY。
 * 只在用户填写 key 后使用，本地退化版地点管理不依赖它。
 */
data class AmapPoi(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val type: String,
    /** 距查询中心距离（米）；文本搜索等无中心查询时为 -1。 */
    val distance: Int = -1
)

/** 校区中心配置：POI 周边搜索的圆心与降级全城搜索的城市。 */
data class CampusCenter(val lat: Double, val lng: Double, val city: String)

object AmapWebApi {
    private const val PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text"
    private const val PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around"
    private const val REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo"
    private const val MAX_BYTES = 256 * 1024

    /** 紫金港校区中心（约）：周边搜索的默认圆心，半径 3000 米覆盖整个校区。 */
    val ZIJINGANG_CENTER = 30.3045 to 120.0840

    /** 默认校区中心：浙大紫金港 · 杭州；其他学校可改（见设置页）。 */
    fun defaultCampusCenter() = CampusCenter(ZIJINGANG_CENTER.first, ZIJINGANG_CENTER.second, "杭州")

    /** 按关键词搜索 POI（全城文本搜索，citylimit 锁定城市），失败抛异常由调用方转文案。 */
    suspend fun searchPois(key: String, keyword: String, city: String = "杭州"): List<AmapPoi> = withContext(Dispatchers.IO) {
        val params = "key=$key&keywords=${URLEncoder.encode(keyword, "UTF-8")}&city=${URLEncoder.encode(city, "UTF-8")}&citylimit=true&offset=20&page=1&extensions=base"
        val connection = (URL("$PLACE_TEXT_URL?$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }
        val body = readBody(connection)
        val root = JSONObject(body)
        require(root.optString("status") == "1") { "搜索失败：${root.optString("info", "未知错误")}" }
        val values = root.optJSONArray("pois") ?: return@withContext emptyList()
        List(values.length()) { index ->
            val value = values.optJSONObject(index) ?: return@List AmapPoi("", 0.0, 0.0, "", "")
            val location = value.optString("location", "0,0").split(",")
            AmapPoi(
                name = value.optString("name", ""),
                lat = location.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                lng = location.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                address = value.optString("address", ""),
                type = value.optString("type", "")
            )
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 校园范围内按关键词搜索 POI（place/around，按距离排序）。
     * 结果只落在以 (lat, lng) 为圆心、radius 米的圆内，比全城文本搜索准确得多；
     * 返回的 distance 为距圆心米数，可帮助判断是否在校园内。
     */
    suspend fun searchAroundPois(key: String, keyword: String, lat: Double, lng: Double, radius: Int = 3000): List<AmapPoi> = withContext(Dispatchers.IO) {
        val params = "key=$key&location=$lng,$lat&keywords=${URLEncoder.encode(keyword, "UTF-8")}&radius=$radius&offset=25&page=1&sortrule=distance&extensions=base"
        val connection = (URL("$PLACE_AROUND_URL?$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }
        val body = readBody(connection)
        val root = JSONObject(body)
        require(root.optString("status") == "1") { "搜索失败：${root.optString("info", "未知错误")}" }
        val values = root.optJSONArray("pois") ?: return@withContext emptyList()
        List(values.length()) { index ->
            val value = values.optJSONObject(index) ?: return@List AmapPoi("", 0.0, 0.0, "", "")
            val location = value.optString("location", "0,0").split(",")
            AmapPoi(
                name = value.optString("name", ""),
                lat = location.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                lng = location.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                address = value.optString("address", ""),
                type = value.optString("type", ""),
                distance = value.optInt("distance", -1)
            )
        }.filter { it.name.isNotBlank() }
    }

    /** 逆地理编码：坐标 → 建议名称（优先街道/兴趣点），失败返回 null。 */
    suspend fun reverseGeocode(key: String, lat: Double, lng: Double): String? = runCatching {
        withContext(Dispatchers.IO) {
            val params = "key=$key&location=$lng,$lat&extensions=base"
            val connection = (URL("$REGEO_URL?$params").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            val root = JSONObject(readBody(connection))
            if (root.optString("status") != "1") return@withContext null
            val regeocode = root.optJSONObject("regeocode") ?: return@withContext null
            val addressComponent = regeocode.optJSONObject("addressComponent") ?: return@withContext null
            val formatted = regeocode.optString("formatted_address", "")
            val poi = regeocode.optJSONArray("pois")?.optJSONObject(0)?.optString("name")
            val poiName = poi?.takeIf { it.isNotBlank() }
            val district = addressComponent.optString("district", "")
            poiName ?: district.takeIf { it.isNotBlank() } ?: formatted.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** 按 POI 类型关键词映射到现有 kind 集合（教学楼/实验/学习/运动/地点）。 */
    fun suggestKind(poiType: String): String {
        val type = poiType.lowercase()
        return when {
            type.contains("教学楼") || type.contains("教室") -> "教学楼"
            type.contains("实验") || type.contains("实验室") -> "实验"
            type.contains("图书馆") || type.contains("自习") -> "学习"
            type.contains("体育") || type.contains("运动") || type.contains("操场") || type.contains("球场") -> "运动"
            else -> "地点"
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
            val buffer = StringBuilder()
            val chunk = CharArray(8192)
            var total = 0
            while (total < MAX_BYTES) {
                val count = reader.read(chunk, 0, minOf(8192, MAX_BYTES - total))
                if (count < 0) break
                buffer.append(chunk, 0, count)
                total += count
            }
            reader.close()
            return buffer.toString()
        } finally {
            connection.disconnect()
        }
    }
}
