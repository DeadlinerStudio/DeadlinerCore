package com.deadliner.domain.ai.error

sealed class AIError(message: String) : Exception(message) {
    data object MissingAPIKey : AIError("请先在设置中填写 API Key")
    data object EmptyResponse : AIError("AI 返回内容为空")
    data object ParsingFailed : AIError("AI 数据解析失败")
    data class NetworkError(val detail: String) : AIError("网络请求失败: $detail")
    data class ConfigError(val detail: String) : AIError("配置错误: $detail")
    data class InvalidToolCall(val detail: String) : AIError("工具调用无效: $detail")
}
