@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.domain.quickadd.inference

import kotlinx.cinterop.*
import platform.CoreML.*
import platform.Foundation.*

internal actual fun createQuickAddInferenceBackend(): QuickAddInferenceBackend = CoreMlQuickAddInferenceBackend()
private class CoreMlQuickAddInferenceBackend : QuickAddInferenceBackend {
    override val name = "CORE_ML"
    private var model: MLModel? = null

    override fun load(modelPath: String) = memScoped {
        require(modelPath.endsWith(".mlmodelc")) { "Core ML requires a compiled .mlmodelc directory" }
        val failure = alloc<ObjCObjectVar<NSError?>>()
        failure.value = null
        // Keep the validated float32 path: GPU float16 can change confidence decisions.
        val config = MLModelConfiguration().apply { computeUnits = MLComputeUnitsCPUOnly }
        val replacement = MLModel.modelWithContentsOfURL(NSURL.fileURLWithPath(modelPath), config, failure.ptr)
            ?: error(failure.value?.localizedDescription ?: "Core ML model load failed")
        model = replacement
    }

    override fun infer(input: QuickAddModelInput): QuickAddModelOutput = memScoped {
        val current = checkNotNull(model) { "Model is not loaded" }
        val descriptions = current.modelDescription.inputDescriptionsByName
        require(input.tensors.map { it.name }.toSet() == descriptions.keys) { "Model input names do not match" }
        val features: Map<Any?, MLFeatureValue> = input.tensors.associate { tensor ->
            val description = descriptions[tensor.name] as? MLFeatureDescription ?: error("Unknown input: ${tensor.name}")
            val constraint = description.multiArrayConstraint ?: error("Input must be MLMultiArray: ${tensor.name}")
            val expectedType = if (tensor.type == QuickAddTensorType.FLOAT32) MLMultiArrayDataTypeFloat32 else MLMultiArrayDataTypeInt32
            require(constraint.dataType == expectedType) { "Input data type does not match: ${tensor.name}" }
            require(constraint.shape.map { (it as NSNumber).longLongValue }.toLongArray().contentEquals(tensor.shape)) {
                "Input shape does not match: ${tensor.name}"
            }
            if (tensor.type == QuickAddTensorType.INT64) require(tensor.integers.all {
                it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            }) { "Core ML token IDs must fit INT32: ${tensor.name}" }
            val failure = alloc<ObjCObjectVar<NSError?>>()
            failure.value = null
            val array = MLMultiArray.create(tensor.shape.map { NSNumber(longLong = it) }, expectedType, failure.ptr)
                ?: error(failure.value?.localizedDescription ?: "Core ML input allocation failed")
            val count = tensorElementCount(tensor.shape)
            for (index in 0 until count) {
                val value = if (tensor.type == QuickAddTensorType.FLOAT32) NSNumber(float = tensor.floats[index])
                    else NSNumber(int = tensor.integers[index].toInt())
                array.setObject(value, index.toLong())
            }
            tensor.name to MLFeatureValue.featureValueWithMultiArray(array)
        }
        val failure = alloc<ObjCObjectVar<NSError?>>()
        failure.value = null
        val provider = MLDictionaryFeatureProvider(features, failure.ptr)
        val prediction = current.predictionFromFeatures(provider, failure.ptr)
            ?: error(failure.value?.localizedDescription ?: "Core ML prediction failed")
        QuickAddModelOutput(input.outputNames.map { name ->
            val array = prediction.featureValueForName(name)?.multiArrayValue ?: error("Missing array output: $name")
            require(array.dataType == MLMultiArrayDataTypeFloat32 || array.dataType == MLMultiArrayDataTypeDouble ||
                array.dataType == MLMultiArrayDataTypeFloat16) { "Output must be floating point: $name" }
            val shape = array.shape.map { (it as NSNumber).longLongValue }.toLongArray()
            val values = FloatArray(tensorElementCount(shape)) { index -> array.objectAtIndexedSubscript(index.toLong()).floatValue }
            QuickAddOutputTensor(name, shape, values)
        })
    }

    override fun close() { model = null }
}
