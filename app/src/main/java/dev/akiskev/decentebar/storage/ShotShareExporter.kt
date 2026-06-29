package dev.akiskev.decentebar.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.FileProvider
import dev.akiskev.decentebar.model.ShotLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

enum class ShotShareFormat(
    val label: String,
    val mimeType: String,
    val extension: String
) {
    PNG("PNG", "image/png", "png"),
    HTML("HTML", "text/html", "html"),
    JSON("JSON", "application/json", "json")
}

data class ShotShareFile(
    val uri: Uri,
    val mimeType: String,
    val subject: String
)

object ShotImageExporter {
    const val DEFAULT_WIDTH = 1600
    const val DEFAULT_HEIGHT = 1200

    fun export(
        log: ShotLog,
        outputFile: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val renderer = ShotFrameRenderer(log, width, height)
            renderer.render(canvas, ShotRenderTiming.finalFrameTimeMs(log))
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}

object ShotCompareImageExporter {
    const val DEFAULT_WIDTH = 1600
    const val DEFAULT_HEIGHT = 2000

    fun export(
        shotA: ShotLog,
        shotB: ShotLog,
        outputFile: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            ShotCompareRenderer(shotA, shotB, width, height).render(canvas)
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}

object ShotShareExporter {
    fun clearShareCache(context: Context) {
        shareDir(context).deleteRecursively()
        shareDir(context).mkdirs()
    }

    fun createShareFile(
        context: Context,
        log: ShotLog,
        format: ShotShareFormat
    ): ShotShareFile {
        clearShareCache(context)
        val file = File(shareDir(context), "${safeFileBase(log)}.${format.extension}")
        when (format) {
            ShotShareFormat.PNG -> ShotImageExporter.export(log, file)
            ShotShareFormat.HTML -> file.writeText(ShotHtmlExporter.export(log))
            ShotShareFormat.JSON -> file.writeText(ShotLogCodec.encode(log))
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return ShotShareFile(
            uri = uri,
            mimeType = format.mimeType,
            subject = "${log.beansName ?: log.profileName} - Decent E-Bar shot"
        )
    }

    fun createCompareShareFile(
        context: Context,
        shotA: ShotLog,
        shotB: ShotLog,
        format: ShotShareFormat
    ): ShotShareFile {
        require(format != ShotShareFormat.JSON) { "Comparison share supports PNG and HTML" }
        clearShareCache(context)
        val file = File(shareDir(context), "${safeCompareFileBase(shotA, shotB)}.${format.extension}")
        when (format) {
            ShotShareFormat.PNG -> ShotCompareImageExporter.export(shotA, shotB, file)
            ShotShareFormat.HTML -> file.writeText(ShotCompareHtmlExporter.export(shotA, shotB))
            ShotShareFormat.JSON -> error("Comparison share supports PNG and HTML")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return ShotShareFile(
            uri = uri,
            mimeType = format.mimeType,
            subject = "${displayLabel(shotA)} vs ${displayLabel(shotB)} - Decent E-Bar compare"
        )
    }

    private fun shareDir(context: Context): File =
        File(context.cacheDir, SHARE_DIR)

    private fun safeFileBase(log: ShotLog): String {
        val base = listOfNotNull(log.beansName, log.profileName)
            .joinToString("-")
            .ifBlank { "shot" }
        return base
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "-")
            .take(80)
    }

    private fun safeCompareFileBase(shotA: ShotLog, shotB: ShotLog): String {
        val left = safeFileBase(shotA).take(38)
        val right = safeFileBase(shotB).take(38)
        return "compare-$left-vs-$right".take(110)
    }

    private fun displayLabel(log: ShotLog): String =
        log.beansName?.takeIf { it.isNotBlank() } ?: log.profileName

    private const val SHARE_DIR = "shot-shares"
}

object ShotRenderTiming {
    fun finalFrameTimeMs(log: ShotLog): Long {
        val absoluteDuration = if (log.startedAtMs != null && log.stoppedAtMs != null) {
            log.stoppedAtMs - log.startedAtMs
        } else {
            0L
        }
        return max(1L, absoluteDuration)
            .coerceAtLeast(log.samples.lastOrNull()?.timeMs ?: 1L)
    }
}
