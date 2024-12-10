package io.github.aaos.mirror

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aaos.mirror.ui.theme.MirrorSamplesTheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val mainHandler by lazy { Handler(mainLooper) }

    private lateinit var askProjection: ActivityResultLauncher<Intent>
    private lateinit var askRecordAudio: ActivityResultLauncher<Array<String>>
    private val mediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }
    private var mediaProjection : MediaProjection? = null
    private var projectionSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.i(TAG, "Capture onStop")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            super.onCapturedContentResize(width, height)
            Log.i(TAG, "Capture onCapturedContentResize $width $height")
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            super.onCapturedContentVisibilityChanged(isVisible)
            Log.i(TAG, "Capture onCapturedContentVisibilityChanged $isVisible")
        }
    }

    private val windowMetrics by lazy { windowManager.maximumWindowMetrics }
    private val imageDir by lazy {
        File(filesDir, "captures").also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }
    private val videoDir by lazy {
        File(filesDir, "recordings").also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askProjection = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(), this::onResult
        )
        askRecordAudio = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(), this::onPermission
        )
        askRecordAudio.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
        setContent {
            MirrorSamplesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        )
                        Button(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 16.dp),
                            onClick = {
                                startCapture()
                            }
                        ) {
                            Text("Capture Screen", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                        )
                        Button(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 16.dp),
                            onClick = {
                                stopCapture()
                            }
                        ) {
                            Text("STOP CAPTURING", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                        )
                        Button(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 16.dp),
                            onClick = {
                                startRecording()
                            }
                        ) {
                            Text("Record Screen", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                        )
                        Button(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 16.dp),
                            onClick = {
                                stopRecording()
                            }
                        ) {
                            Text("STOP RECORDING", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }

    private fun onPermission(grantResults: Map<String, Boolean>) {
        if (!grantResults.containsKey(android.Manifest.permission.RECORD_AUDIO)) {
            Log.i(TAG, "Record Audio Canceled")
            finish()
            return
        }
        if (grantResults[android.Manifest.permission.RECORD_AUDIO] != true) {
            Log.i(TAG, "Record Audio Denied")
            finish()
            return
        }
        Log.i(TAG, "Record Audio Granted")
    }

    private fun onResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            mediaProjection = mediaProjectionManager
                .getMediaProjection(result.resultCode, result.data!!)
            mediaProjection?.registerCallback(mediaProjectionCallback, mainHandler)
            projectionSurface?.let {
                virtualDisplay = mediaProjection?.createVirtualDisplay(DISPLAY_NAME,
                    windowMetrics.bounds.width(), windowMetrics.bounds.height(),
                    resources.configuration.densityDpi, 0, it, null, null)
            }
            Log.i(TAG, "Granted")
        } else {
            Log.i(TAG, "Denied")
            stopService(Intent(this, RecordingForegroundService::class.java))
        }
    }

    private fun fileName(isVideo: Boolean): String {
        val name = System.currentTimeMillis()
        return File(if (isVideo) videoDir else imageDir,
            name.toString() + (if (isVideo) "mp4" else "jpg")).path
    }

    private fun startProjection() {
        startForegroundService(Intent(this, RecordingForegroundService::class.java))
        askProjection.launch(
            mediaProjectionManager.createScreenCaptureIntent()
        )
    }

    private fun startCapture() {
        imageReader = ImageReader.newInstance(
            windowMetrics.bounds.width(), windowMetrics.bounds.height(), PixelFormat.RGBA_8888, 2
        ).apply {
            setOnImageAvailableListener({
                saveImage(it.acquireLatestImage())
            }, null)
        }
        projectionSurface = imageReader?.surface
        startProjection()
    }

    private fun saveImage(image: Image?) {
        image?.let {
            val width = it.width
            val height = it.height
            val plane = it.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride,
                height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val output = FileOutputStream(fileName(false))
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
    }

    private fun stopCapture() {
        stopProjection()
        imageReader?.close()
        imageReader = null
    }

    private fun startRecording() {
        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoSize(windowMetrics.bounds.width(), windowMetrics.bounds.height())
            setVideoEncodingBitRate(3 * 1024 * 1024)
            setVideoFrameRate(25)
            setOutputFile(fileName(true))
        }
        try {
            mediaRecorder?.prepare()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Prepare failed ${e.message}")
        } catch (e: IOException) {
            Log.e(TAG, "Prepare failed ${e.message}")
        }
        projectionSurface = mediaRecorder?.surface
        startProjection()
        try {
            mediaRecorder?.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Start failed ${e.message}")
        }
    }

    private fun stopRecording() {
        stopProjection()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder = null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Stop failed ${e.message}")
        }
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection = null
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DISPLAY_NAME = "MainActivity-Recording"
    }
}
