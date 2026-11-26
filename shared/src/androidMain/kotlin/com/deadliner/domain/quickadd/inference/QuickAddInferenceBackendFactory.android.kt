package com.deadliner.domain.quickadd.inference

/** Keep ONNX references in a separate class so a runtime-free APK can create the engine. */
internal actual fun createQuickAddInferenceBackend(): QuickAddInferenceBackend = try {
    Class.forName("ai.onnxruntime.OrtEnvironment", false, QuickAddInferenceEngine::class.java.classLoader)
    OnnxQuickAddInferenceBackend()
} catch (_: ClassNotFoundException) {
    MissingOnnxRuntimeBackend()
} catch (_: LinkageError) {
    MissingOnnxRuntimeBackend()
}

private class MissingOnnxRuntimeBackend : QuickAddInferenceBackend {
    override val name = "UNAVAILABLE_ONNX_RUNTIME"
    override val available = false
    override fun load(modelPath: String): Unit = error("This build does not package ONNX Runtime")
    override fun infer(input: QuickAddModelInput): QuickAddModelOutput = error("ONNX Runtime is unavailable")
    override fun close() = Unit
}
