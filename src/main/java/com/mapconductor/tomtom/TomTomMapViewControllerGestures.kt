package com.mapconductor.tomtom

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.marker.dispatchNativeMarkerClick
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

/**
 * オーバーレイのクリックを、コア共通のヒットテストで振り分ける。
 *
 * 探索順は他プロバイダと同じ `circle → groundImage → polyline → polygon`。
 * 当たれば配送して true。
 *
 * TomTom のネイティブのオーバーレイ用リスナー（PolygonClickListener /
 * PolylineClickListener）は、**識別手段ではなく発火のきっかけ**としてのみ使う。
 * どのエンティティかの判定はここでコアに委ねているので、他プロバイダと同じ結果になる
 * （例: 穴の内側は「外」と判定される）。
 *
 * ネイティブのリスナーを外せない理由:
 * 実機計測（Lenovo TB520FU / TomTom SDK 2.4.1）で、`isClickable = false` にすると
 * オーバーレイ上のタップは MapClickListener にも届かず、どのリスナーも発火しない。
 * Google Maps の `clickable(false)`（透過して map click が来る）とは意味が違い、
 * TomTom では握り潰しになる。よってオーバーレイ上のタップを拾うにはネイティブの
 * リスナーが要る。なお `isClickable = true` のとき MapClickListener は発火しないので、
 * ネイティブ経路と地図クリック経路は排他で、二重配送にはならない。
 */
internal fun TomTomMapViewController.dispatchOverlayTap(position: GeoPointInterface?): Boolean {
    val touchPosition = position ?: return false

    circleController.find(touchPosition)?.let { entity ->
        circleController.dispatchClick(CircleEvent(entity.state, GeoPoint.from(touchPosition)))
        return true
    }
    groundImageController.find(touchPosition)?.let { entity ->
        groundImageController.dispatchClick(GroundImageEvent(entity.state, GeoPoint.from(touchPosition)))
        return true
    }
    polylineController.findWithClosestPoint(touchPosition)?.let { hit ->
        // クリック座標は「タップ位置と線の最近傍点」にする（他プロバイダと同じ）。
        polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
        return true
    }
    polygonController.find(touchPosition)?.let { entity ->
        polygonController.dispatchClick(PolygonEvent(entity.state, GeoPoint.from(touchPosition)))
        return true
    }
    return false
}

/** ネイティブのポリラインクリック（PolylineClickListener から配線）。 */
internal fun TomTomMapViewController.onPolylineClickedInternal(
    @Suppress("UNUSED_PARAMETER") polyline: Polyline,
) {
    onNativeOverlayTapped()
}

/** ネイティブのポリゴンクリック（PolygonClickListener から配線）。円とグラウンドイメージも native Polygon）。 */
internal fun TomTomMapViewController.onPolygonClickedInternal(
    @Suppress("UNUSED_PARAMETER") polygon: Polygon,
) {
    onNativeOverlayTapped()
}

/**
 * ネイティブのオーバーレイクリックを、地図クリックと同じ配送へ流す。
 *
 * コアの判定で何にも当たらなければ地図クリックとして扱う。ネイティブは当たったと
 * 言っているがコアは当たっていないと言う状況（穴の内側など）で、イベントが
 * 握り潰されないようにするため。
 */
private fun TomTomMapViewController.onNativeOverlayTapped() {
    val touchPosition = lastTapPosition() ?: return
    if (dispatchOverlayTap(touchPosition)) return
    mapClickHandler()?.let { cb ->
        mainCoroutine.launch { cb(GeoPoint.from(touchPosition)) }
    }
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
        // オーバーレイ上のタップはネイティブのリスナー経由で来るのが通常だが、
        // 取りこぼしに備えて地図クリック側でも同じカスケードを通す。
        if (withContext(mainCoroutine.coroutineContext) { dispatchOverlayTap(touchPosition) }) {
            return@launch
        }
        mapClickHandler()?.let { cb ->
            mainCoroutine.launch { cb(touchPosition) }
        }
    }
}

/** ネイティブのマーカークリック（MarkerClickListener から配線）。 */
internal fun TomTomMapViewController.onMarkerClickedInternal(marker: Marker): Boolean =
    // TomTom の MarkerOptions には isClickable が無く（polygon / polyline にはある）、
    // マーカーのタップは SDK が消費してしまうため、ここだけネイティブのリスナーを使う。
    // 判断はコアの dispatchNativeMarkerClick に一本化（android-for-googlemaps と同じ経路）。
    // 管理外のマーカーを素通しする tag 判定もそちらに含まれる。
    markerEventControllers.dispatchNativeMarkerClick(marker.tag)
