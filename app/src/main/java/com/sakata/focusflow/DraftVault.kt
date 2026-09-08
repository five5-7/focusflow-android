package com.sakata.focusflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用级草稿保险箱的 CompositionLocal：FocusFlowApp 根部提供，弹窗内直接读取，
 * 无需为每个弹窗增加参数或改动调用点。未提供时抛错（避免预览静默丢失草稿）。
 */
internal val LocalDraftVault = staticCompositionLocalOf<DraftVault> { error("No DraftVault provided") }

@Composable
internal fun ProvideDraftVault(vault: DraftVault, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDraftVault provides vault, content = content)
}

/**
 * 会话内草稿保险箱（仅内存，跨重启不保留，与页面历史同一生命周期）。
 *
 * 弹窗接入约定（同步模式，避免异步竞态）：
 * 1. 打开时 `load(key)`，有草稿则恢复，无则用初始值；
 * 2. 每个变更点（onValueChange / onClick 修改字段后）同步调用 `save(key, draft)`；
 * 3. 确认保存/提交成功后 `clear(key)`；单纯关闭（onDismiss）不删草稿。
 *
 * key 建议格式："弹窗类型:对象id"（新建类弹窗用固定串，如 "goalEdit:new"）。
 * 每个弹窗只存一种固定类型，load 按类型回读，类型不匹配返回 null。
 */
internal class DraftVault {
    private val store = mutableMapOf<String, Any?>()

    fun <T> save(key: String, draft: T) {
        store[key] = draft
    }

    /** reified 读：类型不匹配返回 null（真实运行时类型检查，避免 key 撞车时抛 ClassCastException）。 */
    inline fun <reified T> load(key: String): T? = store[key] as? T

    fun has(key: String): Boolean = store.containsKey(key)

    fun clear(key: String) {
        store.remove(key)
    }

    fun clearAll() {
        store.clear()
    }
}
