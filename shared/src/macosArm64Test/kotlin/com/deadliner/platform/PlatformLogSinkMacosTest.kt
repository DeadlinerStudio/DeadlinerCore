package com.deadliner.platform

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertTrue

private const val TEST_PROCESS_ID = 42L

@OptIn(ExperimentalForeignApi::class)
class PlatformLogSinkMacosTest {
    @Test
    fun configuredSinkAppendsDiagnosticRecords() {
        val root = NSTemporaryDirectory() + "deadliner-log-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        try {
            PlatformLogSink.configure(root, TEST_PROCESS_ID)
            PlatformLogger().w("sync.webdav", "sync.write.skipped reason=test")
            val jsonl = readLog(root, "jsonl")
            val text = readLog(root, "log")
            assertTrue("\"category\":\"sync.webdav\"" in jsonl, jsonl)
            assertTrue("\"level\":\"warn\"" in jsonl, jsonl)
            assertTrue("\"processID\":$TEST_PROCESS_ID" in jsonl, jsonl)
            assertTrue("sync.write.skipped reason=test" in text, text)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun missingParentFallsBackToSystemLog() {
        val missing = NSTemporaryDirectory() + NSUUID().UUIDString
        PlatformLogSink.configure(missing, TEST_PROCESS_ID)
        val messages = mutableListOf<String>()
        PlatformLogSink.writeWithFallback(LogLevel.INFO, "sync", "50% complete", null, messages::add)
        kotlin.test.assertEquals(listOf("[INFO] sync: 50% complete"), messages)
    }

    private fun readLog(root: String, extension: String): String =
        NSString.stringWithContentsOfFile(
            "$root/Diagnostics/deadliner-diagnostics.$extension", NSUTF8StringEncoding, null,
        ).orEmpty()
}
