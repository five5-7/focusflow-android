package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** baseline_profile / baseline_variants 的 JSON 边界：字段键名与缺省值固定（旧存档兼容）。 */
object BaselineProfileCodec {
    fun parse(json: JSONObject): BaselineProfile = BaselineProfile(
        lifeStage = LifeStage.fromKey(json.optString("lifeStage", "")),
        wakeMinute = json.optInt("wakeMinute", -1),
        sleepMinute = json.optInt("sleepMinute", -1),
        meals = runCatching {
            val values = json.optJSONArray("meals") ?: JSONArray()
            List(values.length()) { index ->
                val meal = values.getJSONObject(index)
                MealTimeline(
                    type = MealType.fromLabel(meal.getString("type")) ?: return@List MealTimeline(MealType.BREAKFAST, 480),
                    typicalStartMinute = meal.optInt("typicalStartMinute", 480).coerceIn(0, 24 * 60 - 1),
                    typicalMinutes = meal.optInt("typicalMinutes", 20).coerceIn(5, 120)
                )
            }
        }.getOrDefault(emptyList()),
        entertainmentWindow = json.optString("entertainmentWindow", ""),
        variantName = json.optString("variantName", ""),
        dayGroups = runCatching {
            val values = json.optJSONArray("dayGroups") ?: JSONArray()
            List(values.length()) { index ->
                val group = values.getJSONObject(index)
                val days = group.optJSONArray("days")?.let { daysArray -> List(daysArray.length()) { i -> daysArray.optInt(i, 0) }.filter { it in 1..7 }.toSet() } ?: emptySet()
                val meals = runCatching {
                    val mealValues = group.optJSONArray("meals") ?: JSONArray()
                    List(mealValues.length()) { i ->
                        val meal = mealValues.getJSONObject(i)
                        MealTimeline(
                            type = MealType.fromLabel(meal.getString("type")) ?: return@List MealTimeline(MealType.BREAKFAST, 480),
                            typicalStartMinute = meal.optInt("typicalStartMinute", 480).coerceIn(0, 24 * 60 - 1),
                            typicalMinutes = meal.optInt("typicalMinutes", 20).coerceIn(5, 120)
                        )
                    }
                }.getOrDefault(emptyList())
                DayGroup(group.optString("label", ""), days, group.optInt("wakeMinute", -1), group.optInt("sleepMinute", -1), meals)
            }
        }.getOrDefault(emptyList())
    )

    fun toJson(profile: BaselineProfile): JSONObject {
        val meals = JSONArray()
        profile.meals.forEach { meal -> meals.put(JSONObject().apply {
            put("type", meal.type.label)
            put("typicalStartMinute", meal.typicalStartMinute)
            put("typicalMinutes", meal.typicalMinutes)
        }) }
        val groups = JSONArray()
        profile.dayGroups.forEach { group ->
            groups.put(JSONObject().apply {
                put("label", group.label)
                val days = JSONArray()
                group.days.sorted().forEach { days.put(it) }
                put("days", days)
                put("wakeMinute", group.wakeMinute)
                put("sleepMinute", group.sleepMinute)
                val groupMeals = JSONArray()
                group.meals.forEach { meal -> groupMeals.put(JSONObject().apply {
                    put("type", meal.type.label)
                    put("typicalStartMinute", meal.typicalStartMinute)
                    put("typicalMinutes", meal.typicalMinutes)
                }) }
                put("meals", groupMeals)
            })
        }
        return JSONObject().apply {
            put("lifeStage", profile.lifeStage?.storageKey ?: "")
            put("wakeMinute", profile.wakeMinute)
            put("sleepMinute", profile.sleepMinute)
            put("meals", meals)
            put("entertainmentWindow", profile.entertainmentWindow)
            put("variantName", profile.variantName)
            put("dayGroups", groups)
        }
    }

    fun encode(profile: BaselineProfile): String = toJson(profile).toString()
}
