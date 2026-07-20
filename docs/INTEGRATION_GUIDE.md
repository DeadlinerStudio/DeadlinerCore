# Deadliner KMP 集成指南

## 场景 1：创建任务

### 流程图

```
用户点击"创建任务"按钮
    │
    ▼
原生 UI 层 (SwiftUI / Compose / ArkUI)
    │
    │ 调用 KMP ViewModel
    ▼
TaskListViewModel.createTask(config)
    │
    │ 1. 构造 Task 实体
    │ 2. 调用 Repository
    │ 3. 更新内存缓存
    ▼
TaskRepository.create(task)
    │
    │ 1. DB INSERT (SQLDelight)
    │ 2. 内存缓存更新
    │ 3. ChangeLog 记录
    ▼
UI 自动刷新 (StateFlow)
```

### iOS 集成示例

```swift
import Shared

// 1. 初始化 KMP 组件
let database = DeadlinerDatabase.create(IosDatabaseDriverFactory())
let notificationService = NotificationService()
let taskListViewModel = TaskListViewModel(
    taskRepository: database.tasks,
    scope: nil  // 使用默认 scope
)

// 2. 创建 SwiftUI 视图
struct TaskListView: View {
    @StateObject private var viewModel: TaskListViewModelWrapper

    init(database: DeadlinerDatabase, notificationService: NotificationService) {
        _viewModel = StateObject(wrappedValue: TaskListViewModelWrapper(
            kmp: TaskListViewModel(
                taskRepository: database.tasks,
                scope: nil
            )
        ))
    }

    var body: some View {
        List(viewModel.tasks, id: \.uid) { task in
            TaskRow(task: task)
        }
        .onAppear {
            viewModel.initialize()
        }
    }
}

// 3. 包装 KMP ViewModel
class TaskListViewModelWrapper: ObservableObject {
    private let kmp: TaskListViewModel
    @Published var tasks: [Task] = []

    init(kmp: TaskListViewModel) {
        self.kmp = kmp
        // 观察 KMP StateFlow
        // 注意：需要使用 KMP 的 StateFlow 收集机制
    }

    func initialize() {
        kmp.initialize()
    }

    func createTask(title: String, dueAt: String?) {
        let config = TaskCreateConfig(
            title: title,
            note: "",
            startAt: nil,
            dueAt: dueAt,
            categoryUid: nil,
            isStarred: false,
            calendarEventId: nil
        )
        kmp.createTask(config: config)
    }
}
```

### Android 集成示例

```kotlin
import com.deadliner.db.DeadlinerDatabase
import com.deadliner.platform.NotificationService
import com.deadliner.viewmodel.task.TaskListViewModel
import com.deadliner.viewmodel.task.TaskCreateConfig

// 1. 初始化 KMP 组件
class DeadlinerApp : Application() {
    lateinit var database: DeadlinerDatabase
    lateinit var notificationService: NotificationService

    override fun onCreate() {
        super.onCreate()
        database = DeadlinerDatabase.create(AndroidDatabaseDriverFactory(this))
        notificationService = NotificationService(this)
    }
}

// 2. 创建 Compose 视图
@Composable
fun TaskListScreen() {
    val app = LocalContext.current.applicationContext as DeadlinerApp
    val viewModel: TaskListViewModel = remember {
        TaskListViewModel(
            taskRepository = app.database.tasks,
            scope = null
        )
    }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    LazyColumn {
        items(uiState.tasks) { task ->
            TaskRow(task = task)
        }
    }

    // 创建任务示例
    Button(onClick = {
        viewModel.createTask(
            TaskCreateConfig(
                title = "新任务",
                note = "",
                startAt = null,
                dueAt = "2024-12-31T23:59:59Z",
                categoryUid = null,
                isStarred = false,
                calendarEventId = null
            )
        )
    }) {
        Text("创建任务")
    }
}
```

### HarmonyOS 集成示例

```typescript
import { DeadlinerDatabase } from '@deadliner/shared'
import { TaskListViewModel, TaskCreateConfig } from '@deadliner/shared'

// 1. 初始化 KMP 组件
export default class EntryAbility extends UIAbility {
  database: DeadlinerDatabase

  onCreate(want, launchParam) {
    this.database = DeadlinerDatabase.create(new OhosDatabaseDriverFactory(this.context))
  }
}

// 2. 创建 ArkUI 视图
@Entry
@Component
struct TaskListPage {
  @State tasks: Task[] = []
  private viewModel: TaskListViewModel

  aboutToAppear() {
    const ability = getContext(this) as EntryAbility
    this.viewModel = new TaskListViewModel(
      ability.database.tasks,
      null
    )
    this.viewModel.initialize()
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

    Button('创建任务')
      .onClick(() => {
        this.viewModel.createTask(
          new TaskCreateConfig({
            title: '新任务',
            note: '',
            startAt: null,
            dueAt: '2024-12-31T23:59:59Z',
            categoryUid: null,
            isStarred: false,
            calendarEventId: null
          })
        )
      })
  }
}
```

## 场景 2：习惯打卡

### 流程图

```
用户点击"打卡"按钮
    │
    ▼
原生 UI 层
    │
    │ 调用 KMP ViewModel
    ▼
HabitListViewModel.toggleHabit(uid)
    │
    │ 1. 计算今日日期
    │ 2. 调用 Repository.toggleRecord
    │ 3. 更新内存缓存
    ▼
HabitRepository.toggleRecord(habitUid, occurredOn, occurredAt)
    │
    │ 1. 查询当日记录
    │ 2. 判断是否需要添加/删除
    │ 3. DB 操作
    │ 4. ChangeLog 记录
    ▼
UI 自动刷新 (StateFlow)
```

## 场景 3：同步数据

### 流程图

```
同步触发（手动/自动）
    │
    ▼
SyncService.syncNow()
    │
    │ 1. 获取待同步变更
    │ 2. 推送到远程
    │ 3. 拉取远程变更
    │ 4. 应用远程变更
    ▼
ChangeLogSyncFacade.mergeRemotePayload(remotePayload)
    │
    │ 1. 解析远程变更
    │ 2. 冲突解决 (LWW)
    │ 3. 应用到本地 DB
    │ 4. 记录变更日志
    ▼
UI 自动刷新 (StateFlow)
```

## 关键代码路径

| 模块 | 文件路径 |
|------|---------|
| TaskListViewModel | `shared/src/commonMain/kotlin/com/deadliner/viewmodel/task/TaskListViewModel.kt` |
| HabitListViewModel | `shared/src/commonMain/kotlin/com/deadliner/viewmodel/habit/HabitListViewModel.kt` |
| TaskRepository | `shared/src/commonMain/kotlin/com/deadliner/db/repository/TaskRepository.kt` |
| HabitRepository | `shared/src/commonMain/kotlin/com/deadliner/db/repository/HabitRepository.kt` |
| NotificationService (expect) | `shared/src/commonMain/kotlin/com/deadliner/platform/NotificationService.kt` |
| NotificationService (iOS) | `shared/src/iosMain/kotlin/com/deadliner/platform/NotificationService.ios.kt` |
| NotificationService (Android) | `shared/src/androidMain/kotlin/com/deadliner/platform/NotificationService.android.kt` |
| NotificationService (HarmonyOS) | `shared/src/ohosArm64Main/kotlin/com/deadliner/platform/NotificationService.ohos.kt` |
