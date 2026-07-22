package com.mapconductor.tomtom

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.tomtom.circle.TomTomCircleController
import com.mapconductor.tomtom.marker.DefaultTomTomMarkerEventController
import com.mapconductor.tomtom.marker.StrategyTomTomMarkerEventController
import com.mapconductor.tomtom.marker.TomTomMarkerController
import com.mapconductor.tomtom.marker.TomTomMarkerEventControllerInterface
import com.mapconductor.tomtom.marker.TomTomMarkerRenderer
import com.mapconductor.tomtom.polygon.TomTomPolygonController
import com.mapconductor.tomtom.polyline.TomTomPolylineController
import com.tomtom.sdk.map.display.camera.CameraChangeListener
import com.tomtom.sdk.map.display.camera.CameraSteadyListener
import com.tomtom.sdk.map.display.circle.Circle
import com.tomtom.sdk.map.display.circle.CircleClickListener
import com.tomtom.sdk.map.display.gesture.MapClickListener
import com.tomtom.sdk.map.display.gesture.MapLongClickListener
import com.tomtom.sdk.map.display.marker.Marker
import com.tomtom.sdk.map.display.marker.MarkerClickListener
import com.tomtom.sdk.map.display.polygon.Polygon
import com.tomtom.sdk.map.display.polygon.PolygonClickListener
import com.tomtom.sdk.map.display.polyline.Polyline
import com.tomtom.sdk.map.display.polyline.PolylineClickListener
import com.tomtom.sdk.map.display.style.LoadingStyleFailure
import com.tomtom.sdk.map.display.style.StyleLoadingCallback
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import android.annotation.SuppressLint
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * TomTom Orbis 用のマップコントローラ（コア + マーカー + Polyline/Polygon/Circle）。
 *
 * カメラ移動/停止（change・steady）、マーカー・地図・オーバーレイのタップ、スタイル切替（loadStyle）を
 * TomTom Maps Display SDK のネイティブ API に配線している。
 */
class TomTomMapViewController internal constructor(
    override val holder: TomTomMapViewHolder,
    private val markerController: TomTomMarkerController,
    private val polylineController: TomTomPolylineController,
    private val polygonController: TomTomPolygonController,
    private val circleController: TomTomCircleController,
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(),
    TomTomMapViewControllerInterface {
    private val markerEventControllers = mutableListOf<TomTomMarkerEventControllerInterface>()
    private val _mapLoadedState = MutableStateFlow(false)
    val mapLoadedState: StateFlow<Boolean> = _mapLoadedState

    private var markerClickListener: OnMarkerEventHandler? = null
    private var markerDragStartListener: OnMarkerEventHandler? = null
    private var markerDragListener: OnMarkerEventHandler? = null
    private var markerDragEndListener: OnMarkerEventHandler? = null
    private var markerAnimateStartListener: OnMarkerEventHandler? = null
    private var markerAnimateEndListener: OnMarkerEventHandler? = null

    // カメラ移動の開始/終了を change + steady から擬似的に判定するためのフラグ。
    private var cameraMovingStarted = false

    // 自前ドラッグ実装用（TomTom はマーカーのネイティブドラッグを持たない）。
    // draggable マーカー上の DOWN でジェスチャを占有し（地図パンを抑止）、スロップ超えの
    // 移動でドラッグ開始、指を追従、離したら確定する。動かず離した場合はクリック扱い。
    private var draggingEntity: MarkerEntityInterface<TomTomActualMarker>? = null
    private var pendingEntity: MarkerEntityInterface<TomTomActualMarker>? = null
    private var downX = 0f
    private var downY = 0f

    init {
        setupListeners()
        registerOverlayController(markerController)
        registerOverlayController(polylineController)
        registerOverlayController(polygonController)
        registerOverlayController(circleController)
        registerMarkerEventController(DefaultTomTomMarkerEventController(markerController))

        // getMapAsync で得た map は描画準備が整っている想定のため、初期化完了を通知する。
        // notifyMapInitialized は sticky なので、MapViewBase 側のリスナー登録前でも失われない。
        _mapLoadedState.value = true
        notifyMapInitialized()
    }

    @SuppressLint("ClickableViewAccessibility")
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
        holder.map.addCircleClickListener(CircleClickListener { circle -> onCircleClickedInternal(circle) })
        // マーカードラッグは MotionEvent を直接見て実装する。
        holder.mapView.setOnTouchListener { _, event -> onMapTouchInternal(event) }
    }

    /**
     * MotionEvent を処理してマーカーの自前ドラッグを実現する。
     *  - draggable マーカー上の DOWN: ジェスチャを占有（true を返し地図パンを抑止）
     *  - スロップ超えの MOVE: ドラッグ開始 → 以降 指に追従してマーカーを再配置
     *  - UP: ドラッグしていれば確定、動いていなければクリック扱い
     *  - マーカー外／非 draggable の場合は false を返して地図に委ねる
     */
    private fun onMapTouchInternal(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = holder.fromScreenOffsetSync(Offset(event.x, event.y)) ?: return false
                val entity = markerController.find(position, holder.map.cameraPosition.zoom)
                if (entity == null || !entity.state.draggable) return false
                pendingEntity = entity
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dragging = draggingEntity
                if (dragging != null) {
                    val position = holder.fromScreenOffsetSync(Offset(event.x, event.y)) ?: return true
                    dragging.state.position = position
                    (markerController.renderer as TomTomMarkerRenderer).setMarkerPosition(dragging, position)
                    markerController.dispatchDrag(dragging.state)
                    return true
                }
                val pending = pendingEntity ?: return false
                if (abs(event.x - downX) > TOUCH_SLOP_PX || abs(event.y - downY) > TOUCH_SLOP_PX) {
                    draggingEntity = pending
                    holder.map.isScrollEnabled = false
                    markerController.dispatchDragStart(pending.state)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dragging = draggingEntity
                if (dragging != null) {
                    markerController.dispatchDragEnd(dragging.state)
                    draggingEntity = null
                    pendingEntity = null
                    holder.map.isScrollEnabled = true
                    return true
                }
                val pending = pendingEntity
                pendingEntity = null
                if (pending != null) {
                    // 動かず離した → クリック（掴んでいる間はネイティブのクリックが発火しないため）。
                    markerController.dispatchClick(pending.state)
                    return true
                }
                return false
            }
        }
        return false
    }

    override fun moveCamera(position: MapCameraPosition) {
        mainCoroutine.launch {
            holder.map.moveCamera(position.toCameraOptions())
        }
    }

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) {
        mainCoroutine.launch {
            holder.map.animateCamera(
                position.toCameraOptions(),
                duration.milliseconds,
            )
        }
    }

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) {
        // NOTE: TomTom Maps Display SDK には矩形フィットの直接 API が無いため、
        // 境界の中心へ移動するのみ（ズームは現状維持）の簡易実装。必要に応じて
        // ズーム計算を追加すること。
        val sw = bounds.southWest ?: return
        val ne = bounds.northEast ?: return
        val centerLat = (sw.latitude + ne.latitude) / 2.0
        val centerLng = (sw.longitude + ne.longitude) / 2.0
        val current = getMapCameraPosition()
        moveCamera(
            current.copy(
                position =
                    com.mapconductor.core.features.GeoPoint
                        .fromLatLong(centerLat, centerLng),
            ),
        )
    }

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>> =
        mapOf(
            "marker" to markerController,
            "polyline" to polylineController,
            "polygon" to polygonController,
            "circle" to circleController,
        )

    override suspend fun clearOverlays() {
        markerController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
    }

    override suspend fun compositionMarkers(data: List<MarkerState>) = markerController.add(data)

    override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) {
        (markerController.renderer as TomTomMarkerRenderer).animationOverlayHost = host
    }

    override suspend fun updateMarker(state: MarkerState) = markerController.update(state)

    override fun hasMarker(state: MarkerState): Boolean = this.markerController.markerManager.hasEntity(state.id)

    // ---- Polyline / Polygon / Circle capable ------------------------------

    override suspend fun compositionPolylines(data: List<PolylineState>) = polylineController.add(data)

    override suspend fun updatePolyline(state: PolylineState) = polylineController.update(state)

    override fun hasPolyline(state: PolylineState): Boolean = polylineController.polylineManager.hasEntity(state.id)

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    override suspend fun compositionPolygons(data: List<PolygonState>) = polygonController.add(data)

    override suspend fun updatePolygon(state: PolygonState) = polygonController.update(state)

    override fun hasPolygon(state: PolygonState): Boolean = polygonController.polygonManager.hasEntity(state.id)

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    override suspend fun compositionCircles(data: List<CircleState>) = circleController.add(data)

    override suspend fun updateCircle(state: CircleState) = circleController.update(state)

    override fun hasCircle(state: CircleState): Boolean = circleController.circleManager.hasEntity(state.id)

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    private fun onPolylineClickedInternal(polyline: Polyline) {
        val entity =
            polylineController.polylineManager
                .allEntities()
                .firstOrNull { it.polyline.id == polyline.id } ?: return
        val clicked = entity.state.points.firstOrNull() ?: return
        polylineController.dispatchClick(PolylineEvent(entity.state, clicked))
    }

    private fun onPolygonClickedInternal(polygon: Polygon) {
        val entity =
            polygonController.polygonManager
                .allEntities()
                .firstOrNull { it.polygon.id == polygon.id } ?: return
        val clicked = entity.state.points.firstOrNull() ?: return
        polygonController.dispatchClick(PolygonEvent(entity.state, clicked))
    }

    private fun onCircleClickedInternal(circle: Circle) {
        // circle は塗り（native circle）+ 枠線（polyline）の合成。クリックは塗り側で判定する。
        val entity =
            circleController.circleManager
                .allEntities()
                .firstOrNull { it.circle?.fill?.id == circle.id } ?: return
        circleController.dispatchClick(CircleEvent(entity.state, entity.state.center))
    }

    // ---- カメライベント ---------------------------------------------------

    private fun onCameraChangeInternal() {
        val mapCameraPosition = getMapCameraPosition()
        if (!cameraMovingStarted) {
            cameraMovingStarted = true
            cameraMoveStartCallback?.invoke(mapCameraPosition)
        }
        defaultCoroutine.launch { notifyMapCameraPosition(mapCameraPosition) }
        cameraMoveCallback?.invoke(mapCameraPosition)
    }

    /** カメラ停止時（steady リスナーから配線）。 */
    private fun onCameraSteadyInternal() {
        cameraMovingStarted = false
        val mapCameraPosition = getMapCameraPosition()
        defaultCoroutine.launch { markerController.onCameraChanged(mapCameraPosition) }
        cameraMoveEndCallback?.invoke(mapCameraPosition)
    }

    private fun getMapCameraPosition(): MapCameraPosition = holder.map.cameraPosition.toMapCameraPosition()

    /** マップ（マーカー以外）タップ時（MapClickListener から配線）。 */
    private fun onMapClickInternal(coordinate: com.tomtom.sdk.location.GeoPoint) {
        val touchPosition = coordinate.toGeoPoint()
        val zoomSnapshot = holder.map.cameraPosition.zoom
        defaultCoroutine.launch {
            markerController.find(touchPosition, zoomSnapshot)?.let { entity ->
                if (!entity.state.clickable) return@launch
                mainCoroutine.launch { markerController.dispatchClick(entity.state) }
                return@launch
            }
            mapClickCallback?.let { cb ->
                mainCoroutine.launch { cb(touchPosition) }
            }
        }
    }

    /** ネイティブのマーカークリック（MarkerClickListener から配線）。 */
    private fun onMarkerClickedInternal(marker: Marker): Boolean {
        val stateId = marker.tag ?: return false
        markerEventControllers.forEach { controller ->
            val entity = controller.getEntity(stateId) ?: return@forEach
            if (!entity.state.clickable) return true
            controller.dispatchClick(entity.state)
            return true
        }
        return false
    }

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

    private var mapDesignType: TomTomMapDesignType = TomTomMapDesign.Standard
    private var mapDesignTypeChangeListener: TomTomMapDesignTypeChangeHandler? = null

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
        // タップとドラッグを区別する移動しきい値（px）。
        private const val TOUCH_SLOP_PX = 24f
    }
}
