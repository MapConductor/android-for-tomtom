package com.mapconductor.tomtom.polyline

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polyline.AbstractPolylineOverlayRenderer
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.core.spherical.createLinearInterpolatePoints
import com.mapconductor.tomtom.TomTomActualPolyline
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.toTomTomGeoPoint
import com.tomtom.sdk.location.GeoPoint as TomTomGeoPoint
import com.tomtom.sdk.map.display.common.WidthByZoom
import com.tomtom.sdk.map.display.polyline.PolylineOptions
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TomTomPolylineOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractPolylineOverlayRenderer<TomTomActualPolyline>() {
    // TomTom は座標間を直線で結ぶため、測地線は補間した座標列で近似する。
    private fun coordinates(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<TomTomGeoPoint> {
        val geoPoints =
            if (geodesic) {
                createInterpolatePoints(points, maxSegmentLength = maxSegmentLengthMeters())
            } else {
                createLinearInterpolatePoints(points)
            }
        return geoPoints.map { GeoPoint.from(it).toTomTomGeoPoint() }
    }

    private fun maxSegmentLengthMeters(): Double {
        val zoom = holder.map.cameraPosition.zoom
        val metersPerPixel = Earth.CIRCUMFERENCE_METERS / (256.0 * 2.0.pow(zoom))
        return metersPerPixel * 64.0
    }

    override suspend fun createPolyline(state: PolylineState): TomTomActualPolyline? =
        withContext(coroutine.coroutineContext) {
            val options =
                PolylineOptions(
                    coordinates = coordinates(state.points, state.geodesic),
                    lineColor = state.strokeColor.toArgb(),
                    lineWidths = listOf(WidthByZoom(ResourceProvider.dpToPx(state.strokeWidth))),
                    isClickable = true,
                    tag = state.id,
                )
            holder.map.addPolyline(options)
        }

    override suspend fun updatePolylineProperties(
        polyline: TomTomActualPolyline,
        current: PolylineEntityInterface<TomTomActualPolyline>,
        prev: PolylineEntityInterface<TomTomActualPolyline>,
    ): TomTomActualPolyline? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            if (finger.points != prevFinger.points || finger.geodesic != prevFinger.geodesic) {
                polyline.coordinates = coordinates(current.state.points, current.state.geodesic)
            }
            if (finger.strokeColor != prevFinger.strokeColor) {
                polyline.lineColor = current.state.strokeColor.toArgb()
            }
            if (finger.strokeWidth != prevFinger.strokeWidth) {
                polyline.lineWidths = listOf(WidthByZoom(ResourceProvider.dpToPx(current.state.strokeWidth)))
            }
            polyline
        }

    override suspend fun removePolyline(entity: PolylineEntityInterface<TomTomActualPolyline>) {
        withContext(coroutine.coroutineContext) {
            holder.map.removePolylines(tag = entity.state.id)
        }
    }
}
