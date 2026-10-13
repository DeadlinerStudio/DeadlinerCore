@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.domain.quickadd.inference

import com.deadliner.interop.mindspore.*
import kotlinx.cinterop.*

internal actual fun createQuickAddInferenceBackend(): QuickAddInferenceBackend = MindSporeQuickAddInferenceBackend()
private class MindSporeQuickAddInferenceBackend : QuickAddInferenceBackend {
    override val name = "MINDSPORE_LITE"
    private var model: COpaquePointer? = null
    private var context: COpaquePointer? = null

    override fun load(modelPath: String) {
        val replacementContext = OH_AI_ContextCreate() ?: error("MindSpore context allocation failed")
        var replacementModel: COpaquePointer? = null
        try {
            OH_AI_ContextSetThreadNum(replacementContext, 2)
            val cpu = OH_AI_DeviceInfoCreate(OH_AI_DEVICETYPE_CPU) ?: error("MindSpore CPU allocation failed")
            OH_AI_DeviceInfoSetEnableFP16(cpu, false)
            // Context takes ownership of device info; destroy it through the context only.
            OH_AI_ContextAddDeviceInfo(replacementContext, cpu)
            replacementModel = OH_AI_ModelCreate() ?: error("MindSpore model allocation failed")
            check(OH_AI_ModelBuildFromFile(replacementModel, modelPath, OH_AI_MODELTYPE_MINDIR, replacementContext) == OH_AI_STATUS_SUCCESS) {
                "MindSpore model build failed"
            }
        } catch (failure: Throwable) {
            destroy(replacementModel, replacementContext)
            throw failure
        }
        close()
        model = replacementModel
        context = replacementContext
    }

    override fun infer(input: QuickAddModelInput): QuickAddModelOutput = memScoped {
        val current = checkNotNull(model) { "Model is not loaded" }
        val nativeInputs = OH_AI_ModelGetInputs(current)
        val expectedNames = nativeInputs.useContents {
            val handles = checkNotNull(handle_list) { "Missing MindSpore input tensors" }
            List(handle_num.toInt()) { index -> OH_AI_TensorGetName(handles[index])?.toKString() ?: error("Unnamed input") }
        }
        require(input.tensors.map { it.name }.toSet() == expectedNames.toSet()) { "Model input names do not match" }
        input.tensors.forEach { tensor ->
            val native = OH_AI_ModelGetInputByTensorName(current, tensor.name) ?: error("Missing input: ${tensor.name}")
            require(readShape(native).contentEquals(tensor.shape)) { "Input shape does not match: ${tensor.name}" }
            val expectedType = when (tensor.type) {
                QuickAddTensorType.INT32 -> OH_AI_DATATYPE_NUMBERTYPE_INT32
                QuickAddTensorType.INT64 -> OH_AI_DATATYPE_NUMBERTYPE_INT64
                QuickAddTensorType.FLOAT32 -> OH_AI_DATATYPE_NUMBERTYPE_FLOAT32
            }
            require(OH_AI_TensorGetDataType(native) == expectedType) { "Input data type does not match: ${tensor.name}" }
            val count = tensorElementCount(tensor.shape)
            require(OH_AI_TensorGetElementNum(native) == count.toLong()) { "Input size does not match" }
            val bytesPerElement = if (tensor.type == QuickAddTensorType.INT64) 8 else 4
            require(OH_AI_TensorGetDataSize(native) == count.toULong() * bytesPerElement.toULong()) { "Input buffer size does not match" }
            val data = OH_AI_TensorGetMutableData(native) ?: error("Missing input buffer")
            when (tensor.type) {
                QuickAddTensorType.INT32 -> for (i in 0 until count) data.reinterpret<IntVar>()[i] = tensor.integers[i].toInt()
                QuickAddTensorType.INT64 -> for (i in 0 until count) data.reinterpret<LongVar>()[i] = tensor.integers[i]
                QuickAddTensorType.FLOAT32 -> for (i in 0 until count) data.reinterpret<FloatVar>()[i] = tensor.floats[i]
            }
        }
        val outputs = alloc<OH_AI_TensorHandleArray>()
        outputs.handle_num = 0u
        outputs.handle_list = null
        check(OH_AI_ModelPredict(current, nativeInputs, outputs.ptr, null, null) == OH_AI_STATUS_SUCCESS) { "MindSpore inference failed" }
        QuickAddModelOutput(input.outputNames.map { name ->
            val native = OH_AI_ModelGetOutputByTensorName(current, name) ?: error("Missing output: $name")
            require(OH_AI_TensorGetDataType(native) == OH_AI_DATATYPE_NUMBERTYPE_FLOAT32) { "Output must be FLOAT32: $name" }
            val shape = readShape(native)
            val count = tensorElementCount(shape)
            require(OH_AI_TensorGetElementNum(native) == count.toLong() && OH_AI_TensorGetDataSize(native) == count.toULong() * 4u) {
                "Output buffer size does not match"
            }
            val data = OH_AI_TensorGetData(native)?.reinterpret<FloatVar>() ?: error("Missing output buffer")
            QuickAddOutputTensor(name, shape, FloatArray(count) { data[it] })
        })
    }

    private fun readShape(tensor: COpaquePointer): LongArray = memScoped {
        val rank = alloc<ULongVar>()
        rank.value = 0u
        val dimensions = OH_AI_TensorGetShape(tensor, rank.ptr) ?: error("Missing tensor shape")
        require(rank.value in 1u..32u) { "Invalid tensor rank" }
        LongArray(rank.value.toInt()) { dimensions[it] }
    }

    override fun close() {
        destroy(model, context)
        model = null
        context = null
    }

    private fun destroy(modelHandle: COpaquePointer?, contextHandle: COpaquePointer?) = memScoped {
        if (modelHandle != null) {
            val pointer = alloc<COpaquePointerVar>(); pointer.value = modelHandle
            OH_AI_ModelDestroy(pointer.ptr)
        }
        if (contextHandle != null) {
            val pointer = alloc<COpaquePointerVar>(); pointer.value = contextHandle
            OH_AI_ContextDestroy(pointer.ptr)
        }
    }
}
