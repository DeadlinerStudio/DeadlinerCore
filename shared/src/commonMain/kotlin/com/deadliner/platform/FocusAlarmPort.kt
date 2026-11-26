package com.deadliner.platform

data class FocusAlarmResult(
    val succeeded: Boolean,
    val failureMessage: String? = null,
)

interface FocusAlarmPort {
    fun scheduleCountdown(sessionUid: String, fireAt: String, taskTitle: String): FocusAlarmResult
    fun cancelCountdown(sessionUid: String): FocusAlarmResult

    companion object {
        val NONE: FocusAlarmPort = object : FocusAlarmPort {
            override fun scheduleCountdown(
                sessionUid: String,
                fireAt: String,
                taskTitle: String,
            ) = FocusAlarmResult(succeeded = true)

            override fun cancelCountdown(sessionUid: String) = FocusAlarmResult(succeeded = true)
        }
    }
}
