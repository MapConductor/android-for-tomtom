package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.MapPaddingsInterface
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import com.mapconductor.tomtom.zoom.ZoomAltitudeConverter
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.camera.CameraPosition as TomTomCameraPosition
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

private val converter = ZoomAltitudeConverter(AbstractZoomAltitudeConverter.DEFAULT_ZOOM0_ALTITUDE)

// tilt < 0（上向きピッチ）の擬似表現に用いる定数。MapLibre 実装（android-for-maplibre の
// MapCameraPosition.kt）と同一値を用いることで、プロバイダ間で挙動を揃える。
private const val NEGATIVE_TILT_TARGET_DISTANCE_SCALE = 1.83
private const val NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT = -0.9

/**
 * 統一 bearing を TomTom の [CameraOptions.rotation] が要求する [0.0, 360.0] へ正規化する。
 *
 * 他プロバイダ（MapTiler/MapLibre GL JS 等）は方位角を (-180, 180] で報告するため、地図を回転させた
 * 状態で TomTom へ切り替えると負の rotation が渡り、`IllegalArgumentException: rotation must be in
 * range [0.0,360.0]` でクラッシュしていた。ここで [0, 360) に丸めて防止する（NaN は 0 とみなす）。
 */
private fun Double.toTomTomRotation(): Double = if (isNaN()) 0.0 else ((this % 360.0) + 360.0) % 360.0

/**
 * MapConductor の MapCameraPosition → TomTom の [CameraOptions]。
 *
 * rotation は方位角（bearing）に対応。tilt は 0.0〜60.0 にクランプする。
 * 統一ズームは Google Maps 換算のため、緯度を使って TomTom ネイティブズームへ変換する。
 */
fun MapCameraPosition.toCameraOptions(): CameraOptions {
    if (tilt >= 0) {
        return CameraOptions(
            position = GeoPoint.from(position).toTomTomGeoPoint(),
            zoom = ZoomAltitudeConverter.googleZoomToTomTomZoom(zoom, position.latitude),
            tilt = tilt.coerceIn(0.0, 60.0),
            rotation = bearing.toTomTomRotation(),
        )
    }

    // tilt < 0: TomTom（Mapbox GL 由来のベクターエンジン）は上向きピッチを直接表現できない。
    // MapLibre 実装と同じ方式で、地上ターゲットを進行方向（bearing）へ前進させ、abs(tilt) の
    // 下向きピッチで描画することで、擬似的に上向き（負tilt）視点を再現する。
    val tiltAbsDeg = abs(tilt).coerceIn(0.0, 60.0)
    val tiltAbsRad = Math.toRadians(tiltAbsDeg)
    val tomtomZoomForAltitude = ZoomAltitudeConverter.googleZoomToTomTomZoom(zoom, position.latitude)
    val altitude = converter.zoomLevelToAltitude(tomtomZoomForAltitude, position.latitude, 0.0)
    val distanceForward =
        altitude *
            cos(tiltAbsRad) *
            tan(tiltAbsRad) *
            NEGATIVE_TILT_TARGET_DISTANCE_SCALE
    val target = Spherical.computeOffset(position, distanceForward, bearing)
    val adjustedZoom = zoom + NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (tiltAbsDeg / 60.0)

    return CameraOptions(
        position = target.toTomTomGeoPoint(),
        zoom = ZoomAltitudeConverter.googleZoomToTomTomZoom(adjustedZoom, target.latitude),
        tilt = tiltAbsDeg,
        rotation = bearing.toTomTomRotation(),
    )
}

fun MapCameraPosition.Companion.from(position: MapCameraPositionInterface): MapCameraPosition =
    when (position) {
        is MapCameraPosition -> position
        else ->
            MapCameraPosition(
                position = position.position,
                zoom = position.zoom,
                bearing = position.bearing,
                tilt = position.tilt,
                paddings = position.paddings,
                visibleRegion = position.visibleRegion,
            )
    }

/**
 * TomTom の [CameraPosition] → MapConductor の MapCameraPosition。
 *
 * @param logicalTiltHint 直近に要求した論理 tilt（[MapCameraPosition.tilt]）。これが負値のとき、
 *   シフト済みカメラ状態（前進ターゲット + 正ピッチ）から元の位置・ズーム・負tilt を復元する。
 *   null または 0 以上のときは通常変換。MapLibre 実装と同一ロジック。
 */
fun TomTomCameraPosition.toMapCameraPosition(
    paddings: MapPaddingsInterface = MapPaddings.Zeros,
    logicalTiltHint: Double? = null,
): MapCameraPosition {
    val pitchAbsDeg = abs(tilt).coerceIn(0.0, 60.0)

    if (logicalTiltHint == null || logicalTiltHint >= 0.0 || pitchAbsDeg == 0.0) {
        // `zoom` は TomTom ネイティブズーム。統一ズーム（Google 換算）へ緯度依存で補正する。
        val altitude =
            converter.zoomLevelToAltitude(
                zoomLevel = zoom,
                latitude = position.latitude,
                tilt = tilt,
            )
        val corePosition = position.toGeoPoint().copy(altitude = altitude)
        return MapCameraPosition(
            position = corePosition,
            zoom = ZoomAltitudeConverter.tomtomZoomToGoogleZoom(zoom, position.latitude),
            bearing = rotation,
            tilt = tilt,
            paddings = paddings,
            visibleRegion = null,
        )
    }

    // tilt < 0 の復元：前進させたターゲットとズームオフセットを逆算し、元の位置・ズーム・負tilt を返す。
    val pitchAbsRad = Math.toRadians(pitchAbsDeg)
    val shiftedCenter = position.toGeoPoint()
    val googleZoom = ZoomAltitudeConverter.tomtomZoomToGoogleZoom(zoom, position.latitude)
    val originalGoogleZoom = googleZoom - NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (pitchAbsDeg / 60.0)
    val originalTomTomZoom = ZoomAltitudeConverter.googleZoomToTomTomZoom(originalGoogleZoom, shiftedCenter.latitude)
    val altitude = converter.zoomLevelToAltitude(originalTomTomZoom, shiftedCenter.latitude, 0.0)
    val distanceBackward = altitude * cos(pitchAbsRad) * tan(pitchAbsRad) * NEGATIVE_TILT_TARGET_DISTANCE_SCALE
    val originalPosition = Spherical.computeOffset(shiftedCenter, distanceBackward, rotation + 180.0)

    return MapCameraPosition(
        position = originalPosition.copy(altitude = altitude),
        zoom = originalGoogleZoom,
        bearing = rotation,
        tilt = -pitchAbsDeg,
        paddings = paddings,
        visibleRegion = null,
    )
}
