package com.startale.startale_mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class SurfacePlacementPolicyTest {
    @Test fun partialTableOrInstantPointDoesNotWaitForACompletePolygon() {
        assertEquals("partial",SurfacePlacementPolicy.choose(listOf("instant" to SurfacePlacementPolicy.Source.INSTANT_PLACEMENT,"partial" to SurfacePlacementPolicy.Source.PLANE_EXTENT))?.first)
        assertEquals("instant",SurfacePlacementPolicy.choose(listOf("instant" to SurfacePlacementPolicy.Source.INSTANT_PLACEMENT))?.first)
    }
    @Test fun confirmedTableIsPreferredToEstimatesRegardlessOfOrder() {
        val options=SurfacePlacementPolicy.Source.entries.reversed().map { it.name to it }
        assertEquals(SurfacePlacementPolicy.Source.CONFIRMED_PLANE,SurfacePlacementPolicy.choose(options)?.second)
        assertNull(SurfacePlacementPolicy.choose(emptyList<Pair<String,SurfacePlacementPolicy.Source>>()))
    }
    @Test fun wallsStoppedTrackingAndUnusableDistancesAreRejected() {
        assertTrue(SurfacePlacementPolicy.eligible(.75f,1f,true))
        assertFalse(SurfacePlacementPolicy.eligible(.75f,0f,true))
        assertFalse(SurfacePlacementPolicy.eligible(.75f,-1f,true))
        assertFalse(SurfacePlacementPolicy.eligible(.75f,1f,false))
        for(distance in listOf(0f,-1f,4f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertFalse(SurfacePlacementPolicy.eligible(distance,1f,true))
        }
    }
    @Test fun manualPlacementUsesTheUserDistanceInWorldSpace() {
        val camera=floatArrayOf(2f,1f,3f)
        val point=SurfacePlacementPolicy.estimate(camera,floatArrayOf(0f,-3f,-4f),.75f)!!
        assertArrayEquals(floatArrayOf(2f,.55f,2.4f),point,.0001f)
        val distance=sqrt(point.indices.sumOf { i -> ((point[i]-camera[i])*(point[i]-camera[i])).toDouble() })
        assertEquals(.75,distance,.0001)
        camera[0]=20f
        assertEquals(2f,point[0],0f)
    }
    @Test fun manualPlacementRejectsSkyHorizontalAndInvalidRays() {
        val camera=floatArrayOf(0f,1f,0f)
        for(direction in listOf(floatArrayOf(0f,1f,-1f),floatArrayOf(0f,0f,-1f),floatArrayOf(0f,0f,0f))) {
            assertNull(SurfacePlacementPolicy.estimate(camera,direction,.75f))
        }
        assertNull(SurfacePlacementPolicy.estimate(camera,floatArrayOf(0f,-1f,-1f),Float.NaN))
    }
}
