package com.mapconductor.tomtom

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.VisibleRegion
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
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.tomtom.circle.TomTomCircleController
import com.mapconductor.tomtom.groundimage.TomTomGroundImageController
import com.mapconductor.tomtom.marker.DefaultTomTomMarkerEventController
import com.mapconductor.tomtom.marker.MarkerTileRasterLayerCallback
import com.mapconductor.tomtom.marker.StrategyTomTomMarkerEventController
import com.mapconductor.tomtom.marker.TomTomMarkerController
import com.mapconductor.tomtom.marker.TomTomMarkerEventControllerInterface
import com.mapconductor.tomtom.marker.TomTomMarkerRenderer
import com.mapconductor.tomtom.polygon.TomTomPolygonController
import com.mapconductor.tomtom.polyline.TomTomPolylineController
import com.mapconductor.tomtom.raster.TomTomRasterLayerSink
import com.mapconductor.tomtom.raster.TomTomStyleComposer
import com.tomtom.sdk.map.display.style.StyleDescriptor
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val groundImageController: TomTomGroundImageController,
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(),
    TomTomMapViewControllerInterface,
    RasterLayerCapableInterface,
    TomTomRasterLayerSink {
    private val markerEventControllers = mutableListOf<TomTomMarkerEventControllerInterface>()
    private val _mapLoadedState = MutableStateFlow(false)
    val mapLoadedState: StateFlow<Boolean> = _mapLoadedState

    // 破棄後にネイティブ map へ触れると "Instance has been closed" で落ちるためのガード。
    // destroy()/生成はいずれもメインスレッドなので単純な Boolean で十分。
    private var destroyed = false

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

    // 直近のタップ画面座標。TomTom のオーバーレイ用クリックリスナー（PolylineClickListener 等）は
    // タップ座標を渡さないため、ここで記録した位置から緯度経度を復元してクリック位置に使う。
    private var lastTapScreenX = 0f
    private var lastTapScreenY = 0f

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
                // オーバーレイクリック（polyline/polygon/circle）でタップ位置を復元するため常に記録する。
                lastTapScreenX = event.x
                lastTapScreenY = event.y
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

    override fun destroy() {
        // これ以降のカメラ操作などを無効化してから破棄。
        destroyed = true
        super.destroy()
        // 基底は defaultCoroutine のみ cancel する。mainCoroutine に積まれた
        // moveCamera 等が map 破棄後に走ると "Instance has been closed" で落ちるため止める。
        mainCoroutine.cancel()
    }

    override fun moveCamera(position: MapCameraPosition) {
        if (destroyed) return
        mainCoroutine.launch {
            if (destroyed) return@launch
            holder.map.moveCamera(position.toCameraOptions())
        }
    }

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) {
        if (destroyed) return
        mainCoroutine.launch {
            if (destroyed) return@launch
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
        if (destroyed) return
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

    /** 直近タップの画面座標を現在の投影で緯度経度へ復元する。 */
    private fun lastTapPosition() = holder.fromScreenOffsetSync(Offset(lastTapScreenX, lastTapScreenY))

    private fun onPolylineClickedInternal(polyline: Polyline) {
        // クリックイベントの緯度経度は「タップ位置とポリラインの最近傍点」にする（他プロバイダと同じ）。
        val tap = lastTapPosition()
        if (tap != null) {
            polylineController.findWithClosestPoint(tap)?.let { hit ->
                polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
                return
            }
        }
        // フォールバック: ネイティブクリックの polyline + タップ位置（無ければ先頭点）。
        val entity =
            polylineController.polylineManager
                .allEntities()
                .firstOrNull { it.polyline.id == polyline.id } ?: return
        val clicked = tap ?: entity.state.points.firstOrNull() ?: return
        polylineController.dispatchClick(PolylineEvent(entity.state, clicked))
    }

    private fun onPolygonClickedInternal(polygon: Polygon) {
        // ポリゴンは穴なし=native Polygon、穴あり=PolygonOverlay+輪郭Polygon で構成が異なる。
        // クリックされた native Polygon の tag（= state.id）でエンティティを引く。
        val entity =
            polygonController.polygonManager
                .allEntities()
                .firstOrNull { it.polygon.tag == polygon.tag } ?: return
        // クリック位置はタップした緯度経度（無ければ先頭頂点）。
        val clicked = lastTapPosition() ?: entity.state.points.firstOrNull() ?: return
        polygonController.dispatchClick(PolygonEvent(entity.state, clicked))
    }

    private fun onCircleClickedInternal(circle: Circle) {
        // circle は塗り（native circle）+ 枠線（polyline）の合成。クリックは塗り側で判定する。
        val entity =
            circleController.circleManager
                .allEntities()
                .firstOrNull { it.circle?.fill?.id == circle.id } ?: return
        // クリック位置はタップした緯度経度（無ければ中心）。
        val clicked = lastTapPosition() ?: entity.state.center
        circleController.dispatchClick(CircleEvent(entity.state, clicked))
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
        // 範囲・ズーム制限に違反していれば矩形内へ引き戻す（TomTom はネイティブの範囲制限 API が無いため）。
        // 再適用すると再度 steady が発火し、そこでは補正不要になり通常フローへ進む。
        cameraRestrictionCorrection(mapCameraPosition)?.let { corrected ->
            moveCamera(corrected)
            return
        }
        defaultCoroutine.launch { markerController.onCameraChanged(mapCameraPosition) }
        cameraMoveEndCallback?.invoke(mapCameraPosition)
    }

    private fun getMapCameraPosition(): MapCameraPosition {
        val camera = holder.map.cameraPosition.toMapCameraPosition()
        // 画面四隅を投影して visibleRegion（ビューポート）を構築する。
        // これが無いと marker-clustering がビューポートを算出できずクラスタが一切描画されない
        // （他プロバイダは getMapCameraPosition で visibleRegion を設定している）。
        val w = holder.mapView.width
        val h = holder.mapView.height
        if (w <= 0 || h <= 0) return camera
        val farLeft = holder.fromScreenOffsetSync(Offset(0f, 0f))
        val farRight = holder.fromScreenOffsetSync(Offset(w.toFloat(), 0f))
        val nearLeft = holder.fromScreenOffsetSync(Offset(0f, h.toFloat()))
        val nearRight = holder.fromScreenOffsetSync(Offset(w.toFloat(), h.toFloat()))
        val corners = listOfNotNull(farLeft, farRight, nearLeft, nearRight)
        if (corners.isEmpty()) return camera
        val bounds = GeoRectBounds().apply { corners.forEach { extend(it) } }
        return camera.copy(
            visibleRegion =
                VisibleRegion(
                    bounds = bounds,
                    nearLeft = nearLeft,
                    nearRight = nearRight,
                    farLeft = farLeft,
                    farRight = farRight,
                ),
        )
    }

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

    // ---- ラスタレイヤーの実体化（compose + loadStyle） ------------------------------------
    //
    // TomTom には実行時の addLayer/addSource が無いため、マーカータイル・GroundImage など複数の
    // ラスタレイヤーを「フル browsing スタイル + TomTom ラスタ地図(可視ベース) + 各ラスタレイヤー」を
    // 合成して loadStyle することで実体化する（[TomTomStyleComposer] 参照）。ラスタが 0 枚になれば
    // 通常のデザインスタイル（ベクタ）へ戻す。
    //
    // 注意: 合成は現状 browsing/light ベース固定。Standard 以外のデザインでラスタを載せた場合は
    // ベースが browsing になる（対象ページは Standard のため実用上問題なし）。
    private val composedRasterLayers = LinkedHashMap<String, TomTomStyleComposer.RasterSpec>()
    private val composedStyleMutex = Mutex()
    private var rasterApiKey: String? = null
    private var rasterCacheDir: File? = null

    fun setupMarkerTileRaster(
        apiKey: String,
        cacheDir: File,
    ) {
        rasterApiKey = apiKey
        rasterCacheDir = cacheDir
        markerController.setRasterLayerCallback(
            MarkerTileRasterLayerCallback { state ->
                if (state == null) {
                    removeRasterLayer(MARKER_RASTER_ID)
                    return@MarkerTileRasterLayerCallback
                }
                val src =
                    state.source as? RasterLayerSource.UrlTemplate
                        ?: return@MarkerTileRasterLayerCallback
                // マーカータイルは透明 PNG（アイコンのみ）なので不透明で重ねる。
                upsertRasterLayer(MARKER_RASTER_ID, src.template, 1.0)
            },
        )
    }

    // 公開 RasterLayer オーバーレイ（サンプルの RasterLayer(state)）経由で追加された id を追跡する。
    // マーカータイル/GroundImage 用の内部ラスタと区別し、それらを誤って消さないようにする。
    private val publicRasterLayerIds = mutableSetOf<String>()

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) {
        val present = data.map { it.id }.toSet()
        // 無くなった公開ラスタレイヤーを削除。
        (publicRasterLayerIds - present).forEach { removeRasterLayer(it) }
        publicRasterLayerIds.clear()
        data.forEach { state ->
            publicRasterLayerIds.add(state.id)
            applyPublicRasterLayer(state)
        }
    }

    override suspend fun updateRasterLayer(state: RasterLayerState) {
        publicRasterLayerIds.add(state.id)
        applyPublicRasterLayer(state)
    }

    override fun hasRasterLayer(state: RasterLayerState): Boolean = composedRasterLayers.containsKey(state.id)

    private suspend fun applyPublicRasterLayer(state: RasterLayerState) {
        val src = state.source as? RasterLayerSource.UrlTemplate
        if (src == null || !state.visible) {
            removeRasterLayer(state.id)
            return
        }
        // ソースの tileSize / minZoom / maxZoom を合成スタイルへ伝える。maxZoom を渡すと
        // 高ズームでオーバーズーム表示され、実タイルの無い領域での歯抜けを防げる。
        upsertRasterLayer(
            id = state.id,
            tilesUrl = src.template,
            opacity = state.opacity.toDouble(),
            tileSize = src.tileSize,
            minZoom = src.minZoom,
            maxZoom = src.maxZoom,
        )
    }

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

    private var styleReloadJob: Job? = null

    /**
     * 合成スタイルの再ロードをデバウンスして予約する。TomTom は実行時に paint（raster-opacity 等）を
     * 変更する API が無く、変更のたびに `loadStyle` で全タイルを再フェッチするため、opacity スライダー
     * のような連続変更をそのまま反映すると地図が空白のまま追いつかなくなる。最後の変更だけ反映する。
     */
    private fun scheduleComposedStyleReload() {
        styleReloadJob?.cancel()
        styleReloadJob =
            defaultCoroutine.launch {
                delay(COMPOSED_STYLE_DEBOUNCE_MS)
                composedStyleMutex.withLock { applyComposedStyle() }
            }
    }

    // 合成スタイル JSON の書き出し先を 2 ファイルで交互（ping-pong）に使う。同じ file:// URI へ
    // 上書き再ロードすると TomTom がタイルを再描画せず地図が空白のままになるため、毎回異なる URI
    // を渡し、かつ「今ロード中のファイル」を上書きしないようにする。
    private var composedStyleToggle = 0

    /** 現在のラスタレイヤー群で合成スタイルを再ロードする。0 枚ならデザインスタイルへ戻す。 */
    private suspend fun applyComposedStyle() {
        if (destroyed) return
        val apiKey = rasterApiKey
        val cacheDir = rasterCacheDir
        if (composedRasterLayers.isEmpty() || apiKey == null || cacheDir == null) {
            loadDesignStyle()
            return
        }
        composedStyleToggle = composedStyleToggle xor 1
        val outFile = File(cacheDir, "tomtom_composed_style_$composedStyleToggle.json")
        val uri =
            TomTomStyleComposer.composeRasterStyle(
                apiKey = apiKey,
                cacheDir = cacheDir,
                layers = composedRasterLayers.values.toList(),
                outFile = outFile,
            ) ?: return
        if (destroyed) return
        withContext(Dispatchers.Main) {
            // loadStyle はカメラをリセットし、その状態だと再ロード後に現在ビューポートの
            // タイル取得がトリガーされず地図が空白のままになる。ロード完了後に現在カメラを
            // 再適用してタイル取得を促す（マーカータイリングの初期化と同じ対処）。
            val currentCamera = holder.map.cameraPosition.toMapCameraPosition()
            holder.map.loadStyle(
                StyleDescriptor(uri, uri),
                object : StyleLoadingCallback {
                    override fun onSuccess() {
                        if (destroyed) return
                        // 合成スタイルの再ロード後は、同一ビューポートのタイルが自動で再取得されず
                        // 地図が空白のままになる。初期ロードと同じく mapView.post 経由でカメラを
                        // 再適用し、レイアウト後にタイル取得を促す。
                        holder.mapView.post {
                            if (!destroyed) moveCamera(currentCamera)
                        }
                    }

                    override fun onFailure(failure: LoadingStyleFailure) {
                        android.util.Log.e("TomTomRaster", "loadStyle failed (composed-raster): $failure")
                    }
                },
            )
        }
    }

    private suspend fun loadDesignStyle() {
        val descriptor =
            (mapDesignType as? TomTomMapDesign)?.styleDescriptor
                ?: TomTomMapDesign.create(mapDesignType.id).styleDescriptor
        withContext(Dispatchers.Main) {
            holder.map.loadStyle(descriptor, styleLoadingCallback("design-revert"))
        }
    }

    private fun styleLoadingCallback(tag: String) =
        object : StyleLoadingCallback {
            override fun onSuccess() = Unit

            override fun onFailure(failure: LoadingStyleFailure) {
                android.util.Log.e("TomTomRaster", "loadStyle failed ($tag): $failure")
            }
        }

    // ---- GroundImage（ラスタレイヤー方式で描画） --------------------------------------------

    override suspend fun compositionGroundImages(data: List<GroundImageState>) = groundImageController.add(data)

    override suspend fun updateGroundImage(state: GroundImageState) = groundImageController.update(state)

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
        private const val MARKER_RASTER_ID = "marker-tile"

        // タップとドラッグを区別する移動しきい値（px）。
        private const val TOUCH_SLOP_PX = 24f

        // 合成スタイル再ロードのデバウンス時間（ms）。opacity スライダー等の連続変更をまとめる。
        private const val COMPOSED_STYLE_DEBOUNCE_MS = 300L
    }
}
