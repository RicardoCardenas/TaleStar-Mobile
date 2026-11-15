package com.startale.startale_mobile

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.View
import com.google.ar.core.*
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** ARCore and the story share one GL scene. No screen-space story overlay is used. */
class ArCorePlatformView(
    private val context: Context,
    private val activity: Activity,
    id: Int,
    creationParams: Map<String?, Any?>?,
    messenger: BinaryMessenger,
    private val channel: MethodChannel,
    private val onDisposed: ((ArCorePlatformView) -> Unit)? = null
) : PlatformView, GLSurfaceView.Renderer {
    private val glSurfaceView = GLSurfaceView(context)
    private val background = BackgroundRenderer()
    private val storyRenderer = StorySceneRenderer()
    private val rotation = DisplayRotationHelper(activity)
    private val pageExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var disposed = false
    @Volatile private var pageVersion = 0
    private var session: Session? = null
    private var configured = false
    private var frame: Frame? = null
    private var anchor: Anchor? = null
    private var table: Plane? = null
    private var placementType = "NONE"
    private var instantPoint: InstantPlacementPoint? = null
    private var approximateDistance = 0.75f
    private var placed = false
    private var preview = false
    private var dynamics = StoryDynamics()
    private val previewDynamics = StoryDynamics().apply { physics = false }
    private var offsetX = 0f
    private var offsetZ = 0f
    private var grabOffset = floatArrayOf(0f,0f)
    private var lastTime = 0L
    private var lastEmit = 0L
    private var events = 0
    private var width = 1080
    private var height = 2400
    private val viewMatrix = FloatArray(16)
    private val projection = FloatArray(16)
    private val vp = FloatArray(16)
    private val anchorMatrix = FloatArray(16)

    init {
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8,8,8,8,16,0)
        glSurfaceView.setRenderer(this)
    }
    override fun getView(): View = glSurfaceView

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.createOnGlThread()
        storyRenderer.create()
        try {
            if (session == null) {
                session = Session(activity).also { ar ->
                    ar.configure(Config(ar).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                        if(ar.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        }
                    })
                }
            }
            session?.setCameraTextureName(background.textureId)
            rotation.onResume()
            session?.resume()
            configured = true
        } catch (e: Exception) { android.util.Log.e("TaleStarAR","Session initialization",e) }
    }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width=w; height=h
        rotation.onSurfaceChanged(w,h)
        GLES20.glViewport(0,0,w,h)
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(.08f,.07f,.12f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val now = System.nanoTime()
        val dt = if(lastTime == 0L) 0f else (now-lastTime)/1_000_000_000f
        lastTime = now
        if (preview) {
            Matrix.setIdentityM(anchorMatrix,0)
            storyRenderer.draw(vp,anchorMatrix,previewDynamics,0f,0f,true,width.toFloat()/height)
            return
        }
        val ar = session ?: return
        if (!configured || disposed) return
        try {
            rotation.updateSessionIfNeeded(ar)
            val current = ar.update()
            frame = current
            val camera = current.camera
            background.draw(current)
            camera.getViewMatrix(viewMatrix,0)
            camera.getProjectionMatrix(projection,0,.01f,50f)
            Matrix.multiplyMM(vp,0,projection,0,viewMatrix,0)
            val tracking = camera.trackingState == TrackingState.TRACKING
            val attached = anchor
            if(instantPoint?.trackingMethod==InstantPlacementPoint.TrackingMethod.FULL_TRACKING) {
                placementType="INSTANT_FULL"
            }
            if(placed && tracking && attached?.trackingState == TrackingState.TRACKING) {
                attached.pose.toMatrix(anchorMatrix,0)
                dynamics.advance(dt)
                storyRenderer.draw(vp,anchorMatrix,dynamics,offsetX,offsetZ,false,width.toFloat()/height)
            }
            if(now-lastEmit > 120_000_000L) {
                lastEmit=now; events++
                val planes=ar.getAllTrackables(Plane::class.java)
                val valid=planes.filter { it.subsumedBy==null && it.type==Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState==TrackingState.TRACKING }
                val candidate=if(tracking && !placed) placementCandidate(.5f,.5f) else null
                val planesData=valid.map { p -> mapOf("id" to p.hashCode().toString(),"type" to "horizontalUpward","extentX" to p.extentX.toDouble(),"extentZ" to p.extentZ.toDouble(),"centerPoseX" to p.centerPose.tx().toDouble(),"centerPoseY" to p.centerPose.ty().toDouble(),"centerPoseZ" to p.centerPose.tz().toDouble()) }
                val status=mapOf(
                    "sessionState" to "ACTIVE", "cameraTrackingState" to camera.trackingState.name,
                    "trackingFailureReason" to if(tracking) "NONE" else camera.trackingFailureReason.name,
                    "frameTimestampNs" to current.timestamp,"eventsSentCount" to events,
                    "knownPlanesCount" to planes.size,"validPlanesCount" to valid.size,
                    "hasValidPlacementPoint" to (candidate!=null),"hasHitAtReticle" to (candidate!=null),
                    "placementPointType" to (if(placed) placementType else candidate?.second?.name ?: "NONE"),
                    "hitDistanceMeters" to (candidate?.first?.distance?.toDouble() ?: 0.0),
                    "planeDiscardReason" to if(valid.isEmpty()) "Aún no hay un contorno de mesa; buscando puntos de apoyo" else "Apunta al interior de la superficie detectada",
                    "isAnchorTracking" to (tracking && attached?.trackingState==TrackingState.TRACKING),
                    "viewportWidth" to width.toDouble(),"viewportHeight" to height.toDouble()
                )
                activity.runOnUiThread { if(!disposed) { channel.invokeMethod("onPlanesUpdated",planesData); channel.invokeMethod("onArStatusUpdated",status) } }
            }
        } catch(e: Exception) { android.util.Log.w("TaleStarAR","Transient AR frame",e) }
    }
    private fun placementCandidate(x: Float,y: Float): Pair<HitResult, SurfacePlacementPolicy.Source>? {
        val current=frame ?: return null
        val candidates=mutableListOf<Pair<HitResult, SurfacePlacementPolicy.Source>>()
        for(hit in current.hitTest(x*width,y*height)) {
            val p=hit.trackable
            val normalY=hit.hitPose.getTransformedAxis(1,1f)[1]
            if(!SurfacePlacementPolicy.eligible(hit.distance,normalY,p.trackingState==TrackingState.TRACKING)) continue
            val source=when {
                p is Plane && p.type==Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInPolygon(hit.hitPose) -> SurfacePlacementPolicy.Source.CONFIRMED_PLANE
                p is Plane && p.type==Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInExtents(hit.hitPose) -> SurfacePlacementPolicy.Source.PLANE_EXTENT
                p is DepthPoint -> SurfacePlacementPolicy.Source.DEPTH_SURFACE
                p is Point && p.orientationMode==Point.OrientationMode.ESTIMATED_SURFACE_NORMAL -> SurfacePlacementPolicy.Source.ORIENTED_POINT
                else -> null
            }
            if(source!=null) candidates.add(hit to source)
        }
        // Prefer measured geometry. Instant Placement fills the gap while the plane grows.
        if(candidates.isNotEmpty()) return SurfacePlacementPolicy.choose(candidates)
        try {
            for(hit in current.hitTestInstantPlacement(x*width,y*height,approximateDistance)) {
                val point=hit.trackable as? InstantPlacementPoint ?: continue
                if(!SurfacePlacementPolicy.eligible(hit.distance,1f,point.trackingState==TrackingState.TRACKING)) continue
                val source=if(point.trackingMethod==InstantPlacementPoint.TrackingMethod.FULL_TRACKING)
                    SurfacePlacementPolicy.Source.INSTANT_FULL else SurfacePlacementPolicy.Source.INSTANT_PLACEMENT
                candidates.add(hit to source)
            }
        } catch(e: Exception) { android.util.Log.d("TaleStarAR","Instant placement is not ready",e) }
        return SurfacePlacementPolicy.choose(candidates)
    }
    /** Hit tests and anchor mutations run on the rendering thread. */
    fun placeStory(x: Float,y: Float, result: MethodChannel.Result) = onGl(result) {
        if(frame?.camera?.trackingState != TrackingState.TRACKING) return@onGl false
        val candidate=placementCandidate(x,y) ?: return@onGl false
        val hit=candidate.first
        val replacement=if(hit.trackable is Plane || hit.trackable is InstantPlacementPoint) hit.createAnchor()
            else hit.trackable.createAnchor(Pose.makeTranslation(hit.hitPose.translation))
        attachStory(replacement,hit.trackable as? Plane,candidate.second.name)
        instantPoint=hit.trackable as? InstantPlacementPoint
        true
    }

    private fun attachStory(replacement: Anchor, plane: Plane?, source: String) {
        anchor?.detach()
        anchor=replacement
        table=plane
        instantPoint=null
        placementType=source
        dynamics=StoryDynamics()
        // Face the viewer once at placement; never billboard toward later camera movement.
        val relative=replacement.pose.inverse().compose(frame!!.camera.pose)
        dynamics.yaw=atan2(relative.tx(),relative.tz())
        offsetX=0f; offsetZ=0f; preview=false; placed=true
    }

    /** Explicit fallback: the user supplies distance; this never claims a detected table. */
    fun placeEstimatedStory(distance: Float, result: MethodChannel.Result) = onGl(result) {
        val current=frame ?: return@onGl false
        if(current.camera.trackingState!=TrackingState.TRACKING) return@onGl false
        val inverse=FloatArray(16)
        if(!Matrix.invertM(inverse,0,vp,0)) return@onGl false
        val near=FloatArray(4); val far=FloatArray(4)
        Matrix.multiplyMV(near,0,inverse,0,floatArrayOf(0f,0f,-1f,1f),0)
        Matrix.multiplyMV(far,0,inverse,0,floatArrayOf(0f,0f,1f,1f),0)
        val direction=FloatArray(3) { far[it]/far[3]-near[it]/near[3] }
        approximateDistance=distance.coerceIn(.25f,2f)
        val camera=current.camera.pose.translation
        val world=SurfacePlacementPolicy.estimate(camera,direction,distance) ?: return@onGl false
        val replacement=session?.createAnchor(Pose.makeTranslation(world)) ?: return@onGl false
        attachStory(replacement,null,"MANUAL_ESTIMATE")
        true
    }
    fun setPage(imageBytes: ByteArray?, text: String, page: Int, total: Int, result: MethodChannel.Result) {
        val version=++pageVersion
        pageExecutor.execute {
            try {
                val image=imageBytes?.let { bytes ->
                    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    var sample=1
                    while(options.outWidth/sample>1536 || options.outHeight/sample>1536) sample*=2
                    options.inJustDecodeBounds=false
                    options.inSampleSize=sample
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                        ?: throw IllegalArgumentException("No se pudo leer la ilustración del backend")
                }
                val bitmap=StorySceneRenderer.pageBitmap(image,text,page,total)
                image?.recycle()
                if(disposed) { bitmap.recycle(); return@execute }
                glSurfaceView.queueEvent {
                    if(version==pageVersion && !disposed) storyRenderer.setPage(bitmap) else bitmap.recycle()
                    activity.runOnUiThread { result.success(true) }
                }
            } catch(e: Exception) { activity.runOnUiThread { result.error("STORY_TEXTURE",e.message,null) } }
        }
    }
    fun sceneCommand(command: String, args: Map<*,*>?, result: MethodChannel.Result) = onGl(result) {
        fun number(key: String, default: Float=0f)=(args?.get(key) as? Number)?.toFloat() ?: default
        val body=if(preview) previewDynamics else dynamics
        when(command) {
            "preview" -> {
                val enabled=args?.get("enabled")==true
                if(enabled && !preview) { previewDynamics.scale=1f; previewDynamics.yaw=0f; previewDynamics.standUp() }
                if(enabled && !preview) session?.pause()
                if(!enabled && preview) session?.resume()
                dynamics.held=false; preview=enabled; lastTime=0L
            }
            "physics" -> { dynamics.physics=args?.get("enabled")==true; dynamics.angularVelocity=0f }
            "stand" -> body.standUp()
            "clear" -> {
                if(preview) session?.resume()
                anchor?.detach(); anchor=null; table=null; instantPoint=null; placed=false; preview=false
            }
            "begin" -> {
                body.held=true; body.angularVelocity=0f
                val point=tablePoint(number("x"),number("y"))
                grabOffset=if(point!=null) floatArrayOf(offsetX-point[0],offsetZ-point[1]) else floatArrayOf(0f,0f)
            }
            "end" -> body.held=false
            "transform" -> {
                body.manipulate(number("scale",body.scale),number("yaw"),number("tilt"))
                if(args?.get("move")==true && !preview) {
                    val point=tablePoint(number("x"),number("y"))
                    if(point!=null) {
                        val x=point[0]+grabOffset[0]; val z=point[1]+grabOffset[1]
                        var p=table
                        while(p?.subsumedBy!=null) p=p.subsumedBy
                        val world=anchor?.pose?.transformPoint(floatArrayOf(x,0f,z))
                        val inside=if(p==null) abs(x)<.75f && abs(z)<.75f
                            else world!=null && (p.isPoseInPolygon(Pose.makeTranslation(world)) || p.isPoseInExtents(Pose.makeTranslation(world)))
                        if(inside && abs(x)<2f && abs(z)<2f) { offsetX=x; offsetZ=z }
                    }
                }
            }
        }
        true
    }
    /** Screen ray intersected with y=0 in anchor space, without switching surfaces. */
    private fun tablePoint(x: Float,y: Float): FloatArray? {
        if(preview || frame?.camera?.trackingState!=TrackingState.TRACKING) return null
        val attached=anchor ?: return null
        val inverse=FloatArray(16)
        if(!Matrix.invertM(inverse,0,vp,0)) return null
        fun unproject(z: Float): FloatArray {
            val p=FloatArray(4)
            Matrix.multiplyMV(p,0,inverse,0,floatArrayOf(2*x-1,1-2*y,z,1f),0)
            return attached.pose.inverse().transformPoint(floatArrayOf(p[0]/p[3],p[1]/p[3],p[2]/p[3]))
        }
        val near=unproject(-1f); val far=unproject(1f)
        val dy=far[1]-near[1]
        if(abs(dy)<.0001f) return null
        val t=-near[1]/dy
        if(t<0f || t>1f) return null
        return floatArrayOf(near[0]+t*(far[0]-near[0]),near[2]+t*(far[2]-near[2]))
    }
    private fun onGl(result: MethodChannel.Result, action: ()->Boolean) {
        if(disposed) { result.success(false); return }
        glSurfaceView.queueEvent {
            try { val success=action(); activity.runOnUiThread { result.success(success) } }
            catch(e: Exception) { activity.runOnUiThread { result.error("AR_SCENE",e.message,null) } }
        }
    }
    fun resumeSession() {
        if(disposed) return
        glSurfaceView.onResume()
        rotation.onResume()
        glSurfaceView.queueEvent { try { if(!preview) session?.resume(); lastTime=0L } catch(e: Exception) { android.util.Log.w("TaleStarAR","Resume",e) } }
    }
    fun pauseSession() {
        rotation.onPause()
        glSurfaceView.queueEvent { try { session?.pause() } catch(_: Exception) {} }
        glSurfaceView.onPause()
    }
    override fun dispose() {
        if(disposed) return
        disposed=true
        pageExecutor.shutdown()
        rotation.onPause()
        glSurfaceView.queueEvent { anchor?.detach(); anchor=null; storyRenderer.dispose(); session?.close(); session=null; configured=false }
        glSurfaceView.onPause()
        onDisposed?.invoke(this)
    }
}
