package com.mapconductor.tomtom.marker

import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.AbstractMarkerController
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerHitTest
import com.mapconductor.core.marker.MarkerIngestionEngine
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTileRenderer
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.tileserver.TileServerRegistry
import com.mapconductor.tomtom.TomTomActualMarker
import com.mapconductor.tomtom.TomTomMapViewHolder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

interface TomTomMarkerControllerInterface :
    OverlayControllerInterface<MarkerState, MarkerEntityInterface<TomTomActualMarker>>

/**
 * TomTom がマーカータイル用ラスタレイヤーを追加/更新/削除する必要が生じたときに呼ばれるコールバック。
 *
 * TomTom には実行時の addLayer/addSource が無いため、[state] が非 null のときは
 * 「ベーススタイル + ラスタ」を合成して `loadStyle` し、null のときは通常のデザインスタイルへ戻す
 * ——という実装を [TomTomMapViewController] 側で配線する。
 */
fun interface MarkerTileRasterLayerCallback {
    suspend fun onRasterLayerUpdate(state: RasterLayerState?)
}

/**
 * TomTom 用マーカーコントローラ。
 *
 * 他プロバイダ（GoogleMap 等）と同様に、`minMarkerCount` を超える静的マーカーを
 * [MarkerTileRenderer] で PNG タイルに描画し、ローカルタイルサーバ経由の**ラスタレイヤー**として
 * 表示する（＝数千件のネイティブマーカーを常駐させない）。ラスタレイヤーの実体化は
 * [MarkerTileRasterLayerCallback] を通じて [TomTomMapViewController] が
 * 「スタイル合成 + loadStyle」で行う（TomTom SDK に動的レイヤー追加 API が無いため）。
 *
 * タイル対象外（ドラッグ可能・アニメーション付き）のマーカーは従来どおりネイティブマーカーで描画する。
 */
internal class TomTomMarkerController private constructor(
    renderer: TomTomMarkerRenderer,
    markerManager: MarkerManager<TomTomActualMarker>,
    private val markerTiling: MarkerTilingOptions,
) : AbstractMarkerController<TomTomActualMarker>(
        markerManager = markerManager,
        renderer = renderer,
    ),
    TomTomMarkerControllerInterface,
    OnCameraChangeReceiverInterface {
    private val defaultMarkerIcon: BitmapIcon = DefaultMarkerIcon().toBitmapIcon()
    private val tiledMarkerIds = LinkedHashSet<String>()
    private var lastKnownZoom: Double = 0.0

    // タイル描画（ローカルサーバ + ラスタレイヤー）。
    // TomTom はローカルの動的マーカータイルを immutable ヘッダで積極キャッシュし、
    // `?v=` によるキャッシュ無効化が効きにくく古いタイルが残り得るため no-store で配信する
    // （ArcGIS と同じ扱い）。
    private val tileServer = TileServerRegistry.get(forceNoStoreCache = true)
    private var markerTileRenderer: MarkerTileRenderer<TomTomActualMarker>? = null
    private var markerTileGroupId: String? = null
    private var markerTileRasterLayerState: RasterLayerState? = null
    private var rasterLayerCallback: MarkerTileRasterLayerCallback? = null
    private var cacheVersion: Int = 0

    internal fun setRasterLayerCallback(callback: MarkerTileRasterLayerCallback?) {
        rasterLayerCallback = callback
    }

    override fun find(position: GeoPointInterface): MarkerEntityInterface<TomTomActualMarker>? =
        find(position = position, zoom = lastKnownZoom)

    /**
     * ネイティブの marker click に乗らないマーカー（タイル描画されたもの）を、地図クリックから
     * 拾うためのヒットテスト。
     *
     * 判定は他プロバイダと同じ [MarkerHitTest]（アイコン矩形 + tapTolerance）。以前は
     * 「tapTolerance を metersPerPixel で距離へ換算した固定半径」で測地距離と比較していたため、
     * アイコンの大きさを一切見ておらず、大きいアイコンは端をタップしても反応せず、小さいアイコンは
     * 離れていても反応していた。
     *
     * @param zoom 呼び出し側が握っているカメラのズーム。判定自体は画面座標で行うため使わないが、
     *   既存の呼び出し側シグネチャを保つために残している。
     */
    @Suppress("UNUSED_PARAMETER")
    fun find(
        position: GeoPointInterface,
        zoom: Double,
    ): MarkerEntityInterface<TomTomActualMarker>? {
        val nearest = markerManager.findNearest(position) ?: return null
        val touchScreen = renderer.holder.toScreenOffset(position) ?: return null
        val markerScreen = renderer.holder.toScreenOffset(nearest.state.position) ?: return null

        return if (MarkerHitTest.hitsIcon(touchScreen, markerScreen, nearest.state)) {
            nearest
        } else {
            null
        }
    }

    override suspend fun add(data: List<MarkerState>) {
        semaphore.withPermit {
            val tilingEnabled =
                markerTiling.enabled && data.size >= markerManager.minMarkerCount

            val result =
                withContext(Dispatchers.Default) {
                    MarkerIngestionEngine.ingest(
                        data = data,
                        markerManager = markerManager,
                        renderer = renderer,
                        defaultMarkerIcon = defaultMarkerIcon,
                        tilingEnabled = tilingEnabled,
                        tiledMarkerIds = tiledMarkerIds,
                        shouldTile = { state -> !state.draggable && state.getAnimation() == null },
                    )
                }

            if (result.tiledDataChanged) {
                syncTiledOverlay()
            } else if (result.hasTiledMarkers) {
                if (markerTileRenderer == null || markerTileRasterLayerState == null) {
                    syncTiledOverlay()
                }
            } else {
                removeTileOverlay()
            }
        }
    }

    override suspend fun update(state: MarkerState) {
        if (!markerManager.hasEntity(state.id)) return

        val prevEntity = markerManager.getEntity(state.id) ?: return
        val currentFinger = state.fingerPrint()
        val prevFinger = prevEntity.fingerPrint
        if (currentFinger == prevFinger) return

        semaphore.withPermit {
            val tilingEnabled =
                markerTiling.enabled && markerManager.allEntities().size >= markerManager.minMarkerCount
            val wantsTiled = tilingEnabled && !state.draggable && state.getAnimation() == null
            val wasTiled = tiledMarkerIds.contains(state.id)
            val markerIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon

            if (wantsTiled) {
                if (!wasTiled) {
                    prevEntity.marker?.let { renderer.onRemove(listOf(prevEntity)) }
                    tiledMarkerIds.add(state.id)
                }
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = null,
                        state = state,
                        visible = prevEntity.visible,
                        isRendered = true,
                    ),
                )
                syncTiledOverlay()
                return
            }

            if (wasTiled) {
                tiledMarkerIds.remove(state.id)
            }

            val renderEntity =
                MarkerEntity(
                    marker = prevEntity.marker,
                    state = state,
                    visible = prevEntity.visible,
                    isRendered = true,
                )

            val markerParams =
                object : MarkerOverlayRendererInterface.ChangeParamsInterface<TomTomActualMarker> {
                    override val current: MarkerEntityInterface<TomTomActualMarker> = renderEntity
                    override val bitmapIcon: BitmapIcon = markerIcon
                    override val prev: MarkerEntityInterface<TomTomActualMarker> = prevEntity
                }
            val markers = renderer.onChange(listOf(markerParams))

            markers.firstOrNull()?.let { actualMarker ->
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = actualMarker,
                        state = state,
                        visible = prevEntity.visible,
                        isRendered = true,
                    ),
                )

                if (prevFinger.animation != currentFinger.animation) {
                    state.getAnimation()?.let { renderer.onAnimate(markerManager.getEntity(state.id)!!) }
                }
            }

            renderer.onPostProcess()

            if (tiledMarkerIds.isNotEmpty()) {
                syncTiledOverlay()
            } else {
                removeTileOverlay()
            }
        }
    }

    override suspend fun clear() {
        semaphore.withPermit {
            val entities = markerManager.allEntities()
            val toRemove = entities.filter { it.marker != null }
            if (toRemove.isNotEmpty()) {
                renderer.onRemove(toRemove)
            }
            markerManager.clear()
            tiledMarkerIds.clear()
            removeTileOverlay()
        }
    }

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        lastKnownZoom = mapCameraPosition.zoom
    }

    override fun destroy() {
        // タイルサーバは他マップと共有のプロセス内シングルトンなので stop はしない。
        // このマップ用のルート登録だけ解除する。
        markerTileGroupId?.let { groupId -> tileServer.unregister(groupId) }
        markerTileGroupId = null
        markerTileRenderer = null
        markerTileRasterLayerState = null
        rasterLayerCallback = null
        super.destroy()
    }

    private suspend fun syncTiledOverlay() {
        if (tiledMarkerIds.isEmpty()) {
            removeTileOverlay()
            return
        }
        if (!markerTiling.enabled) {
            removeTileOverlay()
            tiledMarkerIds.clear()
            return
        }
        getOrCreateTileRenderer()
        updateRasterLayerSource()
    }

    private fun getOrCreateTileRenderer(): MarkerTileRenderer<TomTomActualMarker> {
        markerTileRenderer?.let { return it }

        val groupId = UUID.randomUUID().toString()
        markerTileGroupId = groupId

        val tileRenderer =
            MarkerTileRenderer(
                markerManager = markerManager,
                tileSize = 256,
                cacheSizeBytes = markerTiling.cacheSize,
                debugTileOverlay = markerTiling.debugTileOverlay,
                iconScaleCallback = markerTiling.iconScaleCallback,
                // TomTom は tileSize=256 のラスタソースを「非 retina(256px)」前提で表示するため、
                // 密度でスケールされた retina タイル(256dp*density px)を渡すとアイコンが density 倍に
                // 拡大される（他プロバイダは retina を正しく扱う）。アイコン描画を 1/density して相殺する。
                extraIconScale = 1.0 / ResourceProvider.getDensity(),
            )
        markerTileRenderer = tileRenderer
        tileServer.register(groupId, tileRenderer)

        markerTileRasterLayerState =
            RasterLayerState(
                id = "marker-tile-$groupId",
                source =
                    RasterLayerSource.UrlTemplate(
                        template = tileServer.urlTemplate(groupId, tileRenderer.tileSize),
                        tileSize = tileRenderer.tileSize,
                        maxZoom = 22,
                        scheme = TileScheme.XYZ,
                    ),
                opacity = 1.0f,
                visible = true,
            )

        return tileRenderer
    }

    /**
     * ラスタソースの URL を更新（キャッシュ無効化）して再描画をトリガする。
     * マーカーデータが変わるたびに新しい [RasterLayerState] を生成し、コールバックへ渡す
     * （TomTom 側でスタイル合成 + loadStyle される）。
     */
    private suspend fun updateRasterLayerSource() {
        val groupId = markerTileGroupId ?: return
        val tileRenderer = markerTileRenderer ?: return
        val oldState = markerTileRasterLayerState ?: return

        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        tileRenderer.invalidate()

        val newState =
            RasterLayerState(
                id = oldState.id,
                source =
                    RasterLayerSource.UrlTemplate(
                        template = "${tileServer.urlTemplate(groupId, tileRenderer.tileSize)}?v=$cacheVersion",
                        tileSize = tileRenderer.tileSize,
                        maxZoom = 22,
                        scheme = TileScheme.XYZ,
                    ),
                opacity = 1.0f,
                visible = true,
            )
        markerTileRasterLayerState = newState
        rasterLayerCallback?.onRasterLayerUpdate(newState)
    }

    private suspend fun removeTileOverlay() {
        // タイルが一度も有効化されていない（＝合成スタイル未適用の）ページでは revert を呼ばない。
        // 呼ぶとデザインスタイルの不要な再ロード（ちらつき）が起きるため。
        val hadTiles = markerTileGroupId != null
        markerTileGroupId?.let { groupId -> tileServer.unregister(groupId) }
        markerTileGroupId = null
        markerTileRenderer = null
        markerTileRasterLayerState = null
        if (hadTiles) {
            rasterLayerCallback?.onRasterLayerUpdate(null)
        }
    }

    companion object {
        fun create(
            holder: TomTomMapViewHolder,
            markerTiling: MarkerTilingOptions = MarkerTilingOptions.Default,
        ): TomTomMarkerController {
            val markerManager =
                MarkerManager.defaultManager<TomTomActualMarker>(
                    minMarkerCount = markerTiling.minMarkerCount,
                )
            val renderer = TomTomMarkerRenderer(holder = holder)
            return TomTomMarkerController(
                renderer = renderer,
                markerManager = markerManager,
                markerTiling = markerTiling,
            )
        }
    }
}
