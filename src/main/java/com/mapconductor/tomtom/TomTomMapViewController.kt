package com.mapconductor.tomtom

import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.marker.dispatchGeoMarkerClick
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.tomtom.circle.TomTomCircleController
import com.mapconductor.tomtom.groundimage.TomTomGroundImageController
import com.mapconductor.tomtom.marker.DefaultTomTomMarkerEventController
import com.mapconductor.tomtom.marker.StrategyTomTomMarkerEventController
import com.mapconductor.tomtom.marker.TomTomMarkerController
import com.mapconductor.tomtom.marker.TomTomMarkerEventControllerInterface
import com.mapconductor.tomtom.marker.TomTomMarkerRenderer
import com.mapconductor.tomtom.polygon.TomTomPolygonController
import com.mapconductor.tomtom.polyline.TomTomPolylineController
import com.mapconductor.tomtom.raster.TomTomRasterLayerSink
import com.mapconductor.tomtom.raster.TomTomStyleComposer
import com.tomtom.sdk.map.display.camera.CameraChangeListener
import com.tomtom.sdk.map.display.camera.CameraSteadyListener
import com.tomtom.sdk.map.display.gesture.MapClickListener
import com.tomtom.sdk.map.display.gesture.MapLongClickListener
import com.tomtom.sdk.map.display.marker.MarkerClickListener
import com.tomtom.sdk.map.display.polygon.PolygonClickListener
import com.tomtom.sdk.map.display.polyline.PolylineClickListener
import com.tomtom.sdk.map.display.style.LoadingStyleFailure
import com.tomtom.sdk.map.display.style.StyleLoadingCallback
import java.io.File
import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TomTom Orbis 用のマップコントローラ（コア + マーカー + Polyline/Polygon/Circle）。
 *
 * カメラ移動/停止（change・steady）、マーカー・地図・オーバーレイのタップ、スタイル切替（loadStyle）を
 * TomTom Maps Display SDK のネイティブ API に配線している。
 */
class TomTomMapViewController internal constructor(
    override val holder: TomTomMapViewHolder,
    internal val markerController: TomTomMarkerController,
    internal val polylineController: TomTomPolylineController,
    internal val polygonController: TomTomPolygonController,
    internal val circleController: TomTomCircleController,
    internal val groundImageController: TomTomGroundImageController,
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(),
    TomTomMapViewControllerInterface,
    RasterLayerCapableInterface,
    TomTomRasterLayerSink {
    internal val markerEventControllers = mutableListOf<TomTomMarkerEventControllerInterface>()
    private val _mapLoadedState = MutableStateFlow(false)
    val mapLoadedState: StateFlow<Boolean> = _mapLoadedState

    // 破棄後にネイティブ map へ触れると "Instance has been closed" で落ちるためのガード。
    // destroy()/生成はいずれもメインスレッドなので単純な Boolean で十分。
    internal var destroyed = false

    internal var markerClickListener: OnMarkerEventHandler? = null
    internal var markerDragStartListener: OnMarkerEventHandler? = null
    internal var markerDragListener: OnMarkerEventHandler? = null
    internal var markerDragEndListener: OnMarkerEventHandler? = null
    internal var markerAnimateStartListener: OnMarkerEventHandler? = null
    internal var markerAnimateEndListener: OnMarkerEventHandler? = null

    // カメラ移動の開始/終了を change + steady から擬似的に判定するためのフラグ。
    internal var cameraMovingStarted = false

    // 自前ドラッグ実装用（TomTom はマーカーのネイティブドラッグを持たない）。
    // draggable マーカー上の DOWN でジェスチャを占有し（地図パンを抑止）、スロップ超えの
    // 移動でドラッグ開始、指を追従、離したら確定する。動かず離した場合はクリック扱い。
    internal var draggingEntity: MarkerEntityInterface<TomTomActualMarker>? = null
    internal var pendingEntity: MarkerEntityInterface<TomTomActualMarker>? = null
    internal var downX = 0f
    internal var downY = 0f

    // 直近のタップ画面座標。TomTom のオーバーレイ用クリックリスナー（PolylineClickListener 等）は
    // タップ座標を渡さないため、ここで記録した位置から緯度経度を復元してクリック位置に使う。
    internal var lastTapScreenX = 0f
    internal var lastTapScreenY = 0f

    init {
        setupListeners()
        registerOverlayController(markerController)
        registerOverlayController(polylineController)
        registerOverlayController(polygonController)
        registerOverlayController(circleController)
        registerOverlayController(groundImageController)
        registerMarkerEventController(DefaultTomTomMarkerEventController(markerController))

        // getMapAsync で得た map は描画準備が整っている想定のため、初期化完了を通知する。
        // notifyMapInitialized は sticky なので、MapViewBase 側のリスナー登録前でも失われない。
        _mapLoadedState.value = true
        notifyMapInitialized()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun moveCamera(position: MapCameraPosition) = handleMoveCamera(position)

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = handleAnimateCamera(position, duration)

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = handleFitBounds(bounds, padding)

    // 拡張ファイル（Gestures / Camera / Raster）からは基底クラスの protected へ
    // 触れないため、ここで internal の入口を用意しておく。

    internal fun emitCameraMoveStart(position: MapCameraPosition) {
        cameraMoveStartCallback?.invoke(position)
    }

    internal fun emitCameraMove(position: MapCameraPosition) {
        cameraMoveCallback?.invoke(position)
    }

    internal fun emitCameraMoveEnd(position: MapCameraPosition) {
        cameraMoveEndCallback?.invoke(position)
    }

    internal suspend fun emitCameraPosition(position: MapCameraPosition) {
        notifyMapCameraPosition(position)
    }

    internal fun correctForCameraRestriction(current: MapCameraPosition): MapCameraPosition? =
        cameraRestrictionCorrection(current)

    /**
     * タイル描画されたマーカーのヒットテスト。
     *
     * ネイティブの `Marker` として描かれたものは MarkerClickListener が先に消費するので
     * ここへは来ない（[com.mapconductor.core.marker.dispatchNativeMarkerClick]）。
     * 呼び出し元がメインスレッドなので `pointForCoordinate` を触ってよい。
     */
    override fun dispatchMarkerTap(position: GeoPointInterface): Boolean =
        markerEventControllers.dispatchGeoMarkerClick(position)

    internal fun mapClickHandler(): ((GeoPoint) -> Unit)? = mapClickCallback

    private fun setupListeners() {
        // カメラ移動中は連続的に、停止時は steady で発火する。
        holder.map.addCameraChangeListener(CameraChangeListener { onCameraChangeInternal() })
        holder.map.addCameraSteadyListener(CameraSteadyListener { onCameraSteadyInternal() })
        // マーカー／地図タップ。
        holder.map.addMarkerClickListener(MarkerClickListener { marker -> onMarkerClickedInternal(marker) })
        holder.map.addMapClickListener(
            MapClickListener { coordinate ->
                onMapClickInternal(coordinate)
                false
            },
        )
        // 地図のロングプレスコールバック（マーカードラッグとは独立）。
        holder.map.addMapLongClickListener(
            MapLongClickListener { coordinate ->
                mapLongClickCallback?.invoke(coordinate.toGeoPoint())
                false
            },
        )
        // オーバーレイのネイティブクリック。
        holder.map.addPolylineClickListener(PolylineClickListener { polyline -> onPolylineClickedInternal(polyline) })
        holder.map.addPolygonClickListener(PolygonClickListener { polygon -> onPolygonClickedInternal(polygon) })
        // マーカードラッグは MotionEvent を直接見て実装する。
        holder.mapView.setOnTouchListener { _, event -> onMapTouchInternal(event) }
    }

    override fun destroy() {
        // これ以降のカメラ操作などを無効化してから破棄。
        destroyed = true
        super.destroy()
        // 基底は defaultCoroutine のみ cancel する。mainCoroutine に積まれた
        // moveCamera 等が map 破棄後に走ると "Instance has been closed" で落ちるため止める。
        mainCoroutine.cancel()
    }

    // 直近に要求した論理カメラ位置。tilt < 0 の擬似表現は SDK 側で正ピッチへ変換されるため、
    // カメラ状態の読み戻し時に元の負tilt を復元するヒントとして保持する（MapLibre と同方針）。
    internal var lastLogicalCameraPosition: MapCameraPosition? = null

    override suspend fun clearOverlays() {
        markerController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
    }

    override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) {
        (markerController.renderer as TomTomMarkerRenderer).animationOverlayHost = host
    }

    // ---- Polyline / Polygon / Circle capable ------------------------------

    override fun hasPolyline(state: PolylineState): Boolean = polylineController.polylineManager.hasEntity(state.id)

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    override fun hasPolygon(state: PolygonState): Boolean = polygonController.polygonManager.hasEntity(state.id)

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    override fun hasCircle(state: CircleState): Boolean = circleController.circleManager.hasEntity(state.id)

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    // ---- カメライベント ---------------------------------------------------

    // ---- マーカーイベントリスナー（Deprecated 経路） -----------------------

    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) {
        markerDragStartListener = listener
        markerEventControllers.forEach { it.setDragStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) {
        markerDragListener = listener
        markerEventControllers.forEach { it.setDragListener(listener) }
    }

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) {
        markerDragEndListener = listener
        markerEventControllers.forEach { it.setDragEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) {
        markerAnimateStartListener = listener
        markerEventControllers.forEach { it.setAnimateStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) {
        markerAnimateEndListener = listener
        markerEventControllers.forEach { it.setAnimateEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) {
        markerClickListener = listener
        markerEventControllers.forEach { it.setClickListener(listener) }
    }

    // ---- マップデザイン ---------------------------------------------------

    internal var mapDesignType: TomTomMapDesignType = TomTomMapDesign.Standard
    internal var mapDesignTypeChangeListener: TomTomMapDesignTypeChangeHandler? = null

    override fun applyUISettings(settings: MapUISettings) {
        // TomTomMap implements GesturesController directly in this SDK version.
        holder.map.apply {
            isScrollEnabled = settings.scrollGesture
            isZoomEnabled = settings.zoomGesture
            isRotationEnabled = settings.rotateGesture
            isTiltEnabled = settings.tiltGesture
        }
    }

    override fun setMapDesignType(value: TomTomMapDesignType) {
        val descriptor =
            (value as? TomTomMapDesign)?.styleDescriptor
                ?: TomTomMapDesign.create(value.id).styleDescriptor
        mainCoroutine.launch {
            holder.map.loadStyle(
                descriptor,
                object : StyleLoadingCallback {
                    override fun onSuccess() = Unit

                    override fun onFailure(failure: LoadingStyleFailure) = Unit
                },
            )
        }
        mapDesignType = value
        mapDesignTypeChangeListener?.invoke(value)
    }

    override fun setMapDesignTypeChangeListener(listener: TomTomMapDesignTypeChangeHandler) {
        mapDesignTypeChangeListener = listener
        listener(mapDesignType)
    }

    // ---- ラスタレイヤーの実体化（compose + loadStyle） ------------------------------------
    //
    // TomTom には実行時の addLayer/addSource が無いため、マーカータイルなど複数の
    // ラスタレイヤーを「フル browsing スタイル + TomTom ラスタ地図(可視ベース) + 各ラスタレイヤー」を
    // 合成して loadStyle することで実体化する（[TomTomStyleComposer] 参照）。ラスタが 0 枚になれば
    // 通常のデザインスタイル（ベクタ）へ戻す。
    //
    // 注意: 合成は現状 browsing/light ベース固定。Standard 以外のデザインでラスタを載せた場合は
    // ベースが browsing になる（対象ページは Standard のため実用上問題なし）。
    internal val composedRasterLayers = LinkedHashMap<String, TomTomStyleComposer.RasterSpec>()
    internal val composedStyleMutex = Mutex()
    internal var rasterApiKey: String? = null
    internal var rasterCacheDir: File? = null

    // 公開 RasterLayer オーバーレイ（サンプルの RasterLayer(state)）経由で追加された id を追跡する。
    // マーカータイル用の内部ラスタと区別し、それを誤って消さないようにする。
    internal val publicRasterLayerIds = mutableSetOf<String>()

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) {
        val present = data.map { it.id }.toSet()
        // 無くなった公開ラスタレイヤーを削除。
        (publicRasterLayerIds - present).forEach { removeRasterLayer(it) }
        publicRasterLayerIds.clear()
        data.forEach { state ->
            // TomTom Android はタイル要求を書き換える公開 API を持たない。
            RasterHeaderRuleSet.warnUnsupported(provider = "TomTom", state = state)
            publicRasterLayerIds.add(state.id)
            applyPublicRasterLayer(state)
        }
    }

    override suspend fun updateRasterLayer(state: RasterLayerState) {
        publicRasterLayerIds.add(state.id)
        applyPublicRasterLayer(state)
    }

    override fun hasRasterLayer(state: RasterLayerState): Boolean = composedRasterLayers.containsKey(state.id)

    override suspend fun upsertRasterLayer(
        id: String,
        tilesUrl: String,
        opacity: Double,
        tileSize: Int,
        minZoom: Int?,
        maxZoom: Int?,
    ) {
        composedStyleMutex.withLock {
            composedRasterLayers[id] =
                TomTomStyleComposer.RasterSpec(id, tilesUrl, opacity, tileSize, minZoom, maxZoom)
        }
        scheduleComposedStyleReload()
    }

    override suspend fun removeRasterLayer(id: String) {
        val removed = composedStyleMutex.withLock { composedRasterLayers.remove(id) != null }
        if (!removed) return
        scheduleComposedStyleReload()
    }

    internal var styleReloadJob: Job? = null

    // 合成スタイル JSON の書き出し先を 2 ファイルで交互（ping-pong）に使う。同じ file:// URI へ
    // 上書き再ロードすると TomTom がタイルを再描画せず地図が空白のままになるため、毎回異なる URI
    // を渡し、かつ「今ロード中のファイル」を上書きしないようにする。
    internal var composedStyleToggle = 0

    internal suspend fun loadDesignStyle() {
        val descriptor =
            (mapDesignType as? TomTomMapDesign)?.styleDescriptor
                ?: TomTomMapDesign.create(mapDesignType.id).styleDescriptor
        withContext(Dispatchers.Main) {
            holder.map.loadStyle(descriptor, styleLoadingCallback("design-revert"))
        }
    }

    // ---- GroundImage（画像付き native Polygon で描画） --------------------------------------

    override fun hasGroundImage(state: GroundImageState): Boolean =
        groundImageController.groundImageManager.hasEntity(state.id)

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        groundImageController.clickListener = listener
    }

    // ---- MarkerRenderingSupport（marker-clustering 連携用） ----------------

    fun createMarkerRenderer(): MarkerOverlayRendererInterface<TomTomActualMarker> =
        TomTomMarkerRenderer(holder = holder)

    fun createMarkerEventController(
        controller: StrategyMarkerController<TomTomActualMarker>,
    ): MarkerEventControllerInterface<TomTomActualMarker> = StrategyTomTomMarkerEventController(controller)

    fun registerMarkerEventController(controller: MarkerEventControllerInterface<TomTomActualMarker>) {
        val typed = controller as? TomTomMarkerEventControllerInterface ?: return
        registerMarkerEventController(typed)
    }

    internal fun registerMarkerEventController(controller: TomTomMarkerEventControllerInterface) {
        if (markerEventControllers.contains(controller)) return
        markerEventControllers.add(controller)
        controller.setClickListener(markerClickListener)
        controller.setDragStartListener(markerDragStartListener)
        controller.setDragListener(markerDragListener)
        controller.setDragEndListener(markerDragEndListener)
        controller.setAnimateStartListener(markerAnimateStartListener)
        controller.setAnimateEndListener(markerAnimateEndListener)
    }

    fun onMarkerRenderingReady() {
        val mapCameraPosition = getMapCameraPosition()
        defaultCoroutine.launch { notifyMapCameraPosition(mapCameraPosition) }
    }

    companion object {
        // マーカータイル用ラスタレイヤーの id（composedRasterLayers のキー）。
        internal const val MARKER_RASTER_ID = "marker-tile"

        // タップとドラッグを区別する移動しきい値（px）。
        internal const val TOUCH_SLOP_PX = 24f

        // 合成スタイル再ロードのデバウンス時間（ms）。opacity スライダー等の連続変更をまとめる。
        internal const val COMPOSED_STYLE_DEBOUNCE_MS = 300L
    }
}
