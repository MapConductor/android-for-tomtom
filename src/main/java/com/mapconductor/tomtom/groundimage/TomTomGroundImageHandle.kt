package com.mapconductor.tomtom.groundimage

import com.tomtom.sdk.map.display.polygon.Polygon

/**
 * TomTom の GroundImage 実体。
 *
 * TomTom ネイティブの画像付き [Polygon] を保持する。
 */
class TomTomGroundImageHandle(
    val polygon: Polygon,
)
