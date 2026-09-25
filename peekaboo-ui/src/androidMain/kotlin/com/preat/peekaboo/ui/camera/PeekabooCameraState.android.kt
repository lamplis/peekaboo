/*
 * Copyright 2024 onseok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.preat.peekaboo.ui.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
actual class PeekabooCameraState(
    cameraMode: CameraMode,
    internal var onFrame: ((frame: ByteArray) -> Unit)?,
    internal var onScannerFrame: ((frame: PeekabooCameraFrame) -> Unit)?,
    internal var onCapture: (ByteArray?) -> Unit,
) {
    actual var isCameraReady: Boolean by mutableStateOf(false)

    actual var isCapturing: Boolean by mutableStateOf(false)

    actual var cameraMode: CameraMode by mutableStateOf(cameraMode)

    actual var isTorchAvailable: Boolean by mutableStateOf(false)

    actual var isTorchEnabled: Boolean by mutableStateOf(false)

    actual var previewFrozen: Boolean by mutableStateOf(false)

    internal var triggerCaptureAnchor: (() -> Unit)? = null

    actual fun toggleCamera() {
        isTorchEnabled = false
        isTorchAvailable = false
        cameraMode = cameraMode.inverse()
    }

    actual fun setTorchActive(enabled: Boolean) {
        isTorchEnabled = enabled && isTorchAvailable
    }

    actual fun toggleTorch() {
        setTorchActive(!isTorchEnabled)
    }

    actual fun capture() {
        isCapturing = true
        triggerCaptureAnchor?.invoke()
    }

    internal fun stopCapturing() {
        isCapturing = false
    }

    internal fun onCapture(image: ByteArray?) {
        onCapture.invoke(image)
    }

    internal fun onCameraReady() {
        isCameraReady = true
    }

    companion object {
        fun saver(
            onFrame: ((frame: ByteArray) -> Unit)?,
            onScannerFrame: ((frame: PeekabooCameraFrame) -> Unit)?,
            onCapture: (ByteArray?) -> Unit,
        ): Saver<PeekabooCameraState, Int> {
            return Saver(
                save = {
                    it.cameraMode.id()
                },
                restore = {
                    PeekabooCameraState(
                        cameraMode = cameraModeFromId(it),
                        onFrame = onFrame,
                        onScannerFrame = onScannerFrame,
                        onCapture = onCapture,
                    )
                },
            )
        }
    }
}

@Composable
actual fun rememberPeekabooCameraState(
    initialCameraMode: CameraMode,
    onFrame: ((frame: ByteArray) -> Unit)?,
    onScannerFrame: ((frame: PeekabooCameraFrame) -> Unit)?,
    onCapture: (ByteArray?) -> Unit,
): PeekabooCameraState {
    return rememberSaveable(
        saver = PeekabooCameraState.saver(onFrame, onScannerFrame, onCapture),
    ) { PeekabooCameraState(initialCameraMode, onFrame, onScannerFrame, onCapture) }.apply {
        this.onFrame = onFrame
        this.onScannerFrame = onScannerFrame
        this.onCapture = onCapture
    }
}

actual class PeekabooCameraFrame internal constructor(
    private val imageProxy: ImageProxy?,
    actual val metadata: PeekabooFrameMetadata,
) {
    constructor(metadata: PeekabooFrameMetadata) : this(imageProxy = null, metadata = metadata)

    private var retainedForAsyncAnalysis = false
    private var released = false
    private var imageClosed = false
    private var cachedBitmap: Bitmap? = null

    val bitmap: Bitmap
        get() {
            check(!released) { "Camera frame was released before bitmap conversion" }
            cachedBitmap?.let { return it }
            val proxy = imageProxy
            val created =
                if (proxy == null) {
                    Bitmap.createBitmap(
                        metadata.width.coerceAtLeast(8),
                        metadata.height.coerceAtLeast(8),
                        Bitmap.Config.ARGB_8888,
                    )
                } else {
                    proxy.toSoftwareBitmap(::closeImageProxy)
                }
            cachedBitmap = created
            return created
        }

    actual fun retainForAsyncAnalysis() {
        retainedForAsyncAnalysis = true
    }

    actual fun releaseAfterAsyncAnalysis() {
        if (released) return
        cachedBitmap?.recycle()
        cachedBitmap = null
        closeImageProxy()
        retainedForAsyncAnalysis = false
        released = true
    }

    private fun closeImageProxy() {
        if (imageClosed) return
        imageClosed = true
        imageProxy?.close()
    }

    internal fun releaseIfNotRetained() {
        if (!retainedForAsyncAnalysis) {
            releaseAfterAsyncAnalysis()
        }
    }
}

actual fun metadataOnlyCameraFrame(metadata: PeekabooFrameMetadata): PeekabooCameraFrame =
    PeekabooCameraFrame(metadata)

/**
 * CameraX [ImageProxy.toBitmap] returns a blank buffer on some vendor YUV streams.
 * Rebuild a software bitmap from NV21 so the pose model sees real pixels.
 * [onPlanesCopied] runs after the planes are in memory and before the RGB loop,
 * so the camera buffer is not held through inference and no JPEG encoder runs.
 * Rotation stays with the caller; this copy keeps the sensor width and height.
 */
private fun ImageProxy.toSoftwareBitmap(onPlanesCopied: () -> Unit): Bitmap {
    if (planes.size < 3 || width <= 0 || height <= 0) {
        onPlanesCopied()
        return emptyAnalysisBitmap(width, height)
    }
    val y = planes[0].toYuvPlane()
    val u = planes[1].toYuvPlane()
    val v = planes[2].toYuvPlane()
    onPlanesCopied()
    val nv21 = packYuv420ToNv21(width = width, height = height, y = y, u = u, v = v)
    println(
        "[CardScanner][yuv] y=${y.rowStride}/${y.pixelStride}/${y.bytes.size} " +
            "u=${u.rowStride}/${u.pixelStride}/${u.bytes.size} " +
            "v=${v.rowStride}/${v.pixelStride}/${v.bytes.size} " +
            "yMean=${meanPackedY(nv21, width * height)} rgb=software",
    )
    val argb = nv21ToArgb(nv21, width, height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    return bitmap
}

private fun ImageProxy.PlaneProxy.toYuvPlane(): YuvPlane {
    val source = buffer.duplicate()
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    return YuvPlane(bytes = bytes, rowStride = rowStride, pixelStride = pixelStride)
}

private fun emptyAnalysisBitmap(
    width: Int,
    height: Int,
): Bitmap =
    Bitmap.createBitmap(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )

private fun meanPackedY(
    nv21: ByteArray,
    ySize: Int,
): Int {
    if (ySize <= 0) return 0
    val count = minOf(ySize, nv21.size)
    var sum = 0L
    for (index in 0 until count) {
        sum += nv21[index].toInt() and 0xFF
    }
    return (sum / count).toInt()
}
