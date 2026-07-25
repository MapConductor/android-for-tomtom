package com.mapconductor.tomtom.polygon

import com.tomtom.sdk.map.display.polygon.Polygon
import com.tomtom.sdk.map.display.polygon.PolygonOverlay

/**
 * TomTom ポリゴンのネイティブ実体ハンドル。
 *
 * - 穴なし: [polygon] に fill+outline を持つ native [Polygon] を保持（[overlay] は null）。
 * - 穴あり: [overlay] に fill+穴の [PolygonOverlay] を保持。輪郭は同じ [tag] を付けた
 *   stroke-only な native Polygon として別途追加され、[tag] で一括削除される。
 */
class TomTomPolygonHandle(
    val tag: String,
    val polygon: Polygon? = null,
    val overlay: PolygonOverlay? = null,
)
