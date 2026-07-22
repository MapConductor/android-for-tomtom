package com.mapconductor.tomtom.polygon

import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonManagerInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.tomtom.TomTomActualPolygon

interface TomTomPolygonControllerInterface :
    OverlayControllerInterface<PolygonState, PolygonEntityInterface<TomTomActualPolygon>>

internal class TomTomPolygonController(
    polygonManager: PolygonManagerInterface<TomTomActualPolygon> = PolygonManager(),
    renderer: TomTomPolygonOverlayRenderer,
) : PolygonController<TomTomActualPolygon>(polygonManager, renderer),
    TomTomPolygonControllerInterface
