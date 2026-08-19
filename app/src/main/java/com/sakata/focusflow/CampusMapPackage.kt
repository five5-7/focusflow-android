package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

data class CampusMapPackage(
    val name: String,
    val version: Int = 1,
    val places: List<CampusPlace>
)

object CampusMapPackageCodec {
    const val SUPPORTED_VERSION = 1
    private const val MAX_PLACES = 100

    fun parse(text: String): CampusMapPackage {
        require(text.isNotBlank()) { "地点包为空" }
        val root = JSONObject(text)
        val name = root.optString("name").trim()
        require(name.isNotBlank()) { "缺少地点包名称" }
        val version = root.optInt("version", SUPPORTED_VERSION)
        require(version == SUPPORTED_VERSION) { "暂不支持地点包版本 $version" }
        val values = root.optJSONArray("places") ?: throw IllegalArgumentException("缺少 places 列表")
        require(values.length() in 1..MAX_PLACES) { "地点数量需为 1–$MAX_PLACES 个" }

        val seenNames = mutableSetOf<String>()
        val places = List(values.length()) { index ->
            val value = values.optJSONObject(index) ?: throw IllegalArgumentException("第 ${index + 1} 个地点格式不正确")
            val placeName = value.optString("name").trim()
            require(placeName.isNotBlank()) { "第 ${index + 1} 个地点缺少名称" }
            require(seenNames.add(placeName.lowercase())) { "地点名称重复：$placeName" }
            parsePlace(value)
        }
        return CampusMapPackage(name = name, version = version, places = places)
    }

    fun encode(mapPackage: CampusMapPackage): String = JSONObject().apply {
        put("name", mapPackage.name)
        put("version", mapPackage.version)
        put("places", JSONArray().apply {
            mapPackage.places.forEach { place -> put(encodePlace(place)) }
        })
    }.toString()

    /** 单个地点的 JSON 解析，供地点包与自定义地点存储共用。lat/lng 可选，越界视为缺失。 */
    internal fun parsePlace(json: JSONObject): CampusPlace {
        val name = json.optString("name").trim()
        require(name.isNotBlank()) { "地点缺少名称" }
        val zoneName = json.optString("zone").trim()
        val zone = runCatching { CampusZone.valueOf(zoneName) }
            .getOrElse { throw IllegalArgumentException("$name 的分区无效：$zoneName") }
        val kind = json.optString("kind", "地点").trim().ifBlank { "地点" }
        val lat = json.optDouble("lat", Double.NaN).takeIf { !it.isNaN() && it in -90.0..90.0 }
        val lng = json.optDouble("lng", Double.NaN).takeIf { !it.isNaN() && it in -180.0..180.0 }
        return CampusPlace(name, zone, kind, lat, lng)
    }

    internal fun encodePlace(place: CampusPlace): JSONObject = JSONObject().apply {
        put("name", place.name)
        put("zone", place.zone.name)
        put("kind", place.kind)
        place.lat?.let { put("lat", it) }
        place.lng?.let { put("lng", it) }
    }
}
