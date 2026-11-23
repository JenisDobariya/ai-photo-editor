package com.photo_editor.my_photo_editor.ui.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.math.roundToInt

/**
 * What this phone's video decoders can actually play, read from MediaCodecList, so the
 * viewer can explain a failure ("4K · 60 fps · HDR — your phone plays up to 1080p")
 * instead of showing a black screen.
 */
object VideoSupport {

    /** Dolby Vision files carry an HEVC (or AVC) base layer that ordinary decoders play. */
    private fun decoderMime(mime: String?): String = when (mime) {
        null -> MimeTypes.VIDEO_H264
        MimeTypes.VIDEO_DOLBY_VISION -> MimeTypes.VIDEO_H265
        else -> mime
    }

    private val decoders: List<MediaCodecInfo> by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun capabilities(mime: String?): List<MediaCodecInfo.VideoCapabilities> {
        val type = decoderMime(mime)
        return decoders.mapNotNull { info ->
            if (info.supportedTypes.none { it.equals(type, ignoreCase = true) }) return@mapNotNull null
            try {
                info.getCapabilitiesForType(type).videoCapabilities
            } catch (e: Exception) {
                null
            }
        }
    }

    /** True if any decoder for this format accepts this frame size (either orientation). */
    fun isSizeSupported(mime: String?, width: Int, height: Int): Boolean =
        capabilities(mime).any { it.isSizeSupported(width, height) || it.isSizeSupported(height, width) }

    /** Largest common resolution this phone can decode for [mime], e.g. "1080p" or "4K". */
    fun maxPlayableLabel(mime: String?): String? {
        val caps = capabilities(mime)
        if (caps.isEmpty()) return null
        val steps = listOf(2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p")
        return steps.firstOrNull { (h, _) ->
            val w = h * 16 / 9
            caps.any { it.isSizeSupported(w, h) || it.isSizeSupported(w, h + 8) || it.isSizeSupported(h, w) }
        }?.second
    }

    /** "4K · 60 fps · Dolby Vision HDR" */
    fun describe(format: Format): String {
        val shortSide = minOf(format.width, format.height)
        val resolution = when {
            shortSide >= 2100 -> "4K"
            shortSide >= 1400 -> "1440p"
            shortSide >= 1060 -> "1080p"
            shortSide >= 700 -> "720p"
            else -> "${format.width}×${format.height}"
        }
        val parts = mutableListOf(resolution)
        if (format.frameRate > 0) parts += "${format.frameRate.roundToInt()} fps"
        val hdr = format.colorInfo?.colorTransfer.let {
            it == C.COLOR_TRANSFER_HLG || it == C.COLOR_TRANSFER_ST2084
        }
        when {
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> parts += "Dolby Vision HDR"
            hdr -> parts += "HDR"
        }
        when (decoderMime(format.sampleMimeType)) {
            MimeTypes.VIDEO_H265 -> parts += "HEVC"
            MimeTypes.VIDEO_H264 -> parts += "H.264"
            MimeTypes.VIDEO_AV1 -> parts += "AV1"
            MimeTypes.VIDEO_VP9 -> parts += "VP9"
        }
        return parts.joinToString(" · ")
    }
}
