package com.mapconductor.tomtom.groundimage

import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageManagerInterface
import com.mapconductor.tomtom.TomTomActualGroundImage

class TomTomGroundImageController(
    groundImageManager: GroundImageManagerInterface<TomTomActualGroundImage> = GroundImageManager(),
    renderer: TomTomGroundImageOverlayRenderer,
) : GroundImageController<TomTomActualGroundImage>(groundImageManager, renderer)
