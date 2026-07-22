package com.mapconductor.tomtom

import com.mapconductor.core.map.StaticHolder
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

internal object TomTomMapViewControllerStore : StaticHolder<TomTomMapViewController>()

internal fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
