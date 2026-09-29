package com.mapconductor.tomtom

import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapDesignTypeInterface
import com.tomtom.sdk.map.display.style.StandardStyles
import android.net.Uri
import com.mapconductor.core.map.BlankMapStyle
import com.tomtom.sdk.map.display.style.StyleDescriptor

typealias TomTomMapDesignType = MapDesignTypeInterface<String>

/**
 * TomTom のマップデザイン（スタイル）。
 *
 * ## ★ Orbis のスタイル記述子は使わない
 *
 * `StandardStyles.TomTomOrbisMaps.*`（`tomtom://styles/standard/orbismap/browsing` 等）を
 * 渡すと `loadStyle` は **success を返すのに何も描画されない**（地色だけの地図になる）。
 * 実機（Pixel 5a / map-display 2.4.x）で確認したうえ、API 側も
 * `api.tomtom.com/maps/orbis/map-display/...` が 400 を返し、classic の
 * `api.tomtom.com/map/1/tile/basic/main/...` は 200 を返す。Orbis のベクタ地図は
 * 別建ての権限が要り、キーがそれを持たないと**エラーではなく無音の白紙**になる。
 *
 * `MapOptions.mapStyle` の既定は `null`（SDK 内部の既定スタイル）で、これは描画される。
 * その挙動に合わせて classic の `StandardStyles.TomTomMaps.*` を使う。Orbis が要る
 * 場合は、Orbis 権限付きのキーを用意したうえでここを差し替えること。
 *
 * `id` / `getValue()` は安定キー（保存・復元に使用）で、実際に TomTom へ読み込ませる
 * [StyleDescriptor] は [styleDescriptor] が保持する（ライト/ダークは各 [StandardStyles]
 * の descriptor に内包され `StyleMode` で切り替わる）。
 */
sealed class TomTomMapDesign(
    override val id: String,
    val styleDescriptor: StyleDescriptor,
    override val attributionRules: List<AttributionRule> = emptyList(),
) : TomTomMapDesignType {
    /** 既定（ブラウジング）スタイル。 */
    /** ベースマップ無し。背景色だけのスタイル（core の同梱アセット）。 */
    object None : TomTomMapDesign("none", StyleDescriptor(Uri.parse(BlankMapStyle.ASSET_URI)))

    object Standard : TomTomMapDesign("standard", StandardStyles.TomTomMaps.BROWSING)

    /** ナビゲーション向けスタイル。 */
    object Driving : TomTomMapDesign("driving", StandardStyles.TomTomMaps.DRIVING)

    /** 衛星写真スタイル。 */
    object Satellite : TomTomMapDesign("satellite", StandardStyles.TomTomMaps.SATELLITE)

    /** 任意の [StyleDescriptor]（独自スタイル URI 等）を使うカスタムデザイン。 */
    class Custom(
        id: String,
        styleDescriptor: StyleDescriptor,
        attributionRules: List<AttributionRule> = emptyList(),
    ) : TomTomMapDesign(id, styleDescriptor, attributionRules)

    override fun getValue(): String = id

    companion object {
        fun create(id: String): TomTomMapDesign =
            when (id) {
                None.id -> None
                Standard.id -> Standard
                Driving.id -> Driving
                Satellite.id -> Satellite
                else -> Standard
            }
    }
}
