package com.mapconductor.tomtom.circle

import com.tomtom.sdk.map.display.circle.Circle as TomTomNativeCircle
import com.tomtom.sdk.map.display.polyline.Polyline as TomTomNativePolyline

/**
 * A circle is composited from two TomTom overlays (mirrors the Mapbox renderer's fill-layer +
 * line-layer approach): the native [fill] circle for the fill, and a [stroke] polyline ring for the
 * outline (constant pixel width; TomTom's native circle outline is meters-based / absent on iOS).
 */
class TomTomCircleHandle(
    var fill: TomTomNativeCircle?,
    var stroke: TomTomNativePolyline?,
)
