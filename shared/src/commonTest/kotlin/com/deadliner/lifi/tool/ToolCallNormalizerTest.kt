package com.deadliner.lifi.tool

import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.ai.model.AgentRole
import com.deadliner.domain.ai.model.MixedResult
import com.deadliner.domain.ai.model.ToolCall
import com.deadliner.lifi.pipeline.parseLifiMixedResult
import com.deadliner.lifi.skill.SkillRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCallNormalizerTest {
    private val normalizer = ToolCallNormalizer(SkillRegistry.loadEmbedded(), platform = "ios")

    @Test
    fun taskProposalFallbackBecomesConfirmableTypedCreateTask() {
        val normalized = normalizer.normalizeAndValidate(
            role = AgentRole.TaskAgent,
            originalText = "7 月 27 日早上 9 点前巧巧需要提交转段申请",
            result = MixedResult(
                primaryIntent = "ExtractTasks",
                tasks = listOf(
                    AiTaskProposal(
                        title = "提交转段申请",
                        dueAt = "2026-07-27 09:00",
                        note = "巧巧",
                    ),
                ),
            ),
        )

        assertTrue(normalized.tasks.isEmpty())
        val call = assertIs<ToolCall.CreateTask>(normalized.toolCalls.single())
        assertEquals("提交转段申请", call.name)
        assertEquals("2026-07-27 09:00", call.dueTime)
        assertEquals(AgentRole.TaskAgent, call.agent)
    }

    @Test
    fun emptySpecialistReplyDoesNotBecomeUserVisibleToolError() {
        val normalized = normalizer.normalizeAndValidate(
            role = AgentRole.TaskAgent,
            originalText = "帮我处理这件事",
            result = MixedResult(chatResponse = "请告诉我任务名称和时间。"),
        )

        assertTrue(normalized.toolCalls.isEmpty())
        assertEquals("请告诉我任务名称和时间。", normalized.chatResponse)
    }

    @Test
    fun openAiStyleToolWireFormatBecomesTypedToolCall() {
        val parsed = parseLifiMixedResult(
            """
            {
              "tasks": [],
              "chatResponse": "好的，我将为您创建明天下午3点的产品评审会任务。请确认。",
              "toolCalls": [{
                "tool": "create_task",
                "args": {
                  "name": "产品评审会",
                  "dueTime": "2026-07-25 15:00",
                  "note": "与产品团队开评审会"
                },
                "reason": "用户明确表达新增任务",
                "executionMode": "ASK_USER"
              }]
            }
            """.trimIndent(),
        )

        assertEquals("好的，我将为您创建明天下午3点的产品评审会任务。请确认。", parsed.chatResponse)
        val call = assertIs<ToolCall.CreateTask>(parsed.toolCalls.single())
        assertEquals("产品评审会", call.name)
        assertEquals("2026-07-25 15:00", call.dueTime)
    }

    @Test
    fun habitToolWireFormatPreservesWorkdayQuantityPlan() {
        val parsed = parseLifiMixedResult(
            """
            {
              "toolCalls": [{
                "tool": "create_habit",
                "args": {
                  "name": "背单词",
                  "schedule": {"kind":"WEEKLY_DAYS","startOn":"2026-08-19","weekdaysMask":31},
                  "goal": {"window":"SCHEDULED_DAY","targetCount":20},
                  "tracking": {"mode":"QUANTITY","unit":"个单词","defaultIncrement":1},
                  "reminder": {"localTime":"08:30","isEnabled":true}
                },
                "executionMode": "ASK_USER"
              }]
            }
            """.trimIndent(),
        )

        val call = assertIs<ToolCall.CreateHabit>(parsed.toolCalls.single())
        assertEquals(31, call.schedule.weekdaysMask)
        assertEquals(20, call.goal.targetCount)
        assertEquals("个单词", call.tracking.unit)
        assertEquals("08:30", call.reminder?.localTime)
    }

    @Test
    fun legacyHabitToolWireFormatIsMarkedForCompatibilityMigration() {
        val parsed = parseLifiMixedResult(
            """{"toolCalls":[{"tool":"create_habit","args":{"name":"阅读","period":"WEEKLY","timesPerPeriod":3,"goalType":"PER_PERIOD"}}]}""",
        )

        val call = assertIs<ToolCall.CreateHabit>(parsed.toolCalls.single())
        assertEquals("WEEK", call.goal.window.name)
        assertTrue(call.compatibilityWarning?.contains("Legacy habit fields") == true)
    }
}
