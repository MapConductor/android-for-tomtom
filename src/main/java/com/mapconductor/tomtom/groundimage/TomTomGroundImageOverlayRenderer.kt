package com.mapconductor.tomtom.groundimage

import com.mapconductor.core.groundimage.AbstractGroundImageOverlayRenderer
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.GroundImageTileProvider
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.tomtom.TomTomActualGroundImage
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.mapconductor.tomtom.raster.TomTomRasterLayerSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GroundImage を「ローカルタイルサーバ + 合成スタイルのラスタレイヤー」で描画する（HERE のラスタ方式と同方針）。
 *
 * TomTom には実行時の addLayer/addSource が無いため、画像を [GroundImageTileProvider] でタイル化して
 * ローカルサーバへ配信し、そのタイル URL を [TomTomRasterLayerSink] 経由でラスタレイヤーとして
 * 合成スタイルに載せる。不透明度はタイル生成時に焼き込むため、レイヤー側の opacity は 1.0 とする。
 */
class TomTomGroundImageOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    private val tileServer: LocalTileServer,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractGroundImageOverlayRenderer<TomTomActualGroundImage>() {
    /** ラスタレイヤーの実体化先。[com.mapconductor.tomtom.TomTomMapViewController] が生成後に設定する。 */
    var rasterSink: TomTomRasterLayerSink? = null

    private fun safeRouteId(id: String): String = "ground-" + id.replace(Regex("[^A-Za-z0-9_-]"), "-")

    private fun rasterId(id: String): String = "ground-image-$id"

    private fun cacheKey(
        state: GroundImageState,
        generation: Long,
    ): String = "${state.fingerPrint().hashCode()}-$generation"

    override suspend fun createGroundImage(state: GroundImageState): TomTomActualGroundImage? =
        withContext(coroutine.coroutineContext) {
            val routeId = safeRouteId(state.id)
            val provider = GroundImageTileProvider(tileSize = state.tileSize)
            provider.update(state, opacity = state.opacity)
            tileServer.register(routeId, provider)

            val generation = 0L
            val rid = rasterId(state.id)
            val url = tileServer.urlTemplateWithQueryCacheKey(routeId, state.tileSize, cacheKey(state, generation))
            rasterSink?.upsertRasterLayer(rid, url, 1.0)
            TomTomGroundImageHandle(routeId = routeId, rasterId = rid, tileProvider = provider, generation = generation)
        }

    override suspend fun updateGroundImageProperties(
        groundImage: TomTomActualGroundImage,
        current: GroundImageEntityInterface<TomTomActualGroundImage>,
        prev: GroundImageEntityInterface<TomTomActualGroundImage>,
    ): TomTomActualGroundImage? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint
            val changed =
                finger.bounds != prevFinger.bounds ||
                    finger.image != prevFinger.image ||
                    finger.opacity != prevFinger.opacity ||
                    finger.tileSize != prevFinger.tileSize
            if (!changed) return@withContext groundImage

            val provider =
                if (finger.tileSize != prevFinger.tileSize) {
                    GroundImageTileProvider(tileSize = current.state.tileSize).also {
                        tileServer.register(groundImage.routeId, it)
                    }
                } else {
                    groundImage.tileProvider
                }
            provider.update(current.state, opacity = current.state.opacity)

            val generation = groundImage.generation + 1L
            // cacheKey が変わる＝URL が変わるので、合成スタイル再ロード時にタイルが確実に refetch される。
            val url =
                tileServer.urlTemplateWithQueryCacheKey(
                    groundImage.routeId,
                    current.state.tileSize,
                    cacheKey(current.state, generation),
                )
            rasterSink?.upsertRasterLayer(groundImage.rasterId, url, 1.0)
            TomTomGroundImageHandle(
                routeId = groundImage.routeId,
                rasterId = groundImage.rasterId,
                tileProvider = provider,
                generation = generation,
            )
        }

    override suspend fun removeGroundImage(entity: GroundImageEntityInterface<TomTomActualGroundImage>) {
        withContext(coroutine.coroutineContext) {
            entity.groundImage.let { handle ->
                rasterSink?.removeRasterLayer(handle.rasterId)
                tileServer.unregister(handle.routeId)
            }
        }
    }
}
