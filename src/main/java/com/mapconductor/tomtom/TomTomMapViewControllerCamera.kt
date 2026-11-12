package com.mapconductor.tomtom

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.CameraPaddingEmulation
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.buildVisibleRegion
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch

/** 地図の高さ（dp）。パディングのエミュレーションで遠近を解くのに使う。 */
internal fun TomTomMapViewController.viewportHeightDp(): Double {
    val density = holder.mapView.resources.displayMetrics.density
    return holder.mapView.height / density.toDouble()
}

/** パディングを中心のずらしに置き換えたカメラ。paddings が null なら今のパディングを使い続ける。 */
internal fun TomTomMapViewController.toNativeCamera(position: MapCameraPosition): MapCameraPosition {
    position.paddings?.let { cameraPaddings = it }
    return CameraPaddingEmulation.toNativeCamera(
        position.copy(paddings = cameraPaddings),
        TOMTOM_VERTICAL_FOV,
        viewportHeightDp(),
    )
}

/**
 * カメラの読み書きと、移動中・停止時の通知。
 */
internal fun TomTomMapViewController.handleMoveCamera(position: MapCameraPosition) {
    if (destroyed) return
    lastLogicalCameraPosition = position
    mainCoroutine.launch {
        if (destroyed) return@launch
        holder.map.moveCamera(toNativeCamera(position).toCameraOptions())
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
            toNativeCamera(position).toCameraOptions(),
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
    if (correctPaddedTiltIfClamped()) return
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

/**
 * TomTom はズームに応じて tilt を抑える（ズーム 15 で約 42°）。パディングを中心のずらしで
 * 再現しているので、要求した tilt で解いた中心のままだと、パディング中央からずれる
 * （tilt 60° の要求で 130px）。止まったところで実際の tilt を見て、違っていれば
 * その tilt で解き直して置き直す。置き直したら true。
 */
private fun TomTomMapViewController.correctPaddedTiltIfClamped(): Boolean {
    val requested = lastLogicalCameraPosition ?: return false
    if (requested.tilt <= 0.0) return false
    val paddings = cameraPaddings
    if (paddings.top == 0.0 && paddings.left == 0.0 && paddings.bottom == 0.0 && paddings.right == 0.0) return false
    val actualTilt = holder.map.cameraPosition.tilt
    if (abs(actualTilt - requested.tilt) < TILT_CLAMP_TOLERANCE_DEG) return false
    handleMoveCamera(requested.copy(tilt = actualTilt))
    return true
}

private const val TILT_CLAMP_TOLERANCE_DEG = 0.5

internal fun TomTomMapViewController.getMapCameraPosition(): MapCameraPosition {
    val camera =
        CameraPaddingEmulation.fromNativeCamera(
            holder.map.cameraPosition.toMapCameraPosition(
                logicalTiltHint = lastLogicalCameraPosition?.tilt,
            ),
            cameraPaddings,
            TOMTOM_VERTICAL_FOV,
            viewportHeightDp(),
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
