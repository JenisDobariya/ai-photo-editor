package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Arrays

/**
 * Streaming animated GIF (GIF89a) writer.
 *
 * Frames are written to [output] as soon as they are added, so only one frame is held in memory.
 * Every frame gets its own 256-colour palette from a median-cut quantizer (the first frame's palette
 * doubles as the global colour table, later frames carry a local table), optional Floyd–Steinberg
 * dithering, and variable-code-length LZW compression packed in 255-byte sub-blocks.
 *
 * Usage: create, call [addFrame] for each frame, then [finish] (or [close]). The underlying stream
 * is flushed but not closed.
 */
class GifEncoder(
    output: OutputStream,
    val width: Int,
    val height: Int,
    private val loopForever: Boolean = true,
    private val dither: Boolean = true
) : Closeable {

    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Invalid GIF size ${width}x$height" }
    }

    private val out: OutputStream =
        if (output is BufferedOutputStream) output else BufferedOutputStream(output, 64 * 1024)

    private val pixelCount = width * height
    private val pixelBuffer = IntArray(pixelCount)
    private val indices = ByteArray(pixelCount)

    // Current frame palette (always written as 256 entries; unused slots are black).
    private val palR = IntArray(PALETTE_SIZE)
    private val palG = IntArray(PALETTE_SIZE)
    private val palB = IntArray(PALETTE_SIZE)
    private var paletteCount = 0

    /** 15-bit (5-5-5) colour -> palette index cache, reset for every frame. */
    private val lookup = IntArray(HIST_SIZE)

    private val quantizer = MedianCutQuantizer()

    // Dithering error rows (3 channels, one padding pixel on each side), values scaled by 16.
    private val errCurrentRow = IntArray((width + 2) * 3)
    private val errNextRow = IntArray((width + 2) * 3)

    // LZW state.
    private val hashCodes = IntArray(HASH_SIZE)
    private val hashValues = IntArray(HASH_SIZE)
    private val block = ByteArray(255)
    private var blockLength = 0
    private var bitBuffer = 0L
    private var bitCount = 0
    private var codeSize = 0
    private var nextCode = 0

    private var frameCount = 0
    private var finished = false

    val framesWritten: Int get() = frameCount

    /** Adds [bitmap] (must be exactly [width] x [height]) shown for [delayCs] hundredths of a second. */
    fun addFrame(bitmap: Bitmap, delayCs: Int) {
        require(bitmap.width == width && bitmap.height == height) {
            "Frame is ${bitmap.width}x${bitmap.height}, expected ${width}x$height"
        }
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
        addFrame(pixelBuffer, delayCs)
    }

    /** Adds a frame from ARGB pixels (row-major, [width] x [height]). Alpha is ignored. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        check(!finished) { "GIF already finished" }
        require(argb.size >= pixelCount) { "Pixel array too small" }

        paletteCount = quantizer.quantize(argb, pixelCount, palR, palG, palB)
        for (i in paletteCount until PALETTE_SIZE) {
            palR[i] = 0; palG[i] = 0; palB[i] = 0
        }
        Arrays.fill(lookup, -1)
        if (dither) mapDithered(argb) else mapPlain(argb)

        val first = frameCount == 0
        if (first) writeHeader()
        writeGraphicControl(delayCs)
        writeImageDescriptor(localTable = !first)
        if (!first) writePalette()
        writeImageData()
        frameCount++
    }

    /** Writes the trailer and flushes. Safe to call more than once. */
    fun finish() {
        if (finished) return
        check(frameCount > 0) { "A GIF needs at least one frame" }
        finished = true
        out.write(0x3B)
        out.flush()
    }

    override fun close() {
        if (!finished && frameCount > 0) finish() else out.flush()
    }

    // ---------------------------------------------------------------- pixel -> index mapping

    private fun nearestIndex(r: Int, g: Int, b: Int): Int {
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val cached = lookup[key]
        if (cached >= 0) return cached
        // Compare against the centre of the 5-bit bin so the cache is consistent.
        val cr = (r and 0xF8) or 4
        val cg = (g and 0xF8) or 4
        val cb = (b and 0xF8) or 4
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in 0 until paletteCount) {
            val dr = cr - palR[i]
            val dg = cg - palG[i]
            val db = cb - palB[i]
            // Perceptual-ish weights (green most visible, blue least).
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDist) {
                bestDist = d
                best = i
                if (d == 0) break
            }
        }
        lookup[key] = best
        return best
    }

    private fun mapPlain(argb: IntArray) {
        for (i in 0 until pixelCount) {
            val c = argb[i]
            indices[i] = nearestIndex((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toByte()
        }
    }

    private fun mapDithered(argb: IntArray) {
        var cur = errCurrentRow
        var next = errNextRow
        Arrays.fill(cur, 0)
        for (y in 0 until height) {
            Arrays.fill(next, 0)
            val rowStart = y * width
            for (x in 0 until width) {
                val c = argb[rowStart + x]
                val e = (x + 1) * 3
                val r = clamp(((c shr 16) and 0xFF) + (cur[e] shr 4))
                val g = clamp(((c shr 8) and 0xFF) + (cur[e + 1] shr 4))
                val b = clamp((c and 0xFF) + (cur[e + 2] shr 4))
                val idx = nearestIndex(r, g, b)
                indices[rowStart + x] = idx.toByte()

                // Slightly damped error (7/8) keeps animations from shimmering too much.
                val er = ((r - palR[idx]) * 7) shr 3
                val eg = ((g - palG[idx]) * 7) shr 3
                val eb = ((b - palB[idx]) * 7) shr 3

                cur[e + 3] += er * 7; cur[e + 4] += eg * 7; cur[e + 5] += eb * 7
                next[e - 3] += er * 3; next[e - 2] += eg * 3; next[e - 1] += eb * 3
                next[e] += er * 5; next[e + 1] += eg * 5; next[e + 2] += eb * 5
                next[e + 3] += er; next[e + 4] += eg; next[e + 5] += eb
            }
            val tmp = cur
            cur = next
            next = tmp
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    // ---------------------------------------------------------------- GIF blocks

    private fun writeHeader() {
        writeAscii("GIF89a")
        // Logical screen descriptor.
        writeShort(width)
        writeShort(height)
        // Global colour table present, colour resolution 8 bits, not sorted, 2^(7+1) = 256 entries.
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio (none)
        writePalette()
        if (loopForever) {
            // NETSCAPE2.0 application extension: loop count 0 = forever.
            out.write(0x21)
            out.write(0xFF)
            out.write(11)
            writeAscii("NETSCAPE2.0")
            out.write(3)
            out.write(1)
            writeShort(0)
            out.write(0)
        }
    }

    private fun writeGraphicControl(delayCs: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        // Disposal method 1 (leave in place), no user input, no transparency.
        out.write(0x04)
        writeShort(delayCs.coerceIn(0, 0xFFFF))
        out.write(0) // transparent colour index (unused)
        out.write(0) // block terminator
    }

    private fun writeImageDescriptor(localTable: Boolean) {
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        // Local colour table flag + 256 entries, not interlaced, or no local table.
        out.write(if (localTable) 0x87 else 0x00)
    }

    private fun writePalette() {
        for (i in 0 until PALETTE_SIZE) {
            out.write(palR[i])
            out.write(palG[i])
            out.write(palB[i])
        }
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeAscii(s: String) {
        for (ch in s) out.write(ch.code)
    }

    // ---------------------------------------------------------------- LZW

    private fun writeImageData() {
        out.write(MIN_CODE_SIZE)
        bitBuffer = 0L
        bitCount = 0
        blockLength = 0
        resetDictionary()
        writeCode(CLEAR_CODE)

        var prefix = indices[0].toInt() and 0xFF
        for (p in 1 until pixelCount) {
            val c = indices[p].toInt() and 0xFF
            val key = (c shl 12) or prefix // c: 8 bits, prefix: < 4096 (12 bits)
            var slot = ((c shl 4) xor prefix) % HASH_SIZE
            var found = false
            if (hashCodes[slot] != -1) {
                val step = if (slot == 0) 1 else HASH_SIZE - slot
                while (true) {
                    if (hashCodes[slot] == key) {
                        found = true
                        break
                    }
                    slot -= step
                    if (slot < 0) slot += HASH_SIZE
                    if (hashCodes[slot] == -1) break
                }
            }
            if (found) {
                prefix = hashValues[slot]
                continue
            }
            writeCode(prefix)
            prefix = c
            if (nextCode < MAX_CODES) {
                hashCodes[slot] = key
                hashValues[slot] = nextCode
                nextCode++
            } else {
                // Dictionary full: emit a clear code (at the current width) and start over.
                writeCode(CLEAR_CODE)
                resetDictionary()
            }
        }
        writeCode(prefix)
        writeCode(END_CODE)

        if (bitCount > 0) {
            addByte((bitBuffer and 0xFF).toInt())
            bitBuffer = 0L
            bitCount = 0
        }
        flushBlock()
        out.write(0) // block terminator
    }

    private fun resetDictionary() {
        Arrays.fill(hashCodes, -1)
        codeSize = MIN_CODE_SIZE + 1
        nextCode = END_CODE + 1
    }

    /**
     * Emits [code] with the current width, then widens the code if the decoder (which adds its
     * dictionary entries one code later than we do) will have reached the next power of two.
     */
    private fun writeCode(code: Int) {
        bitBuffer = bitBuffer or (code.toLong() shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            addByte((bitBuffer and 0xFF).toInt())
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        if (codeSize < MAX_CODE_SIZE && nextCode >= (1 shl codeSize)) {
            codeSize++
        }
    }

    private fun addByte(b: Int) {
        block[blockLength++] = b.toByte()
        if (blockLength == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength > 0) {
            out.write(blockLength)
            out.write(block, 0, blockLength)
            blockLength = 0
        }
    }

    // ---------------------------------------------------------------- median-cut quantizer

    /**
     * Median cut over a 5-5-5 histogram. Boxes are first split by population, then by
     * population x volume, always along their widest (perceptually weighted) axis at the
     * weighted median. Palette entries are the exact average of the pixels in each box.
     */
    private class MedianCutQuantizer {
        private val hist = IntArray(HIST_SIZE)
        private val sumR = LongArray(HIST_SIZE)
        private val sumG = LongArray(HIST_SIZE)
        private val sumB = LongArray(HIST_SIZE)
        private val colors = IntArray(HIST_SIZE)
        private val sortKeys = IntArray(HIST_SIZE)

        private val boxLo = IntArray(PALETTE_SIZE)
        private val boxHi = IntArray(PALETTE_SIZE)
        private val boxCount = LongArray(PALETTE_SIZE)
        private val boxMin = IntArray(PALETTE_SIZE * 3)
        private val boxMax = IntArray(PALETTE_SIZE * 3)

        fun quantize(argb: IntArray, n: Int, outR: IntArray, outG: IntArray, outB: IntArray): Int {
            Arrays.fill(hist, 0)
            Arrays.fill(sumR, 0L)
            Arrays.fill(sumG, 0L)
            Arrays.fill(sumB, 0L)
            for (i in 0 until n) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val idx = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                hist[idx]++
                sumR[idx] += r.toLong()
                sumG[idx] += g.toLong()
                sumB[idx] += b.toLong()
            }
            var nColors = 0
            for (idx in 0 until HIST_SIZE) if (hist[idx] > 0) colors[nColors++] = idx

            if (nColors <= PALETTE_SIZE) {
                for (k in 0 until nColors) {
                    val idx = colors[k]
                    val cnt = hist[idx].toLong()
                    outR[k] = (sumR[idx] / cnt).toInt()
                    outG[k] = (sumG[idx] / cnt).toInt()
                    outB[k] = (sumB[idx] / cnt).toInt()
                }
                return maxOf(nColors, 1)
            }

            var boxes = 1
            boxLo[0] = 0
            boxHi[0] = nColors
            computeBox(0)
            while (boxes < PALETTE_SIZE) {
                val byVolume = boxes >= PALETTE_SIZE / 2
                var best = -1
                var bestScore = -1L
                for (b in 0 until boxes) {
                    if (boxHi[b] - boxLo[b] < 2) continue
                    val score = if (byVolume) {
                        val vol = (boxMax[b * 3] - boxMin[b * 3] + 1).toLong() *
                            (boxMax[b * 3 + 1] - boxMin[b * 3 + 1] + 1) *
                            (boxMax[b * 3 + 2] - boxMin[b * 3 + 2] + 1)
                        boxCount[b] * vol
                    } else {
                        boxCount[b]
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = b
                    }
                }
                if (best < 0) break
                split(best, boxes)
                boxes++
            }

            for (b in 0 until boxes) {
                var cnt = 0L
                var r = 0L
                var g = 0L
                var bl = 0L
                for (k in boxLo[b] until boxHi[b]) {
                    val idx = colors[k]
                    cnt += hist[idx]
                    r += sumR[idx]
                    g += sumG[idx]
                    bl += sumB[idx]
                }
                if (cnt == 0L) cnt = 1L
                outR[b] = (r / cnt).toInt()
                outG[b] = (g / cnt).toInt()
                outB[b] = (bl / cnt).toInt()
            }
            return boxes
        }

        private fun computeBox(b: Int) {
            var cnt = 0L
            var rMin = 31; var rMax = 0
            var gMin = 31; var gMax = 0
            var bMin = 31; var bMax = 0
            for (k in boxLo[b] until boxHi[b]) {
                val idx = colors[k]
                cnt += hist[idx]
                val r = idx shr 10
                val g = (idx shr 5) and 31
                val bl = idx and 31
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (bl < bMin) bMin = bl
                if (bl > bMax) bMax = bl
            }
            boxCount[b] = cnt
            boxMin[b * 3] = rMin; boxMax[b * 3] = rMax
            boxMin[b * 3 + 1] = gMin; boxMax[b * 3 + 1] = gMax
            boxMin[b * 3 + 2] = bMin; boxMax[b * 3 + 2] = bMax
        }

        private fun split(b: Int, newBox: Int) {
            val lo = boxLo[b]
            val hi = boxHi[b]
            val rRange = (boxMax[b * 3] - boxMin[b * 3]) * 3
            val gRange = (boxMax[b * 3 + 1] - boxMin[b * 3 + 1]) * 4
            val bRange = (boxMax[b * 3 + 2] - boxMin[b * 3 + 2]) * 2
            val shift = when {
                gRange >= rRange && gRange >= bRange -> 5
                rRange >= bRange -> 10
                else -> 0
            }
            for (k in lo until hi) {
                val idx = colors[k]
                sortKeys[k] = (((idx shr shift) and 31) shl 15) or idx
            }
            Arrays.sort(sortKeys, lo, hi)
            for (k in lo until hi) colors[k] = sortKeys[k] and 0x7FFF

            val half = boxCount[b] / 2
            var cum = 0L
            var splitAt = hi - 1
            for (k in lo until hi - 1) {
                cum += hist[colors[k]]
                if (cum >= half) {
                    splitAt = k + 1
                    break
                }
            }
            boxHi[b] = splitAt
            boxLo[newBox] = splitAt
            boxHi[newBox] = hi
            computeBox(b)
            computeBox(newBox)
        }
    }

    private companion object {
        const val PALETTE_SIZE = 256
        const val HIST_SIZE = 32768
        const val MIN_CODE_SIZE = 8
        const val MAX_CODE_SIZE = 12
        const val MAX_CODES = 4096
        const val CLEAR_CODE = 256
        const val END_CODE = 257
        const val HASH_SIZE = 5003 // prime, ~80% max load with 4096 codes
    }
}
