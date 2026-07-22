package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.MapPaddingsInterface
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import com.mapconductor.tomtom.zoom.ZoomAltitudeConverter
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.camera.CameraPosition as TomTomCameraPosition

private val converter = ZoomAltitudeConverter(AbstractZoomAltitudeConverter.DEFAULT_ZOOM0_ALTITUDE)

/**
 * MapConductor の MapCameraPosition → TomTom の [CameraOptions]。
 *
 * rotation は方位角（bearing）に対応。tilt は 0.0〜60.0 にクランプする。
 * 統一ズームは Google Maps 換算のため、緯度を使って TomTom ネイティブズームへ変換する。
 */
fun MapCameraPosition.toCameraOptions(): CameraOptions =
    CameraOptions(
        position = GeoPoint.from(position).toTomTomGeoPoint(),
        zoom = ZoomAltitudeConverter.googleZoomToTomTomZoom(zoom, position.latitude),
        tilt = tilt.coerceIn(0.0, 60.0),
        rotation = bearing,
    )

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
 */
fun TomTomCameraPosition.toMapCameraPosition(paddings: MapPaddingsInterface = MapPaddings.Zeros): MapCameraPosition {
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
