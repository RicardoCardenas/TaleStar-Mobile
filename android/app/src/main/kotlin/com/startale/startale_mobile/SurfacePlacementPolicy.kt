package com.startale.startale_mobile

import kotlin.math.sqrt

/** Keeps placement usable before ARCore has grown a complete plane polygon. */
object SurfacePlacementPolicy {
    enum class Source(val priority: Int, val estimated: Boolean) {
        CONFIRMED_PLANE(0, false), PLANE_EXTENT(1, true),
        DEPTH_SURFACE(2, false), ORIENTED_POINT(3, true),
        INSTANT_FULL(4, false), INSTANT_PLACEMENT(5, true)
    }

    fun eligible(distance: Float, normalY: Float, tracking: Boolean): Boolean =
        tracking && distance.isFinite() && distance in 0.15f..3f && normalY >= 0.75f

    fun <T> choose(candidates: List<Pair<T, Source>>): Pair<T, Source>? =
        candidates.minByOrNull { it.second.priority }

    /** A manual estimate is a fixed world-space position, never an overlay following a phone. */
    fun estimate(camera: FloatArray, direction: FloatArray, distance: Float): FloatArray? {
        if(!distance.isFinite() || camera.any { !it.isFinite() } || direction.any { !it.isFinite() }) return null
        val length=sqrt(direction.sumOf { (it*it).toDouble() }).toFloat()
        if(length<.001f || direction[1]/length>-.08f) return null
        val metres=distance.coerceIn(.25f,2f)
        return FloatArray(3) { camera[it]+direction[it]/length*metres }
    }
}
