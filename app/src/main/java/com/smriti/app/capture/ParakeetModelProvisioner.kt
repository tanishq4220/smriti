package com.smriti.app.capture

import android.content.Context
import java.io.File

/**
 * Finds NVIDIA Parakeet TDT 0.6B v2 (sherpa-onnx int8 export): encoder, decoder, joiner and
 * tokens.txt in one directory. About 630 MB, English only, never bundled in the APK.
 */
object ParakeetModelProvisioner {

    data class Paths(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    private fun searchDirs(context: Context) = listOf(
        File(context.filesDir, "parakeet"),
        File("/data/local/tmp/parakeet")
    )

    fun locate(context: Context): Paths? = searchDirs(context).firstNotNullOfOrNull { dir ->
        val files = dir.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile && it.canRead() }
            ?: return@firstNotNullOfOrNull null
        fun pick(part: String) = files
            .filter { it.name.contains(part, ignoreCase = true) && it.name.endsWith(".onnx") }
            .minByOrNull { if (it.name.contains("int8")) 0 else 1 }
        val tokens = files.firstOrNull { it.name.contains("tokens") && it.name.endsWith(".txt") }
        val encoder = pick("encoder")
        val decoder = pick("decoder")
        val joiner = pick("joiner")
        if (encoder != null && decoder != null && joiner != null && tokens != null) {
            Paths(encoder, decoder, joiner, tokens)
        } else {
            null
        }
    }

    fun missingMessage(): String = """
        Parakeet model not found. Push it with:
          adb shell mkdir -p /data/local/tmp/parakeet
          adb push encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt /data/local/tmp/parakeet/
        from k2-fsa/sherpa-onnx releases: sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2
    """.trimIndent()
}
