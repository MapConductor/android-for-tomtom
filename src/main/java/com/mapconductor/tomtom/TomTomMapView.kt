package com.mapconductor.tomtom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
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
import com.mapconductor.tomtom.circle.TomTomCircleController
import com.mapconductor.tomtom.circle.TomTomCircleOverlayRenderer
import com.mapconductor.tomtom.groundimage.TomTomGroundImageController
import com.mapconductor.tomtom.groundimage.TomTomGroundImageOverlayRenderer
import com.mapconductor.tomtom.marker.TomTomMarkerController
import com.mapconductor.tomtom.polygon.TomTomPolygonController
import com.mapconductor.tomtom.polygon.TomTomPolygonOverlayRenderer
import com.mapconductor.tomtom.polyline.TomTomPolylineController
import com.mapconductor.tomtom.polyline.TomTomPolylineOverlayRenderer
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.MapOptions
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.style.StandardStyles
import com.tomtom.sdk.map.display.ui.MapView
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
    val registry = remember { scope.buildRegistry() }
    val serviceRegistry = remember { MutableMapServiceRegistry() }
    val cameraState = remember { mutableStateOf<MapCameraPositionInterface?>(state.cameraPosition) }
    val initialCameraPosition = remember(state) { MapCameraPosition.from(state.cameraPosition) }
    // TomTom の MapView は composition 側（remember）で生成して単一インスタンスに固定する。
    // viewProvider（SubcomposeLayout の計測フェーズ）で生成すると、ライフサイクル observer が
    // 追加される時点で MapView が未生成となり、onStart/onResume が MapView に届かず地図が
    // 描画されない（View だけ出てタイルが出ない）。tmp の動作実装と同じ構成にする。
    val mapView =
        remember {
            val options =
                MapOptions(
                    mapKey = tomtomApiKey(context),
                    // Compose の SubcomposeLayout 内に埋め込むため TextureView 描画にする。
                    renderToTexture = true,
                )
            MapView(context, options).apply { onCreate(null) }
        }

    // MapView のライフサイクルをホスト Activity のライフサイクルに同期させる。
    // observer 追加時に現在の状態（STARTED/RESUMED）まで catch-up されるため、
    // この時点で MapView に onStart/onResume が届く（これがタイル描画に必須）。
    DisposableEffect(lifecycle, mapView) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    mapView.onStart()
                }

                override fun onResume(owner: LifecycleOwner) {
                    mapView.onResume()
                }

                override fun onPause(owner: LifecycleOwner) {
                    mapView.onPause()
                }

                override fun onStop(owner: LifecycleOwner) {
                    mapView.onStop()
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    MapViewBase(
        state = state,
        cameraState = cameraState,
        modifier = modifier,
        // composition 側で生成済みの単一 MapView を返す（MapViewBase 側が AndroidView でホストする）。
        viewProvider = { mapView },
        serviceRegistry = serviceRegistry,
        holderProvider = { mapView ->
            // 生成済みの MapView から地図本体（TomTomMap）を取得する。
            // getMapAsync は MapView がウィンドウに接続され描画準備が整った時点で発火する。
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
            // MapView のライフサイクルは上の本体 DisposableEffect で駆動済み。
            // ここではコンポジション破棄時にコントローラを破棄してリークを防ぐ。
            DisposableEffect(lifecycle) {
                val stateId = state.id
                onDispose {
                    TomTomMapViewControllerStore.get(stateId)?.destroy()
                    TomTomMapViewControllerStore.remove(stateId)
                }
            }
        },
        sdkInitialize = {
            // NOTE: Orbis 2.x のオンライン地図表示は MapOptions(mapKey=...) だけで動作し、
            // TomTomSdk.initialize() は不要（むしろ呼ぶと map-display のネイティブ初期化と競合して
            // 地図が生成されない）。初期化が必要なのは検索/ルーティング等のナビ機能のみ。
            sdkInitialize?.invoke(context) ?: true
        },
        content = content,
    )
}

fun createTomTomMapViewController(
    holder: TomTomMapViewHolder,
    markerTiling: MarkerTilingOptions = MarkerTilingOptions.Default,
    serviceRegistry: MutableMapServiceRegistry? = null,
): TomTomMapViewController {
    // GroundImage は TomTom ネイティブの画像付き Polygon で描画する。
    val groundImageRenderer = TomTomGroundImageOverlayRenderer(holder = holder)
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
