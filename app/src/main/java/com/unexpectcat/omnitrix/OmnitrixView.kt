package com.unexpectcat.omnitrix

import android.content.Context
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Hosts [OmnitrixFace] in the launcher app, adding swipes and the crown on top of taps. */
class OmnitrixView(context: Context) : View(context) {

    private val face = OmnitrixFace(context)
    private var running = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scrollFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    private var downX = 0f
    private var downY = 0f
    private var upX = 0f
    private var upY = 0f
    private var rotaryAccum = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun start() {
        running = true
        face.start()
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        face.stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = face.setSize(w, h)

    override fun onDraw(canvas: Canvas) {
        val now = LocalTime.now()
        face.draw(canvas, now.hour, now.minute, now.second, ambient = false)
        if (running) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                upX = event.x
                upY = event.y
                val dx = upX - downX
                val dy = upY - downY
                if (abs(dx) > width * 0.12f && abs(dx) > abs(dy)) {
                    face.swipe(if (dx < 0) 1 else -1)
                } else if (hypot(dx, dy) < touchSlop * 2) {
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        face.tap(upX, upY)
        return true
    }

    /** Crown / bezel rotation scrolls through aliens too. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotaryAccum += -event.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor
            if (abs(rotaryAccum) > width * 0.12f) {
                face.swipe(sign(rotaryAccum).toInt())
                rotaryAccum = 0f
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
