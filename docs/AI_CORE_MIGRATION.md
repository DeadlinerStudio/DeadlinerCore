# Lifi AI Core → DeadlinerCore KMP 迁移方案

## 1. 背景与动机

### 1.1 现状

Lifi AI Core 当前以 Rust 实现（`deadliner_core`），通过 UniFFI (iOS) 和 NAPI (鸿蒙) 桥接原生层。该方案存在以下问题：

| 问题 | 具体表现 |
|------|----------|
| **FFI 边界 JSON 开销** | `OnToolRequest.args_json` 在 iOS 侧被解析 2~3 次（`DeadlinerCoreBridge` 取 `executionMode`，`ToolCallExecutor` 取参数） |
| **持久化三副本** | Rust `memories.json` + iOS `UserDefaults` + KMP `DeadlinerDatabase`，全量覆盖同步，`drain_pending_sync` 从未被调用 |
| **模型定义三处维护** | Rust `models.rs` + Swift `AIModels.swift` + UniFFI Record，任何字段变更需同步修改三处 |
| **工具参数动态类型** | Rust 用 `serde_json::Value`，编译时无类型安全；Swift 端需手动 `decodeArgs` |
| **会话历史孤岛** | `conversation_history` 仅存于 Rust `memories.json`，iOS 端无法展示 |

### 1.2 迁移到 KMP 的核心收益

1. **对象直传**：`ToolCall` 用 Kotlin sealed class 定义，Swift 端直接访问属性，零 JSON 序列化
2. **单一持久化**：KMP `DeadlinerDatabase` 为唯一 source of truth，消除全量覆盖同步
3. **模型一处定义**：Kotlin data class 同时生成 Swift/ArkTS 可用类型
4. **编译时安全**：`sealed class ToolCall` 替代 `serde_json::Value`，IDE 自动补全 + 类型检查

---

## 2. 架构设计

### 2.1 开源/闭源拆分原则

```
┌─────────────────────────────────────────────────────┐
│  开源层 (MIT License)                                │
│                                                      │
│  domain/ai/model/     — 所有数据类型                  │
│  domain/ai/port/      — 所有抽象接口                  │
│  domain/ai/error/     — 错误类型                      │
│  interfaces/          — DeadlinerCore 公开门面        │
│                                                      │
│  特征：零实现逻辑，纯接口 + 数据定义                   │
│  价值：社区可基于接口开发替代实现                      │
└─────────────────────────────────────────────────────┘
                        ↕ 依赖注入
┌─────────────────────────────────────────────────────┐
│  闭源层 (.gitignore)                                 │
│                                                      │
│  ai/orchestrator/     — 多 Agent 调度逻辑             │
│  ai/prompt/           — Prompt 模板（核心 IP）        │
│  ai/pipeline/         — 主流程编排                    │
│  ai/memory/           — 记忆综合                      │
│  ai/llm/              — LLM 客户端实现                │
│  ai/skill/            — 技能注册表 + JSON             │
│  ai/tool/             — 工具规范化管线                 │
│                                                      │
│  特征：所有业务实现，Prompt 模板，AI 编排策略          │
│  价值：Lifi AI 的核心竞争力                           │
└─────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
interfaces/DeadlinerCore.kt
    │
    ├── depends on → domain/ai/model/* (数据类型)
    ├── depends on → domain/ai/port/*  (抽象接口)
    └── depends on → ai/*              (闭源实现，通过 DI 注入)
                        │
                        ├── ai/orchestrator/ → domain/ai/port/LlmGateway
                        ├── ai/pipeline/     → domain/ai/port/ToolExecutor
                        ├── ai/memory/       → domain/ai/port/MemoryStorePort
                        ├── ai/llm/          → implements domain/ai/port/LlmGateway
                        ├── ai/skill/        → implements domain/ai/port/SkillRegistryPort
                        └── ai/tool/         → domain/ai/model/ToolCall
```

### 2.3 目录结构

```
shared/src/commonMain/kotlin/com/deadliner/
├── domain/ai/                          # 开源
│   ├── model/
│   │   ├── AgentRole.kt
│   │   ├── ToolCall.kt                 # sealed class，替代 serde_json::Value
│   │   ├── CoreEvent.kt                # sealed class，替代 UniFFI Enum
│   │   ├── MixedResult.kt
│   │   ├── AiTaskProposal.kt           # 复用现有
│   │   ├── AiHabitProposal.kt          # 复用现有
│   │   ├── AiReadFilter.kt             # 复用现有
│   │   ├── ChatRequest.kt
│   │   ├── ChatResponse.kt
│   │   ├── ToolMetadata.kt
│   │   └── AIConfig.kt
│   ├── port/
│   │   ├── LlmGateway.kt
│   │   ├── AgentOrchestrator.kt
│   │   ├── ToolExecutor.kt
│   │   ├── MemoryStorePort.kt
│   │   ├── SkillRegistryPort.kt
│   │   └── ToolResult.kt
│   └── error/
│       └── AIError.kt
│
├── interfaces/                         # 开源
│   ├── DeadlinerCore.kt                # 公开门面
│   └── AIComponent.kt                  # DI 接口
│
├── lifi/                               # 闭源 (.gitignore)，包名 com.deadliner.lifi
│   ├── orchestrator/
│   │   ├── Orchestrator.kt
│   │   ├── WorkerDispatcher.kt
│   │   └── SupervisorRouter.kt
│   ├── prompt/
│   │   ├── PromptBuilder.kt
│   │   └── templates/
│   ├── pipeline/
│   │   ├── Pipeline.kt
│   │   └── ToolResultContinuation.kt
│   ├── memory/
│   │   ├── MemoryManager.kt
│   │   ├── MemoryContextBuilder.kt
│   │   └── KmpMemoryStoreAdapter.kt
│   ├── llm/
│   │   └── KtorLlmClient.kt
│   ├── skill/
│   │   ├── SkillRegistry.kt
│   │   └── SkillLoader.kt
│   ├── tool/
│   │   ├── ToolCallNormalizer.kt
│   │   └── ConfidenceGate.kt
│   ├── DI.kt                        # LifiAIComponent
│   └── resources/
│       └── skills/
│           ├── task/
│           │   ├── create_task.json
│           │   ├── read_tasks.json
│           │   └── update_deadline.json
│           └── habit/
│               ├── create_habit.json
│               └── read_habits.json
│
└── domain/memory/                      # 已有，开源
    └── Memory.kt                       # MemoryFragment / MemorySnapshot
```

---

## 3. 开源层详细设计

### 3.1 领域模型 — `domain/ai/model/`

#### AgentRole

```kotlin
// domain/ai/model/AgentRole.kt
package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRole {
    Supervisor,
    TaskAgent,
    HabitAgent,
    ChatAgent;

    val label: String get() = name
}
```

#### ToolCall — 替代 `serde_json::Value`

```kotlin
// domain/ai/model/ToolCall.kt
package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * 工具调用的类型安全表示。
 *
 * 替代 Rust 中的 `serde_json::Value`，在编译时保证参数结构正确。
 * 原生端直接 when(toolCall) 分发，无需 JSON 解析。
 */
@Serializable
sealed class ToolCall {
    abstract val agent: AgentRole?
    abstract val reason: String?
    abstract val executionMode: CapabilityExecutionMode?

    @Serializable
    data class ReadTasks(
        val timeRangeDays: Int? = null,
        val status: String? = null,
        val keywords: List<String> = emptyList(),
        val limit: Int = 20,
        val sort: String? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class CreateTask(
        val name: String,
        val dueTime: String? = null,
        val note: String? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class UpdateDeadline(
        val taskId: String,
        val newDueTime: String,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class ReadHabits(
        val keywords: List<String> = emptyList(),
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class CreateHabit(
        val name: String,
        val period: String,
        val timesPerPeriod: Int = 1,
        val goalType: String = "per_period",
        val totalTarget: Int? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()
}

@Serializable
enum class CapabilityExecutionMode {
    Auto,
    AskUser,
    Never,
}
```

#### CoreEvent — 替代 UniFFI Enum

```kotlin
// domain/ai/model/CoreEvent.kt
package com.deadliner.domain.ai.model

/**
 * AI Core 向原生端推送的事件流。
 *
 * 替代 Rust 的 UniFFI CoreEvent Enum，
 * 原生端通过 SharedFlow<CoreEvent> 订阅，直接访问 Kotlin 属性。
 */
sealed class CoreEvent {
    data class OnLifecycle(
        val requestId: String,
        val stage: String,      // "started" | "waiting_tool" | "completed" | "failed"
        val status: String,     // "running" | "success" | "error"
        val message: String? = null,
    ) : CoreEvent()

    data class OnThinking(
        val agentName: String,
        val phase: String,      // "routing" | "working" | "memory"
        val message: String? = null,
    ) : CoreEvent()

    data class OnTextStream(
        val chunk: String,
    ) : CoreEvent()

    data class OnToolRequest(
        val id: String,
        val tool: ToolCall,     // 类型安全，非 JSON String
        val executionMode: CapabilityExecutionMode? = null,
    ) : CoreEvent()

    data class OnFinish(
        val result: MixedResult,
    ) : CoreEvent()

    data class OnMemoryCommitted(
        val addedMemories: List<String> = emptyList(),
        val profileUpdated: Boolean = false,
        val newRevision: Long = 0,
    ) : CoreEvent()

    data class OnError(
        val message: String,
    ) : CoreEvent()
}
```

#### MixedResult

```kotlin
// domain/ai/model/MixedResult.kt
package com.deadliner.domain.ai.model

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import kotlinx.serialization.Serializable

@Serializable
data class MixedResult(
    val primaryIntent: String? = null,          // "ExtractTasks" | "ExtractHabits" | "Chat"
    val routedAgents: List<AgentRole> = emptyList(),
    val tasks: List<AiTaskProposal> = emptyList(),
    val habits: List<AiHabitProposal> = emptyList(),
    val retrievedTasks: List<AiTaskProposal> = emptyList(),
    val retrievedHabits: List<AiHabitProposal> = emptyList(),
    val newMemories: List<String> = emptyList(),
    val chatResponse: String? = null,
    val sessionSummary: String? = null,
    val userProfile: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
)
```

#### ToolMetadata

```kotlin
// domain/ai/model/ToolMetadata.kt
package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolMetadata(
    val name: String,
    val description: String,
    val domain: AgentRole,
    val capabilities: List<ToolCapability> = emptyList(),
    val supportedPlatforms: List<String> = emptyList(),
    val requiresUiConfirmation: Boolean = false,
    val parameters: JsonElement,               // JSON Schema，保留为 JsonElement
)

@Serializable
enum class ToolCapability {
    READ_TASK_CONTEXT,
    READ_HABIT_CONTEXT,
    CREATE_TASK,
    CREATE_HABIT,
    UPDATE_TASK_DEADLINE,
}
```

#### AIConfig

```kotlin
// domain/ai/model/AIConfig.kt
package com.deadliner.domain.ai.model

data class AIConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val platform: String,                      // "ios" | "android" | "harmony"
    val timezone: String,                      // "Asia/Shanghai"
    val temperature: Double? = null,           // null = 使用默认策略
)
```

#### ChatRequest / ChatResponse

```kotlin
// domain/ai/model/ChatModels.kt
package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,      // "system" | "user" | "assistant" | "tool"
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>,
)
```

### 3.2 端口接口 — `domain/ai/port/`

```kotlin
// domain/ai/port/LlmGateway.kt
package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.AIError
import com.deadliner.domain.ai.model.ChatRequest
import com.deadliner.domain.ai.model.ChatResponse

/**
 * 大模型网关抽象。
 *
 * 闭源层提供 Ktor 实现；测试时可注入 Mock。
 */
fun interface LlmGateway {
    suspend fun chat(request: ChatRequest): ChatResponse
}
```

```kotlin
// domain/ai/port/AgentOrchestrator.kt
package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.MixedResult

/**
 * Agent 编排器抽象。
 *
 * 接收用户自然语言输入，返回结构化的 MixedResult。
 * 闭源层实现 Supervisor-Worker 多 Agent 调度。
 */
fun interface AgentOrchestrator {
    suspend fun processMessage(text: String): MixedResult
}
```

```kotlin
// domain/ai/port/ToolExecutor.kt
package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.MixedResult

/**
 * 工具执行器抽象。
 *
 * 原生端执行完本地工具后，通过此接口回灌结果，
 * 闭源层进行二段推理并返回最终 MixedResult。
 */
fun interface ToolExecutor {
    suspend fun submitToolResult(toolCallId: String, result: ToolResult): MixedResult
}
```

```kotlin
// domain/ai/port/ToolResult.kt
package com.deadliner.domain.ai.port

import kotlinx.serialization.Serializable

/**
 * 原生端回灌的工具执行结果。
 *
 * payload 为 JSON String，因为不同工具的返回结构不同，
 * 但此 JSON 仅作为 LLM 上下文文本传入，不做结构化解析。
 */
@Serializable
data class ToolResult(
    val tool: String,
    val payload: String,
)
```

```kotlin
// domain/ai/port/MemoryStorePort.kt
package com.deadliner.domain.ai.port

import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.memory.MemorySnapshot

/**
 * 记忆存储抽象。
 *
 * 实现可委托给现有的 KMP MemoryRepository (SQLDelight)。
 * 迁移后消除 Rust memories.json 和 iOS UserDefaults 两份副本。
 */
interface MemoryStorePort {
    suspend fun saveMemory(content: String, category: String)
    suspend fun getAllFragments(): List<MemoryFragment>
    suspend fun saveUserProfile(profile: String)
    suspend fun getUserProfile(): String
    suspend fun getLongTermContext(): String
    suspend fun replaceSnapshot(snapshot: MemorySnapshot)
    suspend fun currentSnapshot(): MemorySnapshot
    suspend fun appendConversationTurn(role: String, content: String)
    suspend fun getRecentConversationContext(limit: Int): String
}
```

```kotlin
// domain/ai/port/SkillRegistryPort.kt
package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.AgentRole
import com.deadliner.domain.ai.model.ToolCapability
import com.deadliner.domain.ai.model.ToolMetadata

/**
 * 技能注册表抽象。
 *
 * 按 AgentRole + 平台过滤可用工具；按 capability 反查工具定义。
 */
interface SkillRegistryPort {
    fun forRoleAndPlatform(role: AgentRole, platform: String): List<ToolMetadata>
    fun resolveExecutionPlan(capability: ToolCapability): ToolMetadata?
}
```

### 3.3 错误类型 — `domain/ai/error/`

```kotlin
// domain/ai/error/AIError.kt
package com.deadliner.domain.ai.error

sealed class AIError(message: String) : Exception(message) {
    data object MissingAPIKey : AIError("请先在设置中填写 API Key")
    data object EmptyResponse : AIError("AI 返回内容为空")
    data object ParsingFailed : AIError("AI 数据解析失败")
    data class NetworkError(val detail: String) : AIError("网络请求失败: $detail")
    data class ConfigError(val detail: String) : AIError("配置错误: $detail")
    data class InvalidToolCall(val detail: String) : AIError("工具调用无效: $detail")
}
```

### 3.4 公开门面 — `interfaces/`

```kotlin
// interfaces/DeadlinerCore.kt
package com.deadliner.interfaces

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.CoreEvent
import com.deadliner.domain.ai.model.MixedResult
import com.deadliner.domain.ai.port.AgentOrchestrator
import com.deadliner.domain.ai.port.ToolExecutor
import com.deadliner.domain.ai.port.ToolResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lifi AI Core 公开 API。
 *
 * 原生端通过此类与 AI 交互：
 * - processInput(text) 发送用户输入
 * - submitToolResult(id, result) 回灌工具结果
 * - events 订阅事件流
 *
 * 内部委托给闭源的 AgentOrchestrator 和 ToolExecutor 实现。
 */
class DeadlinerCore(
    private val config: AIConfig,
    private val orchestrator: AgentOrchestrator,
    private val toolExecutor: ToolExecutor,
) {
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    suspend fun processInput(text: String) {
        val requestId = generateRequestId()
        _events.emit(CoreEvent.OnLifecycle(requestId, "started", "running"))

        try {
            _events.emit(CoreEvent.OnThinking("Supervisor", "routing"))
            val result = orchestrator.processMessage(text)
            handleMixedResult(requestId, result)
        } catch (e: Exception) {
            _events.emit(CoreEvent.OnError(e.message ?: "Unknown error"))
            _events.emit(CoreEvent.OnLifecycle(requestId, "failed", "error", e.message))
        }
    }

    suspend fun submitToolResult(toolCallId: String, result: ToolResult) {
        try {
            val mixed = toolExecutor.submitToolResult(toolCallId, result)
            handleMixedResult(toolCallId, mixed)
        } catch (e: Exception) {
            _events.emit(CoreEvent.OnError(e.message ?: "Tool execution failed"))
        }
    }

    private suspend fun handleMixedResult(requestId: String, result: MixedResult) {
        if (result.toolCalls.isNotEmpty()) {
            result.toolCalls.forEach { call ->
                val callId = "${requestId}_${call.hashCode()}"
                _events.emit(CoreEvent.OnToolRequest(callId, call, call.executionMode))
            }
        } else {
            _events.emit(CoreEvent.OnFinish(result))
            _events.emit(CoreEvent.OnLifecycle(requestId, "completed", "success"))
        }
    }

    private fun generateRequestId(): String =
        "req_${Platform.currentTimeMillis()}"
}
```

```kotlin
// interfaces/AIComponent.kt
package com.deadliner.interfaces

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.port.AgentOrchestrator
import com.deadliner.domain.ai.port.LlmGateway
import com.deadliner.domain.ai.port.MemoryStorePort
import com.deadliner.domain.ai.port.SkillRegistryPort
import com.deadliner.domain.ai.port.ToolExecutor

/**
 * AI Core 依赖注入接口。
 *
 * 开源层定义接口，闭源层提供 LifiAIComponent 实现。
 * 原生端初始化时组装：
 *
 * ```kotlin
 * val component = LifiAIComponent(config, memoryStore)
 * val core = DeadlinerCore(config, component.orchestrator(), component.toolExecutor())
 * ```
 */
interface AIComponent {
    fun orchestrator(): AgentOrchestrator
    fun toolExecutor(): ToolExecutor
    fun memoryStore(): MemoryStorePort
    fun llmGateway(): LlmGateway
    fun skillRegistry(): SkillRegistryPort
}
```

---

## 4. 闭源层设计概要

> 以下仅描述职责与接口契约，不包含具体实现。

### 4.1 Orchestrator

实现 `AgentOrchestrator`，职责：

1. 调用 `SupervisorRouter` 进行意图路由（Task / Habit / Chat）
2. 调用 `WorkerDispatcher` 并行调度领域专家
3. 合并所有 Worker 结果为 `MixedResult`

### 4.2 PromptBuilder

核心 IP，职责：

- `buildSupervisorPrompt()` — 意图分类
- `buildTaskAgentPrompt()` — 任务专家（含时间推算规则）
- `buildHabitAgentPrompt()` — 习惯专家
- `buildChatAgentPrompt()` — 聊天/记忆专家
- `buildMemorySynthesizerPrompt()` — 长期记忆生成

### 4.3 Pipeline

实现 `ToolExecutor`，职责：

1. 从 pending tool calls 取出上下文
2. 调用 `MemoryManager` 注入记忆上下文
3. 调用 `LlmGateway` 进行二段推理
4. 应用 `ConfidenceGate` 置信度门控
5. 返回最终 `MixedResult`

### 4.4 ToolCallNormalizer

职责：

1. `requestedCapabilities` → `ToolCall`（新协议优先）
2. `actions` → `ToolCall`（旧协议兼容）
3. 参数 Schema 校验
4. 置信度 4 维评分（intent_match + args_complete + time_parse + query）

### 4.5 SkillRegistry

实现 `SkillRegistryPort`，职责：

- 从内嵌 JSON 加载工具定义
- 按 `AgentRole` + `platform` 过滤
- `capability` → 唯一 `ToolMetadata` 解析

### 4.6 KtorLlmClient

实现 `LlmGateway`，职责：

- Ktor HttpClient 调用 `/chat/completions`
- URL 智能规范化（自动补 https、去尾斜杠）
- Moonshot API 自动调整 temperature

---

## 5. 与现有 KMP 模块的集成

### 5.1 复用关系

| 现有模块 | 文件路径 | AI Core 如何使用 |
|----------|----------|-----------------|
| `MemoryFragment` | `domain/memory/Memory.kt` | `MemoryStorePort` 直接返回此类型 |
| `MemorySnapshot` | `domain/memory/Memory.kt` | `replaceSnapshot()` / `currentSnapshot()` 使用 |
| `AiTaskProposal` | `domain/ai/AiProposals.kt` | `MixedResult.tasks` 类型 |
| `AiHabitProposal` | `domain/ai/AiProposals.kt` | `MixedResult.habits` 类型 |
| `AiReadFilter` | `domain/ai/AiProposals.kt` | `ToolCall.ReadTasks` 参数映射 |
| `MemoryRepository` | `db/repository/MemoryRepository.kt` | `MemoryStorePort` 实现可委托给它 |
| `TaskRepository` | `db/repository/TaskRepository.kt` | `ToolExecutor` 内部调用 create/update |
| `HabitRepository` | `db/repository/HabitRepository.kt` | 同上 |

### 5.2 持久化统一

**迁移前（三副本）**：

```
Rust: memories.json (LocalMemoryStore)
  ↕ replace_snapshot() 全量覆盖
iOS: UserDefaults (MemoryBank)
  ↕ persistKMPMemorySnapshot() 双向 diff
KMP: DeadlinerDatabase (已部分迁移)
```

**迁移后（单一 KMP store）**：

```
KMP: DeadlinerDatabase (唯一 source of truth)
  ├── AI 推理层通过 MemoryStorePort 读写
  ├── iOS UI 层通过 StateFlow 观察
  └── Android UI 层通过 StateFlow 观察
```

`MemoryStorePort` 的实现委托给现有 `MemoryRepository`，内部使用 SQLDelight：

```kotlin
// 闭源层：ai/memory/KmpMemoryStoreAdapter.kt
class KmpMemoryStoreAdapter(
    private val memoryRepo: MemoryRepository,
    private val profileRepo: UserProfileRepository,
) : MemoryStorePort {
    override suspend fun saveMemory(content: String, category: String) {
        memoryRepo.create(MemoryFragment(
            uid = PlatformIdentity.newId(),
            content = content,
            category = category,
            createdAt = Clock.System.now().toString(),
            updatedAt = Clock.System.now().toString(),
        ))
    }
    // ...
}
```

---

## 6. 原生端集成对比

### 6.1 iOS — 迁移前后

**Before（Rust + UniFFI）**：

```swift
// 工具请求：拿到 String，要自己解析
case .onToolRequest(let id, let toolName, let argsJson):
    let mode = decodeToolExecutionMode(from: argsJson)     // JSON 解析 #1
    let args = decodeArgs(ReadTasksArgs.self, from: argsJson) // JSON 解析 #2

// 提交结果：要自己序列化
let resultJson = try encodeResult(ReadTasksResult(tasks: [...]))
await bridge.submitToolResult(id: id, resultJson: resultJson)

// 记忆同步：全量 JSON 覆盖
bridge.replaceMemorySnapshot(snapshotJson: memoryBank.exportSnapshotJson())
```

**After（KMP）**：

```swift
// 工具请求：直接拿到 Kotlin 对象
for await event in core.events {
    if case .onToolRequest(let id, let tool, _) = event {
        if case .readTasks(let keywords, let status, _, _, _) = tool {
            // 直接用，无需解析
        }
    }
}

// 提交结果：直接传 Kotlin 对象
await core.submitToolResult(toolCallId: id, result: ToolResult(tool: "read_tasks", payload: json))

// 记忆同步：不需要了，KMP store 是唯一 source of truth
```

### 6.2 数据传递开销对比

| 路径 | 迁移前 | 迁移后 |
|------|--------|--------|
| `OnToolRequest` | `serde_json::Value` → `String` → UniFFI → Swift `JSONSerialization` × 2~3 次 | Kotlin sealed class → Swift 直接访问属性 |
| `submitToolResult` | Swift `JSONEncoder` → `String` → UniFFI → Rust 嵌入 LLM prompt | Kotlin `ToolResult(payload)` → 嵌入 LLM prompt |
| `MemorySnapshot` | iOS `UserDefaults` → `JSON String` → UniFFI → Rust `serde_json::from_str` | KMP `MemoryStorePort.currentSnapshot()` 直接返回对象 |

---

## 7. .gitignore 配置

```gitignore
# === Lifi AI Core 闭源实现 ===
shared/src/commonMain/kotlin/com/deadliner/lifi/
shared/src/commonMain/resources/skills/
```

---

## 8. 迁移阶段

### Phase 1：开源骨架

- [ ] 创建 `domain/ai/model/` — 移植所有 sealed class / data class
- [ ] 创建 `domain/ai/port/` — 移植所有 trait 为 Kotlin interface
- [ ] 创建 `domain/ai/error/` — 移植 AIError
- [ ] 创建 `interfaces/DeadlinerCore.kt` — 公开门面
- [ ] 创建 `interfaces/AIComponent.kt` — DI 接口

### Phase 2：闭源实现

- [ ] `ai/llm/KtorLlmClient.kt` — 用 Ktor 替代 reqwest
- [ ] `ai/orchestrator/` — 移植 Supervisor-Worker 调度
- [ ] `ai/prompt/` — 移植 PromptBuilder（核心 IP）
- [ ] `ai/pipeline/` — 移植主流程 + 工具回灌
- [ ] `ai/tool/` — 移植 ToolCallNormalizer + 置信度门控
- [ ] `ai/memory/` — 移植记忆综合逻辑
- [ ] `ai/skill/` — 移植 SkillRegistry + 内嵌 JSON
- [ ] 实现 `LifiAIComponent` 组装

### Phase 3：持久化统一

- [ ] `MemoryStorePort` 实现委托给现有 `MemoryRepository`
- [ ] 移除 Rust `memories.json` 双副本
- [ ] `ConversationHistory` 存入 KMP store
- [ ] iOS 端移除 `MemoryBank` UserDefaults 层

### Phase 4：iOS 集成

- [ ] `AIFunctionView` 改为监听 KMP `CoreEvent`
- [ ] 移除 `DeadlinerCoreBridge`（Rust UniFFI）
- [ ] 移除 `AIService.swift`（已 deprecated）
- [ ] `ToolCallExecutor` 改为接收 Kotlin `ToolCall` 对象

### Phase 5：Android / 鸿蒙集成

- [ ] Android 端接入同一套 KMP AI Core
- [ ] 鸿蒙端接入同一套 KMP AI Core

---

## 9. Rust → KMP 模块映射表

| Rust 文件 | KMP 文件 | 开源/闭源 |
|-----------|----------|-----------|
| `domain/models.rs` (326 行) | `domain/ai/model/*.kt` | 开源 |
| `domain/traits.rs` (57 行) | `domain/ai/port/*.kt` | 开源 |
| `application/prompt_builder.rs` (152 行) | `lifi/prompt/PromptBuilder.kt` | 闭源 |
| `application/orchestrator/basics.rs` (271 行) | `lifi/orchestrator/Orchestrator.kt` | 闭源 |
| `application/orchestrator/tool_calls.rs` (498 行) | `lifi/tool/ToolCallNormalizer.kt` + `ConfidenceGate.kt` | 闭源 |
| `application/orchestrator/memory_and_workers.rs` (423 行) | `lifi/orchestrator/WorkerDispatcher.kt` + `lifi/memory/MemoryManager.kt` | 闭源 |
| `application/orchestrator/pipeline.rs` (486 行) | `lifi/pipeline/Pipeline.kt` + `ToolResultContinuation.kt` | 闭源 |
| `infrastructure/llm_client.rs` (87 行) | `lifi/llm/KtorLlmClient.kt` | 闭源 |
| `infrastructure/memory_store.rs` (275 行) | `lifi/memory/KmpMemoryStoreAdapter.kt` | 闭源 |
| `infrastructure/skill_registry/` (187 行) | `lifi/skill/SkillRegistry.kt` | 闭源 |
| `interfaces/ffi_handlers.rs` (539 行) | `interfaces/DeadlinerCore.kt` | 开源 |
| `interfaces/events.rs` (58 行) | `domain/ai/model/CoreEvent.kt` | 开源 |
| `skills/*.json` | `lifi/resources/skills/*.json` | 闭源 |
