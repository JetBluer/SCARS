
package edu.califer.facemaskdectector

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import edu.califer.facemaskdectector.databinding.ActivityMainBinding
import edu.califer.facemaskdectector.ml.FackMaskDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.model.Model
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


typealias  CameraBitmapOutputListener = (bitmap: Bitmap) -> Unit

class MainActivity : AppCompatActivity() {

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var binding: ActivityMainBinding

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        // Hide the status bar.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        // Remember that you should never show the action bar if the
        // status bar is hidden, so hide that too if necessary.
        actionBar?.hide()

        setUpML()

        setUpCameraThread()

        setUpCameraControllers()

        if (!allPermissionGranted) {
            requireCameraPermission()
        } else {
            setUpCamera()
        }

    }

    private fun setUpCameraThread() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setUpCameraControllers() {

        binding.cameraSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }

            setUpCameraUseCases()
        }
        try {
            binding.cameraSwitch.isEnabled = hasBackCamera && hasFrontCamera
        } catch (exception: CameraInfoUnavailableException) {
            binding.cameraSwitch.isEnabled = false
            exception.printStackTrace()
        }
    }

    private fun requireCameraPermission() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSION)
    }

    private fun grantedCameraPermission(requestCode: Int) {
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (allPermissionGranted) {
                setUpCamera()
            } else {
                Toast.makeText(applicationContext, "Permission Not Granted !", Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }
    }

    private fun setUpCameraUseCases() {

        val cameraSelector: CameraSelector =
            CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val metrics: DisplayMetrics = DisplayMetrics().also {
            binding.cameraView.display.getRealMetrics(it)
        }
        val rotation: Int = binding.cameraView.display.rotation
        val screenAspectRatio: Int = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()


        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetAspectRatio(rotation)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor,
                    BitmapOutPutAnalysis(applicationContext) { bitmap ->
                        setUpMLOutput(bitmap)
                    }
                )
            }

        cameraProvider?.unbindAll()
        try {
            camera = cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.cameraView.surfaceProvider)
        } catch (exception: Exception) {
            Log.d(TAG, "USE CASE BINDING FAILURE", exception)
        }


    }

    private fun setUpCamera() {

        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {

            cameraProvider = cameraProviderFuture.get()

            lensFacing = when {
                hasFrontCamera -> CameraSelector.LENS_FACING_FRONT
                hasBackCamera -> CameraSelector.LENS_FACING_BACK
                else -> throw IllegalStateException("No Camera available")
            }

            setUpCameraControllers()
            setUpCameraUseCases()
        }, ContextCompat.getMainExecutor(this))

    }

    private val allPermissionGranted: Boolean
        get() {
            return REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                    baseContext,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

    private val hasBackCamera: Boolean
        get() {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
        }

    private val hasFrontCamera: Boolean
        get() {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        grantedCameraPermission(requestCode)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setUpCameraControllers()
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio: Double = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    private lateinit var faceMaskDetection: FackMaskDetection


    private fun setUpML() {
        val options: Model.Options =
            Model.Options.Builder().setDevice(Model.Device.GPU).setNumThreads(5).build()
        faceMaskDetection = FackMaskDetection.newInstance(applicationContext, options)
    }

    private fun setUpMLOutput(bitmap: Bitmap) {
        val tensorImage: TensorImage = TensorImage.fromBitmap(bitmap)
        val result: FackMaskDetection.Outputs = faceMaskDetection.process(tensorImage)
        val output: List<Category> = result.probabilityAsCategoryList.apply {
            sortByDescending { res -> res.score }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            output.firstOrNull()?.let { category ->
                binding.textView.text = category.label
                binding.textView.setTextColor(
                    ContextCompat.getColor(
                        applicationContext,
                        if (category.label == "without_mask") R.color.red else R.color.green
                    )
                )

                binding.overlay.background = AppCompatResources.getDrawable(
                    applicationContext,
                    if (category.label == "without_mask") R.drawable.red_border else R.drawable.green_border
                )

                binding.progressBar.progressTintList = AppCompatResources.getColorStateList(
                    applicationContext,
                    if (category.label == "without_mask") R.color.red else R.color.green
                )

                binding.progressBar.progress = (category.score * 100).toInt()
            }
        }
    }

    companion object {
        private const val TAG = "Face_Mask_Detector"
        private const val REQUEST_CODE_PERMISSION = 0x98
        private val REQUIRED_PERMISSIONS: Array<String> =
            arrayOf(android.Manifest.permission.CAMERA)
        private const val RATIO_4_3_VALUE: Double = 4.0 / 3.0
        private const val RATIO_16_9_VALUE: Double = (16.0 / 9.0)

    }

}

private class BitmapOutPutAnalysis(
    context: Context,