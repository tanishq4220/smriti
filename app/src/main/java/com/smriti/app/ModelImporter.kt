package com.smriti.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a `.litertlm` model the user picked with the system file picker into
 * `filesDir/models`, where [ModelProvisioner] looks first.
 *
 * Why a copy and not a path: Android gives an app no way to read another app's private
 * storage, so the Gemma file AI Edge Gallery already downloaded cannot be used where it sits.
 * The picker hands us a content URI with one-time read access; streaming it into our own
 * storage is the only way to keep it across restarts. No network is involved at any point.
 *
 * The copy goes to a `.part` file first and is renamed only once complete, so a cancelled or
 * failed import never leaves a truncated model that [ModelProvisioner] would then try to load.
 */
object ModelImporter {

    /** Bytes copied so far and the total, when the provider reports one. */
    fun interface Progress {
        fun onProgress(copied: Long, total: Long?)
    }

    suspend fun import(context: Context, uri: Uri, progress: Progress): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (rawName, size) = describe(context, uri)
                // A provider-supplied display name is untrusted: keep it to a bare file name.
                val name = rawName.substringAfterLast('/').ifBlank { "model.litertlm" }
                require(name.endsWith(".litertlm", ignoreCase = true)) {
                    "not a .litertlm model: $name"
                }

                val dir = File(context.filesDir, "models").apply { mkdirs() }
                val target = File(dir, name)
                val part = File(dir, "$name.part")

                val free = dir.usableSpace
                if (size != null && size > free) {
                    throw IOException("needs ${size / MB} MB, only ${free / MB} MB free")
                }

                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("could not open $name")
                    input.use { src ->
                        part.outputStream().use { dst ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                ensureActive()
                                val n = src.read(buf)
                                if (n < 0) break
                                dst.write(buf, 0, n)
                                copied += n
                                if (copied - lastReport >= 16 * MB) {
                                    progress.onProgress(copied, size)
                                    lastReport = copied
                                }
                            }
                            progress.onProgress(copied, size)
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("could not finalise $name")
                } finally {
                    part.delete()
                }

                // One model at a time: a second 2.6 GB file is wasted storage, and keeping
                // only the newest import means the user's latest pick is the one that loads.
                dir.listFiles()
                    ?.filter { it != target && it.name.endsWith(".litertlm", ignoreCase = true) }
                    ?.forEach { it.delete() }

                target
            }
        }

    private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "model.litertlm"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "model.litertlm") to null
    }

    private const val MB = 1024L * 1024
}
