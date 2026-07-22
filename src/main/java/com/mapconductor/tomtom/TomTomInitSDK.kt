package com.mapconductor.tomtom

import android.content.Context
import android.content.pm.PackageManager

/**
 * TomTom Orbis Maps の API キー（mapKey）を AndroidManifest の meta-data から取得する。
 *
 * ```xml
 * <meta-data android:name="TOMTOM_API_KEY" android:value="your-api-key" />
 * ```
 *
 * TomTom SDK は Mapbox のようなグローバル初期化ではなく、MapOptions(mapKey = ...) に
 * キーを渡す方式。ここではキーの取得のみ行い、実際の適用は TomTomMapView 側で行う。
 */
fun tomtomApiKey(context: Context): String {
    val apiKey =
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString("TOMTOM_API_KEY")
    if (apiKey.isNullOrEmpty()) {
        throw Exception(
            "<meta-data android:name=\"TOMTOM_API_KEY\" /> is required",
        )
    }
    return apiKey
}
