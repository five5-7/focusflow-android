package com.sakata.focusflow

internal object NavigationMotion {
    fun direction(fromDepth: Int, toDepth: Int): Int = toDepth.compareTo(fromDepth)
}
