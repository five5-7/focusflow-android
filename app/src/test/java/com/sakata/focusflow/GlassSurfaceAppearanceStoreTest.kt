package com.sakata.focusflow

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlassSurfaceAppearanceStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)

    @Before fun clearBefore() { check(preferences.edit().clear().commit()) }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test
    fun oldAppearanceKeepsDistinctMaterialDefaultsAcrossSaveAndReload() {
        val store = PrototypeStore(context)
        val old = store.loadAppearance()
        assertNull(old.glassSurfaceOpacity)
        assertEquals(60, glassSurfaceOpacityPercent(CardMaterial.ACRYLIC, old.glassSurfaceOpacity))
        assertEquals(48, glassSurfaceOpacityPercent(CardMaterial.FROSTED, old.glassSurfaceOpacity))

        store.saveAppearance(old.copy(richEffects = true, cardMaterial = CardMaterial.FROSTED))
        assertFalse(preferences.contains("appearance_glass_surface_opacity"))
        assertNull(PrototypeStore(context).loadAppearance().glassSurfaceOpacity)
    }

    @Test
    fun explicitGlassOpacityPersistsClampsAndDefaultResetRemovesTheKey() {
        val store = PrototypeStore(context)
        store.saveAppearance(AppearanceSpec.DEFAULT.copy(glassSurfaceOpacity = 95))
        assertEquals(95, PrototypeStore(context).loadAppearance().glassSurfaceOpacity)
        assertEquals(95, preferences.getInt("appearance_glass_surface_opacity", -1))

        store.saveAppearance(AppearanceSpec.DEFAULT.copy(glassSurfaceOpacity = 130))
        assertEquals(95, PrototypeStore(context).loadAppearance().glassSurfaceOpacity)
        store.saveAppearance(AppearanceSpec.DEFAULT)
        assertFalse(preferences.contains("appearance_glass_surface_opacity"))
        assertNull(PrototypeStore(context).loadAppearance().glassSurfaceOpacity)
    }

    @Test
    fun malformedGlassOpacityDoesNotEraseOtherAppearancePreferences() {
        check(preferences.edit()
            .putString("appearance_glass_surface_opacity", "bad")
            .putString("appearance_card_material", CardMaterial.ACRYLIC.storageKey)
            .putBoolean("appearance_rich_effects", true)
            .commit())

        val appearance = PrototypeStore(context).loadAppearance()
        assertNull(appearance.glassSurfaceOpacity)
        assertTrue(appearance.richEffects)
        assertEquals(CardMaterial.ACRYLIC, appearance.cardMaterial)
        assertEquals("bad", preferences.getString("appearance_glass_surface_opacity", null))
    }
}
