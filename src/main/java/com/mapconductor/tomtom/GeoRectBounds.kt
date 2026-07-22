package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoRectBounds
import com.tomtom.sdk.location.GeoBoundingBox

// GeoBoundingBox のコンストラクタは (topLeft, bottomRight)。
// topLeft = 北西（北緯・西経）、bottomRight = 南東（南緯・東経）。
fun GeoRectBounds.toGeoBoundingBox(): GeoBoundingBox? {
    val sw = southWest ?: return null
    val ne = northEast ?: return null

    val topLeft =
        com.tomtom.sdk.location
            .GeoPoint(ne.latitude, sw.longitude)
    val bottomRight =
        com.tomtom.sdk.location
            .GeoPoint(sw.latitude, ne.longitude)
    return GeoBoundingBox(topLeft = topLeft, bottomRight = bottomRight)
}

fun GeoBoundingBox.toGeoRectBounds(): GeoRectBounds {
    // topLeft = 北西, bottomRight = 南東
    val southWest =
        com.mapconductor.core.features.GeoPoint
            .fromLatLong(bottomRight.latitude, topLeft.longitude)
    val northEast =
        com.mapconductor.core.features.GeoPoint
            .fromLatLong(topLeft.latitude, bottomRight.longitude)
    return GeoRectBounds(
        southWest = southWest,
        northEast = northEast,
    )
}
