package com.startale.startale_mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.io.File

/** Real GLES2 rendering on Android, without a camera or simulated ARCore tracking. */
@RunWith(AndroidJUnit4::class)
class StoryRendererTest {
    @Test fun texturedPageRendersIn3dAndDisappearsWhenTheCameraLooksAway() {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0)
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,384,EGL14.EGL_HEIGHT,768,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context))
        val renderer=StorySceneRenderer()
        try {
            val app=InstrumentationRegistry.getInstrumentation().targetContext
            val image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70,120,180)) }
            renderer.create()
            renderer.setPage(StorySceneRenderer.pageBitmap(image,"A page rendered in world space.",1,2))
            image.recycle()
            GLES20.glViewport(0,0,384,768)
            val anchor=FloatArray(16).apply { Matrix.setIdentityM(this,0) }
            val vp=FloatArray(16)
            val projection=FloatArray(16).apply { Matrix.perspectiveM(this,0,45f,.5f,.01f,20f) }
            val view=FloatArray(16)
            val body=StoryDynamics()
            fun draw(eyeX: Float, away: Boolean=false, preview: Boolean=false): ByteBuffer {
                GLES20.glClearColor(.08f,.07f,.12f,1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                Matrix.setLookAtM(view,0,eyeX,.27f,.85f,eyeX,.15f,if(away) 2f else 0f,0f,1f,0f)
                Matrix.multiplyMM(vp,0,projection,0,view,0)
                renderer.draw(vp,anchor,body,0f,0f,preview,.5f)
                GLES20.glFinish()
                assertEquals("GL error",GLES20.GL_NO_ERROR,GLES20.glGetError())
                return ByteBuffer.allocateDirect(384*768*4).apply { GLES20.glReadPixels(0,0,384,768,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,this) }
            }
            fun whites(pixels: ByteBuffer): List<Int> = (0 until 384*768).filter { i ->
                (pixels.get(i*4).toInt() and 255)>230 && (pixels.get(i*4+1).toInt() and 255)>230 && (pixels.get(i*4+2).toInt() and 255)>230
            }
            val initial=whites(draw(0f))
            assertTrue("Upright page should be visible",initial.size>1000)
            val moved=whites(draw(.15f))
            assertTrue(moved.isNotEmpty())
            assertTrue("A world anchor must move left on screen as the camera moves right",moved.map { it%384 }.average()<initial.map { it%384 }.average()-15)
            assertEquals("The page must not follow a camera looking away",0,whites(draw(0f,away=true)).size)
            val preview=draw(0f,preview=true)
            assertTrue(whites(preview).size>1000)
            val colors=IntArray(384*768) { i ->
                val source=((767-i/384)*384+i%384)*4
                android.graphics.Color.rgb(preview.get(source).toInt() and 255,preview.get(source+1).toInt() and 255,preview.get(source+2).toInt() and 255)
            }
            val screenshot=Bitmap.createBitmap(colors,384,768,Bitmap.Config.ARGB_8888)
            File(app.getExternalFilesDir(null),"ar-renderer-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
            body.tilt=-StoryDynamics.HALF_PI
            assertTrue("A fallen page should still render on the table",whites(draw(0f,preview=true)).isNotEmpty())
        } finally {
            renderer.dispose()
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface)
            EGL14.eglDestroyContext(display,context)
            EGL14.eglTerminate(display)
        }
    }
}
