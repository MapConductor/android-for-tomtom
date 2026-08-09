package com.mapconductor.tomtom

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface
import com.tomtom.sdk.common.Result
import com.tomtom.sdk.map.display.TomTomMap
import com.tomtom.sdk.map.display.ui.MapView
import android.graphics.Point

class TomTomMapViewHolder(
    override val mapView: MapView,
    override val map: TomTomMap,
) : MapViewHolderInterface<MapView, TomTomMap> {
    /** 地理座標 → 画面上のピクセル座標（[TomTomMap.pointForCoordinate]）。 */
    override fun toScreenOffset(position: GeoPointInterface): Offset? {
        val point = map.pointForCoordinate(GeoPoint.from(position).toTomTomGeoPoint())
        return Offset(point.x.toFloat(), point.y.toFloat())
    }

    /** 画面上のピクセル座標 → 地理座標（[TomTomMap.coordinateForPoint]）。 */
    override fun fromScreenOffsetSync(offset: Offset): GeoPoint? {
        val result = map.coordinateForPoint(Point(offset.x.toInt(), offset.y.toInt()))
        return (result as? Result.Success)?.value()?.toGeoPoint()
    }
}
