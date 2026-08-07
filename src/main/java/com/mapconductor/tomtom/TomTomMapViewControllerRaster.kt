package com.mapconductor.tomtom

import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.tomtom.marker.MarkerTileRasterLayerCallback
import com.mapconductor.tomtom.raster.TomTomStyleComposer
import com.tomtom.sdk.map.display.style.LoadingStyleFailure
import com.tomtom.sdk.map.display.style.StyleDescriptor
import com.tomtom.sdk.map.display.style.StyleLoadingCallback
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ラスターレイヤーをスタイルへ合成する部分。
 *
 * TomTom はスタイルにレイヤーを後から足せないため、ラスターが増減するたびに
 * スタイル JSON を作り直して読み込ませる。読み込みは重いので
 * [scheduleComposedStyleReload] でまとめてから 1 回だけ走らせる。
 */
internal fun TomTomMapViewController.setupMarkerTileRaster(
    apiKey: String,
    cacheDir: File,
) {
    rasterApiKey = apiKey
    rasterCacheDir = cacheDir
    markerController.setRasterLayerCallback(
        MarkerTileRasterLayerCallback { state ->
            if (state == null) {
                removeRasterLayer(TomTomMapViewController.MARKER_RASTER_ID)
                return@MarkerTileRasterLayerCallback
            }
            val src =
                state.source as? RasterLayerSource.UrlTemplate
                    ?: return@MarkerTileRasterLayerCallback
            // マーカータイルは透明 PNG（アイコンのみ）なので不透明で重ねる。
            upsertRasterLayer(TomTomMapViewController.MARKER_RASTER_ID, src.template, 1.0)
        },
    )
}

internal suspend fun TomTomMapViewController.applyPublicRasterLayer(state: RasterLayerState) {
    val src = state.source as? RasterLayerSource.UrlTemplate
    if (src == null || !state.visible) {
        removeRasterLayer(state.id)
        return
    }
    // ソースの tileSize / minZoom / maxZoom を合成スタイルへ伝える。maxZoom を渡すと
    // 高ズームでオーバーズーム表示され、実タイルの無い領域での歯抜けを防げる。
    upsertRasterLayer(
        id = state.id,
        tilesUrl = src.template,
        opacity = state.opacity.toDouble(),
        tileSize = src.tileSize,
        minZoom = src.minZoom,
        maxZoom = src.maxZoom,
    )
}

/**
 * 合成スタイルの再ロードをデバウンスして予約する。TomTom は実行時に paint（raster-opacity 等）を
 * 変更する API が無く、変更のたびに `loadStyle` で全タイルを再フェッチするため、opacity スライダー
 * のような連続変更をそのまま反映すると地図が空白のまま追いつかなくなる。最後の変更だけ反映する。
 */
internal fun TomTomMapViewController.scheduleComposedStyleReload() {
    styleReloadJob?.cancel()
    styleReloadJob =
        defaultCoroutine.launch {
            delay(TomTomMapViewController.COMPOSED_STYLE_DEBOUNCE_MS)
            composedStyleMutex.withLock { applyComposedStyle() }
        }
}

/** 現在のラスタレイヤー群で合成スタイルを再ロードする。0 枚ならデザインスタイルへ戻す。 */
internal suspend fun TomTomMapViewController.applyComposedStyle() {
    if (destroyed) return
    val apiKey = rasterApiKey
    val cacheDir = rasterCacheDir
    if (composedRasterLayers.isEmpty() || apiKey == null || cacheDir == null) {
        loadDesignStyle()
        return
    }
    composedStyleToggle = composedStyleToggle xor 1
    val outFile = File(cacheDir, "tomtom_composed_style_$composedStyleToggle.json")
    val uri =
        TomTomStyleComposer.composeRasterStyle(
            apiKey = apiKey,
            cacheDir = cacheDir,
            layers = composedRasterLayers.values.toList(),
            outFile = outFile,
        ) ?: return
    if (destroyed) return
    withContext(Dispatchers.Main) {
        // loadStyle はカメラをリセットし、その状態だと再ロード後に現在ビューポートの
        // タイル取得がトリガーされず地図が空白のままになる。ロード完了後に現在カメラを
        // 再適用してタイル取得を促す（マーカータイリングの初期化と同じ対処）。
        val currentCamera =
            holder.map.cameraPosition.toMapCameraPosition(
                logicalTiltHint = lastLogicalCameraPosition?.tilt,
            )
        holder.map.loadStyle(
            StyleDescriptor(uri, uri),
            object : StyleLoadingCallback {
                override fun onSuccess() {
                    if (destroyed) return
                    // 合成スタイルの再ロード後は、同一ビューポートのタイルが自動で再取得されず
                    // 地図が空白のままになる。初期ロードと同じく mapView.post 経由でカメラを
                    // 再適用し、レイアウト後にタイル取得を促す。
                    holder.mapView.post {
                        if (!destroyed) moveCamera(currentCamera)
                    }
                }

                override fun onFailure(failure: LoadingStyleFailure) {
                    android.util.Log.e("TomTomRaster", "loadStyle failed (composed-raster): $failure")
                }
            },
        )
    }
}

internal fun TomTomMapViewController.styleLoadingCallback(tag: String) =
    object : StyleLoadingCallback {
        override fun onSuccess() = Unit

        override fun onFailure(failure: LoadingStyleFailure) {
            android.util.Log.e("TomTomRaster", "loadStyle failed ($tag): $failure")
        }
    }
