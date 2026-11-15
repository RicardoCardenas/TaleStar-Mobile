package com.startale.startale_mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StoryDynamicsTest {
    @Test fun uprightPageRemainsStanding() {
        val body=StoryDynamics()
        repeat(600) { body.advance(1f/60f) }
        assertEquals(0f,body.tilt,0f)
    }

    @Test fun tippedPageFallsAndSettlesOnEitherSideOfTheTable() {
        for(direction in listOf(-1f,1f)) {
            val body=StoryDynamics().apply { tilt=.2f*direction }
            repeat(600) { body.advance(1f/60f) }
            assertEquals(direction*StoryDynamics.HALF_PI,body.tilt,.001f)
            assertEquals(0f,body.angularVelocity,.01f)
        }
    }

    @Test fun pageCornersNeverPenetrateTheTableDuringAFall() {
        val body=StoryDynamics().apply { tilt=-.1f }
        repeat(600) {
            body.advance(1f/60f)
            for(y in listOf(0f,body.height)) for(z in listOf(-body.thickness/2,body.thickness/2)) {
                val worldY=body.contactHeight()+y*cos(body.tilt)-z*sin(body.tilt)
                assertTrue("A corner went below the table: $worldY",worldY>=-.000001f)
            }
        }
    }

    @Test fun noPhysicsAndHoldingBothFreezeTheTilt() {
        val body=StoryDynamics().apply { tilt=.6f; physics=false }
        repeat(120) { body.advance(.05f) }
        assertEquals(.6f,body.tilt,0f)
        body.physics=true; body.held=true
        repeat(120) { body.advance(.05f) }
        assertEquals(.6f,body.tilt,0f)
    }

    @Test fun standUpKeepsSizeAndYawAndCancelsTheFall() {
        val body=StoryDynamics().apply { tilt=.8f; angularVelocity=2f; scale=1.4f; yaw=2.1f }
        body.standUp()
        repeat(120) { body.advance(1f/60f) }
        assertEquals(0f,body.tilt,0f)
        assertEquals(0f,body.angularVelocity,0f)
        assertEquals(1.4f,body.scale,0f)
        assertEquals(2.1f,body.yaw,0f)
    }

    @Test fun rotationIsNotLimitedToASlightScreenTiltAndLongFramesRemainStable() {
        val body=StoryDynamics()
        body.manipulate(10f,3f,10f)
        assertEquals(2f,body.scale,0f)
        assertEquals(3f,body.yaw,0f)
        assertEquals(StoryDynamics.HALF_PI,body.tilt,0f)
        repeat(300) { body.advance(3f) }
        assertTrue(body.tilt.isFinite())
        assertTrue(abs(body.tilt)<=StoryDynamics.HALF_PI)
    }
}
