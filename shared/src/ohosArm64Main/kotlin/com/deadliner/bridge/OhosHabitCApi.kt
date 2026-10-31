@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import kotlinx.cinterop.*

@CName("deadliner_persistence_habit_state_json")
fun deadlinerPersistenceHabitStateJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.habitStateJson(), output, outputCapacity)

@CName("deadliner_persistence_habit_state_for_date_json")
fun deadlinerPersistenceHabitStateForDateJson(
    selectedDate: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val date = selectedDate?.toKString() ?: return 0
    return writeCallerOwnedJson(PersistenceRuntime.habitStateJson(date), output, outputCapacity)
}

@CName("deadliner_persistence_habit_create_json")
fun deadlinerPersistenceHabitCreateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = habitPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_create", payload, output, outputCapacity) {
        PersistenceRuntime.createHabitJson(payload)
    }
}

@CName("deadliner_persistence_habit_update_json")
fun deadlinerPersistenceHabitUpdateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = habitPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_update", payload, output, outputCapacity) {
        PersistenceRuntime.updateHabitJson(payload)
    }
}

@CName("deadliner_persistence_habit_category_update_json")
fun deadlinerPersistenceHabitCategoryUpdateJson(
    payload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(payload?.toKString()?.let(PersistenceRuntime::updateHabitCategoryJson), output, outputCapacity)

@CName("deadliner_persistence_habit_status_action_json")
fun deadlinerPersistenceHabitStatusActionJson(
    uid: CPointer<ByteVar>?,
    actionPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = actionPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_status_action", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.applyHabitStatusJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_toggle_record_json")
fun deadlinerPersistenceHabitToggleRecordJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_toggle_record", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.toggleHabitRecordJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_save_record_json")
fun deadlinerPersistenceHabitSaveRecordJson(
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_save_record", payload, output, outputCapacity) {
        PersistenceRuntime.saveHabitRecordJson(payload)
    }
}

@CName("deadliner_persistence_habit_clear_records_json")
fun deadlinerPersistenceHabitClearRecordsJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_clear_records", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.clearHabitRecordsJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_delete_json")
fun deadlinerPersistenceHabitDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val timestamp = updatedAt?.toKString() ?: return 0
    return writeStateMutationJson("habit_delete", resolvedUid + "\u001F" + timestamp, output, outputCapacity) {
        PersistenceRuntime.deleteHabitJson(resolvedUid, timestamp)
    }
}
