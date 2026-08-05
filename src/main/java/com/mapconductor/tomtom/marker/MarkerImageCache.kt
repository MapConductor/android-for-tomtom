package com.mapconductor.tomtom.marker

import com.tomtom.sdk.map.display.image.Image
import com.tomtom.sdk.map.display.image.ImageFactory
import android.graphics.Bitmap
import android.util.LruCache

/**
 * 同一ビットマップから TomTom の [Image] を都度生成しないためのキャッシュ。
 * ビットマップの hashCode をキーに使う（GoogleMap の BitmapDescriptorCache 相当）。
 */
object MarkerImageCache {
    // Bounded LRU (count-based, max 512 entries) rather than an unbounded map:
    // this is a process-global singleton whose clearCache() is never called, so an
    // unbounded map would grow forever with icon churn. The core BitmapIconCache
    // regenerates bitmaps on LRU eviction, minting new identity-hash keys, so the
    // key space is effectively unbounded. LruCache is synchronized internally.
    private val cache = object : LruCache<Int, Image>(512) {
        override fun sizeOf(key: Int, value: Image): Int = 1
    }

    fun fromBitmap(bitmap: Bitmap): Image {
        val key = bitmap.hashCode()
        return cache.get(key) ?: ImageFactory.fromBitmap(bitmap).also { cache.put(key, it) }
    }

    fun clearCache() {
        cache.evictAll()
    }

    fun getCacheSize(): Int = cache.size()
}
