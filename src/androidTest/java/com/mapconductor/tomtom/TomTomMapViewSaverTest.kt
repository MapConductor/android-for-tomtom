package com.mapconductor.tomtom

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import com.tomtom.sdk.map.display.style.StyleDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import android.os.Bundle
import android.os.Parcel

/**
 * 回転などの構成変更で state が [TomTomMapViewSaver] を通って作り直されても、デザイン（id・
 * attributionRules ほか）と uiSettings・カメラが落ちないこと。Bundle は実機の Parcel を一度通す。
 */
@RunWith(AndroidJUnit4::class)
class TomTomMapViewSaverTest {
    private val rules =
        listOf(AttributionRule("© test"), AttributionRule("© ranged", minZoom = 3, maxZoom = 15))

    private val camera =
        MapCameraPosition(
            position = GeoPoint(21.38, -157.93),
            zoom = 12.0,
            bearing = 20.0,
            tilt = 10.0,
        )

    @Test
    fun keepsCustomDesignWithItsStyleDescriptor() {
        val descriptor = StyleDescriptor(Uri.parse("https://example.com/style.json"))
        val design = TomTomMapDesign.Custom("my-style", descriptor, rules)
        val state = TomTomMapViewState(id = "tomtom-1", mapDesignType = design, cameraPosition = camera)
        state.uiSettings = MapUISettings(rotateGesture = false, tiltGesture = false)

        val restored = roundTrip(TomTomMapViewSaver().createSaver(), state)

        // 以前は id だけを保存して create(id) で戻しており、Standard になっていた。
        val actual = restored.mapDesignType
        assertTrue(actual is TomTomMapDesign.Custom)
        actual as TomTomMapDesign.Custom
        assertEquals("my-style", actual.id)
        assertEquals(descriptor, actual.styleDescriptor)
        assertEquals(rules, actual.attributionRules)
        assertCommon(restored, "tomtom-1")
    }

    @Test
    fun keepsPresetDesignAsTheSameObject() {
        val state = TomTomMapViewState(id = "tomtom-2", mapDesignType = TomTomMapDesign.Satellite)
        assertSame(TomTomMapDesign.Satellite, roundTrip(TomTomMapViewSaver().createSaver(), state).mapDesignType)
    }

    private fun assertCommon(restored: com.mapconductor.core.map.MapViewStateInterface<*>, id: String) {
        assertEquals(id, restored.id)
        assertEquals(false, restored.uiSettings.rotateGesture)
        assertEquals(false, restored.uiSettings.tiltGesture)
        assertEquals(true, restored.uiSettings.scrollGesture)
        assertEquals(12.0, restored.cameraPosition.zoom, 1e-9)
        assertEquals(20.0, restored.cameraPosition.bearing, 1e-9)
        assertEquals(21.38, restored.cameraPosition.position.latitude, 1e-9)
    }

    private fun <T : Any> roundTrip(
        saver: Saver<T, Bundle>,
        value: T,
    ): T {
        val saved = with(saver) { SaverScope { true }.save(value) }!!
        // 一度 Parcel に書いて読み戻す（プロセスが落ちたあとの復元と同じ経路）。
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            return saver.restore(parcel.readBundle(javaClass.classLoader)!!)!!
        } finally {
            parcel.recycle()
        }
    }
}
