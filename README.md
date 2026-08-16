# TomTom SDK for MapConductor Android

## Description

TomTom Orbis Maps provider for the MapConductor unified mapping API, built on
[TomTom Orbis Maps Display SDK](https://developer.tomtom.com/android/maps/documentation).

MapConductor provides a unified API for Android Jetpack Compose.
You can use TomTom with Compose, but you can also switch to other Maps SDKs (such as
MapLibre, Google Maps, and so on), anytime.
Even using the wrapper API, you can still access the native TomTom map if you want.

`TomTomMapView` supports the same MapConductor content types as the iOS `ios-for-tomtom`
provider: Marker, Polyline, Polygon, Circle, GroundImage, RasterLayer and InfoBubble.
InfoBubble is drawn through the shared `MapViewBase`, so this module carries no
InfoBubble-specific code.

Dependency: `com.tomtom.sdk.maps:map-display-standard` (the version is managed by
`tomtomMaps` in `gradle/libs.versions.toml`).

## Setup

https://mapconductor.com/setup/android/tomtom/

1. Get a TomTom Orbis Maps API key from the TomTom Developer Portal.
2. Add the key to your app's `AndroidManifest.xml`.

```xml
<meta-data
    android:name="TOMTOM_API_KEY"
    android:value="YOUR_TOMTOM_API_KEY" />
```

3. Add the TomTom Maven repository to `settings.gradle.kts`.

```kotlin
maven { url = uri("https://repositories.tomtom.com/artifactory/maven") }
```

## Usage

```kotlin
val mapState = rememberTomTomMapViewState(
    mapDesign = TomTomMapDesign.Standard,
    cameraPosition = MapCameraPosition(position = GeoPoint(52.3676, 4.9041), zoom = 11.0),
)

TomTomMapView(state = mapState, modifier = Modifier.fillMaxSize()) {
    Marker(
        MarkerState(
            position = GeoPoint(52.3676, 4.9041),
            icon = DefaultMarkerIcon().copy(label = "Amsterdam"),
        ),
    )
}
```

See `sample-app/` for a runnable example.

## Available designs

`TomTomMapDesign` exposes the TomTom Orbis styles: `Standard`, `Driving`, `Satellite`.

## Supported overlays

Marker (including clustering and tile-rendered large marker sets), Polyline, Polygon
(holes supported), Circle, GroundImage, RasterLayer and InfoBubble — the same unified API
as the other providers.

## Components

### TomTomMapView [[docs]](https://mapconductor.com/mapview/)

```kotlin
@Composable
fun MapExample() {
    val initCameraPosition = MapCameraPosition(
        position = GeoPoint(
            latitude = 34.091,
            longitude = -117.886,
        ),
        zoom = 9.0,
        tilt = 60.0,
        bearing = 30.0,
    )

    val mapViewState = rememberTomTomMapViewState(
        cameraPosition = initCameraPosition,
    )

    TomTomMapView(mapViewState)
}
```

------------------------------------------------------------------------

### Marker [[docs]](https://mapconductor.com/markers/)

```kotlin
@Composable
fun MarkerExample() {
    val markerState = remember { MarkerState(
        position = GeoPoint(...),
        icon = DefaultMarkerIcon().copy(
            label = "TomTom",
        ),
        onClick = {
            it.animate(MarkerAnimation.Bounce)
        },
    ) }

    TomTomMapView(...) {
        Marker(markerState)
    }
}
```

------------------------------------------------------------------------

### InfoBubble [[docs]](https://mapconductor.com/info-bubble/)

```kotlin
@Composable
fun InfoBubbleExample() {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val markerState = remember { MarkerState(
        ...,
        onClick = {
            selectedMarker = it
        },
    ) }

    TomTomMapView(...) {
        Marker(markerState)
        selectedMarker?.let {
            InfoBubble(
                marker = it,
            ) {
                Text("Hello, world!")
            }
        }
    }
}
```

------------------------------------------------------------------------

### Circle [[docs]](https://mapconductor.com/circle/)

```kotlin
@Composable
fun CircleExample() {

    val circleState = remember { CircleState(
        center = GeoPoint(...),
        radiusMeters = 50.0,
        fillColor = Color.Blue.copy(alpha = 0.5f),
        onClick = {
            it.state.fillColor = Color.Red.copy(alpha = 0.5f)
        }
    ) }

    TomTomMapView(...) {
        Circle(circleState)
    }
}
```

------------------------------------------------------------------------

### Polyline [[docs]](https://mapconductor.com/polyline/)

```kotlin
@Composable
fun PolylineExample() {

    val polylineState = remember { PolylineState(
            points = airports,
            strokeColor = Color.Blue.copy(alpha = 0.5f),
            strokeWidth = 4.dp,
            geodesic = true,
        ) }

    TomTomMapView(...) {
        Polyline(polylineState)
    }
}
```

------------------------------------------------------------------------

### Polygon [[docs]](https://mapconductor.com/polygon/)

```kotlin
@Composable
fun PolygonExample() {

    val polygonState = remember { PolygonState(
        points = goryokaku,
        strokeColor = Color.Blue.copy(alpha = 0.5f),
        fillColor =  Color.Red.copy(alpha = 0.7f),
    ) }

    TomTomMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### Polygon Hole

```kotlin
@Composable
fun PolygonHoleExample() {

    val polygonState =
        remember {
            PolygonState(
                points = listOf(...),
                holes = listOf(
                            listOf(...),
                            listOf(...),
                        ),
                fillColor = Color(0xCC787880),
                strokeColor = Color.Red,
                strokeWidth = 2.dp,
            )
        }

    TomTomMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### GroundImage [[docs]](https://mapconductor.com/ground-image/)

```kotlin
@Composable
fun GroundImageExample() {
    val groundImageState = remember { GroundImageState(
        bounds = GeoRectBounds(
            southWest = GeoPoint.fromLatLong(...),
            northEast = GeoPoint.fromLatLong(...),
        ),
        image = image,
        opacity = 0.5f,
    ) }

    TomTomMapView(state = mapViewState) {
        GroundImage(groundImageState)
    }
}
```

## Files

| File | Role |
| --- | --- |
| `TomTomMapView.kt` | Compose entry point / controller construction |
| `TomTomMapViewController.kt` | Central controller for camera, markers and design |
| `TomTomMapViewStateImpl.kt` | `rememberTomTomMapViewState` and state retention |
| `TomTomMapViewHolder.kt` | `MapView` / `TomTomMap` wrapper, coordinate conversion |
| `TomTomMapDesign.kt` | Style (map design) definitions |
| `MapCameraPosition.kt` | Camera position conversions |
| `GeoPoint.kt` / `GeoRectBounds.kt` | Coordinate type conversions |
| `marker/` | Native marker rendering and events |
| `polyline/` / `polygon/` / `circle/` | Vector overlay rendering (geodesics use the shared core interpolation) |
| `groundimage/` | Ground image as an image-backed Polygon |
| `raster/TomTomStyleComposer.kt` | Builds a composed style JSON with injected raster sources |
| `raster/TomTomRasterLayerSink.kt` | Applies raster layer state to the composed style |
| `zoom/ZoomAltitudeConverter.kt` | Zoom ↔ altitude conversion |

## Implementation notes / known limitations

- **Compose embedding**: `MapOptions(renderToTexture = true)` is set. The default SurfaceView
  rendering conflicts with the measurement pass of Compose's `SubcomposeLayout`, which shifts
  the drawing position.
- **Sample theme**: TomTom's UI overlays (logo, compass, and so on) log a warning recommending
  an AppCompat-family theme (rendering itself still works). For production, prefer
  `Theme.AppCompat` / `Theme.MaterialComponents`.
- **`fitBounds`**: delegates to `CameraOptionsFactory.lookAt(bounds, zoom = null, padding)`.
  Passing `zoom = null` leaves the zoom to TomTom's own calculation; `padding` (px) is passed
  through as is.
- **Polygon holes / geodesics**: geodesic interpolation of vertices, antimeridian splitting and
  multi-hole unioning reuse the shared core utilities (`WGS84Geodesic` /
  `buildUnwrappedPolygonRings` / `unionHoles`) so the resulting shape matches the other
  providers. TomTom connects coordinates with straight lines, so geodesics are approximated by
  the interpolated coordinate list.
- **Ground images**: drawn as an image-backed Polygon with `PolygonOptions.isImageOverlay`
  enabled (the image is stretched over the polygon's bounding rectangle). Bounds, image and
  color changes update the existing native Polygon in place, as with markers — no
  remove-then-recreate.
- **Raster layers**: the TomTom Map Display SDK has no public API for adding sources/layers at
  runtime, so a style JSON with raster sources injected into the base style is composed and
  loaded as a local `file://` URI (`raster/TomTomStyleComposer.kt`).
- **Marker updates / movement**: TomTom's `Marker` has mutable `coordinate` / `isVisible` and a
  `setPinImage()`, so position, visibility and icon updates mutate the existing instance rather
  than removing and recreating it. This matters: removing and recreating every frame during a
  drag blocks the main thread and causes an ANR.
- **Drag (custom implementation)**: TomTom has no native marker drag, so it is implemented by
  handling `MotionEvent` on the `MapView` directly (the same approach as the ArcGIS module).
  A touch on a draggable marker owns the gesture and suppresses map panning; movement beyond the
  slop starts the drag and the marker follows the finger; releasing commits it. Releasing without
  moving is treated as a click (`onClick`). The finger's screen coordinate is converted with
  `TomTomMap.coordinateForPoint`, and the marker is repositioned by updating `Marker.coordinate`.

## License

Apache License 2.0
