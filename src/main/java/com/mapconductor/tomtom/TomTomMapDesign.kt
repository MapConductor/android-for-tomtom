package com.mapconductor.tomtom

import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapDesignTypeInterface
import com.tomtom.sdk.map.display.style.StandardStyles
import com.tomtom.sdk.map.display.style.StyleDescriptor

typealias TomTomMapDesignType = MapDesignTypeInterface<String>

/**
 * TomTom Orbis Maps のマップデザイン（スタイル）。
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
    object Standard : TomTomMapDesign("standard", StandardStyles.TomTomOrbisMaps.BROWSING)

    /** ナビゲーション向けスタイル。 */
    object Driving : TomTomMapDesign("driving", StandardStyles.TomTomOrbisMaps.DRIVING)

    /** 衛星写真スタイル。 */
    object Satellite : TomTomMapDesign("satellite", StandardStyles.TomTomOrbisMaps.SATELLITE)

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
                Standard.id -> Standard
                Driving.id -> Driving
                Satellite.id -> Satellite
                else -> Standard
            }
    }
}
