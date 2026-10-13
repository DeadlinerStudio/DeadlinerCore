package com.deadliner.domain.quickadd.model

internal actual fun prepareQuickAddModelFile(path: String, format: QuickAddModelFormat): String {
    require(format == QuickAddModelFormat.MINDSPORE) { "HarmonyOS requires a .ms artifact" }; return path
}
