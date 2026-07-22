package com.mapconductor.tomtom.marker

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.AbstractMarkerOverlayRenderer
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.tomtom.TomTomActualMarker
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.toTomTomGeoPoint
import com.tomtom.sdk.map.display.marker.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TomTom Orbis のネイティブマーカー（[com.tomtom.sdk.map.display.marker.Marker]）を描画する。
 *
 * TomTom の [com.tomtom.sdk.map.display.marker.Marker] は `coordinate` / `isVisible` が可変で、
 * `setPinImage` でアイコンも差し替えられるため、位置・表示・アイコンの変更は既存インスタンスを
 * その場で更新する（削除→再生成はしない）。ドラッグ中に毎フレーム削除→再生成すると
 * メインスレッドが詰まって ANR になるため重要。
 */
class TomTomMarkerRenderer(
    holder: TomTomMapViewHolder,
    coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractMarkerOverlayRenderer<TomTomMapViewHolder, TomTomActualMarker>(
        holder = holder,
        coroutine = coroutine,
    ) {
    // 座標↔ピクセル変換（TomTomMapViewHolder）が使えるため、スクリーン座標での
    // マーカーアニメーション（画面上部からのドロップ／バウンス）を有効化する。
    override val supportsAnimationOverlay: Boolean = true

    override fun setMarkerVisible(
        markerEntity: MarkerEntityInterface<TomTomActualMarker>,
        visible: Boolean,
    ) {
        markerEntity.marker?.isVisible = visible
    }

    override fun setMarkerPosition(
        markerEntity: MarkerEntityInterface<TomTomActualMarker>,
        position: GeoPoint,
    ) {
        // ネイティブマーカーの coordinate を直接更新する（削除→再生成しない）。
        markerEntity.marker?.coordinate = position.toTomTomGeoPoint()
    }

    override suspend fun onAdd(
        data: List<MarkerOverlayRendererInterface.AddParamsInterface>,
    ): List<TomTomActualMarker?> =
        withContext(coroutine.coroutineContext) {
            data.map { params ->
                val options =
                    MarkerOptions(
                        coordinate = GeoPoint.from(params.state.position).toTomTomGeoPoint(),
                        pinImage = MarkerImageCache.fromBitmap(params.bitmapIcon.bitmap),
                        tag = params.state.id,
                    )
                holder.map.addMarker(options)
            }
        }

    override suspend fun onRemove(data: List<MarkerEntityInterface<TomTomActualMarker>>) {
        withContext(coroutine.coroutineContext) {
            data.forEach { entity ->
                entity.marker?.remove()
            }
        }
    }

    override suspend fun onPostProcess() {
        // Do nothing here
    }

    override suspend fun onChange(
        data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<TomTomActualMarker>>,
    ): List<TomTomActualMarker?> =
        withContext(coroutine.coroutineContext) {
            data.map { params ->
                // 既存マーカーがあれば再利用してその場で更新。無ければ生成する。
                val marker =
                    params.prev.marker ?: holder.map.addMarker(
                        MarkerOptions(
                            coordinate = GeoPoint.from(params.current.state.position).toTomTomGeoPoint(),
                            pinImage = MarkerImageCache.fromBitmap(params.bitmapIcon.bitmap),
                            tag = params.current.state.id,
                        ),
                    )

                marker.coordinate = GeoPoint.from(params.current.state.position).toTomTomGeoPoint()
                marker.isVisible = params.current.visible
                if (params.prev.fingerPrint.icon != params.current.fingerPrint.icon) {
                    marker.setPinImage(MarkerImageCache.fromBitmap(params.bitmapIcon.bitmap))
                }
                marker
            }
        }
}
