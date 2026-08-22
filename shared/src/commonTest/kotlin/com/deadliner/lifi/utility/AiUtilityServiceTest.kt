package com.deadliner.lifi.utility

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.ChatChoice
import com.deadliner.domain.ai.model.ChatMessage
import com.deadliner.domain.ai.model.ChatResponse
import com.deadliner.domain.ai.port.LlmGateway
import com.deadliner.lifi.llm.normalizeOpenAiChatCompletionsUrl
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiUtilityServiceTest {
    @Test
    fun openAiEndpointNormalizationPreservesExistingIosConfigurationSemantics() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", normalizeOpenAiChatCompletionsUrl("https://api.deepseek.com"))
        assertEquals("https://example.com/v1/chat/completions", normalizeOpenAiChatCompletionsUrl("example.com/v1"))
        assertEquals("https://example.com/v1/chat/completions", normalizeOpenAiChatCompletionsUrl("https://example.com/v1/chat/completions"))
    }

    @Test
    fun taskExtractionAcceptsLegacyEditorKeysInsideFencedJson() = runBlocking {
        val service = serviceFor(
            """
            ```json
            {"tasks":[{"name":"提交报告","dueTime":"2026-07-24 09:00","note":"附上数据"}]}
            ```
            """.trimIndent(),
        )

        val task = service.extractTasks("明早提交报告").single()

        assertEquals("提交报告", task.title)
        assertEquals("2026-07-24 09:00", task.dueAt)
        assertEquals("附上数据", task.note)
    }

    @Test
    fun habitExtractionNormalizesInvalidOptionalValuesToSafeDefaults() = runBlocking {
        val service = serviceFor(
            """{"habits":[{"name":"阅读","period":"unknown","timesPerPeriod":0,"goalType":"invalid","totalTarget":20}]}""",
        )

        val habit = service.extractHabits("每天阅读").single()

        assertEquals("WEEKLY_DAYS", habit.schedule.kind.name)
        assertEquals(127, habit.schedule.weekdaysMask)
        assertEquals(1, habit.goal.targetCount)
        assertEquals("SCHEDULED_DAY", habit.goal.window.name)
        assertEquals("CHECK_OFF", habit.tracking.mode.name)
    }

    @Test
    fun habitExtractionKeepsWeekdayQuantityAndReminderContract() = runBlocking {
        val service = serviceFor(
            """{"habits":[{"name":"背单词","schedule":{"kind":"WEEKLY_DAYS","startOn":"2026-08-19","weekdaysMask":31},"goal":{"window":"SCHEDULED_DAY","targetCount":20},"tracking":{"mode":"QUANTITY","unit":"个单词","defaultIncrement":1},"reminder":{"localTime":"08:30","isEnabled":true}}]}""",
        )

        val habit = service.extractHabits("工作日每天八点半背 20 个单词").single()

        assertEquals(31, habit.schedule.weekdaysMask)
        assertEquals(20, habit.goal.targetCount)
        assertEquals("个单词", habit.tracking.unit)
        assertEquals("08:30", habit.reminder?.localTime)
    }

    @Test
    fun emptyProviderContentSurfacesKmpError() = runBlocking {
        val service = serviceFor("")

        assertFailsWith<Exception> { service.extractTasks("创建任务") }
        Unit
    }

    private fun serviceFor(content: String): AiUtilityService = AiUtilityService(
        config = AIConfig(
            apiKey = "test-key",
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            platform = "test",
            timezone = "UTC",
        ),
        llmGateway = LlmGateway {
            ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content))))
        },
    )
}
