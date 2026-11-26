@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.platform

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke

private typealias OhosWidgetUpdateCallback = CPointer<CFunction<(Int) -> Unit>>

private var widgetUpdateCallback: OhosWidgetUpdateCallback? = null

actual fun updateWidget(reason: WidgetUpdateReason) {
    runCatching { widgetUpdateCallback?.invoke(reason.ordinal) }
}

@CName("deadliner_widget_set_update_callback")
fun deadlinerWidgetSetUpdateCallback(callback: OhosWidgetUpdateCallback?): Int {
    widgetUpdateCallback = callback
    return 1
}

@CName("deadliner_widget_request_update")
fun deadlinerWidgetRequestUpdate(reasonValue: Int): Int {
    val reason = WidgetUpdateReason.entries.getOrNull(reasonValue) ?: WidgetUpdateReason.DATA_MUTATION
    updateWidget(reason)
    return 1
}
