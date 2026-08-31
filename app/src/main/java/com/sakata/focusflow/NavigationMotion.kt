package com.sakata.focusflow

internal object NavigationMotion {
    fun direction(fromDepth: Int, toDepth: Int): Int = toDepth.compareTo(fromDepth)

    fun <T> historyAfterOpen(history: List<T>, current: T?, target: T): List<T> {
        if (current == null || current == target) return history
        val existing = history.indexOf(target)
        return if (existing >= 0) history.take(existing) else history + current
    }
}
