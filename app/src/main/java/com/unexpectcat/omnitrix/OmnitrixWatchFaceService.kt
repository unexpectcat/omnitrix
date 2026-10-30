package com.unexpectcat.omnitrix

import android.graphics.Canvas
import android.graphics.Rect
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** The Omnitrix as a Wear OS watch face. */
class OmnitrixWatchFaceService : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = OmnitrixRenderer(OmnitrixFace(this), surfaceHolder, watchState, currentUserStyleRepository)
        return WatchFace(WatchFaceType.ANALOG, renderer).setTapListener(renderer)
    }
}

private class OmnitrixRenderer(
    private val face: OmnitrixFace,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    styleRepository: CurrentUserStyleRepository,
) : Renderer.CanvasRenderer2<OmnitrixRenderer.Assets>(
    surfaceHolder, styleRepository, watchState, CanvasType.HARDWARE,
    FRAME_MILLIS, clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
), WatchFace.TapListener {

    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val size = Rect()

    init {
        // Sensors only run while the face is on screen and awake; ambient is still and sensor-free.
        scope.launch {
            watchState.isVisible.combine(watchState.isAmbient) { visible, ambient -> visible == true && ambient != true }
                .collect { awake -> if (awake) face.start() else face.stop() }
        }
    }

    override suspend fun createSharedAssets() = Assets()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        if (bounds != size) {
            size.set(bounds)
            face.setSize(bounds.width(), bounds.height())
        }
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        face.draw(canvas, zonedDateTime.hour, zonedDateTime.minute, zonedDateTime.second, ambient)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        renderParameters.highlightLayer?.let { canvas.drawColor(it.backgroundTint) }
    }

    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
        if (tapType == TapType.UP) face.tap(tapEvent.xPos.toFloat(), tapEvent.yPos.toFloat())
    }

    override fun onDestroy() {
        scope.cancel()
        face.stop()
        super.onDestroy()
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
