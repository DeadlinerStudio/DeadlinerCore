package com.deadliner.domain.ai.agent

/** Presentation text only; V2 reason codes remain unchanged for diagnostics. */
object AgentErrorMessages {
    fun describe(code: String): String = when (code) {
        "LIFI_UNAVAILABLE" -> "当前构建未包含 Lifi，请安装包含 Lifi 的版本。"
        "HTTP_401", "HTTP_403" -> "API Key 无效或没有访问权限，请检查设置。"
        "HTTP_429" -> "请求过于频繁，自动重试后仍未成功，请稍后再试。"
        "AI_REQUEST_TIMEOUT", "RUN_TIMEOUT" -> "请求超时，请检查网络后重试。"
        "AI_REQUEST_FAILED" -> "暂时无法连接模型服务，请检查网络和模型设置。"
        "RUN_CANCELLED" -> "本轮已停止；已经提交的操作会保留。"
        "RUN_INTERRUPTED" -> "上次运行已中断；已提交操作不会重复执行，请重新发起请求。"
        "RUN_EXPIRED", "APPROVAL_EXPIRED" -> "本次请求或确认已过期，请重新发起。"
        "RUN_BUSY" -> "上一轮仍在运行，请等待完成或先停止。"
        "TOOL_FAILURE_LIMIT" -> "工具参数连续失败，本轮已停止；已完成的操作会保留。请检查参数后重新发起。"
        "MODEL_CALL_LIMIT", "TOOL_CALL_LIMIT", "TOKEN_BUDGET_EXCEEDED", "NO_PROGRESS" -> "本轮已达到执行限制；已完成的操作会保留，请拆分请求后重试。"
        "INVALID_DECISION", "INVALID_ARGUMENTS", "UNSUPPORTED_TOOL", "EMPTY_RESPONSE" -> "模型返回了无法处理的结果，请重新尝试或检查模型配置。"
        else -> if (code.startsWith("HTTP_5")) "模型服务暂时不可用，自动重试后仍未成功，请稍后再试。" else code
    }
}
