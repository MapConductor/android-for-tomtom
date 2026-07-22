package com.mapconductor.tomtom.marker

import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.AbstractMarkerController
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.spherical.Spherical.computeDistanceBetween
import com.mapconductor.settings.Settings
import com.mapconductor.tomtom.TomTomActualMarker
import com.mapconductor.tomtom.TomTomMapViewHolder

interface TomTomMarkerControllerInterface :
    OverlayControllerInterface<MarkerState, MarkerEntityInterface<TomTomActualMarker>>

/**
 * TomTom 用のマーカーコントローラ。
 *
 * 「コア + マーカーのみ」スコープのため、GoogleMap 実装にあるタイル描画（RasterLayer による
 * 大量マーカー最適化）は含めず、[AbstractMarkerController] の add/update/clear をそのまま利用する。
 * ネイティブマーカーを直接生成/削除するシンプルな実装。
 */
internal class TomTomMarkerController private constructor(
    renderer: TomTomMarkerRenderer,
    markerManager: MarkerManager<TomTomActualMarker>,
    @Suppress("unused") private val markerTiling: MarkerTilingOptions,
) : AbstractMarkerController<TomTomActualMarker>(
        markerManager = markerManager,
        renderer = renderer,
    ),
    TomTomMarkerControllerInterface,
    OnCameraChangeReceiverInterface {
    private var lastKnownZoom: Double = 0.0

    override fun find(position: GeoPointInterface): MarkerEntityInterface<TomTomActualMarker>? =
        find(position = position, zoom = lastKnownZoom)

    fun find(
        position: GeoPointInterface,
        zoom: Double,
    ): MarkerEntityInterface<TomTomActualMarker>? =
        markerManager.findNearest(position)?.let { nearest ->
            val tolerance =
                Settings.Default.tapTolerance.value
                    .toDouble() * ResourceProvider.getDensity()
            val meterInMapPixel = (renderer as TomTomMarkerRenderer).zoomToMetersPerPixel(zoom, 256)
            val radius = tolerance * meterInMapPixel
            val distance = computeDistanceBetween(position, nearest.state.position)
            if (distance <= radius) nearest else null
        }

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        lastKnownZoom = mapCameraPosition.zoom
    }

    companion object {
        fun create(
            holder: TomTomMapViewHolder,
            markerTiling: MarkerTilingOptions = MarkerTilingOptions.Default,
        ): TomTomMarkerController {
            val markerManager =
                MarkerManager.defaultManager<TomTomActualMarker>(
                    minMarkerCount = markerTiling.minMarkerCount,
                )
            val renderer = TomTomMarkerRenderer(holder = holder)
            return TomTomMarkerController(
                renderer = renderer,
                markerManager = markerManager,
                markerTiling = markerTiling,
            )
        }
    }
}
