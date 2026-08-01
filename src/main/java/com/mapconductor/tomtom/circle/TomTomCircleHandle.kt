package com.mapconductor.tomtom.circle

import com.tomtom.sdk.map.display.polygon.Polygon as TomTomNativePolygon
import com.tomtom.sdk.map.display.polyline.Polyline as TomTomNativePolyline

/**
 * A circle is composited from two TomTom overlays (mirrors the Mapbox renderer's fill-layer +
 * line-layer approach): the [fill] polygon built from the shared core circle ring, and a [stroke]
 * polyline ring for the outline (constant pixel width; TomTom's native circle outline is
 * meters-based / absent on iOS, and its native fill interprets the radius differently from the
 * geodesic ring, so both layers are built from the same core ring instead).
 */
class TomTomCircleHandle(
    var fill: List<TomTomNativePolygon>,
    var stroke: List<TomTomNativePolyline>,
)
