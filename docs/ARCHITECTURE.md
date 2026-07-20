# Deadliner KMP 架构设计文档

## 1. 架构总览

### 1.1 核心设计原则

1. **IO 优化**：KMP ↔ 原生交互有成本，内存缓存优先，减少跨层调用
2. **接口扩展性**：使用接口 + 密封类，保持未来扩展性
3. **单向数据流**：UI → ViewModel → Service/Repository → DB，状态通过 StateFlow 回流
4. **关注点分离**：Domain 层零依赖，Repository 层只管持久化，Service 层管编排

### 1.2 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          原生 UI 层                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                      │
│  │  iOS App     │  │ Android App  │  │ HarmonyOS    │                      │
│  │  SwiftUI     │  │ Compose      │  │ ArkUI        │                      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                      │
│         │                 │                 │                               │
│         │  收集 StateFlow  │  收集 StateFlow  │  收集 StateFlow              │
│         │  (只读)          │  (只读)          │  (只读)                      │
└─────────┼─────────────────┼─────────────────┼───────────────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         KMP ViewModel 层 (commonMain)                       │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  持有内存缓存 StateFlow，UI 直接订阅                                  │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │  private val _tasks = MutableStateFlow<List<Task>>(emptyList())     │   │
│  │  val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()            │   │
│  │                                                                     │   │
│  │  初始化时从 DB 加载，后续写操作同步更新内存缓存                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                            │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐                │
│  │ TaskListVM     │  │ HabitListVM    │  │ OverviewVM     │                │
│  │ TaskDetailVM   │  │ HabitDetailVM  │  │ CaptureVM      │                │
│  │ TaskEditorVM   │  │ HabitEditorVM  │  │ SettingsVM     │                │
│  │ ArchiveVM      │  │                │  │ SearchVM       │                │
│  └────────┬───────┘  └────────┬───────┘  └────────┬───────┘                │
│           │                   │                   │                         │
│           │  调用 Repository   │  调用 Repository   │  调用 Repository       │
│           │  (suspend)         │  (suspend)         │  (suspend)             │
│           ▼                   ▼                   ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     KMP Service 层 (commonMain)                     │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │   │
│  │  │ SyncService  │  │ Notification │  │ ReminderScheduler        │  │   │
│  │  │              │  │ Orchestrator │  │ (习惯提醒编排逻辑)        │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│           │                   │                   │                         │
│           │  调用 Repository   │  调用 Platform     │  调用 Repository       │
│           ▼                   ▼                   ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     KMP Repository 层 (commonMain)                  │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │  内存缓存 + SQLDelight 持久化                                 │   │   │
│  │  │  ─────────────────────────────────────────────────────────  │   │   │
│  │  │  - 写操作：先写 DB，成功后更新内存缓存 + 记录 ChangeLog      │   │   │
│  │  │  - 读操作：优先从内存缓存读取                                │   │   │
│  │  │  - 初始化：从 DB 加载到内存缓存                              │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │ TaskRepo     │  │ HabitRepo    │  │ CategoryRepo │              │   │
│  │  │ CaptureRepo  │  │ MemoryRepo   │  │ ProfileRepo  │              │   │
│  │  │ ChangeLog    │  │              │  │              │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│           │                   │                   │                         │
│           ▼                   ▼                   ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     KMP Domain 层 (commonMain)                      │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │   │
│  │  │ Task     │ │ Habit    │ │ Category │ │ Capture  │ │ Memory   │ │   │
│  │  │ StateMachine │ Record │ │          │ │          │ │          │ │   │
│  │  │ Subtask  │ │ Schedule │ │          │ │          │ │          │ │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│           │                   │                   │                         │
│           ▼                   ▼                   ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     KMP Persistence 层 (commonMain)                 │   │
│  │  ┌──────────────────────────────────────────────────────────────┐  │   │
│  │  │ SQLDelight (AppDatabase)                                     │  │   │
│  │  │ task / habit / habit_record / habit_schedule / category /    │  │   │
│  │  │ capture_item / memory_fragment / user_profile / changelog    │  │   │
│  │  └──────────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    KMP Platform Abstraction 层 (expect/actual)              │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  expect 接口 (commonMain)              │  actual 实现               │   │
│  │  ─────────────────────────────────────│─────────────────────────── │   │
│  │  NotificationService                   │  iOS: UNUserNotificationCenter │
│  │  PlatformIdentity                      │  Android: AlarmManager     │   │
│  │  PlatformLogger                        │  HarmonyOS: reminderAgent  │   │
│  │  FileStorage                           │                            │   │
│  │  CalendarPort                          │                            │   │
│  │  PermissionPort                        │                            │   │
│  │  BackgroundScheduler                   │                            │   │
│  │  AnalyticsService                      │                            │   │
│  │  WidgetSnapshotProvider                │                            │   │
│  │  WearableSyncPort                      │                            │   │
│  │  SharePort                             │                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 IO 优化策略

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           IO 优化策略                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  1. 内存缓存优先                                                            │
│     ─────────────────────────────────────────────────────────────────      │
│     - ViewModel 持有 StateFlow<List<T>> 内存缓存                           │
│     - UI 直接订阅内存缓存，零 IO 成本                                       │
│     - 写操作后同步更新内存缓存，无需重新查询 DB                              │
│                                                                            │
│  2. 写操作流程                                                              │
│     ─────────────────────────────────────────────────────────────────      │
│     ViewModel.createTask(task)                                              │
│         │                                                                   │
│         ▼                                                                   │
│     Repository.create(task)                                                 │
│         │                                                                   │
│         ├── 1. DB INSERT (SQLDelight)                                       │
│         ├── 2. 内存缓存更新 (_tasks.value += task)                          │
│         ├── 3. ChangeLog 记录                                               │
│         └── 4. 触发同步调度                                                  │
│                                                                            │
│  3. 读操作流程                                                              │
│     ─────────────────────────────────────────────────────────────────      │
│     ViewModel.loadTasks()                                                   │
│         │                                                                   │
│         ▼                                                                   │
│     Repository.list()                                                       │
│         │                                                                   │
│         ├── 优先从内存缓存读取                                               │
│         └── 缓存未命中时从 DB 加载                                           │
│                                                                            │
│  4. 初始化流程                                                              │
│     ─────────────────────────────────────────────────────────────────      │
│     ViewModel.init {                                                        │
│         Repository.initialize()  // 从 DB 加载到内存缓存                    │
│         _tasks.value = Repository.list()                                    │
│     }                                                                       │
│                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.4 接口扩展性设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         接口扩展性设计                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  1. 使用接口而非具体类                                                      │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ 接口定义，易于扩展和测试                                           │
│     interface TaskRepository {                                              │
│         fun list(): List<Task>                                              │
│         fun find(uid: String): Task?                                        │
│         fun create(task: Task)                                              │
│     }                                                                       │
│                                                                            │
│     // ❌ 具体类，难以扩展                                                   │
│     class TaskRepository(private val db: AppDatabase) { ... }               │
│                                                                            │
│  2. 使用密封类/枚举而非硬编码字符串                                          │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ 密封类，编译时类型安全                                              │
│     sealed class TaskState {                                                │
│         object Active : TaskState()                                         │
│         object Completed : TaskState()                                      │
│         data class Archived(val archivedAt: String) : TaskState()           │
│     }                                                                       │
│                                                                            │
│     // ❌ 硬编码字符串，容易出错                                              │
│     val state = "active"  // 容易拼错                                        │
│                                                                            │
│  3. 使用泛型提高复用性                                                      │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ 泛型 Repository 接口                                               │
│     interface Repository<T : Entity> {                                      │
│         fun list(): List<T>                                                 │
│         fun find(uid: String): T?                                           │
│         fun create(entity: T)                                               │
│         fun update(entity: T)                                               │
│         fun delete(uid: String)                                             │
│     }                                                                       │
│                                                                            │
│     interface TaskRepository : Repository<Task> {                           │
│         fun applyAction(uid: String, action: TaskAction): TaskActionResult  │
│     }                                                                       │
│                                                                            │
│  4. 使用 Result 类型而非抛异常                                               │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ Result 类型，调用方必须处理错误                                     │
│     fun create(task: Task): Result<Task>                                    │
│                                                                            │
│     // ❌ 抛异常，调用方容易忽略                                              │
│     fun create(task: Task): Task  // 可能抛异常                              │
│                                                                            │
│  5. 使用配置对象而非多参数                                                   │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ 配置对象，易于扩展新参数                                            │
│     data class TaskCreateConfig(                                            │
│         val title: String,                                                  │
│         val dueAt: String? = null,                                          │
│         val priority: Priority = Priority.NORMAL,                           │
│         val categoryUid: String? = null,                                    │
│         // 未来可轻松添加新字段                                               │
│     )                                                                       │
│     fun create(config: TaskCreateConfig): Result<Task>                      │
│                                                                            │
│     // ❌ 多参数，扩展困难                                                    │
│     fun create(title: String, dueAt: String?, priority: Priority): Task     │
│                                                                            │
│  6. 使用回调/Flow 而非轮询                                                   │
│     ─────────────────────────────────────────────────────────────────      │
│     // ✅ Flow，响应式更新                                                   │
│     fun observeTasks(): Flow<List<Task>>                                    │
│                                                                            │
│     // ❌ 轮询，浪费资源                                                     │
│     fun getTasks(): List<Task>  // 需要主动调用                              │
│                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 2. 分层职责

### 2.1 Domain 层 (`domain/`)
**职责**：定义业务实体和状态机，零依赖

| 模块 | 文件 | 说明 |
|------|------|------|
| task | `Task.kt`, `TaskState.kt`, `TaskStateMachine.kt` | 任务实体 + 状态机 |
| habit | `Habit.kt`, `HabitRecord.kt`, `HabitSchedule.kt` | 习惯实体 + 打卡 + 排程 |
| category | `Category.kt`, `CategoryPresets.kt` | 分类实体 + 预设 |
| capture | `Capture.kt` | 快速捕获实体 |
| memory | `Memory.kt` | AI Memory 实体 |
| account | `UserProfile.kt`, `UserTier.kt` | 用户配置 |
| sync | `EntityVersion.kt`, `ChangeLogEntry.kt` | 同步版本 + 变更日志 |

### 2.2 Repository 层 (`db/repository/`)
**职责**：数据持久化 + 内存缓存 + 业务逻辑 + 变更日志记录

```kotlin
// 基础 Repository 接口（泛型，提高复用性）
interface Repository<T : Entity> {
    fun list(): List<T>
    fun find(uid: String): T?
    fun create(entity: T): Result<T>
    fun update(entity: T): Result<T>
    fun delete(uid: String): Result<Unit>
    fun observe(): Flow<List<T>>
}

// 实体基础接口
interface Entity {
    val uid: String
    val isDeleted: Boolean
}
```

| Repository | 职责 | 依赖 |
|------------|------|------|
| TaskRepository | Task CRUD + 状态流转 + 子任务 + 内存缓存 | AppDatabase, ChangeLogRepository |
| HabitRepository | Habit CRUD + 打卡 + 周期计算 + 内存缓存 | AppDatabase, ChangeLogRepository |
| CategoryRepository | Category CRUD + 内存缓存 | AppDatabase, ChangeLogRepository |
| CaptureRepository | Capture CRUD + 内存缓存 | AppDatabase, ChangeLogRepository |
| MemoryRepository | Memory CRUD + 内存缓存 | AppDatabase, ChangeLogRepository |
| UserProfileRepository | Profile CRUD + 内存缓存 | AppDatabase, ChangeLogRepository |
| ChangeLogRepository | 变更日志管理 | AppDatabase |

### 2.3 Service 层 (`service/`)
**职责**：跨 Repository 的业务编排 + 平台能力调用

| Service | 职责 | 依赖 |
|---------|------|------|
| SyncService | 同步调度 + 冲突解决 | ChangeLogRepository, SyncTransport |
| NotificationOrchestrator | 通知编排（调用平台 NotificationService） | TaskRepository, HabitRepository, NotificationService |
| ReminderScheduler | 习惯提醒逻辑（周期计算 + 智能调度） | HabitRepository, NotificationService |
| ThemeService | 主题配置管理 | ThemeConfigPort |

### 2.4 ViewModel 层 (`viewmodel/`)
**职责**：管理 UI State（内存缓存），调用 Repository/Service，暴露 StateFlow

```kotlin
// ViewModel 基础接口（扩展性设计）
interface ViewModel<T : UiState> {
    val uiState: StateFlow<T>
    fun initialize()
    fun dispose()
}

// UiState 基础接口
interface UiState {
    val isLoading: Boolean
    val error: String?
}
```

| ViewModel | UiState | 职责 | 依赖 |
|-----------|---------|------|------|
| TaskListViewModel | TaskListUiState | 任务列表页状态 + 内存缓存 | TaskRepository, NotificationOrchestrator |
| TaskDetailViewModel | TaskDetailUiState | 任务详情页状态 | TaskRepository |
| TaskEditorViewModel | TaskEditorUiState | 任务编辑页状态 | TaskRepository, CategoryRepository |
| HabitListViewModel | HabitListUiState | 习惯列表页状态 + 内存缓存 | HabitRepository, ReminderScheduler |
| HabitDetailViewModel | HabitDetailUiState | 习惯详情页状态 | HabitRepository |
| OverviewViewModel | OverviewUiState | 概览页统计状态 | TaskRepository, HabitRepository |
| ArchiveViewModel | ArchiveUiState | 归档页状态 | TaskRepository |
| CaptureViewModel | CaptureUiState | 快速捕获页状态 | CaptureRepository |
| SearchViewModel | SearchUiState | 搜索页状态 | TaskRepository, HabitRepository |
| SettingsViewModel | SettingsUiState | 设置页状态 | UserProfileRepository |

### 2.5 Platform Abstraction 层 (`platform/`)
**职责**：定义平台能力接口，各端 actual 实现

```kotlin
// 平台服务基础接口（扩展性设计）
interface PlatformService {
    fun initialize()
    fun dispose()
}

// 通知服务接口（使用配置对象，易于扩展）
interface NotificationService : PlatformService {
    fun setTaskNotification(config: TaskNotificationConfig)
    fun cancelTaskNotification(taskUid: String)
    fun setHabitNotification(config: HabitNotificationConfig)
    fun cancelHabitNotification(habitUid: String)
    fun setDailySummaryNotification(config: DailySummaryConfig)
    fun cancelDailySummaryNotification()
    fun cancelAll()
    fun refreshAll(tasks: List<Task>, habits: List<Habit>)
}

// 通知配置对象（易于扩展新参数）
data class TaskNotificationConfig(
    val taskUid: String,
    val title: String,
    val dueAt: String,
    val reminderMinutes: Int = 60,  // 提前 N 分钟提醒
    val priority: NotificationPriority = NotificationPriority.NORMAL,
)

data class HabitNotificationConfig(
    val habitUid: String,
    val name: String,
    val alarmTime: String,
    val daysOfWeek: List<DayOfWeek> = DayOfWeek.entries,
)

data class DailySummaryConfig(
    val time: String,
    val showTaskCount: Boolean = true,
    val showHabitProgress: Boolean = true,
)

// 通知优先级（密封类，编译时类型安全）
enum class NotificationPriority {
    LOW, NORMAL, HIGH, URGENT
}

// 星期几（密封类，编译时类型安全）
enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
}
```

| 接口 | 优先级 | 说明 | 扩展性设计 |
|------|--------|------|-----------|
| NotificationService | P0 | 通知调度 | 配置对象 + 密封类 |
| PlatformIdentity | P0 | UUID 生成 + 设备标识 | 接口 |
| PlatformLogger | P0 | 统一日志 | 接口 + 日志级别 |
| AnalyticsService | P0 | 崩溃报告 + 埋点 | 接口 + 事件配置 |
| FileStorage | P1 | 文件读写 | 接口 + 路径配置 |
| CalendarPort | P1 | 日历集成 | 接口 + 事件配置 |
| PermissionPort | P1 | 权限管理 | 接口 + 权限类型枚举 |
| BackgroundScheduler | P1 | 后台任务调度 | 接口 + 调度配置 |
| WidgetSnapshotProvider | P2 | Widget 数据准备 | 接口 + 快照配置 |
| WearableSyncPort | P2 | 穿戴同步 | 接口 + 同步配置 |
| SharePort | P2 | 系统分享 | 接口 + 分享内容配置 |
| ThemeConfigPort | P2 | 主题配置 | 接口 + 主题配置对象 |

## 3. 目标目录结构

```
shared/src/
├── commonMain/kotlin/com/deadliner/
│   ├── domain/                          # Domain 层（实体 + 状态机）
│   │   ├── task/
│   │   │   ├── Task.kt
│   │   │   ├── TaskState.kt
│   │   │   ├── TaskStateMachine.kt
│   │   │   └── TaskSubtask.kt
│   │   ├── habit/
│   │   │   ├── Habit.kt
│   │   │   ├── HabitRecord.kt
│   │   │   ├── HabitSchedule.kt
│   │   │   ├── HabitPeriod.kt
│   │   │   └── HabitStateMachine.kt
│   │   ├── category/
│   │   │   ├── Category.kt
│   │   │   └── CategoryPresets.kt
│   │   ├── capture/
│   │   │   └── Capture.kt
│   │   ├── memory/
│   │   │   └── Memory.kt
│   │   ├── account/
│   │   │   ├── UserProfile.kt
│   │   │   └── UserTier.kt
│   │   └── sync/
│   │       ├── EntityVersion.kt
│   │       └── ChangeLogEntry.kt
│   │
│   ├── db/                              # Persistence 层
│   │   ├── AppDatabase.sq               # SQLDelight Schema
│   │   ├── Task.sq                      # Task 查询
│   │   ├── Habit.sq                     # Habit 查询
│   │   ├── HabitRecord.sq               # HabitRecord 查询
│   │   ├── HabitSchedule.sq             # HabitSchedule 查询
│   │   ├── Category.sq                  # Category 查询
│   │   ├── Capture.sq                   # Capture 查询
│   │   ├── Memory.sq                    # Memory 查询
│   │   ├── UserProfile.sq               # UserProfile 查询
│   │   ├── ChangeLog.sq                 # ChangeLog 查询
│   │   ├── DeadlinerDatabase.kt         # Façade
│   │   ├── DatabaseDriverFactory.kt     # expect
│   │   └── repository/                  # Repository 层
│   │       ├── TaskRepository.kt
│   │       ├── HabitRepository.kt
│   │       ├── CategoryRepository.kt
│   │       ├── CaptureRepository.kt
│   │       ├── MemoryRepository.kt
│   │       ├── UserProfileRepository.kt
│   │       └── ChangeLogRepository.kt
│   │
│   ├── service/                         # Service 层（业务编排）
│   │   ├── SyncService.kt               # 同步服务
│   │   ├── NotificationOrchestrator.kt  # 通知编排
│   │   ├── ReminderScheduler.kt         # 提醒调度
│   │   └── ThemeService.kt              # 主题服务
│   │
│   ├── viewmodel/                       # ViewModel 层
│   │   ├── task/
│   │   │   ├── TaskListViewModel.kt
│   │   │   ├── TaskDetailViewModel.kt
│   │   │   ├── TaskEditorViewModel.kt
│   │   │   └── ArchiveViewModel.kt
│   │   ├── habit/
│   │   │   ├── HabitListViewModel.kt
│   │   │   ├── HabitDetailViewModel.kt
│   │   │   └── HabitEditorViewModel.kt
│   │   ├── overview/
│   │   │   └── OverviewViewModel.kt
│   │   ├── capture/
│   │   │   └── CaptureViewModel.kt
│   │   ├── search/
│   │   │   └── SearchViewModel.kt
│   │   └── settings/
│   │       └── SettingsViewModel.kt
│   │
│   ├── platform/                        # 平台抽象层 (expect)
│   │   ├── NotificationService.kt
│   │   ├── PlatformIdentity.kt
│   │   ├── PlatformLogger.kt
│   │   ├── AnalyticsService.kt
│   │   ├── FileStorage.kt
│   │   ├── CalendarPort.kt
│   │   ├── PermissionPort.kt
│   │   ├── BackgroundScheduler.kt
│   │   ├── WidgetSnapshotProvider.kt
│   │   ├── WearableSyncPort.kt
│   │   ├── SharePort.kt
│   │   └── ThemeConfigPort.kt
│   │
│   └── sync/                            # 同步层
│       ├── SyncEngine.kt
│       ├── ConflictResolver.kt
│       ├── ChangeLogSyncFacade.kt
│       ├── LegacyV2SyncFacade.kt
│       ├── protocol/
│       │   └── SyncProtocol.kt
│       └── snapshot/
│           ├── SnapshotV3.kt
│           ├── SnapshotV3Codec.kt
│           └── v2/
│
├── androidMain/kotlin/com/deadliner/platform/  # Android actual
│   ├── NotificationService.android.kt
│   ├── PlatformIdentity.android.kt
│   ├── PlatformLogger.android.kt
│   ├── AnalyticsService.android.kt
│   ├── FileStorage.android.kt
│   ├── CalendarPort.android.kt
│   ├── PermissionPort.android.kt
│   ├── BackgroundScheduler.android.kt
│   ├── WidgetSnapshotProvider.android.kt
│   └── SharePort.android.kt
│
├── iosMain/kotlin/com/deadliner/platform/      # iOS actual
│   ├── NotificationService.ios.kt
│   ├── PlatformIdentity.ios.kt
│   ├── PlatformLogger.ios.kt
│   ├── AnalyticsService.ios.kt
│   ├── FileStorage.ios.kt
│   ├── CalendarPort.ios.kt
│   ├── PermissionPort.ios.kt
│   ├── BackgroundScheduler.ios.kt
│   ├── WidgetSnapshotProvider.ios.kt
│   ├── WearableSyncPort.ios.kt
│   └── SharePort.ios.kt
│
└── ohosArm64Main/kotlin/com/deadliner/platform/ # HarmonyOS actual
    ├── NotificationService.ohos.kt
    ├── PlatformIdentity.ohos.kt
    ├── PlatformLogger.ohos.kt
    ├── AnalyticsService.ohos.kt
    ├── FileStorage.ohos.kt
    ├── CalendarPort.ohos.kt
    ├── PermissionPort.ohos.kt
    ├── BackgroundScheduler.ohos.kt
    ├── WidgetSnapshotProvider.ohos.kt
    └── SharePort.ohos.kt
```

## 4. ViewModel 详细设计

### 4.1 TaskListViewModel（IO 优化示例）

```kotlin
class TaskListViewModel(
    private val taskRepository: TaskRepository,
    private val notificationOrchestrator: NotificationOrchestrator,
) {
    // ===== 内存缓存（IO 优化核心）=====
    private val _uiState = MutableStateFlow(TaskListUiState())
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    // ===== 初始化（从 DB 加载到内存缓存）=====
    fun initialize() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val tasks = taskRepository.list()  // 优先从内存缓存读取
            _uiState.update {
                it.copy(
                    tasks = tasks,
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    // ===== 写操作（同步更新内存缓存）=====
    fun createTask(config: TaskCreateConfig) {
        viewModelScope.launch {
            val result = taskRepository.create(config)
            result.fold(
                onSuccess = { task ->
                    // 直接更新内存缓存，无需重新查询 DB
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks + task,
                            error = null,
                        )
                    }
                    // 触发通知调度
                    notificationOrchestrator.onTaskCreated(task)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message)
                    }
                },
            )
        }
    }

    fun toggleTaskComplete(uid: String) {
        viewModelScope.launch {
            val result = taskRepository.applyAction(uid, TaskAction.MARK_COMPLETE)
            result.fold(
                onSuccess = { task ->
                    // 直接更新内存缓存中的特定任务
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.map {
                                if (it.uid == uid) task else it
                            },
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message)
                    }
                },
            )
        }
    }

    fun deleteTask(uid: String) {
        viewModelScope.launch {
            val result = taskRepository.delete(uid)
            result.fold(
                onSuccess = {
                    // 从内存缓存中移除
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.filter { it.uid != uid },
                            error = null,
                        )
                    }
                    notificationOrchestrator.onTaskDeleted(uid)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = error.message)
                    }
                },
            )
        }
    }
}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class TaskListUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val filter: TaskFilter = TaskFilter.ALL,
    val sortBy: TaskSortBy = TaskSortBy.DUE_DATE,
) : UiState

// 过滤条件（密封类，编译时类型安全）
sealed class TaskFilter {
    object ALL : TaskFilter()
    object ACTIVE : TaskFilter()
    object COMPLETED : TaskFilter()
    object ARCHIVED : TaskFilter()
    data class BY_CATEGORY(val categoryUid: String) : TaskFilter()
}

// 排序方式（密封类，编译时类型安全）
enum class TaskSortBy {
    DUE_DATE, CREATED_AT, PRIORITY, TITLE
}
```

### 4.2 TaskCreateConfig（配置对象示例）

```kotlin
// 使用配置对象，易于扩展新参数
data class TaskCreateConfig(
    val title: String,
    val note: String = "",
    val startAt: String? = null,
    val dueAt: String? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val categoryUid: String? = null,
    val isStarred: Boolean = false,
    val calendarEventId: Long? = null,
    val reminderMinutes: Int? = null,
    // 未来可轻松添加新字段
    // val tags: List<String> = emptyList(),
    // val location: String? = null,
)

// 优先级（密封类，编译时类型安全）
enum class TaskPriority {
    LOW, NORMAL, HIGH, URGENT
}
```

## 5. 数据流

### 4.1 用户操作流（以创建 Task 为例）

```
用户点击"创建任务"按钮
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 原生 UI 层 (SwiftUI / Compose / ArkUI)                        │
│   TaskEditorView.onCreateButtonTapped()                       │
│       │                                                       │
│       │ 调用 KMP ViewModel                                    │
│       ▼                                                       │
│   taskEditorViewModel.createTask(title, dueAt, ...)           │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ KMP ViewModel 层 (commonMain)                                 │
│   TaskEditorViewModel.createTask()                            │
│       │                                                       │
│       │ 1. 构造 Task 实体                                      │
│       │ 2. 调用 Repository                                     │
│       │ 3. 调用 NotificationOrchestrator                       │
│       │ 4. 更新 UiState                                        │
│       ▼                                                       │
│   taskRepository.create(task)                                 │
│   notificationOrchestrator.onTaskCreated(task)                │
│   _uiState.update { it.copy(tasks = ...) }                   │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ KMP Repository 层 (commonMain)                                │
│   TaskRepository.create(task)                                 │
│       │                                                       │
│       │ 1. INSERT into SQLDelight                              │
│       │ 2. 记录 ChangeLog                                      │
│       ▼                                                       │
│   database.taskQueries.insertTask(...)                        │
│   changeLog.appendLocal("task", uid, INSERT, ...)            │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ KMP Service 层 (commonMain)                                   │
│   NotificationOrchestrator.onTaskCreated(task)                │
│       │                                                       │
│       │ 1. 计算提醒时间                                         │
│       │ 2. 调用平台通知服务                                     │
│       ▼                                                       │
│   notificationService.setTaskNotification(                    │
│       task.uid, task.title, task.dueAt                        │
│   )                                                           │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 平台 actual 实现                                               │
│                                                               │
│   iOS:                                                        │
│       UNUserNotificationCenter.add(request)                   │
│                                                               │
│   Android:                                                    │
│       AlarmManager.setAlarmClock(info, pendingIntent)         │
│                                                               │
│   HarmonyOS:                                                  │
│       reminderAgentManager.publishReminder(request)           │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ UI 更新                                                        │
│                                                               │
│   iOS: StateFlow → @Published → SwiftUI 自动刷新              │
│   Android: StateFlow → collectAsState() → Compose 自动刷新    │
│   HarmonyOS: StateFlow → @State → ArkUI 自动刷新              │
└───────────────────────────────────────────────────────────────┘
```

### 4.2 同步流

```
本地数据变更 (Repository.create/update/delete)
    │
    │ 自动记录 ChangeLog
    ▼
┌───────────────────────────────────────────────────────────────┐
│ ChangeLogRepository                                            │
│   appendLocal(tableName, recordId, operation, changedFields)  │
└───────────────────────────────────────────────────────────────┘
    │
    │ 触发同步调度
    ▼
┌───────────────────────────────────────────────────────────────┐
│ SyncService                                                    │
│   scheduleSync() → 去抖 500ms → syncNow()                     │
│       │                                                       │
│       │ 1. 获取待同步变更                                        │
│       │ 2. 推送到远程                                           │
│       │ 3. 拉取远程变更                                         │
│       │ 4. 应用远程变更                                         │
│       ▼                                                       │
│   changeLogSyncFacade.mergeRemotePayload(remotePayload)       │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 平台 actual 后台调度                                            │
│                                                               │
│   iOS: BGTaskScheduler                                        │
│   Android: WorkManager                                        │
│   HarmonyOS: WorkScheduler                                    │
└───────────────────────────────────────────────────────────────┘
```

## 5. 依赖注入

使用简单的构造函数注入，不引入 DI 框架：

```kotlin
// commonMain
class DeadlinerDatabase private constructor(
    internal val database: AppDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    // Repository 层
    private val changeLogRepository = ChangeLogRepository(database)
    val categories = CategoryRepository(database, changeLogRepository)
    val tasks = TaskRepository(database, changeLogRepository)
    val habits = HabitRepository(database, changeLogRepository)
    val captures = CaptureRepository(database, changeLogRepository)
    val memory = MemoryRepository(database, changeLogRepository)
    val profiles = UserProfileRepository(database, changeLogRepository)
    val changeLog = changeLogRepository

    // Service 层（需要平台注入）
    fun createNotificationOrchestrator(
        notificationService: NotificationService,
    ) = NotificationOrchestrator(tasks, habits, notificationService)

    fun createSyncService(
        transport: SyncTransport,
    ) = SyncService(changeLogRepository, transport)

    // ViewModel 工厂
    fun createTaskListViewModel(
        notificationService: NotificationService,
    ) = TaskListViewModel(tasks, createNotificationOrchestrator(notificationService))

    fun createHabitListViewModel(
        notificationService: NotificationService,
    ) = HabitListViewModel(habits, createNotificationOrchestrator(notificationService))

    fun createOverviewViewModel() = OverviewViewModel(tasks, habits)

    companion object {
        fun create(factory: DatabaseDriverFactory): DeadlinerDatabase {
            val driver = factory.createDriver()
            val database = AppDatabase(driver).also { it.appDatabaseQueries.enableForeignKeys() }
            return DeadlinerDatabase(database, driver)
        }
    }
}
```

## 6. 原生层集成模式

### 6.1 iOS 集成

```swift
// DeadlinerApp.swift
@main
struct DeadlinerApp: App {
    let database = DeadlinerDatabase.create(IosDatabaseDriverFactory())
    let notificationService = IOSNotificationService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.database, database)
                .environment(\.notificationService, notificationService)
        }
    }
}

// TaskListView.swift
struct TaskListView: View {
    @Environment(\.database) var database: DeadlinerDatabase
    @Environment(\.notificationService) var notificationService
    @StateObject private var viewModel: TaskListViewModelWrapper

    init(database: DeadlinerDatabase, notificationService: NotificationService) {
        _viewModel = StateObject(wrappedValue: TaskListViewModelWrapper(
            kmp: database.createTaskListViewModel(notificationService: notificationService)
        ))
    }

    var body: some View {
        List(viewModel.tasks, id: \.uid) { task in
            TaskRow(task: task)
        }
    }
}
```

### 6.2 Android 集成

```kotlin
// DeadlinerApp.kt
class DeadlinerApp : Application() {
    lateinit var database: DeadlinerDatabase
    lateinit var notificationService: AndroidNotificationService

    override fun onCreate() {
        super.onCreate()
        database = DeadlinerDatabase.create(AndroidDatabaseDriverFactory(this))
        notificationService = AndroidNotificationService(this)
    }
}

// TaskListScreen.kt
@Composable
fun TaskListScreen() {
    val app = LocalContext.current.applicationContext as DeadlinerApp
    val viewModel: TaskListViewModel = remember {
        app.database.createTaskListViewModel(app.notificationService)
    }
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn {
        items(uiState.tasks) { task ->
            TaskRow(task = task)
        }
    }
}
```

### 6.3 HarmonyOS 集成

```typescript
// EntryAbility.ets
export default class EntryAbility extends UIAbility {
  database: DeadlinerDatabase
  notificationService: OhosNotificationService

  onCreate(want, launchParam) {
    this.database = DeadlinerDatabase.create(new OhosDatabaseDriverFactory(this.context))
    this.notificationService = new OhosNotificationService(this.context)
  }
}

// TaskListPage.ets
@Component
struct TaskListPage {
  @State tasks: Task[] = []
  private viewModel: TaskListViewModel

  aboutToAppear() {
    const ability = getContext(this) as EntryAbility
    this.viewModel = ability.database.createTaskListViewModel(ability.notificationService)
    this.viewModel.uiState.subscribe(state => {
      this.tasks = state.tasks
    })
  }

  build() {
    List() {
      ForEach(this.tasks, (task: Task) => {
        TaskRow({ task })
      })
    }
  }
}
```

## 7. 迁移策略

### Phase 1: 补充平台抽象层
1. 在 `commonMain/platform/` 定义 expect 接口
2. 各平台实现 actual

### Phase 2: 迁移 ViewModel 层
1. 在 `commonMain/viewmodel/` 实现 ViewModel
2. 从 iOS/Android/HarmonyOS 的 ViewModel/PageController 中提取业务逻辑

### Phase 3: 迁移 Service 层
1. 在 `commonMain/service/` 实现 NotificationOrchestrator
2. 将通知调度逻辑从原生层迁移到 KMP

### Phase 4: 原生层瘦身
1. 原生层只保留 UI 渲染
2. 移除原生层的业务逻辑代码
3. 统一调用 KMP ViewModel

### Phase 5: 测试验证
1. 在 commonTest 中编写 ViewModel 测试
2. 三端 UI 测试验证行为一致性
