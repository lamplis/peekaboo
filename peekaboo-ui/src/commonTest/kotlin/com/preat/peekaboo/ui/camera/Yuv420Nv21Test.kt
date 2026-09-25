package com.preat.peekaboo.ui.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals

class Yuv420Nv21Test {
    @Test
    fun `Given a planar 2x2 frame When packed Then Y is followed by V then U`() {
        val packed =
            packYuv420ToNv21(
                width = 2,
                height = 2,
                y = plane(byteArrayOf(10, 20, 30, 40), rowStride = 2, pixelStride = 1),
                u = plane(byteArrayOf(50), rowStride = 1, pixelStride = 1),
                v = plane(byteArrayOf(60), rowStride = 1, pixelStride = 1),
            )

        assertContentEquals(byteArrayOf(10, 20, 30, 40, 60, 50), packed)
    }

    @Test
    fun `Given interleaved V and a one-byte U When packed Then chroma is copied from V`() {
        val packed =
            packYuv420ToNv21(
                width = 2,
                height = 2,
                y = plane(byteArrayOf(1, 2, 3, 4), rowStride = 2, pixelStride = 1),
                u = plane(byteArrayOf(50), rowStride = 2, pixelStride = 2),
                v = plane(byteArrayOf(60, 50), rowStride = 2, pixelStride = 2),
            )

        assertContentEquals(byteArrayOf(60, 50), packed.copyOfRange(4, 6))
    }

    @Test
    fun `Given interleaved U and a one-byte V When packed Then chroma pairs are swapped to VU`() {
        val packed =
            packYuv420ToNv21(
                width = 2,
                height = 2,
                y = plane(byteArrayOf(1, 2, 3, 4), rowStride = 2, pixelStride = 1),
                u = plane(byteArrayOf(50, 60), rowStride = 2, pixelStride = 2),
                v = plane(byteArrayOf(60), rowStride = 2, pixelStride = 2),
            )

        assertContentEquals(byteArrayOf(60, 50), packed.copyOfRange(4, 6))
    }

    @Test
    fun `Given a padded Y row When packed Then padding bytes are dropped`() {
        val packed =
            packYuv420ToNv21(
                width = 2,
                height = 2,
                y = plane(byteArrayOf(10, 20, 0, 0, 30, 40, 0, 0), rowStride = 4, pixelStride = 1),
                u = plane(byteArrayOf(0), rowStride = 1, pixelStride = 1),
                v = plane(byteArrayOf(0), rowStride = 1, pixelStride = 1),
            )

        assertContentEquals(byteArrayOf(10, 20, 30, 40), packed.copyOfRange(0, 4))
    }

    @Test
    fun `Given equal chroma planes one byte short When packed Then VU is copied and the tail stays zero`() {
        val chroma = byteArrayOf(60, 50, 61, 51, 62, 52, 63)
        val packed =
            packYuv420ToNv21(
                width = 4,
                height = 4,
                y = plane(ByteArray(16), rowStride = 4, pixelStride = 1),
                u = plane(chroma, rowStride = 4, pixelStride = 2),
                v = plane(chroma, rowStride = 4, pixelStride = 2),
            )

        assertContentEquals(byteArrayOf(60, 50, 61, 51, 62, 52, 63, 0), packed.copyOfRange(16, 24))
    }

    @Test
    fun `Given neutral chroma When converted Then pixels stay gray`() {
        val packed =
            packYuv420ToNv21(
                width = 2,
                height = 2,
                y = plane(ByteArray(4) { 128.toByte() }, rowStride = 2, pixelStride = 1),
                u = plane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
                v = plane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
            )
        val gray = 0xFF808080.toInt()

        assertContentEquals(intArrayOf(gray, gray, gray, gray), nv21ToArgb(packed, width = 2, height = 2))
    }

    private fun plane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
    ): YuvPlane = YuvPlane(bytes = bytes, rowStride = rowStride, pixelStride = pixelStride)
}
