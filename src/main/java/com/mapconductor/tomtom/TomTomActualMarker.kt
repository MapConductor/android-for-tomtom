package com.mapconductor.tomtom

import com.tomtom.sdk.map.display.marker.Marker

// 各オーバーレイのネイティブ実体型。
typealias TomTomActualMarker = Marker
typealias TomTomActualPolyline = com.tomtom.sdk.map.display.polyline.Polyline

// Polygon は穴なし（native Polygon の fill+stroke）と穴あり（native PolygonOverlay の fill+穴 ＋
// stroke-only Polygon の輪郭）で構成が異なるためハンドルで保持する。
typealias TomTomActualPolygon = com.mapconductor.tomtom.polygon.TomTomPolygonHandle

// Circle は塗り（native circle）+ 枠線（polyline）の2レイヤー合成のためハンドルで保持する。
typealias TomTomActualCircle = com.mapconductor.tomtom.circle.TomTomCircleHandle

// GroundImage は画像付き native Polygon のハンドルで保持する。
typealias TomTomActualGroundImage = com.mapconductor.tomtom.groundimage.TomTomGroundImageHandle
