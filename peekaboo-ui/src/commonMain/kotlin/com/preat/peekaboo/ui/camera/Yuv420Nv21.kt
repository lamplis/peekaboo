package com.preat.peekaboo.ui.camera

/**
 * One YUV_420_888 plane, already sliced from the buffer position through remaining().
 * [bytes] index 0 is the first valid sample.
 */
data class YuvPlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

/**
 * Packs YUV_420_888 into NV21 (Y then interleaved VU). A sample past the end of a
 * plane stays 0 and copying continues, so a short shared chroma view cannot blank the frame.
 */
fun packYuv420ToNv21(
    width: Int,
    height: Int,
    y: YuvPlane,
    u: YuvPlane,
    v: YuvPlane,
): ByteArray {
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)
    copySamples(
        plane = y,
        sampleStride = y.pixelStride,
        rowWidth = width,
        rows = height,
        dest = nv21,
        offset = 0,
    )
    val chromaRows = height / 2
    val interleaved =
        v.pixelStride == 2 &&
            u.pixelStride == 2 &&
            v.rowStride == u.rowStride
    when {
        interleaved && holdsChromaRows(v, chromaRows, width) && v.bytes.size >= u.bytes.size ->
            copySamples(
                plane = v,
                sampleStride = 1,
                rowWidth = width,
                rows = chromaRows,
                dest = nv21,
                offset = ySize,
            )
        interleaved && holdsChromaRows(u, chromaRows, width) ->
            copySwappedPairs(plane = u, rows = chromaRows, rowWidth = width, dest = nv21, offset = ySize)
        else ->
            interleavePlanarChroma(
                v = v,
                u = u,
                chromaWidth = width / 2,
                chromaRows = chromaRows,
                dest = nv21,
                offset = ySize,
            )
    }
    return nv21
}

/**
 * Full-range BT.601 NV21 to opaque ARGB. A read past [nv21] is 0.
 * Neutral chroma (U = V = 128) keeps the luma value, matching a JPEG decode of the same plane.
 */
fun nv21ToArgb(
    nv21: ByteArray,
    width: Int,
    height: Int,
): IntArray {
    if (width <= 0 || height <= 0) return IntArray(0)
    val ySize = width * height
    val argb = IntArray(ySize)
    var pixel = 0
    for (row in 0 until height) {
        val chromaRow = ySize + (row / 2) * width
        for (col in 0 until width) {
            val y = nv21.nv21Sample(pixel)
            val chromaIndex = chromaRow + (col and 1.inv())
            val v = nv21.nv21Sample(chromaIndex)
            val u = nv21.nv21Sample(chromaIndex + 1)
            argb[pixel] = yuvToArgb(y = y, u = u, v = v)
            pixel += 1
        }
    }
    return argb
}

private fun ByteArray.nv21Sample(index: Int): Int = if (index in indices) this[index].toInt() and 0xFF else 0

private fun yuvToArgb(
    y: Int,
    u: Int,
    v: Int,
): Int {
    val d = u - 128
    val e = v - 128
    val r = (y + ((359 * e) shr 8)).coerceIn(0, 255)
    val g = (y - ((88 * d) shr 8) - ((183 * e) shr 8)).coerceIn(0, 255)
    val b = (y + ((454 * d) shr 8)).coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun holdsChromaRows(
    plane: YuvPlane,
    chromaRows: Int,
    rowWidth: Int,
): Boolean {
    if (chromaRows <= 0) return false
    val needed = plane.rowStride * (chromaRows - 1) + rowWidth - 1
    return plane.bytes.size >= needed
}

private fun copySamples(
    plane: YuvPlane,
    sampleStride: Int,
    rowWidth: Int,
    rows: Int,
    dest: ByteArray,
    offset: Int,
) {
    var output = offset
    for (row in 0 until rows) {
        val rowStart = row * plane.rowStride
        for (col in 0 until rowWidth) {
            val index = rowStart + col * sampleStride
            if (index in plane.bytes.indices && output < dest.size) {
                dest[output] = plane.bytes[index]
            }
            output += 1
        }
    }
}

private fun copySwappedPairs(
    plane: YuvPlane,
    rows: Int,
    rowWidth: Int,
    dest: ByteArray,
    offset: Int,
) {
    var output = offset
    for (row in 0 until rows) {
        val rowStart = row * plane.rowStride
        var col = 0
        while (col + 1 < rowWidth) {
            val uIndex = rowStart + col
            val vIndex = uIndex + 1
            if (output + 1 < dest.size) {
                dest[output] = plane.bytes.getOrElse(vIndex) { 0 }
                dest[output + 1] = plane.bytes.getOrElse(uIndex) { 0 }
            }
            output += 2
            col += 2
        }
    }
}

private fun interleavePlanarChroma(
    v: YuvPlane,
    u: YuvPlane,
    chromaWidth: Int,
    chromaRows: Int,
    dest: ByteArray,
    offset: Int,
) {
    var output = offset
    for (row in 0 until chromaRows) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * v.rowStride + col * v.pixelStride
            val uIndex = row * u.rowStride + col * u.pixelStride
            if (output + 1 < dest.size) {
                dest[output] = v.bytes.getOrElse(vIndex) { 0 }
                dest[output + 1] = u.bytes.getOrElse(uIndex) { 0 }
            }
            output += 2
        }
    }
}
