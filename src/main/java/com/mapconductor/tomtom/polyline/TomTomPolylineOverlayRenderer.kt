package com.mapconductor.tomtom.polyline

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polyline.AbstractPolylineOverlayRenderer
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.Planar
import com.mapconductor.core.spherical.WGS84Geodesic
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
                WGS84Geodesic.createInterpolatePoints(points, maxSegmentLength = maxSegmentLengthMeters())
            } else {
                Planar.createInterpolatePoints(points)
            }
        return geoPoints.map { GeoPoint.from(it).toTomTomGeoPoint() }
    }

    private fun maxSegmentLengthMeters(): Double {
        val zoom = holder.map.cameraPosition.zoom
        val metersPerPixel = Earth.CIRCUMFERENCE_METERS / (256.0 * 2.0.pow(zoom))
        // 低ズーム（地球全体表示）では metersPerPixel が巨大になり、測地線の分割数が
        // 1〜2 本まで激減してカクつく（たわむ）。滑らかさを保つため分割長に上限を設ける。
        return (metersPerPixel * 64.0).coerceAtMost(MAX_GEODESIC_SEGMENT_METERS)
    }

    override suspend fun createPolyline(state: PolylineState): TomTomActualPolyline? =
        withContext(coroutine.coroutineContext) {
            val options =
                PolylineOptions(
                    coordinates = coordinates(state.points, state.geodesic),
                    lineColor = state.strokeColor.toArgb(),
                    lineWidths = listOf(WidthByZoom(state.strokeWidth.value.toDouble())),
                    // 既定 outline（DEFAULT_OUTLINE_COLOR は赤系）を無効化する。無効化しないと
                    // 線の周りに既定 outline が描かれ、他プロバイダと色/太さが異なって見える。
                    outlineWidths = listOf(WidthByZoom(0.0)),
                    outlineColor = android.graphics.Color.TRANSPARENT,
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
                polyline.lineWidths =
                    listOf(
                        WidthByZoom(
                            current.state.strokeWidth.value
                                .toDouble(),
                        ),
                    )
            }
            polyline
        }

    override suspend fun removePolyline(entity: PolylineEntityInterface<TomTomActualPolyline>) {
        withContext(coroutine.coroutineContext) {
            holder.map.removePolylines(tag = entity.state.id)
        }
    }

    private companion object {
        // 測地線を滑らかに見せるための 1 セグメントあたりの最大長（m）。
        // 例: 日本〜北米（約 8,000km）で約 160 分割となり、地球全体表示でもカクつかない。
        private const val MAX_GEODESIC_SEGMENT_METERS = 50_000.0
    }
}
