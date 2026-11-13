package dev.gbot.app.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * One place for how the app moves. Everything is short and lands firmly:
 * the screen is ready before the motion has finished explaining itself,
 * and springs settle instead of drifting.
 */
object Motion {
    /** Material's emphasized decelerate: fast out of the gate, soft landing. */
    val Out = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    const val FAST = 90
    const val QUICK = 140
    const val SCREEN = 170

    fun <T> fast() = tween<T>(FAST, easing = Out)
    fun <T> quick() = tween<T>(QUICK, easing = Out)
    fun <T> screenIn() = tween<T>(SCREEN, easing = Out)
    /** Leaving also starts at full speed: back must answer the thumb at once. */
    fun <T> screenOut() = tween<T>(SCREEN - 20, easing = Out)

    /** A press: down almost at once, back up with a hint of bounce. */
    val pressDown = spring<Float>(dampingRatio = 1f, stiffness = 2600f)
    val pressUp = spring<Float>(dampingRatio = 0.5f, stiffness = 900f)

    /** Small UI parts (chevrons, badges, rings): quick and critically damped. */
    fun <T> snap(threshold: T? = null) = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow * 4, visibilityThreshold = threshold)

    /** Sheets of content opening (expand, toast): a firm spring with a touch of life. */
    val sizeSpring = spring(dampingRatio = 0.9f, stiffness = 1100f, visibilityThreshold = IntSize.VisibilityThreshold)
    val offsetSpring = spring(dampingRatio = 0.8f, stiffness = 900f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** List items moving when the roster re-sorts. */
    val placement: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.85f, stiffness = 700f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /**
     * The avatar flying between roster and chat. Compose's default is a slow
     * StiffnessMediumLow spring that outlasts the screen transition; this one
     * arrives with the screen, overshooting just enough to feel thrown.
     */
    @OptIn(ExperimentalSharedTransitionApi::class)
    val avatarFly = BoundsTransform { _, _ -> spring(dampingRatio = 0.82f, stiffness = 650f, visibilityThreshold = Rect.VisibilityThreshold) }
}
