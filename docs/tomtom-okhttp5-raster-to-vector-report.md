# TomTom Orbis Maps SDK 2.4.2 — raster→vector style switch renders nothing when OkHttp 5.x is on the classpath

## Summary

With `com.squareup.okhttp3:okhttp:5.x` resolved on the application classpath, loading a vector
style after the satellite (raster) style leaves the map as a flat background colour. Nothing is
drawn — no basemap, no labels, no markers, no overlays.

The failure is silent:

- `TomTomMap.loadStyle(...)` invokes `StyleLoadingCallback.onSuccess()`.
- `TomTomMap.layers.size` reports the full layer count of the loaded style (137 for BROWSING).
- The camera is unchanged and correct.
- No exception, no HTTP error, and nothing from `mapdisplay.clientlib.*` in logcat.

Panning or zooming does not recover it. Loading the same vector style a second time does not
recover it. Recreating the `MapView` does.

Pinning OkHttp back to `4.12.0` — changing nothing else — fixes it completely.

## Affected

- `com.tomtom.sdk.maps:map-display-standard:2.4.2` (flavour `complete`)
- `com.squareup.okhttp3:okhttp:5.3.2` (also reproduced via 5.x pulled transitively)
- Device: Pixel 5a, Android 16, arm64-v8a. GPU: Adreno.
- Also seen on a React Native (Hermes) app on the same device.

## Steps to reproduce

1. A single-Activity app with one `MapView`, no other map SDK.
2. Add to the application module:

   ```kotlin
   configurations.configureEach {
       resolutionStrategy {
           force("com.squareup.okhttp3:okhttp:5.3.2")
       }
   }
   ```

3. Load `StandardStyles.TomTomMaps.BROWSING`. → renders correctly.
4. `loadStyle(StandardStyles.TomTomMaps.SATELLITE)`. → renders correctly.
5. `loadStyle(StandardStyles.TomTomMaps.BROWSING)`. → **blank; `onSuccess()` is still called.**

Removing step 2 (leaving OkHttp at the 4.12.0 the SDK resolves by itself) makes step 5 render
correctly.

## What is and is not affected

| transition | OkHttp 4.12.0 | OkHttp 5.3.2 |
|---|---|---|
| initial vector load | renders | renders |
| vector → vector (BROWSING ⇄ DRIVING) | renders | renders |
| vector → raster (→ SATELLITE) | renders | renders |
| **raster → vector (SATELLITE → BROWSING)** | renders | **blank** |

Only the raster→vector direction breaks. `StandardStyles.TomTomMaps.SATELLITE` is the only one of
the three standard descriptors that carries a layer-mapping URI
(`tomtom://mapping/standard/tomtommap/satellite`); BROWSING and DRIVING have
`layerMappingUri == null`. That may be where the raster→vector path differs.

## How this is hit in practice

Applications rarely choose OkHttp 5 deliberately. It arrives transitively — in our case from
`com.esri:arcgis-maps-kotlin:300.1.0`, which requires `okhttp 5.3.2`. Gradle's
highest-version-wins resolution then lifts the SDK's own `4.12.0` to `5.3.2` and the map breaks,
with nothing in the logs to point at the cause.

Verified on-device in both directions, in a TomTom-only sample app:

| ArcGIS on the classpath | resolved OkHttp | satellite → standard |
|---|---|---|
| no  | 4.12.0 (default) | renders |
| no  | 5.3.2 (forced)   | blank |
| yes | 5.3.2 (default)  | blank |
| yes | 4.12.0 (pinned)  | renders |

## Ruled out

Each of these was tested on-device and did **not** change the outcome:

- Compose (`AndroidView`) vs. a plain View hierarchy
- `MapOptions.renderToTexture` true / false
- markers, polygons and polylines present or absent
- re-applying the camera after the style load
- `StyleController.setStyleMode(StyleMode.MAIN)`
- loading the vector style twice (`onSuccess()` twice, still blank)
- supplying a layer mapping for the vector style — there is none:
  `loadStyle failed: Unrecognized uri tomtom://mapping/standard/tomtommap/browsing`
- packaging other map SDKs' native libraries (MapLibre, Mapbox, ArcGIS) without initialising them
  — the ArcGIS native libraries can be excluded from the APK entirely and the bug still occurs,
  which is what pointed at the dependency graph rather than at native symbol collisions

## Ask

Either support OkHttp 5.x, or declare the incompatibility so the failure is not silent — for
example by failing `loadStyle` with a real `LoadingStyleFailure` instead of reporting success.
