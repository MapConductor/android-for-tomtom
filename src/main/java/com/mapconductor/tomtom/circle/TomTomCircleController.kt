package com.mapconductor.tomtom.circle

import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleManagerInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.tomtom.TomTomActualCircle

interface TomTomCircleControllerInterface :
    OverlayControllerInterface<CircleState, CircleEntityInterface<TomTomActualCircle>>

internal class TomTomCircleController(
    circleManager: CircleManagerInterface<TomTomActualCircle> = CircleManager(),
    renderer: TomTomCircleOverlayRenderer,
) : CircleController<TomTomActualCircle>(circleManager, renderer),
    TomTomCircleControllerInterface
