package dev.akiskev.decentebar.ui

import android.content.Context
import android.net.Uri

internal fun sanitizeFilename(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return cleaned.ifBlank { "untitled" }
}

internal fun writeJsonToUri(context: Context, uri: Uri, content: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
    true
}.getOrDefault(false)

internal fun readJsonFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
}.getOrNull()
