package com.smriti.app

import android.content.Context
import java.io.File

/**
 * Finds the `.litertlm` language model provisioned onto the device.
 *
 * The model is never bundled in the APK — Gemma 4 E2B is about 2.6 GB. It arrives one of two
 * ways, both offline: imported in the app with the system file picker ([ModelImporter], which
 * copies into `filesDir/models`), or pushed over adb to `/data/local/tmp/llm` during
 * development. The file Google AI Edge Gallery downloads works as-is.
 */
object ModelProvisioner {

    private const val MIN_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB

    /** Searched in order; the first readable `.litertlm` over 100 MB wins. */
    private fun searchDirs(context: Context) = listOf(
        File(context.filesDir, "models"),
        File("/data/local/tmp/llm")
    )

    /**
     * Preferred first when several models are present. Substring match, case-insensitive.
     * Gemma 4 E2B is the target: it is what AI Edge Gallery ships for phones in this class and
     * it fits an 8 GB device with the camera running. E4B finds more but is too slow here.
     */
    private val preference = listOf("gemma-4-e2b", "gemma-4", "gemma-3n-e2b", "gemma-3n", "gemma", "qwen")

    data class Model(val file: File, val label: String)

    fun locate(context: Context): Model? {
        val found = searchDirs(context)
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.canRead() && it.name.endsWith(".litertlm", ignoreCase = true) }
            .filter { it.length() > MIN_SIZE_BYTES }

        if (found.isEmpty()) return null

        // Stable within a rank: a model the user imported (filesDir) beats an adb-pushed one.
        val best = found.minByOrNull { file ->
            val idx = preference.indexOfFirst { file.name.contains(it, ignoreCase = true) }
            if (idx >= 0) idx else preference.size
        } ?: found.first()

        return Model(best, label(best))
    }

    /** A short human name for the status line, e.g. "gemma-4-E2B-it · 2468 MB". */
    private fun label(file: File): String {
        val name = file.name.removeSuffix(".litertlm")
        val mb = file.length() / (1024 * 1024)
        return "$name · $mb MB"
    }

    fun missingMessage(context: Context): String {
        val dirs = searchDirs(context).joinToString("\n") { "  ${it.absolutePath}" }
        return """
            No language model found.

            Searched for any *.litertlm over 100 MB in:
$dirs

            On the phone: tap the model badge on the capture screen and pick the .litertlm
            file (for example the Gemma 4 E2B file Google AI Edge Gallery downloaded).

            Over adb, copying Gallery's file without it leaving the phone:
              adb shell mkdir -p /data/local/tmp/llm
              adb shell cp /sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/*/gemma-4-E2B-it.litertlm /data/local/tmp/llm/
              adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm

            Everything else in the app works without this — capture, OCR, speech, timeline and
            keyword recall. Only structured extraction and answer synthesis need the model.
        """.trimIndent()
    }
}
