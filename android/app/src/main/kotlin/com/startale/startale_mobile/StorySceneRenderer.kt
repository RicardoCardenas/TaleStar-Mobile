package com.startale.startale_mobile

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Textured page with a front, back and physical thickness, rendered in world space. */
class StorySceneRenderer {
    private var program = 0
    private var texture = 0
    private var bitmap: Bitmap? = null
    private var textureDirty = false
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val surface = FloatArray(16)
    private val previewView = FloatArray(16)
    private val previewProjection = FloatArray(16)
    private val previewVp = FloatArray(16)

    fun create() {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 u_Mvp;
            attribute vec3 a_Position;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            void main() { v_Uv = a_Uv; gl_Position = u_Mvp * vec4(a_Position, 1.0); }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            uniform float u_Textured;
            varying vec2 v_Uv;
            void main() { gl_FragColor = mix(u_Color, texture2D(u_Texture, v_Uv), u_Textured); }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textureDirty = true
    }

    fun setPage(page: Bitmap) {
        bitmap?.recycle()
        bitmap = page
        textureDirty = true
    }

    fun draw(vp: FloatArray, anchor: FloatArray, dynamics: StoryDynamics, x: Float, z: Float, preview: Boolean, aspect: Float) {
        if (program == 0 || bitmap == null) return
        if (textureDirty) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureDirty = false
        }
        val activeVp: FloatArray
        if (preview) {
            Matrix.setLookAtM(previewView, 0, 0f, 0.27f, 0.85f, 0f, 0.15f, 0f, 0f, 1f, 0f)
            Matrix.perspectiveM(previewProjection, 0, 45f, aspect, 0.01f, 20f)
            Matrix.multiplyMM(previewVp, 0, previewProjection, 0, previewView, 0)
            activeVp = previewVp
            Matrix.setIdentityM(surface, 0)
        } else {
            activeVp = vp
            System.arraycopy(anchor, 0, surface, 0, 16)
            Matrix.translateM(surface, 0, x, 0f, z)
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (preview) {
            Matrix.multiplyMM(mvp, 0, activeVp, 0, surface, 0)
            face(floatArrayOf(-2f,0f,-2f, -2f,0f,2f, 2f,0f,-2f, 2f,0f,2f), floatArrayOf(.18f,.17f,.23f,1f))
        }
        // A translucent contact patch stays on the table, never follows the camera.
        Matrix.multiplyMM(mvp, 0, activeVp, 0, surface, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        val w = dynamics.width * .6f
        face(floatArrayOf(-w,.0005f,-.04f, -w,.0005f,.04f, w,.0005f,-.04f, w,.0005f,.04f), floatArrayOf(0f,0f,0f,.18f))
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        System.arraycopy(surface, 0, model, 0, 16)
        Matrix.translateM(model, 0, 0f, dynamics.contactHeight(), 0f)
        Matrix.rotateM(model, 0, dynamics.yaw * 180f / Math.PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, dynamics.tilt * 180f / Math.PI.toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, activeVp, 0, model, 0)
        val l = -dynamics.width / 2f
        val r = -l
        val h = dynamics.height
        val d = dynamics.thickness / 2f
        face(floatArrayOf(l,h,-d, l,0f,-d, r,h,-d, r,0f,-d), floatArrayOf(.30f,.20f,.61f,1f))
        face(floatArrayOf(l,h,d, l,0f,d, l,h,-d, l,0f,-d), floatArrayOf(.8f,.77f,.91f,1f))
        face(floatArrayOf(r,h,-d, r,0f,-d, r,h,d, r,0f,d), floatArrayOf(.8f,.77f,.91f,1f))
        face(floatArrayOf(l,h,-d, r,h,-d, l,h,d, r,h,d), floatArrayOf(.94f,.92f,.98f,1f))
        face(floatArrayOf(l,0f,-d, r,0f,-d, l,0f,d, r,0f,d), floatArrayOf(.8f,.77f,.91f,1f))
        face(floatArrayOf(l,h,d, l,0f,d, r,h,d, r,0f,d), floatArrayOf(1f,1f,1f,1f), true)
    }

    private fun face(vertices: FloatArray, color: FloatArray, textured: Boolean = false) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"u_Mvp"),1,false,mvp,0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program,"u_Color"),1,color,0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"u_Textured"),if(textured) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"u_Texture"),0)
        val position = GLES20.glGetAttribLocation(program,"a_Position")
        val uv = GLES20.glGetAttribLocation(program,"a_Uv")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,buffer(vertices))
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,buffer(floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f)))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
    }

    fun dispose() { bitmap?.recycle(); bitmap = null }

    private fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(id) }
        return id
    }

    companion object {
        /** Rasterize only on page changes; camera frames only update world/view matrices. */
        fun pageBitmap(image: Bitmap?, text: String, page: Int, total: Int): Bitmap {
            val result = Bitmap.createBitmap(768, 1152, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val imageRect = RectF(30f,30f,738f,738f)
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(imageRect, 24f, 24f, Path.Direction.CW) })
            if (image != null) {
                val side = minOf(image.width,image.height)
                val left = (image.width-side)/2
                val top = (image.height-side)/2
                canvas.drawBitmap(image,Rect(left,top,left+side,top+side),imageRect,paint)
            } else { paint.color = Color.rgb(108,80,220); canvas.drawRect(imageRect,paint) }
            canvas.restoreToCount(save)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(30,31,39); textSize=32f; typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            var layout: StaticLayout
            do {
                layout = StaticLayout.Builder.obtain(text,0,text.length,textPaint,660).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(8f,1f).setIncludePad(false).build()
                if (layout.height <= 290 || textPaint.textSize <= 18f) break
                textPaint.textSize -= 2f
            } while(true)
            canvas.save()
            canvas.translate(54f,775f)
            layout.draw(canvas)
            canvas.restore()
            textPaint.textSize=23f; textPaint.color=Color.rgb(110,100,140)
            textPaint.textAlign=Paint.Align.CENTER
            canvas.drawText("$page / $total",384f,1110f,textPaint)
            return result
        }
    }
}
