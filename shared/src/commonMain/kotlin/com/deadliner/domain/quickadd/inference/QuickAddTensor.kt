package com.deadliner.domain.quickadd.inference

/** Dense row-major input. Exported models must preserve these names and shapes. */
enum class QuickAddTensorType { INT32, INT64, FLOAT32 }

data class QuickAddTensor(
    val name: String,
    val shape: LongArray,
    val type: QuickAddTensorType,
    val integers: LongArray = longArrayOf(),
    val floats: FloatArray = floatArrayOf(),
) {
    internal fun validate() {
        require(name.isNotBlank()) { "Tensor name is required" }
        val count = tensorElementCount(shape)
        when (type) {
            QuickAddTensorType.FLOAT32 -> {
                require(integers.isEmpty() && floats.size == count) { "Invalid FLOAT32 payload: $name" }
                require(floats.all { it.isFinite() }) { "Non-finite input: $name" }
            }
            else -> {
                require(floats.isEmpty() && integers.size == count) { "Invalid integer payload: $name" }
                if (type == QuickAddTensorType.INT32) require(integers.all {
                    it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                }) { "INT32 overflow: $name" }
            }
        }
    }
    internal fun snapshot() = copy(shape = shape.copyOf(), integers = integers.copyOf(), floats = floats.copyOf())
}

data class QuickAddModelInput(val tensors: List<QuickAddTensor>, val outputNames: List<String>) {
    internal fun validate() {
        require(tensors.isNotEmpty()) { "Model inputs are required" }
        require(tensors.map { it.name }.distinct().size == tensors.size) { "Duplicate input names" }
        require(outputNames.isNotEmpty() && outputNames.all { it.isNotBlank() } &&
            outputNames.distinct().size == outputNames.size) { "Unique output names are required" }
        tensors.forEach { it.validate() }
    }
    internal fun snapshot() = copy(tensors = tensors.map { it.snapshot() }, outputNames = outputNames.toList())
}

/** Float logits, copied out of platform-owned storage before returning. */
data class QuickAddOutputTensor(val name: String, val shape: LongArray, val values: FloatArray)
data class QuickAddModelOutput(val tensors: List<QuickAddOutputTensor>) {
    internal fun validate(outputNames: List<String>) {
        require(tensors.map { it.name } == outputNames) { "Output names/order do not match" }
        tensors.forEach {
            require(tensorElementCount(it.shape) == it.values.size && it.values.all { v -> v.isFinite() }) {
                "Invalid model output: ${it.name}"
            }
        }
    }
}

internal fun tensorElementCount(shape: LongArray): Int {
    require(shape.isNotEmpty()) { "Scalar tensors are not supported" }
    var count = 1L
    shape.forEach { dimension ->
        require(dimension > 0 && dimension <= Int.MAX_VALUE && count <= Int.MAX_VALUE / dimension) {
            "Tensor shape is empty, dynamic or too large"
        }
        count *= dimension
    }
    return count.toInt()
}
