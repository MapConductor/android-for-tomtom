package com.mapconductor.tomtom.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.circle.Circle
import com.mapconductor.compose.info.InfoBubble
import com.mapconductor.compose.marker.Marker
import com.mapconductor.compose.polygon.Polygon
import com.mapconductor.compose.polyline.Polyline
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerAnimation
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.tomtom.TomTomMapView
import com.mapconductor.tomtom.rememberTomTomMapViewState

// TomTom Orbis Maps の API キーは AndroidManifest の <meta-data android:name="TOMTOM_API_KEY" /> に設定する。

private val AMSTERDAM = GeoPoint(52.3676, 4.9041)
private val ROUTE =
    listOf(
        GeoPoint(52.40, 4.85),
        GeoPoint(52.38, 4.92),
        GeoPoint(52.35, 4.88),
        GeoPoint(52.33, 4.95),
    )
private val AREA =
    listOf(
        GeoPoint(52.36, 4.80),
        GeoPoint(52.40, 4.82),
        GeoPoint(52.39, 4.87),
        GeoPoint(52.35, 4.86),
    )

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MarkerDemo()
            }
        }
    }
}

@Composable
private fun MarkerDemo(modifier: Modifier = Modifier) {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val mapViewState =
        rememberTomTomMapViewState(
            cameraPosition = MapCameraPosition(position = AMSTERDAM, zoom = 11.0),
        )

    val markerState =
        remember {
            MarkerState(
                position = AMSTERDAM,
                icon = DefaultMarkerIcon().copy(label = "Amsterdam"),
                // 長押しでドラッグ（TomTom はネイティブ非対応のため自前実装）。
                draggable = true,
                onClick = {
                    it.animate(MarkerAnimation.Bounce)
                    selectedMarker = it
                },
                onDragEnd = { selectedMarker = null },
            )
        }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            TomTomMapView(state = mapViewState, modifier = modifier.fillMaxSize()) {
                Marker(markerState)

                Circle(
                    remember {
                        CircleState(
                            center = AMSTERDAM,
                            radiusMeters = 1500.0,
                            fillColor = Color.Blue.copy(alpha = 0.2f),
                            strokeColor = Color.Blue,
                            strokeWidth = 2.dp,
                        )
                    },
                )
                Polyline(
                    remember {
                        PolylineState(points = ROUTE, strokeColor = Color.Red, strokeWidth = 4.dp)
                    },
                )
                Polygon(
                    remember {
                        PolygonState(
                            points = AREA,
                            fillColor = Color.Green.copy(alpha = 0.3f),
                            strokeColor = Color(0xFF008000),
                            strokeWidth = 2.dp,
                        )
                    },
                )

                // InfoBubble はスクリーン座標変換（TomTomMapViewHolder.toScreenOffset →
                // TomTomMap.pointForCoordinate）で位置決めされる。
                selectedMarker?.let {
                    InfoBubble(marker = it) {
                        Text("Hello from Amsterdam!")
                    }
                }
            }
        }
    }
}
