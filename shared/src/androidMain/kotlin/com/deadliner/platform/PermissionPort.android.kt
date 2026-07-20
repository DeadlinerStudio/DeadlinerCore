package com.deadliner.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android PermissionPort actual 实现。
 *
 * 使用 ContextCompat 实现权限管理。
 */
actual class PermissionPort(private val context: Context) {
    actual suspend fun checkPermission(type: PermissionType): PermissionStatus {
        return when (type) {
            PermissionType.NOTIFICATION -> checkNotificationPermission()
            PermissionType.CALENDAR -> checkCalendarPermission()
            PermissionType.MICROPHONE -> checkMicrophonePermission()
            PermissionType.SPEECH_RECOGNITION -> checkSpeechRecognitionPermission()
            PermissionType.BATTERY_OPTIMIZATION -> checkBatteryOptimizationPermission()
        }
    }

    actual suspend fun requestPermission(type: PermissionType): Boolean {
        // Android 权限请求需要在 Activity 中进行
        // 这里返回 false，由原生层处理权限请求
        return false
    }

    actual suspend fun shouldShowRationale(type: PermissionType): Boolean {
        // Android 权限说明需要在 Activity 中检查
        return false
    }

    // ===== 辅助方法 =====

    private fun checkNotificationPermission(): PermissionStatus {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        } else {
            // Android 13 以下不需要通知权限
            PermissionStatus.GRANTED
        }
    }

    private fun checkCalendarPermission(): PermissionStatus {
        val readPermission = android.Manifest.permission.READ_CALENDAR
        val writePermission = android.Manifest.permission.WRITE_CALENDAR

        val readGranted = ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(context, writePermission) == PackageManager.PERMISSION_GRANTED

        return if (readGranted && writeGranted) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private fun checkMicrophonePermission(): PermissionStatus {
        val permission = android.Manifest.permission.RECORD_AUDIO
        return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private fun checkSpeechRecognitionPermission(): PermissionStatus {
        val permission = android.Manifest.permission.RECORD_AUDIO
        return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    private fun checkBatteryOptimizationPermission(): PermissionStatus {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        } else {
            PermissionStatus.GRANTED
        }
    }
}
