package com.deadliner.platform

/** Platform-owned values that must never be synthesized by the persistence layer. */
expect object PlatformIdentity {
    fun deviceId(): String
    fun newId(): String
}
