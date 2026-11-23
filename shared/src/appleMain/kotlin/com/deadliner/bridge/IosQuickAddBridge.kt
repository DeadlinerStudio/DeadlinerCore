package com.deadliner.bridge

import com.deadliner.domain.quickadd.QuickAddAiResult
import com.deadliner.lifi.IosLifiCore

class IosQuickAddBridge {
    @Throws(Exception::class)
    suspend fun extractJson(core: IosLifiCore, text: String, kind: String): String =
        if (kind == "HABIT") QuickAddAiResult.habits(core.extractHabits(text)).toJson()
        else QuickAddAiResult.tasks(core.extractTasks(text)).toJson()
}
