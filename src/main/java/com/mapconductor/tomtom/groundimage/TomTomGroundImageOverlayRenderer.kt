package com.mapconductor.tomtom.groundimage

import com.mapconductor.core.groundimage.AbstractGroundImageOverlayRenderer
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.tomtom.TomTomActualGroundImage
import com.mapconductor.tomtom.TomTomMapViewHolder
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.image.ImageFactory
import com.tomtom.sdk.map.display.polygon.PolygonOptions
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GroundImage を TomTom ネイティブの画像付き Polygon で描画する。
 *
 * [PolygonOptions.isImageOverlay] を有効にすると、画像が Polygon の矩形全体へ引き伸ばされる。
 * ローカルタイルサーバやスタイル再ロードを経由しないため、GroundImage の追加・更新がベース地図や
 * 他のラスタレイヤーの読み込み状態へ影響しない。
 */
class TomTomGroundImageOverlayRenderer(
    override val holder: TomTomMapViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractGroundImageOverlayRenderer<TomTomActualGroundImage>() {
    override suspend fun createGroundImage(state: GroundImageState): TomTomActualGroundImage? =
        withContext(coroutine.coroutineContext) {
            val coordinates = state.coordinates() ?: return@withContext null
            val polygon =
                holder.map.addPolygon(
                    PolygonOptions(
                        coordinates = coordinates,
                        // TomTom は outlineWidth=0 を許容しないため、透明な輪郭を指定する。
                        outlineColor = Color.TRANSPARENT,
                        outlineWidth = 1.0,
                        // 画像色に白を乗算し、alpha のみ opacity として適用する。
                        fillColor = state.imageFillColor(),
                        image = ImageFactory.fromBitmap(state.image.toBitmap()),
                        isImageOverlay = true,
                        isClickable = true,
                        tag = state.id,
                    ),
                )
            TomTomGroundImageHandle(polygon)
        }

    override suspend fun updateGroundImageProperties(
        groundImage: TomTomActualGroundImage,
        current: GroundImageEntityInterface<TomTomActualGroundImage>,
        prev: GroundImageEntityInterface<TomTomActualGroundImage>,
    ): TomTomActualGroundImage? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint
            if (finger.bounds != prevFinger.bounds) {
                val coordinates = current.state.coordinates() ?: return@withContext groundImage
                groundImage.polygon.coordinates = coordinates
            }
            if (finger.image != prevFinger.image) {
                groundImage.polygon.updateImage(ImageFactory.fromBitmap(current.state.image.toBitmap()))
            }
            if (finger.opacity != prevFinger.opacity) {
                groundImage.polygon.fillColor = current.state.imageFillColor()
            }
            groundImage
        }

    override suspend fun removeGroundImage(entity: GroundImageEntityInterface<TomTomActualGroundImage>) {
        withContext(coroutine.coroutineContext) {
            entity.groundImage.polygon.remove()
        }
    }

    private fun GroundImageState.coordinates(): List<GeoPoint>? {
        val southWest = bounds.southWest ?: return null
        val northEast = bounds.northEast ?: return null
        // TomTom Polygon の塗りに必要な反時計回り (CCW) の開いたリング。
        return listOf(
            GeoPoint(southWest.latitude, southWest.longitude),
            GeoPoint(southWest.latitude, northEast.longitude),
            GeoPoint(northEast.latitude, northEast.longitude),
            GeoPoint(northEast.latitude, southWest.longitude),
        )
    }

    private fun GroundImageState.imageFillColor(): Int =
        Color.argb((opacity.coerceIn(0.0f, 1.0f) * 255).roundToInt(), 255, 255, 255)

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap

        val width = intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = intrinsicHeight.takeIf { it > 0 } ?: 1
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val originalBounds = Rect(bounds)
        setBounds(0, 0, width, height)
        draw(canvas)
        bounds = originalBounds
        return result
    }
}
