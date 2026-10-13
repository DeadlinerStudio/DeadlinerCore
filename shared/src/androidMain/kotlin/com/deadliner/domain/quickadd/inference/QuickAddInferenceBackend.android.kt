package com.deadliner.domain.quickadd.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

internal class OnnxQuickAddInferenceBackend : QuickAddInferenceBackend {
    override val name = "ONNX_RUNTIME"
    private var session: OrtSession? = null
    // ORT owns a process-wide environment. Do not close it with an individual session.
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    override fun load(modelPath: String) = try {
        val replacement = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(modelPath, options)
        }
        val previous = session
        session = replacement
        previous?.close()
        Unit
    } catch (failure: LinkageError) {
        throw IllegalStateException("ONNX native runtime is unavailable for this build/device", failure)
    }

    override fun infer(input: QuickAddModelInput): QuickAddModelOutput {
        val current = checkNotNull(session) { "Model is not loaded" }
        require(input.tensors.map { it.name }.toSet() == current.inputNames) { "Model input names do not match" }
        require(current.outputNames.containsAll(input.outputNames)) { "Unknown model output" }
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            input.tensors.forEach { tensor ->
                val info = current.inputInfo[tensor.name]?.info as? TensorInfo
                    ?: error("Input must be a tensor: ${tensor.name}")
                val expectedType = when (tensor.type) {
                    QuickAddTensorType.INT32 -> OnnxJavaType.INT32
                    QuickAddTensorType.INT64 -> OnnxJavaType.INT64
                    QuickAddTensorType.FLOAT32 -> OnnxJavaType.FLOAT
                }
                require(info.type == expectedType && info.shape.contentEquals(tensor.shape)) {
                    "Input shape or data type does not match: ${tensor.name}"
                }
                tensors[tensor.name] = when (tensor.type) {
                    QuickAddTensorType.INT32 -> OnnxTensor.createTensor(environment,
                        IntBuffer.wrap(IntArray(tensor.integers.size) { tensor.integers[it].toInt() }), tensor.shape)
                    QuickAddTensorType.INT64 -> OnnxTensor.createTensor(environment, LongBuffer.wrap(tensor.integers), tensor.shape)
                    QuickAddTensorType.FLOAT32 -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor.floats), tensor.shape)
                }
            }
            return current.run(tensors, input.outputNames.toSet()).use { result ->
                QuickAddModelOutput(input.outputNames.map { name ->
                    val tensor = result.get(name).orElseThrow { IllegalStateException("Missing output: $name") }
                    require(tensor is OnnxTensor && tensor.info.type == OnnxJavaType.FLOAT) { "Output must be FLOAT32: $name" }
                    val buffer = checkNotNull(tensor.floatBuffer) { "Missing FLOAT32 data: $name" }
                    val values = FloatArray(buffer.remaining())
                    buffer.get(values)
                    QuickAddOutputTensor(name, tensor.info.shape.copyOf(), values)
                })
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() {
        session?.close()
        session = null
    }
}
