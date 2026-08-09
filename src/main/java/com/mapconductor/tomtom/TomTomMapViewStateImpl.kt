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
import com.mapconductor.core.map.MapViewStateInterface
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
    private var controller: TomTomMapViewControllerInterface? = null

    internal fun setController(controller: TomTomMapViewControllerInterface) {
        this.controller = controller
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
        bundle.putString("id", state.mapDesignType.id)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): TomTomMapViewState =
        TomTomMapViewState(
            id = stateId,
            mapDesignType =
                TomTomMapDesign.create(
                    id = mapDesignBundle?.getString("id") ?: TomTomMapDesign.Standard.id,
                ),
            cameraPosition = cameraPosition,
        )

    override fun getStateId(state: TomTomMapViewState): String = state.id
}

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
