package com.deadliner.platform

/** Why the host should ask its widget framework for a fresh snapshot. */
enum class WidgetUpdateReason {
    DATA_MUTATION,
    USER_REFRESH,
    APP_FOREGROUND,
    PERIODIC,
}

/**
 * Best-effort signal from shared business code to the platform widget host.
 *
 * Implementations must return quickly and must never let a widget failure escape into the
 * database transaction or refresh flow that emitted the signal.
 */
expect fun updateWidget(reason: WidgetUpdateReason = WidgetUpdateReason.DATA_MUTATION)
