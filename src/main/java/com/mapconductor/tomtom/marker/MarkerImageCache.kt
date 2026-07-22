package com.mapconductor.tomtom.marker

import com.tomtom.sdk.map.display.image.Image
import com.tomtom.sdk.map.display.image.ImageFactory
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Bitmap

/**
 * 同一ビットマップから TomTom の [Image] を都度生成しないためのキャッシュ。
 * ビットマップの hashCode をキーに使う（GoogleMap の BitmapDescriptorCache 相当）。
 */
object MarkerImageCache {
    private val cache = ConcurrentHashMap<Int, Image>()

    fun fromBitmap(bitmap: Bitmap): Image {
        val key = bitmap.hashCode()
        return cache.getOrPut(key) {
            ImageFactory.fromBitmap(bitmap)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    fun getCacheSize(): Int = cache.size
}
