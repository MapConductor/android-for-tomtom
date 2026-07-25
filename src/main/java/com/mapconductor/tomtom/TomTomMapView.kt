package com.mapconductor.tomtom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mapconductor.compose.map.MapViewBase
import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapLoadedHandler
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MutableMapServiceRegistry
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.tileserver.TileServerRegistry
import com.mapconductor.tomtom.circle.TomTomCircleController
import com.mapconductor.tomtom.circle.TomTomCircleOverlayRenderer
import com.mapconductor.tomtom.groundimage.TomTomGroundImageController
import com.mapconductor.tomtom.groundimage.TomTomGroundImageOverlayRenderer
import com.mapconductor.tomtom.marker.TomTomMarkerController
import com.mapconductor.tomtom.polygon.TomTomPolygonController
import com.mapconductor.tomtom.polygon.TomTomPolygonOverlayRenderer
import com.mapconductor.tomtom.polyline.TomTomPolylineController
import com.mapconductor.tomtom.polyline.TomTomPolylineOverlayRenderer
import com.tomtom.sdk.map.display.MapOptions
import com.tomtom.sdk.map.display.ui.MapView
import android.view.ViewGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TomTomMapView(
    state: TomTomMapViewState,
    modifier: Modifier = Modifier,
    markerTiling: MarkerTilingOptions? = null,
    cameraRestriction: CameraRestriction? = null,
    sdkInitialize: (suspend (android.content.Context) -> Boolean)? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    onMapLongClick: OnMapEventHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    content: (@Composable TomTomMapViewScope.() -> Unit)? = null,
) {
    val scope = remember { TomTomMapViewScope() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapLifecycle = remember { TomTomMapLifecycle() }
    val registry = remember { scope.buildRegistry() }
    val serviceRegistry = remember { MutableMapServiceRegistry() }
    val cameraState = remember { mutableStateOf<MapCameraPositionInterface?>(state.cameraPosition) }
    val initialCameraPosition = remember(state) { MapCameraPosition.from(state.cameraPosition) }

    MapViewBase(
        state = state,
        cameraState = cameraState,
        modifier = modifier,
        viewProvider = {
            val apiKey = tomtomApiKey(context)
            val mapOptions =
                MapOptions(
                    mapKey = apiKey,
                    cameraOptions = initialCameraPosition.toCameraOptions(),
                    // Compose の SubcomposeLayout 内に埋め込むため TextureView 描画にする。
                    // SurfaceView（既定）だとサブコンポーズ計測と競合し描画位置がずれる。
                    renderToTexture = true,
                )
            MapView(context, mapOptions).apply {
                onCreate(null)
                mapLifecycle.attach(this, lifecycle)
            }
        },
        serviceRegistry = serviceRegistry,
        holderProvider = { mapView ->
            suspendCancellableCoroutine { cont ->
                mapView.getMapAsync { map ->
                    val holder = TomTomMapViewHolder(mapView, map)
                    cont.resume(holder) { _, _, _ -> }
                }
            }
        },
        controllerProvider = { holder ->
            createTomTomMapViewController(
                holder = holder,
                markerTiling = markerTiling ?: MarkerTilingOptions.Default,
                serviceRegistry = serviceRegistry,
            ).also { mapController ->
                TomTomMapViewControllerStore.set(state.id, mapController)
                state.setController(mapController)
                mapController.setCameraMoveStartListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMoveStart?.invoke(it)
                }
                mapController.setCameraMoveListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMove?.invoke(it)
                }
                mapController.setCameraMoveEndListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMoveEnd?.invoke(it)
                }
                mapController.setMapClickListener(onMapClick)
                mapController.setMapLongClickListener(onMapLongClick)
                mapController.setMapDesignTypeChangeListener(state::onMapDesignTypeChange)
                cameraRestriction?.let { mapController.setCameraRestriction(it) }
                // 大量マーカーをラスタタイル化するため、マーカータイル用ラスタレイヤーの
                // 実体化（スタイル合成 + loadStyle）を配線する。
                mapController.setupMarkerTileRaster(
                    apiKey = tomtomApiKey(context),
                    cacheDir = context.cacheDir,
                )
                holder.mapView.post {
                    // Style loading can reset the camera, so reapply it once the view is attached.
                    mapController.moveCamera(initialCameraPosition)
                    mapController.onMarkerRenderingReady()
                }
            }
        },
        scope = scope,
        registry = registry,
        onMapLoaded = onMapLoaded,
        customDisposableEffect = { _, _ ->
            DisposableEffect(lifecycle, mapLifecycle) {
                val stateId = state.id
                val observer =
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            mapLifecycle.start()
                        }

                        override fun onResume(owner: LifecycleOwner) {
                            mapLifecycle.resume()
                        }

                        override fun onPause(owner: LifecycleOwner) {
                            mapLifecycle.pause()
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            mapLifecycle.stop()
                        }

                        override fun onDestroy(owner: LifecycleOwner) {
                            val activity = context.findActivity()
                            if (activity?.isChangingConfigurations == true) {
                                mapLifecycle.mapView?.let {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                }
                            } else {
                                TomTomMapViewControllerStore.get(stateId)?.destroy()
                                TomTomMapViewControllerStore.remove(stateId)
                            }
                            mapLifecycle.destroy()
                        }
                    }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    TomTomMapViewControllerStore.get(stateId)?.destroy()
                    TomTomMapViewControllerStore.remove(stateId)
                    mapLifecycle.destroy()
                }
            }
        },
        sdkInitialize = {
            sdkInitialize?.invoke(context) ?: true
        },
        content = content,
    )
}

private class TomTomMapLifecycle {
    var mapView: MapView? = null
        private set

    private var started = false
    private var resumed = false
    private var destroyed = false

    fun attach(
        mapView: MapView,
        lifecycle: Lifecycle,
    ) {
        this.mapView = mapView
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    fun start() {
        val view = mapView ?: return
        if (!destroyed && !started) {
            view.onStart()
            started = true
        }
    }

    fun resume() {
        val view = mapView ?: return
        if (!destroyed && !resumed) {
            view.onResume()
            resumed = true
        }
    }

    fun pause() {
        val view = mapView ?: return
        if (!destroyed && resumed) {
            view.onPause()
            resumed = false
        }
    }

    fun stop() {
        val view = mapView ?: return
        if (!destroyed && started) {
            view.onStop()
            started = false
        }
    }

    fun destroy() {
        val view = mapView ?: return
        if (!destroyed) {
            pause()
            stop()
            view.onDestroy()
            destroyed = true
            mapView = null
        }
    }
}

fun createTomTomMapViewController(
    holder: TomTomMapViewHolder,
    markerTiling: MarkerTilingOptions = MarkerTilingOptions.Default,
    serviceRegistry: MutableMapServiceRegistry? = null,
): TomTomMapViewController {
    // GroundImage はローカルタイルサーバ + 合成スタイルのラスタレイヤーで描画する。
    // 動的な marker タイルと同様、タイルは no-store で配信する（合成スタイル再ロードで確実に refetch）。
    val groundImageRenderer =
        TomTomGroundImageOverlayRenderer(
            holder = holder,
            tileServer = TileServerRegistry.get(forceNoStoreCache = true),
        )
    val groundImageController = TomTomGroundImageController(renderer = groundImageRenderer)

    val mapController =
        TomTomMapViewController(
            holder = holder,
            markerController = TomTomMarkerController.create(holder, markerTiling),
            polylineController = TomTomPolylineController(renderer = TomTomPolylineOverlayRenderer(holder)),
            polygonController = TomTomPolygonController(renderer = TomTomPolygonOverlayRenderer(holder)),
            circleController = TomTomCircleController(renderer = TomTomCircleOverlayRenderer(holder)),
            groundImageController = groundImageController,
        )
    // GroundImage のラスタ実体化先をマップコントローラ（合成スタイル管理）に接続する。
    groundImageRenderer.rasterSink = mapController

    serviceRegistry?.let { registry ->
        registry.clear()
        registry.put(
            MarkerRenderingSupportKey,
            object : MarkerRenderingSupport<TomTomActualMarker> {
                override val mapLoadedState = mapController.mapLoadedState

                override fun createMarkerRenderer(
                    strategy: MarkerRenderingStrategyInterface<TomTomActualMarker>,
                ): MarkerOverlayRendererInterface<TomTomActualMarker> = mapController.createMarkerRenderer()

                override fun createMarkerEventController(
                    controller: StrategyMarkerController<TomTomActualMarker>,
                    renderer: MarkerOverlayRendererInterface<TomTomActualMarker>,
                ): MarkerEventControllerInterface<TomTomActualMarker> =
                    mapController.createMarkerEventController(controller)

                override fun registerMarkerEventController(
                    controller: MarkerEventControllerInterface<TomTomActualMarker>,
                ) {
                    mapController.registerMarkerEventController(controller)
                }

                override fun onMarkerRenderingReady() {
                    mapController.onMarkerRenderingReady()
                }
            },
        )
    }
    return mapController
}
