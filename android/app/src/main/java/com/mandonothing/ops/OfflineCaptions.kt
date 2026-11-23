package com.mandonothing.ops

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class CaptionAsset(val name: String, val bytes: Long, val sha256: String)

internal class OfflineCaptions private constructor(context: Context) {
    private val directory = File(context.filesDir, "sensevoice-v1")
    private val downloadLock = Mutex()
    private val decoderLock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    val installed: Boolean get() = assets.all { File(directory, it.name).length() == it.bytes }

    suspend fun install(client: OpsClient, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            check(directory.isDirectory || directory.mkdirs())
            val total = assets.sumOf { it.bytes }
            var complete = 0L
            for (asset in assets) {
                val final = File(directory, asset.name)
                if (final.length() == asset.bytes) {
                    complete += asset.bytes
                    continue
                }
                val partial = File(directory, asset.name + ".part")
                if (partial.length() > asset.bytes) partial.delete()
                if (partial.length() < asset.bytes)
                    client.downloadCaptionAsset(asset, partial) { current ->
                        progress(((complete + current) * 100 / total).toInt())
                    }
                val sha = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        coroutineContext.ensureActive()
                        sha.update(buffer, 0, count)
                    }
                }
                val actual = sha.digest().joinToString("") { "%02x".format(it) }
                if (partial.length() != asset.bytes || actual != asset.sha256) {
                    partial.delete()
                    error("模型包校验失败，请重试")
                }
                check(partial.renameTo(final))
                complete += asset.bytes
            }
            progress(100)
        }
    }

    private fun model(): OfflineRecognizer? {
        if (!installed) return null
        return recognizer ?: OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(directory, "model.int8.onnx").absolutePath, language = "auto", useInverseTextNormalization = true,
                    ),
                    tokens = File(directory, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        ).also { recognizer = it }
    }

    suspend fun warm() = withContext(Dispatchers.Default) {
        decoderLock.withLock { runCatching { model() != null }.getOrDefault(false) }
    }

    suspend fun decode(samples: ShortArray): String? = withContext(Dispatchers.Default) {
        decoderLock.withLock {
            if (!installed || samples.none { it > 196 || it < -196 }) return@withLock null
            runCatching {
                val engine = model() ?: return@withLock null
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, sampleRate = 16000)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim().take(4000).ifBlank { null }
                } finally {
                    stream.release()
                }
            }.getOrNull()
        }
    }

    companion object {
        private val assets = listOf(
            CaptionAsset("model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            CaptionAsset("tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        )
        @Volatile private var instance: OfflineCaptions? = null
        fun forApp(context: Context) = instance ?: synchronized(this) {
            instance ?: OfflineCaptions(context.applicationContext).also { instance = it }
        }
    }
}
