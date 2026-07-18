package com.deadliner.sync

import com.deadliner.model.ChangeLogEntry

fun interface ConflictResolver {
    /** Returns true when [incoming] should replace [existing]. */
    fun shouldApply(incoming: ChangeLogEntry, existing: ChangeLogEntry?): Boolean
}

/** Deterministic LWW: timestamp first, then device and finally immutable change ID. */
object LastWriteWinsConflictResolver : ConflictResolver {
    override fun shouldApply(incoming: ChangeLogEntry, existing: ChangeLogEntry?): Boolean {
        if (existing == null) return true
        return compareValuesBy(
            incoming,
            existing,
            ChangeLogEntry::clientTimestamp,
            ChangeLogEntry::deviceId,
            ChangeLogEntry::id,
        ) > 0
    }
}
