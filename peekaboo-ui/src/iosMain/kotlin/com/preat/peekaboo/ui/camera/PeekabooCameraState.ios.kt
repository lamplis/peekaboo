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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferIsPlanar
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.posix.memcpy

@Stable
actual class PeekabooCameraState(
    cameraMode: CameraMode,
    internal var onFrame: ((frame: ByteArray) -> Unit)?,
    internal var onScannerFrame: ((frame: PeekabooCameraFrame) -> Unit)?,
    internal var onCapture: (ByteArray?) -> Unit,
) {
    actual var isCameraReady: Boolean by mutableStateOf(false)

    internal var triggerCaptureAnchor: (() -> Unit)? = null

    actual var isCapturing: Boolean by mutableStateOf(false)

    actual var cameraMode: CameraMode by mutableStateOf(cameraMode)

    actual var isTorchAvailable: Boolean by mutableStateOf(false)

    actual var isTorchEnabled: Boolean by mutableStateOf(false)

    actual var previewFrozen: Boolean by mutableStateOf(false)

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

    fun onCameraReady() {
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

@OptIn(ExperimentalForeignApi::class)
actual class PeekabooCameraFrame internal constructor(
    private val sourcePixelBuffer: platform.CoreVideo.CVPixelBufferRef?,
    actual val metadata: PeekabooFrameMetadata,
) {
    constructor(metadata: PeekabooFrameMetadata) : this(sourcePixelBuffer = null, metadata = metadata)

    private var asyncCopy: platform.CoreVideo.CVPixelBufferRef? = null
    private var asyncCopyReleased = false

    /**
     * The pixel buffer to use for analysis. While the consumer holds the frame
     * synchronously inside [com.preat.peekaboo.ui.camera.CameraFrameAnalyzerDelegate.captureOutput],
     * this returns the original AVCapture pool buffer. Once the consumer calls
     * [retainForAsyncAnalysis] (in preparation for asynchronous CoreML/Vision
     * inference), the pool buffer is copied into a private CVPixelBuffer so the
     * AVCapture pool slot can be released back to AVFoundation immediately when
     * `captureOutput` returns. iOS otherwise rate-limits AVCaptureVideoDataOutput
     * delivery (~5–8 s freeze every ~10 s with no session interruption notification)
     * because Vision holds an internal reference to the pool buffer.
     */
    val pixelBuffer: platform.CoreVideo.CVPixelBufferRef
        get() {
            asyncCopy?.let { return it }
            if (asyncCopyReleased) {
                throw IllegalStateException("Camera frame copy was released")
            }
            return sourcePixelBuffer ?: error("Camera frame has no pixel buffer")
        }

    actual fun retainForAsyncAnalysis() {
        val source = sourcePixelBuffer ?: return
        if (asyncCopy != null) return
        // Copy the AVCapture pool buffer into a Vision-private CVPixelBuffer so
        // the pool slot can be returned immediately when captureOutput returns.
        // This decouples Vision/CoreML from AVCapture's pool, which on iPhones
        // otherwise causes iOS to silently rate-limit AVCaptureVideoDataOutput
        // delivery (multi-second freezes every ~10 s with no session
        // interruption notification) when CoreML/Vision retains pool buffers
        // for the duration of inference. Verified on iPhone 13 mini.
        asyncCopy = copyPixelBuffer(source) ?: return
    }

    actual fun releaseAfterAsyncAnalysis() {
        val copy = asyncCopy ?: return
        CFRelease(copy)
        asyncCopy = null
        asyncCopyReleased = true
    }
}

actual fun metadataOnlyCameraFrame(metadata: PeekabooFrameMetadata): PeekabooCameraFrame =
    PeekabooCameraFrame(metadata)

@OptIn(ExperimentalForeignApi::class)
private fun copyPixelBuffer(
    src: platform.CoreVideo.CVPixelBufferRef,
): platform.CoreVideo.CVPixelBufferRef? {
    val width = CVPixelBufferGetWidth(src)
    val height = CVPixelBufferGetHeight(src)
    val pixelFormat = CVPixelBufferGetPixelFormatType(src)
    val newBuffer = memScoped {
        val outVar = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            allocator = null,
            width = width,
            height = height,
            pixelFormatType = pixelFormat,
            pixelBufferAttributes = null,
            pixelBufferOut = outVar.ptr,
        )
        if (status != 0) null else outVar.value
    } ?: return null

    val readOnlyLock: ULong = 1uL
    CVPixelBufferLockBaseAddress(src, readOnlyLock)
    CVPixelBufferLockBaseAddress(newBuffer, 0uL)
    val copied =
        try {
            if (CVPixelBufferIsPlanar(src)) {
                copyPlanarPixelBuffer(src, newBuffer)
            } else {
                copyPackedPixelBuffer(src, newBuffer, height)
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(newBuffer, 0uL)
            CVPixelBufferUnlockBaseAddress(src, readOnlyLock)
        }
    if (!copied) {
        CFRelease(newBuffer)
        return null
    }
    return newBuffer
}

/**
 * Bi-planar camera frames (420v) are not one contiguous block.
 * [CVPixelBufferGetBytesPerRow] can be wider than plane 0 (2904 vs 1920 on a
 * 1920x1080 buffer). Multiplying that by height writes past the destination.
 */
@OptIn(ExperimentalForeignApi::class)
private fun copyPlanarPixelBuffer(
    src: platform.CoreVideo.CVPixelBufferRef,
    dst: platform.CoreVideo.CVPixelBufferRef,
): Boolean {
    val planeCount =
        minOf(
            CVPixelBufferGetPlaneCount(src),
            CVPixelBufferGetPlaneCount(dst),
        )
    if (planeCount == 0uL) return false
    var plane = 0uL
    while (plane < planeCount) {
        val srcAddress = CVPixelBufferGetBaseAddressOfPlane(src, plane) ?: return false
        val dstAddress = CVPixelBufferGetBaseAddressOfPlane(dst, plane) ?: return false
        copyPixelRows(
            src = srcAddress,
            dst = dstAddress,
            srcBytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(src, plane),
            dstBytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(dst, plane),
            height =
                minOf(
                    CVPixelBufferGetHeightOfPlane(src, plane),
                    CVPixelBufferGetHeightOfPlane(dst, plane),
                ),
        )
        plane++
    }
    return true
}

@OptIn(ExperimentalForeignApi::class)
private fun copyPackedPixelBuffer(
    src: platform.CoreVideo.CVPixelBufferRef,
    dst: platform.CoreVideo.CVPixelBufferRef,
    height: ULong,
): Boolean {
    val srcAddress = CVPixelBufferGetBaseAddress(src) ?: return false
    val dstAddress = CVPixelBufferGetBaseAddress(dst) ?: return false
    copyPixelRows(
        src = srcAddress,
        dst = dstAddress,
        srcBytesPerRow = CVPixelBufferGetBytesPerRow(src),
        dstBytesPerRow = CVPixelBufferGetBytesPerRow(dst),
        height = minOf(height, CVPixelBufferGetHeight(dst)),
    )
    return true
}

@OptIn(ExperimentalForeignApi::class)
private fun copyPixelRows(
    src: COpaquePointer,
    dst: COpaquePointer,
    srcBytesPerRow: ULong,
    dstBytesPerRow: ULong,
    height: ULong,
) {
    val rowBytes = minOf(srcBytesPerRow, dstBytesPerRow)
    if (rowBytes == 0uL || height == 0uL) return
    if (srcBytesPerRow == dstBytesPerRow) {
        memcpy(dst, src, rowBytes * height)
        return
    }
    val srcBytes = src.reinterpret<ByteVar>()
    val dstBytes = dst.reinterpret<ByteVar>()
    var row = 0uL
    while (row < height) {
        memcpy(
            dstBytes + (row * dstBytesPerRow).toLong(),
            srcBytes + (row * srcBytesPerRow).toLong(),
            rowBytes,
        )
        row++
    }
}
