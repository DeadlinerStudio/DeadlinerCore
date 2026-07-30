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

        assertEquals("DAILY", habit.period.name)
        assertEquals(1, habit.timesPerPeriod)
        assertEquals("PER_PERIOD", habit.goalType.name)
        assertEquals(null, habit.totalTarget)
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
