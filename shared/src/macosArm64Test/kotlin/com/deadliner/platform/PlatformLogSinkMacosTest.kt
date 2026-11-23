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

@OptIn(ExperimentalForeignApi::class)
class PlatformLogSinkMacosTest {
    @Test
    fun `configured sink appends records to the diagnostics files`() {
        val root = NSTemporaryDirectory() + "deadliner-log-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        try {
            PlatformLogSink.configure(root, 42)
            PlatformLogger().w("sync.webdav", "sync.write.skipped reason=test")
            val jsonl = NSString.stringWithContentsOfFile("$root/Diagnostics/deadliner-diagnostics.jsonl", NSUTF8StringEncoding, null).orEmpty()
            val text = NSString.stringWithContentsOfFile("$root/Diagnostics/deadliner-diagnostics.log", NSUTF8StringEncoding, null).orEmpty()
            assertTrue("\"category\":\"sync.webdav\"" in jsonl, jsonl)
            assertTrue("\"level\":\"warn\"" in jsonl, jsonl)
            assertTrue("\"processID\":42" in jsonl, jsonl)
            assertTrue("sync.write.skipped reason=test" in text, text)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
