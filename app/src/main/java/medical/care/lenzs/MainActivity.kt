package medical.care.lenzs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.opencv.android.OpenCVLoader
import java.util.*
import kotlin.math.abs


class MainActivity : AppCompatActivity() {
    private lateinit var cameraView: RelativeLayout
    private var captureRequest: CaptureRequest? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private lateinit var textureView: TextureView
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSize: Size? = null
    private lateinit var stateCallback: CameraDevice.StateCallback
    private lateinit var surfaceTextureListener: TextureView.SurfaceTextureListener
    private val CAMERA_REQUEST_CODE: Int = 1888
    private var cameraFacing: Int? = 0
    private lateinit var cameraManager: CameraManager
    private var cameraDevice : CameraDevice? = null
    private lateinit var cameraId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lens_meter)

        textureView = findViewById<TextureView>(R.id.texture)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraFacing = CameraCharacteristics.LENS_FACING_FRONT

        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                setUpCamera()
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                this@MainActivity.cameraDevice = cameraDevice
                createPreviewSession()
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                cameraDevice.close()
                this@MainActivity.cameraDevice = null
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                cameraDevice.close()
                this@MainActivity.cameraDevice = null
            }
        }

        if(OpenCVLoader.initDebug()){
            Toast.makeText(this, "openCv successfully loaded", Toast.LENGTH_SHORT).show()
        }else{
            Toast.makeText(this, "openCv cannot be loaded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpCamera() {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) === cameraFacing) {
                    val streamConfigurationMap = cameraCharacteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                    var width = 0
                    var height = 0

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val metrics = windowManager.currentWindowMetrics
                        // Gets all excluding insets
                        val insets: Insets =  metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
                                or WindowInsets.Type.displayCutout())

                        val insetsWidth: Int = insets.right + insets.left
                        val insetsHeight: Int = insets.top + insets.bottom

                        // Legacy size that Display#getSize reports
                        val bounds: Rect = metrics.bounds
                        val legacySize = Size(bounds.width() - insetsWidth,
                                bounds.height() - insetsHeight)
                        width = legacySize.width
                        height = legacySize.height
                    } else {
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getMetrics(displayMetrics)

                        width = displayMetrics.widthPixels
                        height = displayMetrics.heightPixels
                    }

                    previewSize = chooseOptimalSize(streamConfigurationMap!!.getOutputSizes(SurfaceTexture::class.java), width, height)
                    previewSize?.let { setAspectRatioTextureView(it.width, it.height, width, height) }
                    Toast.makeText(this, previewSize.toString(), Toast.LENGTH_SHORT).show()
                    this.cameraId = cameraId
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setAspectRatioTextureView(ResolutionWidth: Int, ResolutionHeight: Int, DSI_width: Int, DSI_height: Int) {
        val newWidth = if (ResolutionWidth < ResolutionHeight) {
            DSI_height * ResolutionWidth / ResolutionHeight
        } else {
            DSI_height * ResolutionHeight / ResolutionWidth
        }

        updateTextureViewSize(newWidth, DSI_height)
    }

    private fun updateTextureViewSize(viewWidth: Int, viewHeight: Int) {
        textureView.layoutParams = FrameLayout.LayoutParams(viewWidth, viewHeight)
    }

    private fun openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.getLooper())
    }

    override fun onResume() {
        super.onResume()
        openBackgroundThread()
        if (textureView.isAvailable) {
            setUpCamera()
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
        closeBackgroundThread()
    }

    private fun closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun closeBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        }
    }

    private fun createPreviewSession() {
        try {
            val surfaceTexture: SurfaceTexture? = textureView.surfaceTexture
            if (surfaceTexture != null) {
                previewSize?.let { surfaceTexture.setDefaultBufferSize(it.width, previewSize!!.height) }
            }
            val previewSurface = Surface(surfaceTexture)
            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(previewSurface)
            @Suppress("DEPRECATION")
            cameraDevice?.createCaptureSession(Collections.singletonList(previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            if (cameraDevice == null) {
                                return
                            }
                            try {
                                captureRequest = captureRequestBuilder?.build()
                                this@MainActivity.cameraCaptureSession = cameraCaptureSession
                                captureRequest?.let {
                                    this@MainActivity.cameraCaptureSession!!.setRepeatingRequest(it,
                                            null, backgroundHandler)
                                }
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}
                    }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun chooseOptimalSize(outputSizes: Array<Size>, width: Int, height: Int): Size? {
        val preferredRatio = height / width.toDouble()
        var currentOptimalSize = outputSizes[0]
        var currentOptimalRatio = currentOptimalSize.width / currentOptimalSize.height.toDouble()
        for (currentSize in outputSizes) {
            val currentRatio = currentSize.width / currentSize.height.toDouble()
            if (abs(preferredRatio - currentRatio) <
                    abs(preferredRatio - currentOptimalRatio)) {
                currentOptimalSize = currentSize
                currentOptimalRatio = currentRatio
            }
        }
        return currentOptimalSize
    }


}