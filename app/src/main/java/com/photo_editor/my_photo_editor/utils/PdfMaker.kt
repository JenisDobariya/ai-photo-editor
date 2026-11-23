package com.photo_editor.my_photo_editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a PDF with one photo per page.
 *
 * Why not android.graphics.pdf.PdfDocument: it records every page as a picture and only
 * encodes them in writeTo(), so all page bitmaps stay in native memory until the very end
 * (12 pages at 300 dpi is ~400 MB), and it embeds photos losslessly (several MB per page).
 * This writer streams each page straight to disk as a JPEG image (DCTDecode), so memory use
 * stays at one photo at a time and a 150 dpi A4 page is a few hundred KB.
 */
object PdfMaker {

    /** PDF units are points (1/72 inch). */
    enum class PageSize(val widthPt: Float, val heightPt: Float) {
        A4(595f, 842f),
        LETTER(612f, 792f),
        /** The page takes the photo's shape (long edge = A4 long edge). */
        FIT(0f, 0f)
    }

    enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

    enum class Margin(val points: Float) { NONE(0f), SMALL(18f), LARGE(42f) }

    /** [dpi] decides how many pixels are drawn per page, which keeps the file size in check. */
    enum class Quality(val dpi: Int, val jpegQuality: Int) { STANDARD(150, 85), HIGH(300, 90) }

    data class Options(
        val pageSize: PageSize = PageSize.A4,
        val orientation: Orientation = Orientation.AUTO,
        val margin: Margin = Margin.SMALL,
        val quality: Quality = Quality.STANDARD
    )

    /** [pages] written, [failed] photos that couldn't be read (skipped). */
    data class Result(val pages: Int, val failed: Int)

    private const val FIT_LONG_EDGE_PT = 842f

    /**
     * Writes the PDF into [output]. Call from a background dispatcher. Unreadable photos are
     * skipped. Throws IOException if the file can't be written; honours coroutine cancellation.
     */
    suspend fun create(
        context: Context,
        uris: List<Uri>,
        options: Options,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        var pages = 0
        var failed = 0
        val jpeg = ExposedByteArrayOutputStream(512 * 1024)
        PdfWriter(output).use { writer ->
            val catalogNum = writer.reserve()
            val pagesNum = writer.reserve()
            writer.beginObj(catalogNum)
            writer.write("<< /Type /Catalog /Pages $pagesNum 0 R >>")
            writer.endObj()

            val kids = ArrayList<Int>()
            uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onProgress(index, uris.size)
                val ok = try {
                    jpeg.reset()
                    val page = renderPage(context, uri, options, jpeg)
                    if (page == null) {
                        false
                    } else {
                        kids += writer.writeImagePage(pagesNum, page, jpeg)
                        true
                    }
                } catch (e: OutOfMemoryError) {
                    false
                } catch (e: java.io.IOException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
                if (ok) pages++ else failed++
            }
            onProgress(uris.size, uris.size)

            writer.beginObj(pagesNum)
            writer.write("<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>")
            writer.endObj()
            writer.finish(catalogNum)
        }
        return Result(pages, failed)
    }

    // ------------------------------------------------------------------ layout & rendering

    private class PageSpec(
        val pageW: Float, val pageH: Float,
        val drawX: Float, val drawY: Float, val drawW: Float, val drawH: Float,
        val pixelW: Int, val pixelH: Int
    )

    /** Decodes the photo at the size the page needs and JPEG-encodes it into [out]. */
    private fun renderPage(context: Context, uri: Uri, options: Options, out: ByteArrayOutputStream): PageSpec? {
        val size = uprightSize(context, uri) ?: return null
        val imgW = size.first.toFloat()
        val imgH = size.second.toFloat()
        val m = options.margin.points

        var pageW: Float
        var pageH: Float
        if (options.pageSize == PageSize.FIT) {
            val s = FIT_LONG_EDGE_PT / max(imgW, imgH)
            pageW = imgW * s + 2 * m
            pageH = imgH * s + 2 * m
        } else {
            pageW = options.pageSize.widthPt
            pageH = options.pageSize.heightPt
            val landscape = when (options.orientation) {
                Orientation.AUTO -> imgW > imgH
                Orientation.PORTRAIT -> false
                Orientation.LANDSCAPE -> true
            }
            if (landscape) {
                val t = pageW; pageW = pageH; pageH = t
            }
        }
        val contentW = pageW - 2 * m
        val contentH = pageH - 2 * m
        val fit = min(contentW / imgW, contentH / imgH)
        val neededPx = ceil(max(imgW * fit, imgH * fit) / 72f * options.quality.dpi).toInt().coerceAtLeast(16)

        var bitmap = BitmapLoader.decodeUpright(context, uri, neededPx) ?: return null
        try {
            if (bitmap.hasAlpha()) {
                // JPEG has no transparency: flatten onto a white page instead of black.
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                bitmap.recycle()
                bitmap = flat
            }
            // Place using the decoded bitmap's real aspect, centred inside the margins.
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val scale = min(contentW / bw, contentH / bh)
            val dw = bw * scale
            val dh = bh * scale
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, out)) return null
            return PageSpec(
                pageW, pageH,
                (pageW - dw) / 2f, (pageH - dh) / 2f, dw, dh,
                bitmap.width, bitmap.height
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Width/height as the photo is shown (EXIF rotation applied), from the header only. */
    private fun uprightSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val matrix = BitmapLoader.exifMatrix(context, uri)
            ?: return bounds.outWidth to bounds.outHeight
        val r = RectF(0f, 0f, bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
        matrix.mapRect(r)
        return Math.round(r.width()) to Math.round(r.height())
    }

    // ------------------------------------------------------------------ minimal PDF writer

    private class ExposedByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun buffer(): ByteArray = buf
    }

    private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { target.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); count += len }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    private class PdfWriter(file: File) : Closeable {
        private val out = CountingOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
        /** Byte offset of each object; index = object number - 1. */
        private val offsets = ArrayList<Long>()

        init {
            write("%PDF-1.4\n%âãÏÓ\n")
        }

        fun reserve(): Int {
            offsets.add(-1L)
            return offsets.size
        }

        fun beginObj(num: Int) {
            offsets[num - 1] = out.count
            write("$num 0 obj\n")
        }

        fun endObj() = write("\nendobj\n")

        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        private fun stream(num: Int, dict: String, data: ByteArray, length: Int) {
            beginObj(num)
            write("<< $dict /Length $length >>\nstream\n")
            out.write(data, 0, length)
            write("\nendstream")
            endObj()
        }

        /** Writes image + content stream + page objects, returns the page object number. */
        fun writeImagePage(parentNum: Int, spec: PageSpec, jpeg: ExposedByteArrayOutputStream): Int {
            val imageNum = reserve()
            val contentNum = reserve()
            val pageNum = reserve()
            stream(
                imageNum,
                "/Type /XObject /Subtype /Image /Width ${spec.pixelW} /Height ${spec.pixelH} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode",
                jpeg.buffer(), jpeg.size()
            )
            val content = String.format(
                Locale.US, "q\n%.3f 0 0 %.3f %.3f %.3f cm\n/Im0 Do\nQ\n",
                spec.drawW, spec.drawH, spec.drawX, spec.drawY
            ).toByteArray(Charsets.ISO_8859_1)
            stream(contentNum, "", content, content.size)
            beginObj(pageNum)
            write(
                String.format(
                    Locale.US,
                    "<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %.3f %.3f] " +
                        "/Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>",
                    parentNum, spec.pageW, spec.pageH, imageNum, contentNum
                )
            )
            endObj()
            return pageNum
        }

        fun finish(rootNum: Int) {
            val xref = out.count
            val sb = StringBuilder()
            sb.append("xref\n0 ").append(offsets.size + 1).append('\n')
            sb.append("0000000000 65535 f \n")
            offsets.forEach { sb.append(String.format(Locale.US, "%010d 00000 n \n", it)) }
            sb.append("trailer\n<< /Size ").append(offsets.size + 1).append(" /Root ").append(rootNum).append(" 0 R >>\n")
            sb.append("startxref\n").append(xref).append("\n%%EOF\n")
            write(sb.toString())
            out.flush()
        }

        override fun close() = out.close()
    }
}
