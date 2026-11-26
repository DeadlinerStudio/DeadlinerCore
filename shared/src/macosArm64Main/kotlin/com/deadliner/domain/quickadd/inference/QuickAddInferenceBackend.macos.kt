package com.deadliner.domain.quickadd.inference

internal actual fun createQuickAddInferenceBackend(): QuickAddInferenceBackend = MacQuickAddInferenceBackend()
private class MacQuickAddInferenceBackend : QuickAddInferenceBackend {
    override val name = "UNAVAILABLE_MACOS_TEST_HOST"
    override val available = false
    override fun load(modelPath: String): Unit = error("Quick-add inference is supported on Android, iOS and HarmonyOS")
    override fun infer(input: QuickAddModelInput): QuickAddModelOutput = error("No model on macOS test host")
    override fun close() = Unit
}
