package com.sakata.focusflow

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface VisionCipher { fun encrypt(value: String): String; fun decrypt(value: String): String }
internal class AndroidVisionCipher : VisionCipher {
    private val alias = "focusflow.stage8.credentials.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "missing device key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(true))
        return JSONObject().put("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).put("cipher", Base64.encodeToString(c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).toString()
    }
    override fun decrypt(value: String): String {
        val o = JSONObject(value); val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(o.getString("cipher"), Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
sealed class VisionCredentialRead {
    data object Missing : VisionCredentialRead()
    class Ready(val secret: String, val revision: String) : VisionCredentialRead() { override fun toString() = "Ready([redacted])" }
    data object Unavailable : VisionCredentialRead()
    data object Uncertain : VisionCredentialRead()
}

/** All instances share the same lock and failed-commit guard. No readback is proof of disk persistence. */
class VisionCredentialStore internal constructor(
    private val encrypted: SharedPreferences, private val legacy: SharedPreferences, private val cipher: VisionCipher
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("focusflow_credentials", Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences("focusflow", Context.MODE_PRIVATE), AndroidVisionCipher())
    companion object {
        const val SHARED_REF = "siliconflow-shared"
        internal val lock = Any()
        private val uncertain = mutableSetOf<SharedPreferences>()
        internal fun resetGuardsForTest() = synchronized(lock) { uncertain.clear() }
    }
    private fun commit(prefs: SharedPreferences, editor: SharedPreferences.Editor): Boolean {
        if (StorageProtection.readOnly) return false
        val ok = runCatching { StorageProtection.write { editor.commit() } }.getOrDefault(false)
        if (ok) uncertain.remove(prefs) else uncertain.add(prefs)
        return ok
    }
    fun read(ref: String): VisionCredentialRead = synchronized(lock) {
        if (encrypted in uncertain || (ref == SHARED_REF && legacy in uncertain)) return@synchronized VisionCredentialRead.Uncertain
        val raw = encrypted.all[ref] ?: return@synchronized VisionCredentialRead.Missing
        runCatching {
            require(raw is String && raw.length <= 32768 && VisionJsonSyntax.valid(raw))
            val o = JSONObject(raw); require(o.keySet() == setOf("version","revision","encrypted") && o.get("version") is Int && o.getInt("version") == 1)
            require(o.get("revision") is String && o.getString("revision").isNotBlank() && o.get("encrypted") is String)
            val secret = cipher.decrypt(o.getString("encrypted")); require(secret.isNotBlank() && secret.length <= 8192 && secret.none { it == '\r' || it == '\n' })
            VisionCredentialRead.Ready(secret, o.getString("revision"))
        }.getOrElse { VisionCredentialRead.Unavailable }
    }
    fun save(ref: String, value: String): Boolean = synchronized(lock) {
        val secret = value.trim(); if (ref.isBlank() || secret.isBlank() || secret.length > 8192 || secret.any { it == '\r' || it == '\n' } || StorageProtection.readOnly) return@synchronized false
        val raw = runCatching {
            val sealed = cipher.encrypt(secret); require(cipher.decrypt(sealed) == secret)
            JSONObject().put("version",1).put("revision",UUID.randomUUID().toString()).put("encrypted",sealed).toString()
        }.getOrNull() ?: return@synchronized false
        commit(encrypted, encrypted.edit().putString(ref, raw))
    }
    fun remove(ref: String): Boolean = synchronized(lock) { commit(encrypted, encrypted.edit().remove(ref)) }
    /** Confirm encrypted write and decrypt, then confirm removal. Failed migration leaves original data intact/retryable. */
    fun migrateShared(): VisionCredentialRead = synchronized(lock) {
        val old = legacy.all["siliconflow_api_key"]
        if (old == null) {
            if (legacy in uncertain && !commit(legacy, legacy.edit().remove("siliconflow_api_key"))) return@synchronized VisionCredentialRead.Uncertain
            return@synchronized read(SHARED_REF)
        }
        if (legacy in uncertain) {
            // Reconfirm the same legacy mapping before reading a guarded credential.
            if (!commit(legacy, legacy.edit().putString("siliconflow_api_key", old as? String ?: return@synchronized VisionCredentialRead.Unavailable))) return@synchronized VisionCredentialRead.Uncertain
        }
        if (old !is String) return@synchronized VisionCredentialRead.Unavailable
        if (old.isBlank()) {
            if (!commit(legacy, legacy.edit().remove("siliconflow_api_key"))) return@synchronized VisionCredentialRead.Uncertain
            return@synchronized read(SHARED_REF)
        }
        val current = read(SHARED_REF)
        if (current is VisionCredentialRead.Ready && current.secret != old.trim()) return@synchronized VisionCredentialRead.Unavailable
        if (current == VisionCredentialRead.Unavailable) return@synchronized current
        if (current !is VisionCredentialRead.Ready && !save(SHARED_REF, old)) return@synchronized VisionCredentialRead.Uncertain
        val confirmed = read(SHARED_REF)
        if (confirmed !is VisionCredentialRead.Ready || confirmed.secret != old.trim()) return@synchronized VisionCredentialRead.Unavailable
        if (!commit(legacy, legacy.edit().remove("siliconflow_api_key"))) return@synchronized VisionCredentialRead.Uncertain
        confirmed
    }
    /** Shared key replacement also retires an old plaintext key. */
    fun saveShared(value: String): Boolean = synchronized(lock) {
        if (value.isBlank()) {
            if (!remove(SHARED_REF)) return@synchronized false
        } else if (!save(SHARED_REF, value)) return@synchronized false
        commit(legacy, legacy.edit().remove("siliconflow_api_key"))
    }
}
