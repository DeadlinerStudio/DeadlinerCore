package com.deadliner.domain.quickadd.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit experimental protocol. Legacy schema 1 remains independently usable. */
@Serializable
internal data class KivoModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val protocol: String,
    val experimental: Boolean,
    val productionEligible: Boolean,
    val platforms: Map<String, Map<String, QuickAddModelArtifact>>,
    val tokenizer: KivoTokenizerConfig,
    val calibration: Map<String, KivoCalibration>,
    val thresholds: Map<String, Double?>,
) {
    fun sources(platform: String): Map<String, QuickAddModelSource> {
        require(schemaVersion == 2 && protocol == "kivo-joint-v1" && experimental && !productionEligible)
        tokenizer.validate()
        require(calibration.keys == setOf("TASK", "HABIT") && thresholds.keys == calibration.keys)
        calibration.values.forEach { require(it.slope.isFinite() && it.slope > 0 && it.bias.isFinite()) }
        thresholds.values.forEach { require(it == null || it.isFinite() && it in 0.0..1.0) }
        val format = when (platform) {
            "android" -> QuickAddModelFormat.ONNX
            "ios" -> QuickAddModelFormat.CORE_ML
            "ohos" -> QuickAddModelFormat.MINDSPORE
            else -> error("Unsupported Kivo platform")
        }
        val assets = platforms[platform] ?: error("Kivo format unavailable for $platform")
        require(assets.keys == setOf("encoder", "TASK", "HABIT"))
        return assets.mapValues { (role, artifact) ->
            QuickAddModelSource("$modelId-$role", artifact.url, artifact.sha256, artifact.sizeBytes, format).also { it.validate() }
        }
    }

    companion object {
        fun parse(value: String): KivoModelManifest {
            require(value.length <= 8 * 1024 * 1024)
            return Json.decodeFromString<KivoModelManifest>(value)
        }
    }
}

@Serializable internal data class KivoCalibration(val slope: Double, val bias: Double)

@Serializable
internal data class KivoTokenizerConfig(
    val vocabulary: List<String>,
    val normalization: Map<String, String>,
) {
    fun validate() {
        require(vocabulary.size in 4..100000 && vocabulary.distinct().size == vocabulary.size)
        require(vocabulary.all { it.isNotEmpty() && it.length <= 128 })
        require(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]").all { it in vocabulary })
        require(normalization.size <= 30000 && normalization.all { (key, value) ->
            unicodeCharacters(key).size == 1 && unicodeCharacters(value).size <= 8
        })
    }
}
