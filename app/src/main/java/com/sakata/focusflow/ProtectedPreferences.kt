package com.sakata.focusflow

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Receivers and screens share protection, including newly created Store instances. */
internal object StorageProtection {
    private data class Pending(val dir: File, val key: String, val raw: String)
    private val pending = linkedMapOf<String, Pending>()
    var readOnly by mutableStateOf(false)
        private set

    @Synchronized fun backup(dir: File, key: String, raw: String) {
        if (!CorruptionBackup.shouldBackup(raw)) return
        val id = "${dir.absolutePath}/$key"
        if (CorruptionBackup.backup(dir, key, raw)) pending.remove(id)
        else pending[id] = Pending(dir, key, raw)
        readOnly = pending.isNotEmpty()
    }

    @Synchronized fun retry() {
        pending.values.toList().forEach { backup(it.dir, it.key, it.raw) }
    }

    @Synchronized fun write(action: () -> Boolean): Boolean = if (readOnly) false else action()
}

/** Block every put/remove/clear while original data has no safe backup. */
internal class ProtectedPreferences(private val source: SharedPreferences) : SharedPreferences by source {
    override fun edit(): SharedPreferences.Editor = ProtectedEditor(source.edit())
    private class ProtectedEditor(private val source: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { source.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { source.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { source.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { source.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { source.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { source.putBoolean(key, value) }
        override fun remove(key: String?) = apply { source.remove(key) }
        override fun clear() = apply { source.clear() }
        override fun commit(): Boolean = StorageProtection.write { source.commit() }
        override fun apply() { StorageProtection.write { source.apply(); true } }
    }
}
