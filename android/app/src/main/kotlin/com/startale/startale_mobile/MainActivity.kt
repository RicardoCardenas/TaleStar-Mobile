package com.startale.startale_mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.startale.startale_mobile/ar_core"
    private var activeArView: ArCorePlatformView? = null
    private lateinit var arChannel: MethodChannel

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        arChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)

        // Registro de la fábrica de PlatformView para la vista nativa AR con gestión de ciclo de vida
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "com.startale.startale_mobile/ar_view",
            ArCorePlatformViewFactory(
                activity = this,
                messenger = flutterEngine.dartExecutor.binaryMessenger,
                channel = arChannel,
                onViewCreated = { view ->
                    activeArView = view
                },
                onViewDisposed = { view ->
                    if (activeArView === view) {
                        activeArView = null
                    }
                }
            )
        )

        arChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getDeviceInfo" -> {
                    try {
                        val info = mapOf(
                            "manufacturer" to Build.MANUFACTURER,
                            "brand" to Build.BRAND,
                            "model" to Build.MODEL,
                            "device" to Build.DEVICE,
                            "product" to Build.PRODUCT,
                            "sdkInt" to Build.VERSION.SDK_INT,
                            "release" to Build.VERSION.RELEASE
                        )
                        result.success(info)
                    } catch (e: Exception) {
                        result.error("DEVICE_INFO_ERROR", e.localizedMessage, null)
                    }
                }
                "checkArAvailability" -> {
                    try {
                        val availability = ArCoreApk.getInstance().checkAvailability(this)
                        result.success(availability.name)
                    } catch (e: Exception) {
                        result.success("UNSUPPORTED_DEVICE_NOT_CAPABLE")
                    }
                }
                "requestInstallArCore" -> {
                    try {
                        val installStatus = ArCoreApk.getInstance().requestInstall(this, true)
                        result.success(installStatus.name)
                    } catch (e: Exception) {
                        result.error("ARCORE_INSTALL_ERROR", e.localizedMessage, null)
                    }
                }
                "hasCameraPermission" -> {
                    val granted = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    result.success(granted)
                }
                "requestCameraPermission" -> {
                    val granted = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.CAMERA),
                            1001
                        )
                    }
                    result.success(true)
                }
                "initializeSession" -> {
                    activeArView?.resumeSession()
                    result.success(true)
                }
                "stopSession" -> {
                    activeArView?.pauseSession()
                    result.success(true)
                }
                "placeStoryAnchor" -> {
                    val view = activeArView
                    if (view == null) result.success(false)
                    else view.placeStory(0.5f, 0.5f, result)
                }
                "placeStoryAtCoordinates" -> {
                    val rawX = call.argument<Double>("normX") ?: call.argument<Double>("x") ?: 0.5
                    val rawY = call.argument<Double>("normY") ?: call.argument<Double>("y") ?: 0.5
                    val normX = rawX.toFloat()
                    val normY = rawY.toFloat()
                    val view = activeArView
                    if (view == null) result.success(false)
                    else view.placeStory(normX, normY, result)
                }
                "setStoryPage" -> {
                    val view = activeArView
                    if (view == null) result.error("AR_VIEW_MISSING", "La escena no está disponible", null)
                    else view.setPage(call.argument<ByteArray>("imageBytes"), call.argument<String>("text") ?: "",
                        call.argument<Int>("page") ?: 1, call.argument<Int>("total") ?: 1, result)
                }
                "placeEstimatedStory" -> {
                    val view = activeArView
                    if (view == null) result.success(false)
                    else view.placeEstimatedStory((call.argument<Double>("distance") ?: 0.75).toFloat(), result)
                }
                "storySceneCommand" -> {
                    val view = activeArView
                    if (view == null) result.success(false)
                    else view.sceneCommand(call.argument<String>("command") ?: "", call.arguments as? Map<*, *>, result)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activeArView?.resumeSession()
    }

    override fun onPause() {
        activeArView?.pauseSession()
        super.onPause()
    }

    override fun onDestroy() {
        activeArView?.dispose()
        activeArView = null
        super.onDestroy()
    }
}
