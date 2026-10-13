package com.deadliner.domain.quickadd.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Release configuration, not a user preference. Never guess a production download URL. */
@Serializable
data class QuickAddModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val artifacts: Map<String, QuickAddModelArtifact>,
    val codec: QuickAddCharacterConfig,
) {
    fun source(platform: String): QuickAddModelSource? {
        require(schemaVersion == 1) { "Unsupported quick-add manifest version" }
        codec.validate()
        val artifact = artifacts[platform] ?: return null
        val format = when (platform) {
            "android" -> QuickAddModelFormat.ONNX
            "ios" -> QuickAddModelFormat.CORE_ML
            "ohos" -> QuickAddModelFormat.MINDSPORE
            else -> error("Unsupported quick-add platform")
        }
        return QuickAddModelSource(modelId, artifact.url, artifact.sha256, artifact.sizeBytes, format).also { it.validate() }
    }

    companion object {
        fun parse(json: String): QuickAddModelManifest {
            require(json.length <= 8 * 1024 * 1024) { "Model manifest is too large" }
            return Json.decodeFromString(json)
        }
    }
}

@Serializable
data class QuickAddModelArtifact(val url: String, val sha256: String, val sizeBytes: Long)

@Serializable
data class QuickAddCharacterConfig(
    val vocabulary: List<String>,
    val maxLength: Int = 128,
    val intentThreshold: Double = 0.85,
    val slotThreshold: Double = 0.75,
    val intentMargin: Double = 0.25,
    val maxUnknownRatio: Double = 0.1,
    val locale: String = "zh",
    val wordPiece: QuickAddWordPieceConfig? = null,
) {
    internal fun validate() {
        require(locale in setOf("zh", "en")) { "Unsupported quick-add locale" }
        require(maxLength in 8..512)
        require(vocabulary.size in 3..100000 && vocabulary.distinct().size == vocabulary.size)
        require(vocabulary.take(2) == listOf("[PAD]", "[UNK]"))
        require(vocabulary.drop(2).all { unicodeCharacters(it).size == 1 })
        require(intentThreshold in 0.5..1.0 && slotThreshold in 0.5..1.0)
        require(intentMargin in 0.0..1.0 && maxUnknownRatio in 0.0..0.5)
        wordPiece?.validate()
    }
}

@Serializable
data class QuickAddWordPieceConfig(
    val vocabulary: List<String>,
    val lowercase: Boolean = true,
    val normalizationOverrides: Map<String, String> = emptyMap(),
) {
    internal fun validate() {
        require(vocabulary.size in 4..100000 && vocabulary.distinct().size == vocabulary.size)
        require(vocabulary.all { it.isNotEmpty() && it.length <= 128 })
        require(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]").all { it in vocabulary })
        require(normalizationOverrides.size <= 20000 && normalizationOverrides.all { (from, to) ->
            unicodeCharacters(from).size == 1 && unicodeCharacters(to).size == 1
        })
    }
}

/** Preserve supplementary characters as one token; offsets are never UTF-16 half-surrogates. */
internal fun unicodeCharacters(text: String): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val count = if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        result += text.substring(index, index + count)
        index += count
    }
    return result
}
