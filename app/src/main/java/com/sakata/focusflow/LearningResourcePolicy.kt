package com.sakata.focusflow

internal object LearningResourcePolicy {
    fun canSave(title: String, url: String, note: String): Boolean =
        title.isNotBlank() && (url.isNotBlank() || note.isNotBlank())

    fun candidateFirstAction(platform: String, keyword: String): String =
        "在${platform.trim()}搜索“${keyword.trim()}”，确认一份真实资料"
}
