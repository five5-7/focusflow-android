package com.sakata.focusflow

object CommuteLearning {
    fun record(profile: CommuteProfile, routeKey: String, actualMinutes: Int): CommuteProfile {
        val updated = (profile.routeObservations[routeKey].orEmpty() + actualMinutes.coerceIn(1, 180)).takeLast(12)
        return profile.copy(
            routeObservations = profile.routeObservations + (routeKey to updated),
            routeCalibrations = profile.routeCalibrations + (routeKey to median(updated))
        )
    }

    fun undoLatest(profile: CommuteProfile, routeKey: String): CommuteProfile {
        val remaining = profile.routeObservations[routeKey].orEmpty().dropLast(1)
        return if (remaining.isEmpty()) clear(profile, routeKey) else profile.copy(
            routeObservations = profile.routeObservations + (routeKey to remaining),
            routeCalibrations = profile.routeCalibrations + (routeKey to median(remaining))
        )
    }

    fun clear(profile: CommuteProfile, routeKey: String): CommuteProfile = profile.copy(
        routeObservations = profile.routeObservations - routeKey,
        routeCalibrations = profile.routeCalibrations - routeKey
    )

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
