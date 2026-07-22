package com.mapconductor.tomtom.polygon

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.core.spherical.createLinearInterpolatePoints
import com.mapconductor.tomtom.TomTomActualPolygon
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.toTomTomGeoPoint
import com.tomtom.sdk.location.GeoPoint as TomTomGeoPoint
import com.tomtom.sdk.map.display.polygon.PolygonOptions
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NOTE: TomTom Orbis の Polygon は穴（holes）をサポートしないため、外周のみ描画する。
 */
class TomTomPolygonOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractPolygonOverlayRenderer<TomTomActualPolygon>() {
    private fun ring(
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

    override suspend fun createPolygon(state: PolygonState): TomTomActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val options =
                PolygonOptions(
                    coordinates = ring(state.points, state.geodesic),
                    outlineColor = state.strokeColor.toArgb(),
                    outlineWidth = ResourceProvider.dpToPx(state.strokeWidth),
                    fillColor = state.fillColor.toArgb(),
                    isClickable = true,
                    tag = state.id,
                )
            holder.map.addPolygon(options)
        }

    override suspend fun updatePolygonProperties(
        polygon: TomTomActualPolygon,
        current: PolygonEntityInterface<TomTomActualPolygon>,
        prev: PolygonEntityInterface<TomTomActualPolygon>,
    ): TomTomActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            if (finger.points != prevFinger.points || finger.geodesic != prevFinger.geodesic) {
                polygon.coordinates = ring(current.state.points, current.state.geodesic)
            }
            if (finger.strokeColor != prevFinger.strokeColor) {
                polygon.outlineColor = current.state.strokeColor.toArgb()
            }
            if (finger.strokeWidth != prevFinger.strokeWidth) {
                polygon.outlineWidth = ResourceProvider.dpToPx(current.state.strokeWidth)
            }
            if (finger.fillColor != prevFinger.fillColor) {
                polygon.fillColor = current.state.fillColor.toArgb()
            }
            polygon
        }

    override suspend fun removePolygon(entity: PolygonEntityInterface<TomTomActualPolygon>) {
        withContext(coroutine.coroutineContext) {
            holder.map.removePolygons(tag = entity.state.id)
        }
    }
}
