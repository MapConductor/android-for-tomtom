package com.mapconductor.tomtom.groundimage

import com.mapconductor.core.groundimage.GroundImageTileProvider

/**
 * TomTom の GroundImage 実体。
 *
 * GroundImage はローカルタイルサーバに登録した [GroundImageTileProvider] のタイルを、
 * 合成スタイルのラスタレイヤー（[rasterId]）として描画することで表示する。
 */
class TomTomGroundImageHandle(
    val routeId: String,
    val rasterId: String,
    val tileProvider: GroundImageTileProvider,
    val generation: Long,
)
