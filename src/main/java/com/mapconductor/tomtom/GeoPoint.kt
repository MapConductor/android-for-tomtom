package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoPoint
import com.tomtom.sdk.location.GeoPoint as TomTomGeoPoint

// MapConductor の GeoPoint と TomTom SDK の GeoPoint（com.tomtom.sdk.location.GeoPoint）の相互変換。
// NOTE: TomTom の GeoPoint は緯度・経度のみを保持するため、往復で altitude は失われる。

fun GeoPoint.toTomTomGeoPoint(): TomTomGeoPoint = TomTomGeoPoint(latitude, longitude)

fun GeoPoint.Companion.from(point: TomTomGeoPoint) =
    GeoPoint(
        latitude = point.latitude,
        longitude = point.longitude,
    )

fun TomTomGeoPoint.toGeoPoint() = GeoPoint.fromLatLong(latitude, longitude)
