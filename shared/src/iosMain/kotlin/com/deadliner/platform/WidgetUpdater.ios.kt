package com.deadliner.platform

import platform.Foundation.NSNotificationCenter

actual fun updateWidget(reason: WidgetUpdateReason) {
    runCatching {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = IOS_WIDGET_UPDATE_NOTIFICATION,
            `object` = null,
        )
    }
}

private const val IOS_WIDGET_UPDATE_NOTIFICATION = "com.aritxonly.deadliner.widget-update-requested"
