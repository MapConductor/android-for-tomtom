package com.mapconductor.tomtom

import com.tomtom.sdk.map.display.marker.Marker

// 各オーバーレイのネイティブ実体型。
typealias TomTomActualMarker = Marker
typealias TomTomActualPolyline = com.tomtom.sdk.map.display.polyline.Polyline
typealias TomTomActualPolygon = com.tomtom.sdk.map.display.polygon.Polygon

// Circle は塗り（native circle）+ 枠線（polyline）の2レイヤー合成のためハンドルで保持する。
typealias TomTomActualCircle = com.mapconductor.tomtom.circle.TomTomCircleHandle
