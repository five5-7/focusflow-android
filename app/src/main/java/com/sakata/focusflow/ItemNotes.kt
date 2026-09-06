package com.sakata.focusflow

/** 备注与可变的日程状态分离。旧文本不猜测删除，首次修改状态时完整保留。 */
fun Item.editableNote(): String = userNote ?: sourceDetail.ifBlank { detail.removePrefix("刚刚记录 · ") }

fun Item.preservingNote(): Item = if (userNote != null) this else copy(userNote = editableNote())
