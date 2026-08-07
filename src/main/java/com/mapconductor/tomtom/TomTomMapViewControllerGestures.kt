package com.mapconductor.tomtom

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.tomtom.marker.TomTomMarkerRenderer
import com.tomtom.sdk.map.display.marker.Marker
import com.tomtom.sdk.map.display.polygon.Polygon
import com.tomtom.sdk.map.display.polyline.Polyline
import kotlin.math.abs
import android.view.MotionEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// タッチの解釈。TomTom の SDK はマーカーのドラッグを持っていないので、生の
// MotionEvent を受けて「押した位置に自分のマーカーが居るか」から組み立てている。
// タップは**マーカーが先**で、どれにも当たらなかったときだけ地図のタップとして扱う。

/**
 * MotionEvent を処理してマーカーの自前ドラッグを実現する。
 *  - draggable マーカー上の DOWN: ジェスチャを占有（true を返し地図パンを抑止）
 *  - スロップ超えの MOVE: ドラッグ開始 → 以降 指に追従してマーカーを再配置
 *  - UP: ドラッグしていれば確定、動いていなければクリック扱い
 *  - マーカー外／非 draggable の場合は false を返して地図に委ねる
 */
internal fun TomTomMapViewController.onMapTouchInternal(event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            // オーバーレイクリック（polyline/polygon/circle）でタップ位置を復元するため常に記録する。
            lastTapScreenX = event.x
            lastTapScreenY = event.y
            val position = holder.fromScreenOffsetSync(Offset(event.x, event.y)) ?: return false
            val entity = markerController.find(position, holder.map.cameraPosition.zoom)
            if (entity == null || !entity.state.draggable) return false
            pendingEntity = entity
            downX = event.x
            downY = event.y
            return true
        }
        MotionEvent.ACTION_MOVE -> {
            val dragging = draggingEntity
            if (dragging != null) {
                val position = holder.fromScreenOffsetSync(Offset(event.x, event.y)) ?: return true
                dragging.state.position = position
                (markerController.renderer as TomTomMarkerRenderer).setMarkerPosition(dragging, position)
                markerController.dispatchDrag(dragging.state)
                return true
            }
            val pending = pendingEntity ?: return false
            if (abs(event.x - downX) > TomTomMapViewController.TOUCH_SLOP_PX ||
                abs(event.y - downY) > TomTomMapViewController.TOUCH_SLOP_PX
            ) {
                draggingEntity = pending
                holder.map.isScrollEnabled = false
                markerController.dispatchDragStart(pending.state)
            }
            return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            val dragging = draggingEntity
            if (dragging != null) {
                markerController.dispatchDragEnd(dragging.state)
                draggingEntity = null
                pendingEntity = null
                holder.map.isScrollEnabled = true
                return true
            }
            val pending = pendingEntity
            pendingEntity = null
            if (pending != null) {
                // 動かず離した → クリック（掴んでいる間はネイティブのクリックが発火しないため）。
                markerController.dispatchClick(pending.state)
                return true
            }
            return false
        }
    }
    return false
}

internal fun TomTomMapViewController.lastTapPosition() =
    holder
        .fromScreenOffsetSync(Offset(lastTapScreenX, lastTapScreenY))

internal fun TomTomMapViewController.onPolylineClickedInternal(polyline: Polyline) {
    // クリックイベントの緯度経度は「タップ位置とポリラインの最近傍点」にする（他プロバイダと同じ）。
    val tap = lastTapPosition()
    if (tap != null) {
        polylineController.findWithClosestPoint(tap)?.let { hit ->
            polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
            return
        }
    }
    // フォールバック: ネイティブクリックの polyline + タップ位置（無ければ先頭点）。
    val entity =
        polylineController.polylineManager
            .allEntities()
            .firstOrNull { it.polyline.id == polyline.id } ?: return
    val clicked = tap ?: entity.state.points.firstOrNull() ?: return
    polylineController.dispatchClick(PolylineEvent(entity.state, clicked))
}

internal fun TomTomMapViewController.onPolygonClickedInternal(polygon: Polygon) {
    // GroundImage も画像付き native Polygon なので、Polygon 本体の id で先に識別する。
    // state.id ではなく native id を使い、通常 Polygon と同じ tag でも誤配送しない。
    val groundImageEntity =
        groundImageController.groundImageManager
            .allEntities()
            .firstOrNull { it.groundImage.polygon.id == polygon.id }
    if (groundImageEntity != null) {
        groundImageController.dispatchClick(GroundImageEvent(groundImageEntity.state, lastTapPosition()))
        return
    }
    // 円の塗りもコア共通リングによる native Polygon（tag = state.id）なので、
    // native id で円エンティティを先に識別する。
    val circleEntity =
        circleController.circleManager
            .allEntities()
            .firstOrNull { entity -> entity.circle?.fill?.any { it.id == polygon.id } == true }
    if (circleEntity != null) {
        val clicked = lastTapPosition() ?: circleEntity.state.center
        circleController.dispatchClick(CircleEvent(circleEntity.state, clicked))
        return
    }
    // ポリゴンは穴なし=native Polygon、穴あり=PolygonOverlay+輪郭Polygon で構成が異なる。
    // クリックされた native Polygon の tag（= state.id）でエンティティを引く。
    val entity =
        polygonController.polygonManager
            .allEntities()
            .firstOrNull { it.polygon.tag == polygon.tag } ?: return
    // クリック位置はタップした緯度経度（無ければ先頭頂点）。
    val clicked = lastTapPosition() ?: entity.state.points.firstOrNull() ?: return
    polygonController.dispatchClick(PolygonEvent(entity.state, clicked))
}

/** マップ（マーカー以外）タップ時（MapClickListener から配線）。 */
internal fun TomTomMapViewController.onMapClickInternal(coordinate: com.tomtom.sdk.location.GeoPoint) {
    val touchPosition = coordinate.toGeoPoint()
    val zoomSnapshot = holder.map.cameraPosition.zoom
    defaultCoroutine.launch {
        // マーカーのヒットテストはアイコン矩形で判定するため座標を画面へ投影する。
        // TomTom の `pointForCoordinate` はメインスレッド必須（背景スレッドから呼ぶと
        // IllegalStateException）なので、ここだけメインへ切り替える。
        val markerEntity =
            withContext(mainCoroutine.coroutineContext) {
                markerController.find(touchPosition, zoomSnapshot)
            }
        markerEntity?.let { entity ->
            if (!entity.state.clickable) return@launch
            mainCoroutine.launch { markerController.dispatchClick(entity.state) }
            return@launch
        }
        mapClickHandler()?.let { cb ->
            mainCoroutine.launch { cb(touchPosition) }
        }
    }
}

/** ネイティブのマーカークリック（MarkerClickListener から配線）。 */
internal fun TomTomMapViewController.onMarkerClickedInternal(marker: Marker): Boolean {
    val stateId = marker.tag ?: return false
    markerEventControllers.forEach { controller ->
        val entity = controller.getEntity(stateId) ?: return@forEach
        if (!entity.state.clickable) return true
        controller.dispatchClick(entity.state)
        return true
    }
    return false
}
