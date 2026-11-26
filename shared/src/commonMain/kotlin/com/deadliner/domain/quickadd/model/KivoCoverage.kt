package com.deadliner.domain.quickadd.model

/** Exact finite, position-scoped deletion policy from the frozen joint prototype. */
internal object KivoCoverage {
    private val cosmetic = " \t\u3000\u00a0，,；;：:"
    private val prefixes = setOf("提醒我", "请提醒我", "麻烦提醒我", "记得", "帮我安排", "请", "麻烦", "请帮我", "帮我", "我要", "打算", "准备")
    private val locations = setOf("在", "要在", "准备在", "打算在", "需要在", "安排在", "定在", "地点", "地点在", "地点定在", "备注地点")
    private val events = setOf("要", "准备", "打算", "需要", "记得", "提醒我")
    private val times = setOf("时间", "时间是", "安排到", "安排在", "定在")
    private val notes = setOf("备注", "备注是", "备注为")

    fun reasons(text: String, spans: List<KivoSpan>, kind: String): List<String> {
        val chars = unicodeCharacters(text)
        val owners = arrayOfNulls<String>(chars.size)
        for (span in spans) {
            if (span.start < 0 || span.end > chars.size || span.start >= span.end ||
                (span.start until span.end).any { owners[it] != null }) return listOf("invalid-overlapping-source")
            for (i in span.start until span.end) owners[i] = span.role
        }
        var i = 0
        while (i < chars.size) {
            if (owners[i] != null) { i++; continue }
            val start = i
            while (i < chars.size && owners[i] == null) i++
            val left = owners.getOrNull(start - 1)
            val right = owners.getOrNull(i)
            val raw = chars.subList(start, i).joinToString("")
            val clean = raw.filter { it !in cosmetic && it !in "。.!！、" }
            var allowed = clean.isEmpty() || gap(raw, left, right)
            if (kind == "HABIT") {
                if (left == null && right in setOf("TITLE", "TIME", "DAYS", "GOAL_WINDOW"))
                    allowed = allowed || clean in setOf("创建习惯", "添加习惯", "请创建习惯", "请提醒我", "记得", "提醒我")
                if (right == "LOCATION") allowed = allowed || clean in setOf("在", "地点", "地点在", "要在")
                if (right == "TIME") allowed = allowed || clean in setOf("提醒", "提醒时间", "时间", "在", "每天提醒")
                if (right == "NOTE") allowed = allowed || clean in setOf("备注", "备注是")
                if (left == "TIME" && right == null) allowed = allowed || clean == "提醒"
            }
            if (!allowed) return listOf("source-content-deleted")
        }
        return emptyList()
    }

    private fun gap(raw: String, left: String?, right: String?): Boolean {
        val text = raw.filter { it !in cosmetic }
        if (text.isEmpty()) return true
        if (right == null) return false
        if (left == null) return (right in setOf("TITLE", "TIME") && text in prefixes) ||
            right == "TITLE" && text == "请把" || right == "LOCATION" && text in setOf("在", "地点", "地点在", "地点定在")
        return right == "LOCATION" && left in setOf("TITLE", "TIME") && text in locations ||
            right == "TITLE" && left in setOf("TIME", "LOCATION") && text in events ||
            right == "TIME" && left in setOf("TITLE", "LOCATION") && text in times ||
            left == "TIME" && right == "TIME" && text == "的" ||
            right == "NOTE" && left in setOf("TITLE", "TIME", "LOCATION") && text in notes
    }
}
