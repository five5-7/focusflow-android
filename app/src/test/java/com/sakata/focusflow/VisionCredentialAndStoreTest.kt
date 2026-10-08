package com.sakata.focusflow

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

/** Injectable JVM AES-GCM cipher; production always uses AndroidKeyStore, never this implementation. */
internal class TestVisionCipher : VisionCipher {
    private val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(value:String):String { val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);return Base64.getEncoder().encodeToString(c.iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(value.toByteArray())) }
    override fun decrypt(value:String):String { val p=value.split(':');val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return String(c.doFinal(Base64.getDecoder().decode(p[1]))) }
}
/** Memory and confirmed disk are independent. False/throw after memory reproduces SharedPreferences uncertainty. */
internal class ModelVisionPreferences : SharedPreferences {
    enum class Mode { CONFIRM, FAIL_BEFORE, FAIL_AFTER_MEMORY, THROW_AFTER_MEMORY }
    var mode=Mode.CONFIRM
    private var memory=mapOf<String,Any?>()
    var disk=mapOf<String,Any?>();private set
    override fun getAll():MutableMap<String,*> = memory.toMutableMap()
    override fun contains(key:String?)=memory.containsKey(key)
    override fun getString(key:String?,def:String?)=memory[key] as? String ?: def
    override fun getStringSet(key:String?,def:MutableSet<String>?)= (memory[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: def
    override fun getInt(key:String?,def:Int)=memory[key] as? Int ?: def
    override fun getLong(key:String?,def:Long)=memory[key] as? Long ?: def
    override fun getFloat(key:String?,def:Float)=memory[key] as? Float ?: def
    override fun getBoolean(key:String?,def:Boolean)=memory[key] as? Boolean ?: def
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit():SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes=mutableMapOf<String,Any?>();var clear=false
        override fun putString(k:String?,v:String?)=apply {changes[k!!]=v}
        override fun putStringSet(k:String?,v:MutableSet<String>?)=apply {changes[k!!]=v?.toSet()}
        override fun putInt(k:String?,v:Int)=apply {changes[k!!]=v}
        override fun putLong(k:String?,v:Long)=apply {changes[k!!]=v}
        override fun putFloat(k:String?,v:Float)=apply {changes[k!!]=v}
        override fun putBoolean(k:String?,v:Boolean)=apply {changes[k!!]=v}
        override fun remove(k:String?)=apply {changes[k!!]=null}
        override fun clear()=apply {clear=true}
        override fun commit():Boolean {
            if(mode==Mode.FAIL_BEFORE) return false
            val next=(if(clear) emptyMap() else memory).toMutableMap();changes.forEach { (k,v) -> if(v==null) next.remove(k) else next[k]=v };memory=next
            if(mode==Mode.THROW_AFTER_MEMORY) error("secret must never reach diagnostics")
            if(mode==Mode.FAIL_AFTER_MEMORY) return false
            disk=memory.toMap();return true
        }
        override fun apply() { commit() }
    }
}

@RunWith(RobolectricTestRunner::class)
class VisionCredentialAndStoreTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    @Before fun reset() { VisionCredentialStore.resetGuardsForTest();VisionServiceStore.resetGuardsForTest();listOf("focusflow","focusflow_credentials","focusflow_vision_services").forEach { context.getSharedPreferences(it,0).edit().clear().commit() } }
    private fun profile()=VisionServiceProfile("p","Test","https://example.test/v1","vision","vision-p","r")
    @Test fun `successful migration keeps shared consumers models flags and business data`() {
        val legacy=context.getSharedPreferences("focusflow",0);val encrypted=context.getSharedPreferences("focusflow_credentials",0)
        legacy.edit().putString("siliconflow_api_key","private-key").putBoolean("course_vision_enabled",false).putString("course_vision_model","my-vision-model").putBoolean("tutorial_search_enabled",true).putString("tutorial_search_model","my-learning-model").putString("courses","original courses").commit()
        val vault=VisionCredentialStore(encrypted,legacy,TestVisionCipher());val migrated=vault.migrateShared() as VisionCredentialRead.Ready
        assertEquals("private-key",migrated.secret);assertFalse(legacy.contains("siliconflow_api_key"));assertFalse(encrypted.all.toString().contains("private-key"));assertFalse(migrated.toString().contains("private-key"))
        assertEquals("private-key",(VisionCredentialStore(encrypted,legacy,object:VisionCipher { override fun encrypt(value:String)=error("not called");override fun decrypt(value:String)=migrated.secret }).read(VisionCredentialStore.SHARED_REF) as VisionCredentialRead.Ready).secret)
        assertEquals("original courses",legacy.getString("courses",null));assertTrue(legacy.getBoolean("tutorial_search_enabled",false));assertFalse(legacy.getBoolean("course_vision_enabled",true))
        val p=(VisionServiceStore(context).read() as VisionConfigurationRead.Ready).configuration.profiles.single();assertEquals("my-vision-model",p.model);assertNull(p.verifiedCredentialRevision)
        val learning=PrototypeStore(context).loadTutorialSearchSettings(vault)
        assertEquals("private-key",learning.apiKey);assertEquals("my-learning-model",learning.model);assertTrue(learning.enabled)
        assertFalse(learning.toString().contains("private-key"))
    }
    @Test fun `encryption failure preserves plaintext for retry and does not clear business data`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();l.edit().putString("siliconflow_api_key","old-key").putString("courses","keep").commit()
        val broken=object:VisionCipher { override fun encrypt(value:String):String=error("unavailable");override fun decrypt(value:String):String=error("unavailable") }
        assertTrue(VisionCredentialStore(e,l,broken).migrateShared() is VisionCredentialRead.Uncertain);assertEquals("old-key",l.getString("siliconflow_api_key",null));assertEquals("keep",l.disk["courses"]);assertTrue(e.all.isEmpty())
    }
    @Test fun `failed encrypted commit never removes old raw key or claims ready`() {
        for(mode in listOf(ModelVisionPreferences.Mode.FAIL_BEFORE,ModelVisionPreferences.Mode.FAIL_AFTER_MEMORY,ModelVisionPreferences.Mode.THROW_AFTER_MEMORY)) {
            VisionCredentialStore.resetGuardsForTest();val l=ModelVisionPreferences();val e=ModelVisionPreferences();l.edit().putString("siliconflow_api_key","old-key").commit();e.mode=mode
            val cipher=TestVisionCipher();val v=VisionCredentialStore(e,l,cipher)
            assertEquals(VisionCredentialRead.Uncertain,v.migrateShared());assertEquals("old-key",l.disk["siliconflow_api_key"]);assertTrue(e.disk.isEmpty());assertEquals(VisionCredentialRead.Uncertain,VisionCredentialStore(e,l,cipher).read(VisionCredentialStore.SHARED_REF))
            e.mode=ModelVisionPreferences.Mode.CONFIRM;assertTrue(v.migrateShared() is VisionCredentialRead.Ready);assertFalse(l.disk.containsKey("siliconflow_api_key"));assertTrue(e.disk.isNotEmpty())
        }
    }
    @Test fun `failed plaintext removal remains uncertain until confirmed retry`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();l.edit().putString("siliconflow_api_key","old-key").commit();l.mode=ModelVisionPreferences.Mode.FAIL_AFTER_MEMORY
        val v=VisionCredentialStore(e,l,TestVisionCipher());assertEquals(VisionCredentialRead.Uncertain,v.migrateShared());assertEquals("old-key",l.disk["siliconflow_api_key"]);assertTrue(e.disk.isNotEmpty());assertEquals(VisionCredentialRead.Uncertain,v.read(VisionCredentialStore.SHARED_REF))
        l.mode=ModelVisionPreferences.Mode.CONFIRM;assertTrue(v.migrateShared() is VisionCredentialRead.Ready);assertFalse(l.disk.containsKey("siliconflow_api_key"))
    }
    @Test fun `interrupted migration resumes without replacing confirmed encrypted key`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();val v=VisionCredentialStore(e,l,TestVisionCipher());assertTrue(v.save(VisionCredentialStore.SHARED_REF,"key"));val revision=(v.read(VisionCredentialStore.SHARED_REF) as VisionCredentialRead.Ready).revision
        l.edit().putString("siliconflow_api_key","key").commit();assertEquals(revision,(v.migrateShared() as VisionCredentialRead.Ready).revision)
    }
    @Test fun `conflicting old and encrypted keys do not overwrite either`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();val v=VisionCredentialStore(e,l,TestVisionCipher());assertTrue(v.save(VisionCredentialStore.SHARED_REF,"new-key"));l.edit().putString("siliconflow_api_key","different-key").commit()
        assertEquals(VisionCredentialRead.Unavailable,v.migrateShared());assertEquals("different-key",l.disk["siliconflow_api_key"]);assertEquals("new-key",(v.read(VisionCredentialStore.SHARED_REF) as VisionCredentialRead.Ready).secret)
    }
    @Test fun `lost device key damaged ciphertext and bad raw types require reentry without writes`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();val first=VisionCredentialStore(e,l,TestVisionCipher());assertTrue(first.save("vision-p","private-key"));val before=e.disk.toMap()
        assertEquals(VisionCredentialRead.Unavailable,VisionCredentialStore(e,l,TestVisionCipher()).read("vision-p"));assertEquals(before,e.disk)
        e.edit().putString("vision-p","{ broken").commit();assertEquals(VisionCredentialRead.Unavailable,first.read("vision-p"));assertEquals("{ broken",e.disk["vision-p"])
        e.edit().putBoolean("vision-p",true).commit();assertEquals(VisionCredentialRead.Unavailable,first.read("vision-p"));assertEquals(true,e.disk["vision-p"])
        l.edit().putInt("siliconflow_api_key",1).commit();assertEquals(VisionCredentialRead.Unavailable,first.migrateShared());assertEquals(1,l.disk["siliconflow_api_key"])
    }
    @Test fun `shared deletion removes both encrypted and legacy key explicitly`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();l.edit().putString("siliconflow_api_key","key").commit();val v=VisionCredentialStore(e,l,TestVisionCipher());assertTrue(v.migrateShared() is VisionCredentialRead.Ready);assertTrue(v.saveShared(""));assertEquals(VisionCredentialRead.Missing,v.read(VisionCredentialStore.SHARED_REF));assertFalse(l.contains("siliconflow_api_key"))
    }
    @Test fun `unverified profile cannot be default and changed key invalidates verification`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();val configs=ModelVisionPreferences();val v=VisionCredentialStore(e,l,TestVisionCipher());val store=VisionServiceStore(configs,l)
        assertTrue(v.save("vision-p","key"));val initial=(store.read() as VisionConfigurationRead.Ready).configuration;assertTrue(store.saveProfile(initial,profile()));assertFalse(store.setDefault("p",v))
        val saved=(store.read() as VisionConfigurationRead.Ready).configuration.profiles.last();val key=v.read("vision-p") as VisionCredentialRead.Ready;assertTrue(store.markVerified(saved,key.revision,v));assertTrue(store.setDefault("p",v));assertEquals(listOf("p"),store.verifiedProfiles(v).map {it.id})
        assertTrue(v.save("vision-p","replacement"));assertTrue(store.verifiedProfiles(v).isEmpty());assertFalse(store.setDefault("p",v));assertFalse(store.currentAndVerified(saved,v))
    }
    @Test fun `stale probe and changed model url timeout or protocol cannot reuse verification`() {
        val l=ModelVisionPreferences();val v=VisionCredentialStore(ModelVisionPreferences(),l,TestVisionCipher());val store=VisionServiceStore(ModelVisionPreferences(),l);assertTrue(v.save("vision-p","key"))
        assertTrue(store.saveProfile((store.read() as VisionConfigurationRead.Ready).configuration,profile()));val saved=(store.read() as VisionConfigurationRead.Ready).configuration.profiles.last();val key=v.read("vision-p") as VisionCredentialRead.Ready
        assertTrue(store.markVerified(saved,key.revision,v));val current=(store.read() as VisionConfigurationRead.Ready).configuration;assertTrue(store.saveProfile(current,saved.copy(model="another")))
        assertFalse(store.markVerified(saved,key.revision,v));assertTrue(store.verifiedProfiles(v).isEmpty());assertNull((store.read() as VisionConfigurationRead.Ready).configuration.defaultId)
    }
    @Test fun `failed probe removes usable verification and records all three independent states`() {
        val l=ModelVisionPreferences();val e=ModelVisionPreferences();val configs=ModelVisionPreferences();val v=VisionCredentialStore(e,l,TestVisionCipher());val store=VisionServiceStore(configs,l);v.save("vision-p","key");store.saveProfile((store.read() as VisionConfigurationRead.Ready).configuration,profile())
        val p=(store.read() as VisionConfigurationRead.Ready).configuration.profiles.last();val key=v.read("vision-p") as VisionCredentialRead.Ready;assertTrue(store.markVerified(p,key.revision,v))
        val verified=(store.read() as VisionConfigurationRead.Ready).configuration.profiles.last();assertTrue(store.recordProbe(verified,key.revision,VisionCapabilityResult(true,false,true,"static failure"),v));assertTrue(store.verifiedProfiles(v).isEmpty())
        val record=JSONObject(configs.getString("probe_p",null)!!);assertTrue(record.getBoolean("connected"));assertFalse(record.getBoolean("image"));assertTrue(record.getBoolean("structured"));assertTrue(record.getLong("testedAt")>0)
        val reviewable=(store.read() as VisionConfigurationRead.Ready).configuration.profiles.last()
        assertEquals(listOf("p"),store.reviewableProfiles(v).map {it.id})
        assertTrue(store.currentAndReviewable(reviewable,v))
        assertFalse(store.currentAndVerified(reviewable,v))
        assertFalse(store.setDefault("p",v))
    }
    @Test fun `corrupt service configuration is preserved and never interpreted as empty`() {
        val prefs=ModelVisionPreferences();val store=VisionServiceStore(prefs,ModelVisionPreferences());prefs.edit().putString("configuration","{ damaged").commit();assertTrue(store.read() is VisionConfigurationRead.Invalid);assertFalse(store.saveProfile(VisionServiceConfiguration(emptyList()),profile()));assertEquals("{ damaged",prefs.disk["configuration"])
        prefs.edit().putBoolean("configuration",false).commit();assertTrue(store.read() is VisionConfigurationRead.Invalid);assertFalse(store.retryPendingWrite());assertEquals(false,prefs.disk["configuration"])
    }
    @Test fun `failed config commit is uncertain across store objects and explicit retry confirms it`() {
        val prefs=ModelVisionPreferences();val legacy=ModelVisionPreferences();val store=VisionServiceStore(prefs,legacy);val initial=(store.read() as VisionConfigurationRead.Ready).configuration;prefs.mode=ModelVisionPreferences.Mode.FAIL_AFTER_MEMORY
        assertFalse(store.saveProfile(initial,profile()));assertEquals(VisionConfigurationRead.Uncertain,VisionServiceStore(prefs,legacy).read());assertTrue(prefs.disk.isEmpty());prefs.mode=ModelVisionPreferences.Mode.CONFIRM;assertTrue(store.retryPendingWrite());assertTrue(store.read() is VisionConfigurationRead.Ready);assertTrue(prefs.disk.isNotEmpty())
    }
    @Test fun `stale profile save is rejected and deleting service preserves shared learning key`() {
        val l=ModelVisionPreferences();val v=VisionCredentialStore(ModelVisionPreferences(),l,TestVisionCipher());val store=VisionServiceStore(ModelVisionPreferences(),l);v.saveShared("key");val initial=(store.read() as VisionConfigurationRead.Ready).configuration
        assertTrue(store.saveProfile(initial,profile()));assertFalse(store.saveProfile(initial,profile().copy(name="stale")));val current=(store.read() as VisionConfigurationRead.Ready).configuration;assertTrue(store.delete(current,"siliconflow"));assertEquals("key",(v.read(VisionCredentialStore.SHARED_REF) as VisionCredentialRead.Ready).secret)
    }
}
