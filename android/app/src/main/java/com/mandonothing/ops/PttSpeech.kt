package com.mandonothing.ops

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Run after release so voice detection does not gate the live microphone. */
internal class PttSpeech(assets: AssetManager) {
    private val detector by lazy {
        runCatching {
            Vad(assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.74f,
                minSpeechDuration = 0.16f,
                minSilenceDuration = 0.16f,
            )))
        }.getOrNull()
    }

    @Synchronized
    fun containsSpeech(samples: ShortArray): Boolean {
        if (!hasPttSignal(samples)) return false
        val vad = detector ?: return hasPttSignal(samples)
        return runCatching {
            vad.reset()
            val frame = FloatArray(512)
            for (start in samples.indices step frame.size) {
                for (index in frame.indices) frame[index] = samples.getOrElse(start + index) { 0 } / 32768f
                vad.acceptWaveform(frame)
                if (hasAudibleSegment(vad)) return true
            }
            vad.flush()
            hasAudibleSegment(vad)
        }.getOrElse { hasPttSignal(samples) }
    }

    private fun hasAudibleSegment(vad: Vad): Boolean {
        while (!vad.empty()) {
            val segment = vad.front().samples
            vad.pop()
            val pcm = ShortArray(segment.size) {
                (segment[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (hasPttSignal(pcm)) return true
        }
        return false
    }
}
