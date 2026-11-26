package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddProposal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*

class QuickAddRecognitionPolicyTest {
    private val policy = QuickAddRecognitionPolicy()
    private val now = Instant.parse("2026-10-10T04:00:00Z")
    private val zone = TimeZone.of("Asia/Shanghai")

    @Test fun semanticRejectionPreservesInputAndClearsSchedulingFields() {
        val result = policy.decoded(null, "转钟后交报告", "TASK")
        assertEquals("MODEL", result.source)
        assertEquals("REJECTED", result.recognitionState)
        assertEquals("转钟后交报告", result.title)
        assertNull(result.dueAt)
        assertNull(result.reminderTime)
        assertFalse(result.ready)
        assertTrue(result.needsAI)
    }

    @Test fun unavailableRuntimeKeepsSimpleRulesButDefersRiskyExpressions() {
        val ordinary = policy.fallback("明天下午3点交报告", "TASK", now, zone)
        assertTrue(ordinary.ready)
        assertEquals("2026-10-11T15:00", ordinary.dueAt)
        assertEquals("UNAVAILABLE", ordinary.recognitionState)
        for (text in listOf("转钟后交报告", "明天不要开会", "if it rains tomorrow cancel the meeting", "tomorrow at midnight submit report")) {
            val result = policy.fallback(text, "TASK", now, zone)
            assertFalse(result.ready, text)
            assertNull(result.dueAt, text)
        }
    }

    @Test fun acceptsOnlyReadyModelResultsAndPendingIsNotSubmittable() {
        val accepted = policy.decoded(QuickAddProposal(title = "交报告", ready = true), "原文", "TASK")
        assertEquals("ACCEPTED", accepted.recognitionState)
        assertEquals("交报告", accepted.title)
        val pending = policy.pending("买牛奶", "TASK")
        assertEquals("PENDING", pending.recognitionState)
        assertFalse(pending.ready)
        assertFalse(policy.decoded(QuickAddProposal(needsAI = true), "原文", "TASK").ready)
    }
}
