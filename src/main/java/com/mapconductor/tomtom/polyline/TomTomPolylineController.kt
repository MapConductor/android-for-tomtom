package com.mapconductor.tomtom.polyline

import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.polyline.PolylineController
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineManager
import com.mapconductor.core.polyline.PolylineManagerInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.tomtom.TomTomActualPolyline

interface TomTomPolylineControllerInterface :
    OverlayControllerInterface<PolylineState, PolylineEntityInterface<TomTomActualPolyline>>

internal class TomTomPolylineController(
    polylineManager: PolylineManagerInterface<TomTomActualPolyline> = PolylineManager(),
    renderer: TomTomPolylineOverlayRenderer,
) : PolylineController<TomTomActualPolyline>(polylineManager, renderer),
    TomTomPolylineControllerInterface
