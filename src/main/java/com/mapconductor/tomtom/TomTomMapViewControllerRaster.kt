package com.mapconductor.tomtom

import com.mapconductor.core.polygon.POLYGON_TILE_LAYER_ID
import com.mapconductor.core.polygon.PolygonTiler
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
 *
 * **[createTomTomMapViewController] の直後に必ず呼ぶこと。** Compose の
 * [TomTomMapView] も React Native の `TomTomMapViewWrapper` もここを通る。
 * 呼ばないと、コントローラは件数を見てタイル経路へ倒すのに載せる先の
 * ラスタレイヤが無く、**大量マーカーが 1 つも描かれない**（例外も警告も出ない）。
 */
fun TomTomMapViewController.setupMarkerTileRaster(
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
    // Many polygons are drawn as raster tiles, composed into the style like the marker tiles.
    // Not a public layer, so the app's RasterLayer composition never removes it.
    polygonController.tiler =
        PolygonTiler().apply {
            onTemplate = { template, tileSize ->
                if (template != null) {
                    upsertRasterLayer(POLYGON_TILE_LAYER_ID, template, 1.0, tileSize, null, null)
                } else {
                    removeRasterLayer(POLYGON_TILE_LAYER_ID)
                }
            }
        }
}

internal suspend fun TomTomMapViewController.applyPublicRasterLayer(state: RasterLayerState) {
    val src = state.source as? RasterLayerSource.UrlTemplate
    if (src == null || !state.visible) {
        removeRasterLayer(state.id)
        return
    }
    // 帯の中の順（zIndex、同じなら渡された順）。合成のときに並べる。
    composedRasterZIndex[state.id] = state.zIndex
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
    // 「ベースマップ無し」自体が合成スタイル（合成器のコメント参照）なので、
    // ラスタレイヤーが無くても None なら合成する。
    val withoutBasemap = mapDesignType.id == TomTomMapDesign.None.id
    if ((composedRasterLayers.isEmpty() && !withoutBasemap) || apiKey == null || cacheDir == null) {
        loadDesignStyle()
        return
    }
    composedStyleToggle = composedStyleToggle xor 1
    val outFile = File(cacheDir, "tomtom_composed_style_$composedStyleToggle.json")
    // アプリのラスターはラスターの帯（SDK のオーバーレイの下）、マーカーのタイルはマーカーの帯（最前面）。
    // ポリゴンのタイルはアプリのラスターのすぐ上（SDK のオーバーレイの下）。ポリゴンの帯は
    // GroundImage の上だが、TomTom の GroundImage はネイティブの Polygon（SDK のオーバーレイ）で、
    // スタイルの層をその間へは入れられない。サークル・ポリライン・マーカーの下は守れる。
    val (markerTiles, others) =
        composedRasterLayers.values.partition { it.id == TomTomMapViewController.MARKER_RASTER_ID }
    val (polygonTiles, appRasters) = others.partition { it.id == POLYGON_TILE_LAYER_ID }
    val uri =
        TomTomStyleComposer.composeStackedRasterStyle(
            apiKey = apiKey,
            cacheDir = cacheDir,
            belowOverlays = appRasters.sortedBy { composedRasterZIndex[it.id] ?: 0 } + polygonTiles,
            aboveOverlays = markerTiles,
            outFile = outFile,
            withoutBasemap = withoutBasemap,
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

