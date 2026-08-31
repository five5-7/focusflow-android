package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** 通勤路由数据的 JSON 边界：route_calibrations（每条路线一次校准分钟）与 route_observations（观测分钟序列）。 */
object CommuteRouteCodec {
    /** route_calibrations：仅接受 1..180 分钟的合法值。 */
    fun decodeCalibrations(raw: String): Map<String, Int> = runCatching {
        val values = JSONObject(raw.ifBlank { "{}" })
        val parsed = mutableMapOf<String, Int>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values.optInt(key).takeIf { it in 1..180 }?.let { parsed[key] = it }
        }
        parsed
    }.getOrDefault(emptyMap())

    /** route_observations：每条路线保留最近 12 次 1..180 分钟的观测。 */
    fun decodeObservations(raw: String): Map<String, List<Int>> = runCatching {
        val values = JSONObject(raw.ifBlank { "{}" })
        val parsed = mutableMapOf<String, List<Int>>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entries = values.optJSONArray(key) ?: continue
            val minutes = List(entries.length()) { entries.optInt(it) }.filter { it in 1..180 }.takeLast(12)
            if (minutes.isNotEmpty()) parsed[key] = minutes
        }
        parsed
    }.getOrDefault(emptyMap())

    fun encodeCalibrations(calibrations: Map<String, Int>): String = JSONObject(calibrations).toString()

    fun encodeObservations(observations: Map<String, List<Int>>): String = JSONObject().apply {
        observations.forEach { (key, values) ->
            put(key, JSONArray().apply { values.takeLast(12).forEach { put(it) } })
        }
    }.toString()

    /** 旧版 route_calibrations 作为一次性观测的迁移回退。 */
    fun legacyObservations(raw: String): Map<String, List<Int>> = decodeCalibrations(raw).mapValues { listOf(it.value) }
}
