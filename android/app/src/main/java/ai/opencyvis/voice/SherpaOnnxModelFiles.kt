package ai.opencyvis.voice

import android.os.Build
import java.io.File

object SherpaOnnxModelFiles {
    const val FILES_DIR = "asr/zh_en_small_int8"

    private const val DOWNLOAD_URL_TEMPLATE =
        "https://github.com/opencyvis/opencyvis-phone/releases/download/asr-v1/asr-%s.zip"

    val requiredFiles = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
        "bpe.model"
    )

    val nativeLibs = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-jni.so"
    )

    fun downloadUrl(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return DOWNLOAD_URL_TEMPLATE.format(abi)
    }

    fun allFilesPresent(baseDir: File): Boolean {
        val modelDir = File(baseDir, FILES_DIR)
        return (requiredFiles + nativeLibs).all {
            File(modelDir, it).let { f -> f.exists() && f.length() > 0L }
        }
    }

    fun paths(baseDir: File): Paths {
        val modelDir = File(baseDir, FILES_DIR)
        return Paths(
            modelDir = modelDir,
            encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
            decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
            joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            bpeModel = File(modelDir, "bpe.model").absolutePath
        )
    }

    data class Paths(
        val modelDir: File,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val bpeModel: String
    )
}
