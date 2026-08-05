package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
// ===========================================================================
// PEPPER CAMERA CONTROLLER
// ===========================================================================

/*
Gestisce la fotocamera di pepper tramite il takepicture del qisdk builder
 */
private const val TAG = "PepperCamera"

class PepperCameraController {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val captureInProgress = AtomicBoolean(false)

    fun interface FrameCallback {
        /** Chiamato SEMPRE, su thread IO. bitmap è null se la cattura è fallita per qualunque motivo. */
        fun onFrame(bitmap: Bitmap?, timestampMs: Long)
    }

    private var qiContext: QiContext? = null
    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "Camera controller ready.")
    }
    fun onRobotLost() {
        scope.coroutineContext.cancelChildren()
        qiContext = null
        Log.d(TAG, "Camera controller released.")
    }

    fun takeSinglePicture(callback: FrameCallback) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "takeSinglePicture: qiContext null — robot not connected?")
            callback.onFrame(null, System.currentTimeMillis())
            return
        }
        if (!captureInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Capture skipped: previous still running")
            callback.onFrame(null, System.currentTimeMillis())
            return
        }

        scope.launch {
            try {
                val takePicture = TakePictureBuilder.with(ctx).build()
                val timestampedImage: TimestampedImageHandle = takePicture.async().run().get()
                val bitmap = timestampedImage.toBitmap()
                if (bitmap != null) {
                    callback.onFrame(bitmap, System.currentTimeMillis())
                    Log.d(TAG, "Frame captured: ${bitmap.width}×${bitmap.height}")
                } else {
                    Log.e(TAG, "Frame decode failed")
                    callback.onFrame(null, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e.message?.contains("video device") == true) {
                    Log.w(TAG, "Camera busy during movement, retry in 200ms...")
                    delay(200)
                    try {
                        val takePicture2 = TakePictureBuilder.with(ctx).build()
                        val img = takePicture2.async().run().get()
                        val bitmap = img.toBitmap()
                        callback.onFrame(bitmap, System.currentTimeMillis())
                        if (bitmap == null) Log.e(TAG, "Retry decode failed")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Retry failed: ${e2.message}")
                        callback.onFrame(null, System.currentTimeMillis())
                    }
                } else {
                    Log.e(TAG, "takeSinglePicture error: ${e.message}", e)
                    callback.onFrame(null, System.currentTimeMillis())
                }
            } finally {
                captureInProgress.set(false)
            }
        }
    }

    private fun TimestampedImageHandle.toBitmap(): Bitmap? {
        return try {
            val image = this.image.value
            val buffer = image.data
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap error: ${e.message}", e)
            null
        }
    }
}