package com.mapconductor.tomtom

import com.mapconductor.tomtom.zoom.ZoomAltitudeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun tokyoOffsetMatchesCameraSyncCalibration() {
        assertEquals(1.46, ZoomAltitudeConverter.zoomOffsetAt(35.6812), 0.01)
    }

    @Test
    fun oahuOffsetMatchesCameraSyncCalibration() {
        assertEquals(1.66, ZoomAltitudeConverter.zoomOffsetAt(21.4389), 0.01)
    }

    @Test
    fun zoomConversionRoundTrips() {
        val googleZoom = 12.0
        val latitude = 35.6812
        val tomtomZoom = ZoomAltitudeConverter.googleZoomToTomTomZoom(googleZoom, latitude)
        val convertedBack = ZoomAltitudeConverter.tomtomZoomToGoogleZoom(tomtomZoom, latitude)

        assertEquals(googleZoom, convertedBack, 1e-12)
    }
}
