package com.sakata.focusflow

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

sealed class VisionConfigurationRead {
    data class Ready(val configuration: VisionServiceConfiguration) : VisionConfigurationRead()
    data class Invalid(val raw: Any?) : VisionConfigurationRead()
    data object Uncertain : VisionConfigurationRead()
}
class VisionServiceStore internal constructor(private val prefs: SharedPreferences, private val legacy: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("focusflow_vision_services", Context.MODE_PRIVATE), context.applicationContext.getSharedPreferences("focusflow", Context.MODE_PRIVATE))
    companion object { private val uncertain = mutableSetOf<SharedPreferences>(); internal fun resetGuardsForTest() = synchronized(VisionCredentialStore.lock) { uncertain.clear() } }
    fun read(): VisionConfigurationRead = synchronized(VisionCredentialStore.lock) {
        if (prefs in uncertain) return@synchronized VisionConfigurationRead.Uncertain
        val raw = prefs.all["configuration"]
        if (raw == null) {
            val model = (legacy.all["course_vision_model"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_COURSE_VISION_MODEL
            val migrated = when(model) { "Qwen/Qwen2.5-VL-7B-Instruct" -> DEFAULT_COURSE_VISION_MODEL; "Qwen/Qwen2.5-VL-32B-Instruct" -> "Qwen/Qwen3-VL-32B-Instruct"; else -> model }
            return@synchronized VisionConfigurationRead.Ready(VisionServiceConfiguration(listOf(VisionServiceProfile("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", migrated, VisionCredentialStore.SHARED_REF, "legacy-1"))))
        }
        val decoded = (raw as? String)?.let(VisionProfileCodec::decode)
        if (decoded == null) VisionConfigurationRead.Invalid(raw) else VisionConfigurationRead.Ready(decoded)
    }
    private fun write(value: VisionServiceConfiguration): Boolean {
        if (StorageProtection.readOnly) return false
        val raw = runCatching { VisionProfileCodec.encode(value) }.getOrNull() ?: return false
        val ok = runCatching { StorageProtection.write { prefs.edit().putString("configuration",raw).commit() } }.getOrDefault(false)
        if (ok) uncertain.remove(prefs) else uncertain.add(prefs)
        return ok
    }
    fun retryPendingWrite(): Boolean = synchronized(VisionCredentialStore.lock) {
        if (prefs !in uncertain) return@synchronized false
        val raw = prefs.all["configuration"] as? String ?: return@synchronized false
        val decoded = VisionProfileCodec.decode(raw) ?: return@synchronized false
        write(decoded)
    }
    fun saveProfile(expected: VisionServiceConfiguration, input: VisionServiceProfile): Boolean = synchronized(VisionCredentialStore.lock) {
        val current = read() as? VisionConfigurationRead.Ready ?: return@synchronized false
        if (current.configuration != expected || input.rejection() != null) return@synchronized false
        val old = expected.profiles.find { it.id == input.id }
        val unchanged = old?.copy(verifiedCredentialRevision = null, probeVersion = null, revision = input.revision) == input.copy(verifiedCredentialRevision = null, probeVersion = null)
        val saved = if (unchanged) input.copy(revision = old!!.revision, verifiedCredentialRevision = old.verifiedCredentialRevision, probeVersion = old.probeVersion)
            else input.copy(revision = UUID.randomUUID().toString(), verifiedCredentialRevision = null, probeVersion = null)
        val profiles = if (old == null) expected.profiles + saved else expected.profiles.map { if(it.id == saved.id) saved else it }
        write(VisionServiceConfiguration(profiles, expected.defaultId.takeUnless { it == saved.id && !unchanged }))
    }
    fun recordProbe(expected: VisionServiceProfile, keyRevision: String, result: VisionCapabilityResult, vault: VisionCredentialStore): Boolean = synchronized(VisionCredentialStore.lock) {
        val current = (read() as? VisionConfigurationRead.Ready)?.configuration ?: return@synchronized false
        if (current.profiles.find { it.id == expected.id } != expected || (vault.read(expected.credentialRef) as? VisionCredentialRead.Ready)?.revision != keyRevision || StorageProtection.readOnly) return@synchronized false
        val next = current.copy(profiles = current.profiles.map { if(it.id == expected.id) it.copy(verifiedCredentialRevision = if(result.passed) keyRevision else null, probeVersion = if(result.passed) VisionLimits.PROBE_VERSION else null) else it }, defaultId = current.defaultId.takeUnless { it == expected.id && !result.passed })
        val record = org.json.JSONObject().put("profileRevision",expected.revision).put("credentialRevision",keyRevision)
            .put("probeVersion",VisionLimits.PROBE_VERSION).put("testedAt",System.currentTimeMillis()).put("connected",result.connected).put("image",result.image).put("structured",result.structured)
        val ok = runCatching { StorageProtection.write { prefs.edit().putString("configuration",VisionProfileCodec.encode(next)).putString("probe_" + expected.id,record.toString()).commit() } }.getOrDefault(false)
        if(ok) uncertain.remove(prefs) else uncertain.add(prefs)
        ok
    }
    internal fun markVerified(expected: VisionServiceProfile, keyRevision: String, vault: VisionCredentialStore): Boolean = recordProbe(expected,keyRevision,VisionCapabilityResult(true,true,true,""),vault)
    fun setDefault(id: String, vault: VisionCredentialStore): Boolean = synchronized(VisionCredentialStore.lock) {
        val current = (read() as? VisionConfigurationRead.Ready)?.configuration ?: return@synchronized false
        val profile = current.profiles.find { it.id == id } ?: return@synchronized false
        val key = vault.read(profile.credentialRef) as? VisionCredentialRead.Ready ?: return@synchronized false
        if (!profile.verified(key.revision)) return@synchronized false
        write(current.copy(defaultId = id))
    }
    fun delete(expected: VisionServiceConfiguration, id: String): Boolean = synchronized(VisionCredentialStore.lock) {
        if ((read() as? VisionConfigurationRead.Ready)?.configuration != expected) return@synchronized false
        write(expected.copy(profiles = expected.profiles.filterNot { it.id == id }, defaultId = expected.defaultId.takeUnless { it == id }))
    }
    fun verifiedProfiles(vault: VisionCredentialStore): List<VisionServiceProfile> = (read() as? VisionConfigurationRead.Ready)?.configuration?.profiles.orEmpty().filter { p ->
        val key = vault.read(p.credentialRef) as? VisionCredentialRead.Ready
        key != null && p.verified(key.revision)
    }
    fun currentAndVerified(profile: VisionServiceProfile, vault: VisionCredentialStore): Boolean = synchronized(VisionCredentialStore.lock) {
        val now = (read() as? VisionConfigurationRead.Ready)?.configuration?.profiles?.find { it.id == profile.id }
        val key = vault.read(profile.credentialRef) as? VisionCredentialRead.Ready
        now == profile && key != null && profile.verified(key.revision)
    }
}
