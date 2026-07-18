package com.deadliner.sync.snapshot

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SnapshotV3ValidationException(
    val issues: List<SnapshotV3ValidationIssue>,
) : IllegalArgumentException(issues.joinToString(prefix = "Invalid v3 snapshot: ") { it.message })

data class SnapshotV3ValidationIssue(
    val path: String,
    val message: String,
)

object SnapshotV3Codec {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(snapshot: SnapshotV3Root): String {
        SnapshotV3Validator.requireValid(snapshot)
        return json.encodeToString(snapshot)
    }

    fun decode(raw: String): SnapshotV3Root = json.decodeFromString<SnapshotV3Root>(raw).also {
        SnapshotV3Validator.requireValid(it)
    }
}

object SnapshotV3Validator {
    fun requireValid(snapshot: SnapshotV3Root) {
        val issues = validate(snapshot)
        if (issues.isNotEmpty()) throw SnapshotV3ValidationException(issues)
    }

    fun validate(snapshot: SnapshotV3Root): List<SnapshotV3ValidationIssue> = buildList {
        if (snapshot.formatVersion != SnapshotV3Root.FORMAT_VERSION) {
            add(SnapshotV3ValidationIssue("format_version", "expected format version 3"))
        }
        validateItems("categories", snapshot.categories, { it.uid }, { it.deleted }, { it.doc }, { it.uid })
        validateItems("tasks", snapshot.tasks, { it.uid }, { it.deleted }, { it.doc }, { it.uid })
        validateItems("habits", snapshot.habits, { it.uid }, { it.deleted }, { it.doc?.habit }, { it.uid })
        validateItems("captures", snapshot.captures, { it.uid }, { it.deleted }, { it.doc }, { it.uid })
        validateItems("memory_fragments", snapshot.memoryFragments, { it.uid }, { it.deleted }, { it.doc }, { it.uid })
        validateItems("user_profiles", snapshot.userProfiles, { it.uid }, { it.deleted }, { it.doc }, { it.uid })
        snapshot.categories.forEachIndexed { index, item ->
            if (item.doc?.isDeleted == true) add(SnapshotV3ValidationIssue("categories[$index]", "live document must not be marked deleted"))
        }
        snapshot.tasks.forEachIndexed { index, item ->
            if (item.doc?.isDeleted == true) add(SnapshotV3ValidationIssue("tasks[$index]", "live document must not be marked deleted"))
        }
        snapshot.habits.forEachIndexed { index, item ->
            if (item.doc?.habit?.isDeleted == true) add(SnapshotV3ValidationIssue("habits[$index]", "live document must not be marked deleted"))
        }
        snapshot.captures.forEachIndexed { index, item ->
            if (item.doc?.isDeleted == true) add(SnapshotV3ValidationIssue("captures[$index]", "live document must not be marked deleted"))
        }
        snapshot.memoryFragments.forEachIndexed { index, item ->
            if (item.doc?.isDeleted == true) add(SnapshotV3ValidationIssue("memory_fragments[$index]", "live document must not be marked deleted"))
        }
        snapshot.userProfiles.forEachIndexed { index, item ->
            if (item.doc?.isDeleted == true) add(SnapshotV3ValidationIssue("user_profiles[$index]", "live document must not be marked deleted"))
        }

        snapshot.tasks.forEachIndexed { index, item ->
            item.doc?.subtasks.orEmpty().forEachIndexed { subtaskIndex, subtask ->
                val path = "tasks[$index].doc.subtasks[$subtaskIndex]"
                if (subtask.taskUid != item.uid) add(SnapshotV3ValidationIssue(path, "taskUid must equal parent uid"))
            }
        }
        snapshot.habits.forEachIndexed { index, item ->
            item.doc?.records.orEmpty().forEachIndexed { recordIndex, record ->
                val path = "habits[$index].doc.records[$recordIndex]"
                if (record.habitUid != item.uid) add(SnapshotV3ValidationIssue(path, "habitUid must equal parent uid"))
            }
            item.doc?.scheduleItems.orEmpty().forEachIndexed { scheduleIndex, schedule ->
                val path = "habits[$index].doc.schedule_items[$scheduleIndex]"
                if (schedule.habitUid != item.uid) add(SnapshotV3ValidationIssue(path, "habitUid must equal parent uid"))
            }
        }
    }

    private fun <T, D> MutableList<SnapshotV3ValidationIssue>.validateItems(
        collection: String,
        items: List<T>,
        uid: (T) -> String,
        deleted: (T) -> Boolean,
        doc: (T) -> D?,
        documentUid: (D) -> String,
    ) {
        items.forEachIndexed { index, item ->
            val path = "$collection[$index]"
            if (uid(item).isBlank()) add(SnapshotV3ValidationIssue(path, "uid must not be blank"))
            if (deleted(item) && doc(item) != null) add(SnapshotV3ValidationIssue(path, "tombstone must not carry doc"))
            if (!deleted(item) && doc(item) == null) add(SnapshotV3ValidationIssue(path, "live item must carry doc"))
            doc(item)?.let { if (documentUid(it) != uid(item)) add(SnapshotV3ValidationIssue(path, "doc uid must equal item uid")) }
        }
        items.groupBy(uid).filterValues { it.size > 1 }.forEach { (duplicateUid, _) ->
            add(SnapshotV3ValidationIssue(collection, "duplicate uid '$duplicateUid'"))
        }
    }
}
