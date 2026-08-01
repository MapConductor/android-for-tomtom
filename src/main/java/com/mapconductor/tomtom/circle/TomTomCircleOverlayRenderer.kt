package com.mapconductor.tomtom.circle

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.circle.AbstractCircleOverlayRenderer
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.normalize
import com.mapconductor.core.geometry.circleToRing
import com.mapconductor.core.geometry.closeRing
import com.mapconductor.core.geometry.splitRingByMeridian
import com.mapconductor.tomtom.TomTomActualCircle
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.toTomTomGeoPoint
import com.tomtom.sdk.location.GeoPoint as TomTomGeoPoint
import com.tomtom.sdk.map.display.common.WidthByZoom
import com.tomtom.sdk.map.display.polygon.Polygon as TomTomNativePolygon
import com.tomtom.sdk.map.display.polygon.PolygonOptions
import com.tomtom.sdk.map.display.polyline.Polyline as TomTomNativePolyline
import com.tomtom.sdk.map.display.polyline.PolylineOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 円を「塗り（Polygon）」と「枠線（polyline リング）」の2レイヤーで重ねて描画する
 * （Mapbox レンダラーと同方針）。塗り・枠線ともコア共通の [circleToRing] が生成する
 * 同一リングを使うため、半径の解釈が一致する（native circle の半径解釈には依存しない）。
 * ±180 を跨ぐ円は [splitByMeridian] で分割し、断片ごとに描画する（GeoJSON 系ドライバーと
 * 同方針）。native circle のアウトラインはメートル基準でズームに追従してしまう／iOS では
 * 非対応のため、枠線はピクセル幅の polyline で表現する。
 */
class TomTomCircleOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractCircleOverlayRenderer<TomTomActualCircle>() {
    // 枠線 polyline の線幅。TomTom（Mapbox GL 系）の線幅は密度非依存（dp 相当）なので dp 値をそのまま渡す。
    private fun strokeWidthPx(state: CircleState): Double = state.strokeWidth.value.toDouble()

    private fun strokeTag(state: CircleState): String = "circle-stroke-${state.id}"

    /**
     * コア共通の [circleToRing]（unwrap 座標）を正規化してから子午線分割した断片リング。
     * TomTom の GeoPoint 範囲チェック（経度 ±180）を超えず、±180 跨ぎでも塗りが崩れない。
     */
    private fun ringFragments(state: CircleState): List<List<TomTomGeoPoint>> =
        splitRingByMeridian(
            circleToRing(state.center, state.radiusMeters, state.geodesic).map { it.normalize() },
            state.geodesic,
        ).filter { it.size >= 3 }
            .map { fragment -> fragment.map { GeoPoint.from(it).toTomTomGeoPoint() } }

    /** 塗り用リング。TomTom の Polygon は外周が時計回りだと塗られないため CCW へ正規化する。 */
    private fun fillRing(ring: List<TomTomGeoPoint>): List<TomTomGeoPoint> {
        if (ring.size < 3) return ring
        val signedArea =
            ring.indices.sumOf { i ->
                val cur = ring[i]
                val next = ring[(i + 1) % ring.size]
                cur.longitude * next.latitude - next.longitude * cur.latitude
            }
        return if (signedArea < 0) ring.asReversed() else ring
    }

    private fun addFills(state: CircleState): List<TomTomNativePolygon> =
        ringFragments(state).map { fragment ->
            holder.map.addPolygon(
                PolygonOptions(
                    coordinates = fillRing(fragment),
                    fillColor = state.fillColor.toArgb(),
                    // 輪郭は polyline 側で描くため透明にする（幅 0 は受け付けないため 1.0）。
                    outlineColor = android.graphics.Color.TRANSPARENT,
                    outlineWidth = 1.0,
                    isClickable = true,
                    tag = state.id,
                ),
            )
        }

    private fun addStrokes(state: CircleState): List<TomTomNativePolyline> {
        if (strokeWidthPx(state) <= 0.0) return emptyList()
        return ringFragments(state).map { fragment ->
            holder.map.addPolyline(
                PolylineOptions(
                    coordinates = closeRing(fragment),
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
        }
    }

    override suspend fun createCircle(state: CircleState): TomTomActualCircle? =
        withContext(coroutine.coroutineContext) {
            TomTomCircleHandle(fill = addFills(state), stroke = addStrokes(state))
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

            val ringChanged =
                finger.center != prevFinger.center ||
                    finger.radiusMeters != prevFinger.radiusMeters ||
                    finger.geodesic != prevFinger.geodesic

            // Polygon には座標更新 API が無く、分割断片の数も変わり得るため、
            // 形状が変わったら塗り・枠線とも作り直す（色は生成時の state を反映済み）。
            if (ringChanged) {
                holder.map.removePolygons(tag = state.id)
                holder.map.removePolylines(tag = strokeTag(state))
                circle.fill = addFills(state)
                circle.stroke = addStrokes(state)
                return@withContext circle
            }

            if (finger.fillColor != prevFinger.fillColor) {
                holder.map.removePolygons(tag = state.id)
                circle.fill = addFills(state)
            }
            val strokeRecreate =
                finger.strokeWidth != prevFinger.strokeWidth &&
                    (circle.stroke.isEmpty() || strokeWidthPx(state) <= 0.0)
            if (strokeRecreate) {
                holder.map.removePolylines(tag = strokeTag(state))
                circle.stroke = addStrokes(state)
            } else {
                circle.stroke.forEach { stroke ->
                    if (finger.strokeColor != prevFinger.strokeColor) stroke.lineColor = state.strokeColor.toArgb()
                    if (finger.strokeWidth != prevFinger.strokeWidth) {
                        stroke.lineWidths = listOf(WidthByZoom(strokeWidthPx(state)))
                    }
                }
            }
            circle
        }

    override suspend fun removeCircle(entity: CircleEntityInterface<TomTomActualCircle>) {
        withContext(coroutine.coroutineContext) {
            holder.map.removePolygons(tag = entity.state.id)
            holder.map.removePolylines(tag = strokeTag(entity.state))
        }
    }
}
