package com.mapconductor.tomtom

import androidx.compose.ui.graphics.Color
import com.mapconductor.core.marker.BitmapIcon
import com.tomtom.sdk.map.display.image.Image
import com.tomtom.sdk.map.display.image.ImageFactory
import android.graphics.Bitmap

/**
 * BitmapIcon から TomTom の [Image] を生成する。
 *
 * 画像生成 API は com.tomtom.sdk.map.display.image.ImageFactory.fromBitmap(Bitmap)。
 * TomTom のマーカーはアンカーを MarkerOptions 側で扱わないため、必要に応じて事前に
 * 余白付きビットマップを生成してアンカーを調整すること。
 */
internal fun BitmapIcon.toTomTomImage(): Image = ImageFactory.fromBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, true))

/** Compose Color を TomTom が受け付ける 0xAARRGGBB の Int に変換する。 */
fun Color.toTomTomColorInt(): Int {
    val a = (alpha * 255).toInt() and 0xff
    val r = (red * 255).toInt() and 0xff
    val g = (green * 255).toInt() and 0xff
    val b = (blue * 255).toInt() and 0xff
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
