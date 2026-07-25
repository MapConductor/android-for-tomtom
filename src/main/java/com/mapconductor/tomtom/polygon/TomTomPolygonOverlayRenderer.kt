package com.mapconductor.tomtom.polygon

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.bridgeHolesIntoSingleRing
import com.mapconductor.core.polygon.unionHoles
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.createInterpolatePoints
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
 * TomTom Orbis の穴付きポリゴンは「ブリッジ（keyhole）方式」で描画する。
 *
 * TomTom の安定 API（`PolygonOptions`）の Polygon は穴（inner ring）を持てず、`PolygonOverlay`
 * の穴は concentric（単一チェーン）で非重複の複数兄弟穴を表現できない。GeoJSON overlay は
 * 内部/実験 API でスタイル制御・削除ができない。そこで外周＋全穴を[bridgeHolesIntoSingleRing]で
 * 1 本のリングに繋ぎ、通常 Polygon の塗りで穴を抜く。
 *
 * - 穴なし: native Polygon（fill + outline）1枚。
 * - 穴あり: ブリッジした単一リングの塗り Polygon（outline なし。橋の切れ込みが線に出るため）＋
 *   外周・各穴の輪郭を描く stroke-only Polygon（透明 fill）を重ねる。
 *
 * クリックは同じ tag を付けた塗り/輪郭 Polygon の tag 経由で拾う。
 */
class TomTomPolygonOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractPolygonOverlayRenderer<TomTomActualPolygon>() {
    private fun ring(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<TomTomGeoPoint> {
        // 非 geodesic は補間せず生の頂点を使う（HERE と同じ）。geodesic のみ補間する。
        val geoPoints =
            if (geodesic) {
                createInterpolatePoints(points, maxSegmentLength = maxSegmentLengthMeters())
            } else {
                points
            }
        var ring = geoPoints.map { GeoPoint.from(it).toTomTomGeoPoint() }
        // 末尾の重複始点を除く（開いたリング）。
        if (ring.size >= 2 && ring.first() == ring.last()) {
            ring = ring.dropLast(1)
        }
        // TomTom の Polygon は塗り（fill）の巻き方向に依存し、外周が時計回りだと塗られない。
        // 符号付き面積（shoelace）で判定し、反時計回り(CCW)へ正規化する。
        if (ring.size >= 3) {
            val signedArea =
                ring.indices.sumOf { i ->
                    val cur = ring[i]
                    val next = ring[(i + 1) % ring.size]
                    cur.longitude * next.latitude - next.longitude * cur.latitude
                }
            if (signedArea < 0) {
                ring = ring.asReversed()
            }
        }
        return ring
    }

    /**
     * ブリッジ計算用に、補間済み（geodesic 時）の core GeoPoint 列を返す。巻き方向は正規化しない
     * （[bridgeHolesIntoSingleRing] が外周 CCW / 穴 CW に正規化する）。
     */
    private fun interpolatedGeo(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<GeoPointInterface> {
        val geoPoints =
            if (geodesic) {
                createInterpolatePoints(points, maxSegmentLength = maxSegmentLengthMeters())
            } else {
                points
            }
        val ring = geoPoints.map { GeoPoint.from(it) }
        return if (ring.size >= 2 && ring.first() == ring.last()) ring.dropLast(1) else ring
    }

    private fun maxSegmentLengthMeters(): Double {
        val zoom = holder.map.cameraPosition.zoom
        val metersPerPixel = Earth.CIRCUMFERENCE_METERS / (256.0 * 2.0.pow(zoom))
        // 低ズーム（地球全体表示）では metersPerPixel が巨大になり、測地線ポリゴンの辺の分割数が
        // 激減してカクつく。滑らかさを保つため分割長に上限を設ける（polyline と同じ方針）。
        return (metersPerPixel * 64.0).coerceAtMost(MAX_GEODESIC_SEGMENT_METERS)
    }

    /**
     * 複数の穴が重なっている場合は結合（union）して重複を解消する。
     * 他プロバイダ（ArcGIS/Mapbox/MapLibre/HERE/Google）と同じ [unionHoles] を用いる。
     */
    private fun resolveHoles(state: PolygonState): PolygonState =
        if (state.holes.size > 1) state.unionHoles() else state

    override suspend fun createPolygon(state: PolygonState): TomTomActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val resolved = resolveHoles(state)
            val outerRing = ring(resolved.points, resolved.geodesic)
            val holeRings = resolved.holes.map { ring(it, resolved.geodesic) }.filter { it.size >= 3 }

            if (holeRings.isEmpty()) {
                // 穴なし: native Polygon 1枚で fill + outline。
                val polygon =
                    holder.map.addPolygon(
                        PolygonOptions(
                            coordinates = outerRing,
                            outlineColor = state.strokeColor.toArgb(),
                            outlineWidth = state.strokeWidth.value.toDouble(),
                            fillColor = state.fillColor.toArgb(),
                            isClickable = true,
                            tag = state.id,
                        ),
                    )
                return@withContext TomTomPolygonHandle(tag = state.id, polygon = polygon)
            }

            // 穴あり: 外周＋全穴をブリッジで1リングに繋ぎ、通常 Polygon の塗りで穴を抜く。
            // 輪郭（外周・各穴）は stroke-only Polygon で別途描く。
            val outlineColor = state.strokeColor.toArgb()
            val outlineWidth = state.strokeWidth.value.toDouble()

            val outerGeo = interpolatedGeo(resolved.points, resolved.geodesic)
            val holesGeo =
                resolved.holes
                    .map { interpolatedGeo(it, resolved.geodesic) }
                    .filter { it.size >= 3 }

            // 塗り: ブリッジした単一リング（輪郭なし）。
            val bridgedRing =
                bridgeHolesIntoSingleRing(outerGeo, holesGeo)
                    .map { GeoPoint.from(it).toTomTomGeoPoint() }
            holder.map.addPolygon(
                PolygonOptions(
                    coordinates = bridgedRing,
                    // 塗りの輪郭は透明にする（橋の切れ込みが線に出ないように）。TomTom は幅0を
                    // 許容しないため、幅は正の値にして色を透明にする。
                    outlineColor = android.graphics.Color.TRANSPARENT,
                    outlineWidth = outlineWidth.coerceAtLeast(1.0),
                    fillColor = resolved.fillColor.toArgb(),
                    isClickable = true,
                    tag = state.id,
                ),
            )

            // 輪郭: 外周 + 各穴（透明 fill の stroke-only Polygon）。
            holder.map.addPolygon(
                PolygonOptions(
                    coordinates = outerRing,
                    outlineColor = outlineColor,
                    outlineWidth = outlineWidth,
                    fillColor = android.graphics.Color.TRANSPARENT,
                    isClickable = false,
                    tag = state.id,
                ),
            )
            holeRings.forEach { hole ->
                holder.map.addPolygon(
                    PolygonOptions(
                        coordinates = hole,
                        outlineColor = outlineColor,
                        outlineWidth = outlineWidth,
                        fillColor = android.graphics.Color.TRANSPARENT,
                        isClickable = false,
                        tag = state.id,
                    ),
                )
            }
            TomTomPolygonHandle(tag = state.id)
        }

    override suspend fun updatePolygonProperties(
        polygon: TomTomActualPolygon,
        current: PolygonEntityInterface<TomTomActualPolygon>,
        prev: PolygonEntityInterface<TomTomActualPolygon>,
    ): TomTomActualPolygon? =
        withContext(coroutine.coroutineContext) {
            if (current.fingerPrint == prev.fingerPrint) {
                return@withContext polygon
            }
            // 穴の有無で構成（Polygon / PolygonOverlay）が変わり、PolygonOverlay は在庫の座標変更 API を
            // 持たないため、変更時は作り直す（TomTom のベクタ描画は軽量）。
            removeHandle(polygon)
            createPolygon(current.state)
        }

    override suspend fun removePolygon(entity: PolygonEntityInterface<TomTomActualPolygon>) {
        withContext(coroutine.coroutineContext) {
            removeHandle(entity.polygon)
        }
    }

    private fun removeHandle(handle: TomTomActualPolygon) {
        // tag 付き Polygon（穴なしの本体 / 穴ありの輪郭）を一括削除。
        holder.map.removePolygons(tag = handle.tag)
        // PolygonOverlay は tag を持たないためインスタンス参照で削除。
        handle.overlay?.remove()
    }

    private companion object {
        // ポリゴンは塗りの三角形分割があるため、細かすぎるとグローブ上で z-fighting/モアレが出る。
        // エッジが滑らかに見える範囲でなるべく粗くする（polyline の 50km より大きめ）。
        private const val MAX_GEODESIC_SEGMENT_METERS = 200_000.0
    }
}
