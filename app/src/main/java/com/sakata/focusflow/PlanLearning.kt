package com.sakata.focusflow

import org.json.JSONObject

/**
 * 完成率学习（目标智能化的一部分）：
 * 按（星期 × 开始小时）记录自动排入目标任务的 排入/完成 次数，
 * 自动排目标时优先历史完成率高的时段。数据不足时不标注（不假装精确）。
 */
object PlanLearning {
    private fun key(weekday: Int, startHour: Int) = "$weekday:$startHour"

    fun recordScheduled(store: PrototypeStore, weekday: Int, startHour: Int) {
        val data = load(store)
        val k = key(weekday, startHour)
        val entry = data.optJSONObject(k) ?: JSONObject()
        entry.put("s", entry.optInt("s", 0) + 1)
        data.put(k, entry)
        store.savePlanLearning(data)
    }

    fun recordCompleted(store: PrototypeStore, weekday: Int, startHour: Int) {
        val data = load(store)
        val k = key(weekday, startHour)
        val entry = data.optJSONObject(k) ?: return
        entry.put("c", entry.optInt("c", 0) + 1)
        data.put(k, entry)
        store.savePlanLearning(data)
    }

    /** 完成率（0..1）；样本 <3 返回 null。 */
    fun completionRate(store: PrototypeStore, weekday: Int, startHour: Int): Float? {
        val entry = load(store).optJSONObject(key(weekday, startHour)) ?: return null
        val scheduled = entry.optInt("s", 0)
        val completed = entry.optInt("c", 0)
        if (scheduled < 3) return null
        return completed.toFloat() / scheduled.toFloat()
    }

    fun load(store: PrototypeStore): JSONObject = runCatching { JSONObject(store.loadPlanLearningRaw()) }.getOrDefault(JSONObject())
}
