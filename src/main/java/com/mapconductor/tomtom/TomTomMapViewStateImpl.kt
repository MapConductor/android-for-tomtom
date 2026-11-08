package com.mapconductor.tomtom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.MapViewState
import com.mapconductor.core.map.MapViewStateBundle
import com.mapconductor.core.map.MapViewStateInterface
import com.tomtom.sdk.map.display.style.StyleDescriptor
import java.util.UUID
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TomTomMapViewStateInterface : MapViewStateInterface<TomTomMapDesignType>

class TomTomMapViewState(
    override val id: String,
    mapDesignType: TomTomMapDesignType,
    cameraPosition: MapCameraPosition = MapCameraPosition.Default,
) : MapViewState<TomTomMapDesignType>(cameraPosition),
    TomTomMapViewStateInterface {
    // Map padding
    private val _padding = MutableStateFlow(MapPaddings.Zeros)
    val padding: StateFlow<MapPaddings> = _padding.asStateFlow()

    private var _mapDesignType: TomTomMapDesignType = mapDesignType

    override var mapDesignType: TomTomMapDesignType
        set(value) {
            _mapDesignType = value
            this.controller?.setMapDesignType(value)
        }
        get() = _mapDesignType

    // Read through the base so that letting go of the controller there (MapViewBase
    // does it when the map is torn down) leaves nothing behind here.
    private val controller: TomTomMapViewControllerInterface?
        get() = attachedMapController as? TomTomMapViewControllerInterface

    internal fun setController(controller: TomTomMapViewControllerInterface) {
        attachController(controller)
    }

    internal fun onMapDesignTypeChange(value: TomTomMapDesignType) {
        _mapDesignType = value
    }

    /** 戻り型をこのプロバイダのホルダーへ絞る（アプリが `?.map` を取れる形を保つため）。 */
    override fun getMapViewHolder(): TomTomMapViewHolder? = super.getMapViewHolder() as? TomTomMapViewHolder

    internal fun updateCameraPosition(cameraPosition: MapCameraPosition) {
        setCameraPositionInternal(cameraPosition)
    }
}

// TomTomMapViewSaver implementation
class TomTomMapViewSaver : BaseMapViewSaver<TomTomMapViewState>() {
    override fun saveMapDesign(
        state: TomTomMapViewState,
        bundle: Bundle,
    ) {
        val design = state.mapDesignType
        bundle.putString("id", design.id)
        // 定義済み（Standard など）以外は、地図に読み込ませる StyleDescriptor（Parcelable）ごと
        // 保存して Custom で戻す。id だけだと Custom は Standard に化け、attributionRules も落ちる。
        if (design is TomTomMapDesign && design !== TomTomMapDesign.create(design.id)) {
            bundle.putParcelable(KEY_STYLE_DESCRIPTOR, design.styleDescriptor)
        }
        MapViewStateBundle.putAttributionRules(bundle, design.attributionRules)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): TomTomMapViewState =
        TomTomMapViewState(
            id = stateId,
            mapDesignType = restoreDesign(mapDesignBundle),
            cameraPosition = cameraPosition,
        )

    private fun restoreDesign(bundle: Bundle?): TomTomMapDesign {
        val id = bundle?.getString("id") ?: return TomTomMapDesign.Standard
        val rules = MapViewStateBundle.getAttributionRules(bundle)

        @Suppress("DEPRECATION")
        val descriptor = bundle.getParcelable<StyleDescriptor>(KEY_STYLE_DESCRIPTOR)
        val preset = TomTomMapDesign.create(id)
        return when {
            descriptor != null -> TomTomMapDesign.Custom(id, descriptor, rules)
            rules.isNotEmpty() -> TomTomMapDesign.Custom(id, preset.styleDescriptor, rules)
            else -> preset
        }
    }

    override fun getStateId(state: TomTomMapViewState): String = state.id
}

// 公開 API の門番（javap）にコンパニオンの定数が載らないよう、ファイル private にする。
private const val KEY_STYLE_DESCRIPTOR = "styleDescriptor"

@Composable
fun rememberTomTomMapViewState(
    mapDesign: TomTomMapDesign = TomTomMapDesign.Standard,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): TomTomMapViewState {
    val stateId by rememberSaveable {
        val uuid = UUID.randomUUID().toString()
        mutableStateOf(uuid)
    }
    val state =
        rememberSaveable(
            stateSaver = TomTomMapViewSaver().createSaver(),
        ) {
            mutableStateOf(
                TomTomMapViewState(
                    id = stateId,
                    mapDesignType = mapDesign,
                    cameraPosition = MapCameraPosition.from(cameraPosition),
                ),
            )
        }

    return state.value
}
