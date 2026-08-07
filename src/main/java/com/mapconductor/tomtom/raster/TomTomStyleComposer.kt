package com.mapconductor.tomtom.raster

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TomTom の browsing ベーススタイルへ「TomTom ラスタ地図（可視ベース）」と「自前マーカーラスタ」を
 * 注入した合成 style JSON を組み立て、`loadStyle` 可能なローカル `file://` URI を返すユーティリティ。
 *
 * ## 実機スパイクで確定した制約と、この構成の理由
 * TomTom Map Display SDK 1.26.7 には **実行時に source/layer を追加する公開 API が無い**ため、
 * マーカーラスタ層を足すにはスタイル全体を合成して `loadStyle` するしかない。さらに:
 *
 *  1. `loadStyle` は SDK オーバーレイ用の必須レイヤー（polyline-shape / polygon-shape / circle-shape /
 *     marker / route 等）がスタイルに存在することを要求する（無いと "Invalid layer mapping" で失敗）。
 *     → **フルの browsing スタイルをベースにする**必要がある。
 *  2. ただし browsing の **proprietary ベクタタイルは `file://` ロードでは描画されない**（SDK 内部の
 *     ベクタタイルプロバイダ依存）。→ 可視ベース地図として **TomTom のラスタ地図タイル**
 *     （`map/1/tile/basic/main/{z}/{x}/{y}.png`）を最下層に足す（ラスタは `file://` でも描画される）。
 *  3. その上に自前マーカーラスタ（ローカルタイルサーバ）を重ねる。
 *
 * ベーススタイル URL: `https://api.tomtom.com/style/1/style/25.2.*?key=...&map=gosdk/basic_street-light&...`
 * （実機 preloadedStyles と `DefaultStyleUriTransformer` より確認。`25.2.*` はワイルドカードで CDN が
 * 最新解決するため SDK パッチ更新に追随不要）。返却 JSON はキー埋め込み済み・MapLibre 互換 v8。
 *
 * NOTE(security): 生成 JSON には API キーが平文で含まれる。書き出しは必ずアプリ private cacheDir に
 * 限定し、URL/内容をログやリポジトリへ出力しないこと（[[no-hardcoded-secrets]]）。
 */
object TomTomStyleComposer {
    private const val TAG = "TomTomStyleComposer"

    /** browsing / light フルスタイルの実 URL テンプレート（%s = API キー）。SDK 必須レイヤーを含む。 */
    private const val BROWSING_STYLE_URL =
        "https://api.tomtom.com/style/1/style/25.2.*" +
            "?key=%s&map=gosdk/basic_street-light" +
            "&traffic_incidents=gosdk/incidents_light&traffic_flow=gosdk/flow_relative-light" +
            "&hillshade=2-test/hillshade_dem-light"

    /** 可視ベースに使う TomTom ラスタ地図タイル（basic/main）テンプレート（%s = API キー）。 */
    private const val BASE_RASTER_TILES = "https://api.tomtom.com/map/1/tile/basic/main/{z}/{x}/{y}.png?key=%s"

    private const val TILE_SIZE = 256

    /** 合成スタイルに載せる自前ラスタレイヤー1枚分の指定。 */
    data class RasterSpec(
        val id: String,
        val tilesUrl: String,
        val opacity: Double,
        val tileSize: Int = TILE_SIZE,
        // ソースの minzoom/maxzoom。特に maxzoom を設定するとそれ以上のズームでは
        // maxzoom タイルをオーバーズーム表示するため、実タイルが無い高ズームでの
        // 404（＝歯抜け）を防げる。null なら未指定（全ズームで実タイルを要求）。
        val minZoom: Int? = null,
        val maxZoom: Int? = null,
    )

    /**
     * 合成スタイル（フル browsing + ラスタベース + 任意のラスタレイヤー群）を生成し、その `file://` URI を返す。
     * マーカータイル・GroundImage など複数のラスタレイヤーを [layers] の順（＝下→上）で重ねられる。
     *
     * @param apiKey TomTom API キー
     * @param cacheDir 書き出し先（アプリ private `context.cacheDir`）
     * @param layers 追加するラスタレイヤー群（tiles URL テンプレートは `{z}/{x}/{y}` を含むローカルサーバ URL）
     */
    suspend fun composeRasterStyle(
        apiKey: String,
        cacheDir: File,
        layers: List<RasterSpec>,
        outFile: File = File(cacheDir, "tomtom_composed_style.json"),
    ): Uri? =
        withContext(Dispatchers.IO) {
            val baseJson =
                fetch(String.format(BROWSING_STYLE_URL, apiKey)) ?: run {
                    Log.e(TAG, "Failed to fetch base style JSON")
                    return@withContext null
                }

            val root =
                try {
                    JSONObject(baseJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse base style JSON", e)
                    return@withContext null
                }

            val sources = root.optJSONObject("sources") ?: JSONObject().also { root.put("sources", it) }
            sources.put("mc-base-raster", rasterSource(String.format(BASE_RASTER_TILES, apiKey), TILE_SIZE, null, null))

            val styleLayers = root.optJSONArray("layers") ?: JSONArray().also { root.put("layers", it) }
            // 可視ベース地図（ラスタ）は最下層付近（"background" の直後）に挿入し、
            // ラベル/オーバーレイ/マーカーがその上に来るようにする。
            val baseLayer = rasterLayer("mc-base-raster-layer", "mc-base-raster", 1.0)
            val insertAt =
                if (styleLayers.length() > 0 &&
                    styleLayers.optJSONObject(0)?.optString("type") == "background"
                ) {
                    1
                } else {
                    0
                }
            insertLayerAt(styleLayers, insertAt, baseLayer)

            // 自前ラスタレイヤー（マーカータイル / GroundImage など）を最前面（末尾）へ順に重ねる。
            layers.forEachIndexed { index, spec ->
                val sourceId = "mc-raster-src-$index"
                sources.put(sourceId, rasterSource(spec.tilesUrl, spec.tileSize, spec.minZoom, spec.maxZoom))
                styleLayers.put(rasterLayer("mc-raster-layer-$index", sourceId, spec.opacity))
            }

            try {
                outFile.writeText(root.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write composed style", e)
                return@withContext null
            }
            Log.i(
                TAG,
                "Composed style written (${outFile.length()} bytes, ${styleLayers.length()} layers, ${layers.size} raster)",
            )
            Uri.fromFile(outFile)
        }

    private fun rasterSource(
        tilesUrl: String,
        tileSize: Int,
        minZoom: Int?,
        maxZoom: Int?,
    ): JSONObject =
        JSONObject().apply {
            put("type", "raster")
            put("tiles", JSONArray().put(tilesUrl))
            put("tileSize", tileSize)
            minZoom?.let { put("minzoom", it) }
            // maxzoom を設定すると、それ以上のズームでは maxzoom タイルをスケールして
            // 表示する（オーバーズーム）ため、実タイルが無い高ズームでの歯抜けを防ぐ。
            maxZoom?.let { put("maxzoom", it) }
        }

    private fun rasterLayer(
        id: String,
        source: String,
        opacity: Double,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("type", "raster")
            put("source", source)
            put("paint", JSONObject().put("raster-opacity", opacity))
        }

    /** JSONArray への位置指定挿入（org.json は insert が無いため末尾に足してからシフト）。 */
    private fun insertLayerAt(
        layers: JSONArray,
        index: Int,
        layer: JSONObject,
    ) {
        layers.put(layer)
        for (i in layers.length() - 1 downTo index + 1) {
            layers.put(i, layers.get(i - 1))
        }
        layers.put(index, layer)
    }

    private fun fetch(url: String): String? =
        try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MapConductor")
                try {
                    if (responseCode in 200..299) {
                        inputStream.bufferedReader().use { it.readText() }
                    } else {
                        Log.e(TAG, "Base style fetch HTTP $responseCode")
                        null
                    }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Base style fetch error", e)
            null
        }
}
