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
 * `1.735 + log2(cos φ)`。1.735 は実測較正値。
 *
 * 較正は TB520FU（TomTom Maps SDK 2.7.2）で、同じ統一ズーム・同じ地図サイズの Google Maps と
 * 表示範囲（`coordinateForPoint` で取った四隅）を突き合わせて行った。以前の 1.76 では TomTom が
 * 緯度 0〜70°、zoom 12〜18 のどこでも 0.025 段だけ広く写っていた（Camera Sync の参照矩形で約 1.7%）。
 * zoom 10 以下で差が膨らんで見えるのは地球儀表示で四隅が回り込むためで、中心付近の縮尺は合っている。
 *
 * 換算式はコアの [GroundScaleZoomAltitudeConverter] にある。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : GroundScaleZoomAltitudeConverter(zoom0Altitude, baseZoomOffset = TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET) {
    companion object {
        const val TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET = 1.735

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
