package com.mapconductor.tomtom.circle

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.circle.AbstractCircleOverlayRenderer
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.tomtom.TomTomActualCircle
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.toTomTomGeoPoint
import com.tomtom.sdk.location.GeoPoint as TomTomGeoPoint
import com.tomtom.sdk.map.display.circle.CircleOptions
import com.tomtom.sdk.map.display.circle.Radius
import com.tomtom.sdk.map.display.common.WidthByZoom
import com.tomtom.sdk.map.display.polyline.PolylineOptions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 円を「塗り（native circle）」と「枠線（polyline リング）」の2レイヤーで重ねて描画する
 * （Mapbox レンダラーと同方針）。native circle のアウトラインはメートル基準でズームに追従して
 * しまう／iOS では非対応のため、枠線はピクセル幅の polyline で表現する。
 */
class TomTomCircleOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractCircleOverlayRenderer<TomTomActualCircle>() {
    // 枠線 polyline の線幅。TomTom（Mapbox GL 系）の線幅は密度非依存（dp 相当）なので dp 値をそのまま渡す。
    private fun strokeWidthPx(state: CircleState): Double = state.strokeWidth.value.toDouble()

    private fun strokeTag(state: CircleState): String = "circle-stroke-${state.id}"

    /** 中心から半径 radiusMeters の円周を近似する閉じたリング（64分割）。 */
    private fun ringPoints(state: CircleState): List<TomTomGeoPoint> {
        val center = GeoPoint.from(state.center)
        val segments = 64
        val metersPerDegree = 111_320.0
        val latCorrection = if (state.geodesic) cos(Math.toRadians(center.latitude)) else 1.0
        val ring =
            (0 until segments)
                .map { i ->
                    val angle = 2.0 * PI * i / segments
                    val deltaLat = state.radiusMeters / metersPerDegree * cos(angle)
                    val deltaLng = state.radiusMeters / (metersPerDegree * latCorrection) * sin(angle)
                    TomTomGeoPoint(center.latitude + deltaLat, center.longitude + deltaLng)
                }.toMutableList()
        ring.add(ring.first())
        return ring
    }

    override suspend fun createCircle(state: CircleState): TomTomActualCircle? =
        withContext(coroutine.coroutineContext) {
            // 塗りレイヤー: native circle（アウトラインは polyline で描くので透明・0）。
            val fill =
                holder.map.addCircle(
                    CircleOptions(
                        coordinate = GeoPoint.from(state.center).toTomTomGeoPoint(),
                        radius = Radius(state.radiusMeters),
                        fillColor = state.fillColor.toArgb(),
                        isClickable = true,
                        tag = state.id,
                    ),
                )
            // 枠線レイヤー: polyline リング（クリックは塗り側で扱うため isClickable=false）。
            val stroke =
                if (strokeWidthPx(state) > 0.0) {
                    holder.map.addPolyline(
                        PolylineOptions(
                            coordinates = ringPoints(state),
                            lineColor = state.strokeColor.toArgb(),
                            lineWidths = listOf(WidthByZoom(strokeWidthPx(state))),
                            // 既定 outline（DEFAULT_OUTLINE_COLOR は赤系）を無効化する。
                            // 無効化しないと、半透明で細い枠線に既定 outline が滲み、青+赤で紫に見える
                            // （他プロバイダと strokeColor がずれる原因）。幅 0 だけでは消えないため、
                            // outline 色も透明にして確実に色が混ざらないようにする。
                            outlineWidths = listOf(WidthByZoom(0.0)),
                            outlineColor = android.graphics.Color.TRANSPARENT,
                            isClickable = false,
                            tag = strokeTag(state),
                        ),
                    )
                } else {
                    null
                }
            TomTomCircleHandle(fill, stroke)
        }

    override suspend fun updateCircleProperties(
        circle: TomTomActualCircle,
        current: CircleEntityInterface<TomTomActualCircle>,
        prev: CircleEntityInterface<TomTomActualCircle>,
    ): TomTomActualCircle? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint
            val state = current.state

            circle.fill?.let { fill ->
                if (finger.center != prevFinger.center) fill.coordinate = GeoPoint.from(state.center).toTomTomGeoPoint()
                if (finger.radiusMeters != prevFinger.radiusMeters) fill.radius = Radius(state.radiusMeters)
                if (finger.fillColor != prevFinger.fillColor) fill.fillColor = state.fillColor.toArgb()
            }

            val ringChanged = finger.center != prevFinger.center || finger.radiusMeters != prevFinger.radiusMeters
            circle.stroke?.let { stroke ->
                if (ringChanged || finger.geodesic != prevFinger.geodesic) stroke.coordinates = ringPoints(state)
                if (finger.strokeColor != prevFinger.strokeColor) stroke.lineColor = state.strokeColor.toArgb()
                if (finger.strokeWidth != prevFinger.strokeWidth) {
                    stroke.lineWidths = listOf(WidthByZoom(strokeWidthPx(state)))
                }
            }
            circle
        }

    override suspend fun removeCircle(entity: CircleEntityInterface<TomTomActualCircle>) {
        withContext(coroutine.coroutineContext) {
            holder.map.removeCircles(tag = entity.state.id)
            holder.map.removePolylines(tag = strokeTag(entity.state))
        }
    }
}
