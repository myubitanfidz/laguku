package com.laguku.player.ui.motion

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.laguku.player.ui.glass.LiquidGlassDrawable

/**
 * Centralized Apple Liquid Glass press physics & optics animator:
 * - Scales compact icons/pills to 0.92f and wide cards/pills to 0.95f on ACTION_DOWN
 *   with snappy compression (500f / 0.72f) and elastic release (380f / 0.66f) to 1.0f.
 * - Drives pressFraction & fingertip touch coordinates into LiquidGlassDrawable with zero allocations.
 * - Clears default Material foreground ripple so glass refraction stays unobstructed.
 */
object LiquidGlassPressAnimator {

    enum class PressStyle(val pressedScale: Float) {
        COMPACT(COMPACT_PRESS_SCALE),
        WIDE(WIDE_PRESS_SCALE)
    }

    const val COMPACT_PRESS_SCALE = 0.92f
    const val WIDE_PRESS_SCALE = 0.95f
    const val PRESS_STIFFNESS = 500f
    const val PRESS_DAMPING_RATIO = 0.72f
    const val RELEASE_STIFFNESS = 380f
    const val RELEASE_DAMPING_RATIO = 0.66f
    const val CHANNEL_PRESS_SCALE = 31
    const val CHANNEL_PRESS_OPTICS = 32

    /**
     * Pure JVM-testable state machine mirroring touch-driven press compression & release targets.
     */
    class PressPhysicsState(val style: PressStyle = PressStyle.COMPACT) {
        var targetScale: Float = 1f
            private set
        var targetPressFraction: Float = 0f
            private set
        var activeStiffness: Float = RELEASE_STIFFNESS
            private set
        var activeDampingRatio: Float = RELEASE_DAMPING_RATIO
            private set
        var lastTouchX: Float = 0f
            private set
        var lastTouchY: Float = 0f
            private set

        fun onActionDown(x: Float, y: Float) {
            lastTouchX = x
            lastTouchY = y
            targetScale = style.pressedScale
            targetPressFraction = 1f
            activeStiffness = PRESS_STIFFNESS
            activeDampingRatio = PRESS_DAMPING_RATIO
        }

        fun onActionMove(x: Float, y: Float) {
            lastTouchX = x
            lastTouchY = y
        }

        fun onActionUp() {
            targetScale = 1f
            targetPressFraction = 0f
            activeStiffness = RELEASE_STIFFNESS
            activeDampingRatio = RELEASE_DAMPING_RATIO
        }

        fun onActionCancel() {
            onActionUp()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        view: View,
        style: PressStyle = PressStyle.COMPACT,
        glassDrawable: LiquidGlassDrawable? = null
    ) {
        view.foreground = null
        view.setOnTouchListener { v, event ->
            handleTouchEvent(v, event, style, glassDrawable)
            false
        }
    }

    fun handleTouchEvent(
        view: View,
        event: MotionEvent,
        style: PressStyle = PressStyle.COMPACT,
        glassDrawable: LiquidGlassDrawable? = null
    ) {
        val glass = glassDrawable ?: (view.background as? LiquidGlassDrawable)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                animatePressDown(view, style, glass, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                glass?.setPressTouchPosition(event.x, event.y)
                SpringAnimatorHelper.broadcastNeighborLightBleed(view, event.x, event.y, active = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animatePressRelease(view, glass, event.x, event.y)
            }
        }
    }

    fun animatePressDown(
        view: View,
        style: PressStyle,
        glass: LiquidGlassDrawable?,
        touchX: Float,
        touchY: Float
    ) {
        if (SpringAnimatorHelper.isReducedMotionEnabled(view.context)) {
            view.scaleX = 1f
            view.scaleY = 1f
            glass?.setPressState(0f, touchX, touchY)
            SpringAnimatorHelper.broadcastNeighborLightBleed(view, touchX, touchY, active = false)
            return
        }

        SpringAnimatorHelper.animateSpring(
            key = view,
            start = view.scaleX,
            target = style.pressedScale,
            stiffness = PRESS_STIFFNESS,
            dampingRatio = PRESS_DAMPING_RATIO,
            channel = CHANNEL_PRESS_SCALE,
            onUpdate = { s ->
                view.scaleX = s
                view.scaleY = s
            }
        )
        if (glass != null) {
            glass.setPressTouchPosition(touchX, touchY)
            SpringAnimatorHelper.animateSpring(
                key = glass,
                start = glass.getPressFraction(),
                target = 1f,
                stiffness = PRESS_STIFFNESS,
                dampingRatio = PRESS_DAMPING_RATIO,
                channel = CHANNEL_PRESS_OPTICS,
                onUpdate = { f -> glass.setPressState(f) }
            )
        }
        SpringAnimatorHelper.broadcastNeighborLightBleed(view, touchX, touchY, active = true)
    }

    fun animatePressRelease(
        view: View,
        glass: LiquidGlassDrawable?,
        touchX: Float,
        touchY: Float
    ) {
        if (SpringAnimatorHelper.isReducedMotionEnabled(view.context)) {
            SpringAnimatorHelper.cancelSpring(view, CHANNEL_PRESS_SCALE)
            view.scaleX = 1f
            view.scaleY = 1f
            if (glass != null) {
                SpringAnimatorHelper.cancelSpring(glass, CHANNEL_PRESS_OPTICS)
                glass.setPressState(0f, touchX, touchY)
            }
            SpringAnimatorHelper.broadcastNeighborLightBleed(view, touchX, touchY, active = false)
            return
        }

        SpringAnimatorHelper.animateSpring(
            key = view,
            start = view.scaleX,
            target = 1f,
            stiffness = RELEASE_STIFFNESS,
            dampingRatio = RELEASE_DAMPING_RATIO,
            channel = CHANNEL_PRESS_SCALE,
            onUpdate = { s ->
                view.scaleX = s
                view.scaleY = s
            }
        )
        if (glass != null) {
            glass.setPressTouchPosition(touchX, touchY)
            SpringAnimatorHelper.animateSpring(
                key = glass,
                start = glass.getPressFraction(),
                target = 0f,
                stiffness = RELEASE_STIFFNESS,
                dampingRatio = RELEASE_DAMPING_RATIO,
                channel = CHANNEL_PRESS_OPTICS,
                onUpdate = { f -> glass.setPressState(f) }
            )
        }
        SpringAnimatorHelper.broadcastNeighborLightBleed(view, touchX, touchY, active = false)
    }
}
