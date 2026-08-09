package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.buildVisibleRegion
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch

/**
 * カメラの読み書きと、移動中・停止時の通知。
 */
internal fun TomTomMapViewController.handleMoveCamera(position: MapCameraPosition) {
    if (destroyed) return
    lastLogicalCameraPosition = position
    mainCoroutine.launch {
        if (destroyed) return@launch
        holder.map.moveCamera(position.toCameraOptions())
    }
}

internal fun TomTomMapViewController.handleAnimateCamera(
    position: MapCameraPosition,
    duration: Long,
) {
    if (destroyed) return
    lastLogicalCameraPosition = position
    mainCoroutine.launch {
        if (destroyed) return@launch
        holder.map.animateCamera(
            position.toCameraOptions(),
            duration.milliseconds,
        )
    }
}

internal fun TomTomMapViewController.handleFitBounds(
    bounds: GeoRectBounds,
    padding: Int,
) {
    if (destroyed) return
    val sw = bounds.southWest ?: return
    val ne = bounds.northEast ?: return
    // TomTom は CameraOptionsFactory.lookAt(bounds, zoom, padding) で矩形フィットする。
    // ズームは自動計算に委ねる（zoom=null）。padding(px) はそのまま第3引数へ渡す。
    val geoBounds =
        com.tomtom.sdk.location.GeoBounds(
            listOf(
                com.tomtom.sdk.location
                    .GeoPoint(sw.latitude, sw.longitude),
                com.tomtom.sdk.location
                    .GeoPoint(ne.latitude, ne.longitude),
            ),
        )
    val cameraOptions =
        com.tomtom.sdk.map.display.camera.CameraOptionsFactory.lookAt(
            geoBounds,
            null,
            padding,
        )
    mainCoroutine.launch {
        if (destroyed) return@launch
        holder.map.moveCamera(cameraOptions)
    }
}

internal fun TomTomMapViewController.onCameraChangeInternal() {
    val mapCameraPosition = getMapCameraPosition()
    if (!cameraMovingStarted) {
        cameraMovingStarted = true
        emitCameraMoveStart(mapCameraPosition)
    }
    defaultCoroutine.launch { emitCameraPosition(mapCameraPosition) }
    emitCameraMove(mapCameraPosition)
}

/** カメラ停止時（steady リスナーから配線）。 */
internal fun TomTomMapViewController.onCameraSteadyInternal() {
    cameraMovingStarted = false
    val mapCameraPosition = getMapCameraPosition()
    // 範囲・ズーム制限に違反していれば矩形内へ引き戻す（TomTom はネイティブの範囲制限 API が無いため）。
    // 再適用すると再度 steady が発火し、そこでは補正不要になり通常フローへ進む。
    correctForCameraRestriction(mapCameraPosition)?.let { corrected ->
        handleMoveCamera(corrected)
        return
    }
    defaultCoroutine.launch { markerController.onCameraChanged(mapCameraPosition) }
    emitCameraMoveEnd(mapCameraPosition)
}

internal fun TomTomMapViewController.getMapCameraPosition(): MapCameraPosition {
    val camera =
        holder.map.cameraPosition.toMapCameraPosition(
            logicalTiltHint = lastLogicalCameraPosition?.tilt,
        )
    // 画面四隅を投影して visibleRegion（ビューポート）を構築する。
    // これが無いと marker-clustering がビューポートを算出できずクラスタが一切描画されない
    // （他プロバイダは getMapCameraPosition で visibleRegion を設定している）。
    //
    // requireAllCorners = false: 傾けた地図では隅の逆投影が地表に当たらないことがあり、
    // そこで visibleRegion ごと落とすとクラスタが消える。解けた隅だけで bounds を作る。
    val visibleRegion = holder.buildVisibleRegion(requireAllCorners = false) ?: return camera
    return camera.copy(visibleRegion = visibleRegion)
}
