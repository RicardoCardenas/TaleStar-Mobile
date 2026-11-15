package com.startale.startale_mobile

import kotlin.math.*

/** A page hinged at its bottom edge on the table. Units are metres and radians. */
class StoryDynamics {
    var scale = 1f
    var yaw = 0f
    var tilt = 0f
    var angularVelocity = 0f
    var held = false
    var physics = true
    val height get() = 0.33f * scale
    val width get() = 0.22f * scale
    val thickness get() = 0.006f * scale

    fun manipulate(newScale: Float, deltaYaw: Float, deltaTilt: Float) {
        scale = newScale.coerceIn(0.5f, 2f)
        yaw = (yaw + deltaYaw) % (2f * PI.toFloat())
        tilt = (tilt + deltaTilt).coerceIn(-HALF_PI, HALF_PI)
        angularVelocity = 0f
    }

    fun standUp() {
        tilt = 0f
        angularVelocity = 0f
        held = false
    }

    fun advance(elapsedSeconds: Float) {
        if (held || !physics || abs(tilt) < 0.035f) return
        // Fixed substeps keep contact stable even after a slow camera frame.
        val steps = ceil(elapsedSeconds.coerceIn(0f, 0.05f) / (1f / 120f)).toInt().coerceAtLeast(1)
        val dt = elapsedSeconds.coerceIn(0f, 0.05f) / steps
        repeat(steps) {
            angularVelocity += (3f * 9.81f / (2f * height) * sin(tilt) - 1.8f * angularVelocity) * dt
            tilt += angularVelocity * dt
            if (abs(tilt) >= HALF_PI) {
                tilt = sign(tilt) * HALF_PI
                angularVelocity = if (abs(angularVelocity) < 0.8f) 0f else -angularVelocity * 0.12f
            }
        }
    }

    /** Lifts the hinge just enough to prevent either face penetrating the table. */
    fun contactHeight() = thickness * 0.5f * abs(sin(tilt)) + 0.001f

    companion object { val HALF_PI = (PI / 2.0).toFloat() }
}
