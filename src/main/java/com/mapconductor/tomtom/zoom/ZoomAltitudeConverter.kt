package com.mapconductor.tomtom.zoom

import com.mapconductor.core.zoom.GroundScaleZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max

/**
 * 統一ズーム（Google Maps 基準・256px タイル）⇄ 高度の変換。
 *
 * TomTom のズームはグラウンドスケール基準（画面上の meter/pixel が緯度によらず一定）
 * なので、Web Mercator 基準との差が緯度に依存する。オフセットは
 * `1.76 + log2(cos φ)`。1.76 は実測較正値。
 *
 * 換算式はコアの [GroundScaleZoomAltitudeConverter] にある。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : GroundScaleZoomAltitudeConverter(zoom0Altitude, baseZoomOffset = TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET) {
    companion object {
        const val TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET = 1.76

        fun zoomOffsetAt(latitude: Double): Double {
            val clampedLat = latitude.coerceIn(-85.0, 85.0)
            val cosLat = max(MIN_COS_LAT, abs(cos(Math.toRadians(clampedLat))))
            return TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET + log2(cosLat)
        }

        fun tomtomZoomToGoogleZoom(
            tomtomZoom: Double,
            latitude: Double,
        ): Double = (tomtomZoom + zoomOffsetAt(latitude)).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

        fun googleZoomToTomTomZoom(
            googleZoom: Double,
            latitude: Double,
        ): Double = (googleZoom - zoomOffsetAt(latitude)).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
    }
}
