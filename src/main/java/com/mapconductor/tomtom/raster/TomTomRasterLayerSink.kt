package com.mapconductor.tomtom.raster

/**
 * 自前ラスタレイヤー（マーカータイル・GroundImage 等）の追加/更新/削除要求を受け取る窓口。
 *
 * TomTom には実行時の addLayer/addSource が無いため、実体化は
 * 「フル browsing スタイル + ラスタベース + 各ラスタレイヤーを合成して loadStyle」で行う
 * （[TomTomStyleComposer] 参照）。実装は [com.mapconductor.tomtom.TomTomMapViewController] が持ち、
 * 複数レイヤーをまとめて 1 つの合成スタイルへ載せる。
 */
interface TomTomRasterLayerSink {
    /**
     * id をキーにラスタレイヤーを追加/更新し、合成スタイルを再ロードする。
     *
     * [maxZoom] を渡すと、それ以上のズームで maxZoom タイルをオーバーズーム表示するため、
     * 実タイルが無い高ズームでの歯抜けを防げる（GSI など maxzoom があるソース向け）。
     */
    suspend fun upsertRasterLayer(
        id: String,
        tilesUrl: String,
        opacity: Double,
        tileSize: Int = 256,
        minZoom: Int? = null,
        maxZoom: Int? = null,
    )

    /** id のラスタレイヤーを取り除き、合成スタイルを再ロードする（0 枚になればデザインへ復帰）。 */
    suspend fun removeRasterLayer(id: String)
}
