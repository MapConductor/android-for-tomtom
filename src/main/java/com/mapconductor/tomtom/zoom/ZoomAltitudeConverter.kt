package com.mapconductor.tomtom.zoom

import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * TomTom Orbis は Mapbox GL 由来のベクターエンジン（512px タイル）のため、Google Maps
 * （256px タイル）とはズームレベルが約 1.0 ずれる。MapConductor の統一ズームは Google Maps
 * 換算なので、TomTom ネイティブズームとの間で ±1.0 を補正する（Mapbox 実装と同方針）。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : AbstractZoomAltitudeConverter(zoom0Altitude) {
    companion object {
        /**
         * 赤道（緯度 0）での基準ズームオフセット（= googleZoom - tomtomZoom）。
         *
         * TomTom はグラウンドスケール基準（画面上の meter/pixel が一定）でズームを定義するのに対し、
         * Google Maps は Web Mercator 基準（degree/pixel が一定）。このため両者の対応は定数ではなく
         * 緯度に応じて `log2(cos φ)` だけずれる。offset(φ) = BASE + log2(cos φ)。
         *
         * camera-sync の参照ボックス縮尺比で実測・検証（TomTom がフラット Mercator 描画になる通常ズーム域）:
         *   Oahu 21.5°→ offset 1.66, Tokyo 35.7°→ 1.46 がそれぞれ一致。BASE ≈ 1.76。
         * 縦横比と表示密度を変えた実機検証でも追加の viewport 補正は不要だった。
         * （※ 超低ズームでは TomTom が globe 投影に切り替わるため、この Mercator 基準の補正では完全一致しない）
         */
        const val TOMTOM_TO_GOOGLE_ZOOM_BASE_OFFSET = 1.76

        /** 指定緯度での Google↔TomTom ズームオフセット（googleZoom - tomtomZoom）。 */
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

    private fun cosLatitudeFactor(latitudeDeg: Double): Double {
        val clampedLat = latitudeDeg.coerceIn(-85.0, 85.0)
        val latRad = Math.toRadians(clampedLat)
        return max(MIN_COS_LAT, abs(cos(latRad)))
    }

    private fun cosTiltFactor(tiltDeg: Double): Double {
        val clampedTilt = tiltDeg.coerceIn(0.0, 90.0)
        val tiltRad = Math.toRadians(clampedTilt)
        return max(MIN_COS_TILT, cos(tiltRad))
    }

    override fun zoomLevelToAltitude(
        zoomLevel: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        // TomTom ネイティブズームを緯度依存で Google 相当ズームへ変換してから WebMercator のスケール計算を行う。
        val googleZoom = tomtomZoomToGoogleZoom(zoomLevel, latitude)
        val cosLat = cosLatitudeFactor(latitude)
        val cosTilt = cosTiltFactor(tilt)
        val distance = (zoom0Altitude * cosLat) / ZOOM_FACTOR.pow(googleZoom)
        val altitude = distance * cosTilt
        return altitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
    }

    override fun altitudeToZoomLevel(
        altitude: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        val clampedAltitude = altitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
        val cosLat = cosLatitudeFactor(latitude)
        val cosTilt = cosTiltFactor(tilt)
        val distance = clampedAltitude / cosTilt
        val googleZoom = log2((zoom0Altitude * cosLat) / distance)
        return googleZoomToTomTomZoom(googleZoom, latitude)
    }
}
