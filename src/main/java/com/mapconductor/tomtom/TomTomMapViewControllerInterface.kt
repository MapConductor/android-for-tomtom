package com.mapconductor.tomtom

import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.controller.MapViewControllerInterface
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polyline.PolylineCapableInterface

typealias TomTomMapDesignTypeChangeHandler = (TomTomMapDesignType) -> Unit

/**
 * TomTom 実装のマップコントローラ。
 *
 * スコープ: コア + マーカー + Polyline / Polygon / Circle。GroundImage / RasterLayer は未実装。
 */
interface TomTomMapViewControllerInterface :
    MapViewControllerInterface,
    MarkerCapableInterface,
    PolylineCapableInterface,
    PolygonCapableInterface,
    CircleCapableInterface {
    fun setMapDesignType(value: TomTomMapDesignType)

    fun setMapDesignTypeChangeListener(listener: TomTomMapDesignTypeChangeHandler)
}
