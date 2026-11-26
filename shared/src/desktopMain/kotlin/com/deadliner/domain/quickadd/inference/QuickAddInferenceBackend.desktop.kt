package com.deadliner.domain.quickadd.inference

internal actual fun createQuickAddInferenceBackend(): QuickAddInferenceBackend = DesktopQuickAddInferenceBackend()
private class DesktopQuickAddInferenceBackend : QuickAddInferenceBackend {
    override val name = "UNAVAILABLE_DESKTOP"
    override val available = false
    override fun load(modelPath: String): Unit = error("Quick-add inference is supported on Android, iOS and HarmonyOS")
    override fun infer(input: QuickAddModelInput): QuickAddModelOutput = error("No model on desktop host")
    override fun close() = Unit
}
