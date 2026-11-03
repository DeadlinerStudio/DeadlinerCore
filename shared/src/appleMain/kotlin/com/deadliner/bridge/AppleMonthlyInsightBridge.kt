package com.deadliner.bridge

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.lifi.llm.KtorLlmClient
import com.deadliner.lifi.utility.AiUtilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Apple presentation-only monthly analysis. Errors never escape the Kotlin/Swift boundary. */
class AppleMonthlyInsightBridge {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun generate(config: AIConfig, month: String, metrics: String, titles: List<String>,
                 onSuccess: (String, List<String>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val client = KtorLlmClient(config)
                val result = try {
                    AiUtilityService(config, client).generateMonthlyAnalysis(month, metrics, titles, "zh-CN")
                } finally { client.close() }
                withContext(Dispatchers.Main) { onSuccess(result.summary, result.keywords) }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) { onError("月度分析失败，请检查配置或稍后重试") }
            } finally { scope.cancel() }
        }
    }
}
