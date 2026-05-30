package dev.akiskev.decentebar.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import dev.akiskev.decentebar.model.ShotLog
import kotlin.math.max

object ShotVideoExporter {

    enum class Format(val label: String, val width: Int, val height: Int) {
        LANDSCAPE("16:9  1920×1080", 1920, 1080),
        SQUARE("1:1   1080×1080", 1080, 1080),
        PORTRAIT("9:16  1080×1920", 1080, 1920)
    }

    fun export(
        context: Context,
        log: ShotLog,
        outputUri: Uri,
        format: Format,
        fps: Int = 30,
        onProgress: (Float) -> Unit
    ) {
        val renderer = ShotFrameRenderer(log, format.width, format.height)
        val durationMs = max(1L, (log.stoppedAtMs ?: 0L) - (log.startedAtMs ?: 0L))
            .coerceAtLeast(log.samples.lastOrNull()?.timeMs ?: 1L)
        val totalFrames = (durationMs * fps / 1000L).toInt().coerceAtLeast(1)
        val frameDurationUs = 1_000_000L / fps

        val mime = "video/avc"
        val mediaFormat = MediaFormat.createVideoFormat(mime, format.width, format.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
            ?: error("Cannot open output URI")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)
        val pixels = IntArray(format.width * format.height)
        val yuv = ByteArray(format.width * format.height * 3 / 2)

        try {
            var frameIndex = 0
            while (frameIndex <= totalFrames) {
                val isLast = frameIndex == totalFrames
                val frameTimeMs = frameIndex * 1000L / fps
                val presentationUs = frameIndex * frameDurationUs

                // Render
                bitmap.eraseColor(android.graphics.Color.BLACK)
                renderer.render(bitmapCanvas, frameTimeMs)
                bitmapToNV12(bitmap, pixels, yuv, format.width, format.height)

                // Feed input
                val inputIdx = codec.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val buf = codec.getInputBuffer(inputIdx)!!
                    buf.clear()
                    buf.put(yuv)
                    val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    codec.queueInputBuffer(inputIdx, 0, yuv.size, presentationUs, flags)
                    frameIndex++
                    onProgress(frameIndex.toFloat() / totalFrames)
                }

                // Drain
                drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted) { idx, started ->
                    trackIndex = idx
                    muxerStarted = started
                }

                if (isLast) break
            }

            // Drain remaining output
            var eos = false
            while (!eos) {
                eos = drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted) { idx, started ->
                    trackIndex = idx
                    muxerStarted = started
                }
            }
        } finally {
            codec.stop()
            codec.release()
            bitmap.recycle()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            pfd.close()
        }
    }

    // Returns true when EOS is reached
    private inline fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        trackIndexIn: Int,
        muxerStartedIn: Boolean,
        onTrack: (Int, Boolean) -> Unit
    ): Boolean {
        var trackIndex = trackIndexIn
        var muxerStarted = muxerStartedIn
        var eos = false

        while (true) {
            val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    onTrack(trackIndex, muxerStarted)
                }
                outputIdx >= 0 -> {
                    val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!isCodecConfig && muxerStarted && bufferInfo.size > 0) {
                        muxer.writeSampleData(trackIndex, codec.getOutputBuffer(outputIdx)!!, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIdx, false)
                    if (isEos) { eos = true; break }
                }
            }
        }
        return eos
    }

    private fun bitmapToNV12(bitmap: Bitmap, pixels: IntArray, out: ByteArray, w: Int, h: Int) {
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var yOff = 0
        var uvOff = w * h
        for (row in 0 until h) {
            for (col in 0 until w) {
                val px = pixels[row * w + col]
                val r = (px shr 16) and 0xff
                val g = (px shr 8) and 0xff
                val b = px and 0xff
                out[yOff++] = (((66 * r + 129 * g + 25 * b + 128) ushr 8) + 16).coerceIn(16, 235).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    out[uvOff++] = (((-38 * r - 74 * g + 112 * b + 128) ushr 8) + 128).coerceIn(16, 240).toByte()
                    out[uvOff++] = (((112 * r - 94 * g - 18 * b + 128) ushr 8) + 128).coerceIn(16, 240).toByte()
                }
            }
        }
    }
}
