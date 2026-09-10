package com.deadliner.platform

/** Android application hook installed after the process creates its Glance coordinator. */
object AndroidWidgetUpdateBridge {
    @Volatile
    private var handler: ((WidgetUpdateReason) -> Unit)? = null

    fun setHandler(value: ((WidgetUpdateReason) -> Unit)?) {
        handler = value
    }

    internal fun dispatch(reason: WidgetUpdateReason) {
        runCatching { handler?.invoke(reason) }
    }
}

actual fun updateWidget(reason: WidgetUpdateReason) {
    AndroidWidgetUpdateBridge.dispatch(reason)
}
