# iOS 迁移指南：接入 DeadlinerCore

本文说明如何把既有 iOS 宿主逐步迁移到 `DeadlinerCore` 的 KMP 数据、业务编排和 ViewModel 层。迁移目标是让 SwiftUI 继续拥有页面、导航、权限申请与系统生命周期，而把领域规则、SQLite 持久化、变更日志和通知生命周期决策收敛到 shared framework。

## 当前可迁移范围

| 能力 | KMP 状态 | iOS 宿主责任 |
| --- | --- | --- |
| Task、Habit、Category 持久化 | 可用，SQLDelight Native SQLite | 创建 `IosDatabaseDriverFactory` 并持有 `DeadlinerDatabase` |
| Task/Habit 通知生命周期 | 可用，`NotificationOrchestrator` | 请求通知权限，注入 `NotificationService` |
| 页面状态与写入 | Task/Habit ViewModel 可用 | StateFlow 转为 `ObservableObject` / SwiftUI state |
| 同步与快照 | shared contract 可用 | 网络传输、认证、重试、远端数据应用 |
| SwiftUI UI 测试 | 不在本仓库 | 在 iOS 宿主工程编写并执行 |

不要把 KMP preview 数据库直接替换旧 v5 数据库。它的数据库名固定为 `deadliner_new_era.db`，与旧库隔离；历史数据迁移必须走导出、审查、导入的显式流程。

## 1. 构建并接入 framework

在仓库根目录构建 XCFramework：

```bash
./gradlew :shared:assembleSharedXCFramework --no-daemon --console=plain
```

将生成的 `Shared.xcframework` 添加到 iOS Xcode target，并把 `sqlite3` 加入 **Build Settings → Other Linker Flags**：

```text
-lsqlite3
```

在 Xcode 的 Frameworks, Libraries, and Embedded Content 中选择 **Embed & Sign**。Simulator 只需先确认 framework 链接：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon --console=plain
```

`Shared` 的 generated Swift/Objective-C header 是 Swift 调用签名的唯一准则。Kotlin 默认参数和 companion 方法的 Swift selector 可能随 Kotlin/Native 版本变化，集成时不要根据本文手写的示意名称猜测 selector。

## 2. 在 App composition root 创建 Core

在 `App`、`AppDelegate` 或既有 dependency container 中为每个数据库生命周期创建一次 `DeadlinerDatabase`。推荐的对象关系是：

```text
SwiftUI App
  └─ IOSCoreContainer
       ├─ IosDatabaseDriverFactory
       ├─ DeadlinerDatabase
       ├─ NotificationService
       └─ NotificationOrchestrator
            └─ Task/Habit ViewModel
```

对应的 Kotlin API 关系如下（Swift 调用时以 generated header 的实际 selector 为准）：

```kotlin
val core = DeadlinerDatabase.create(IosDatabaseDriverFactory())
val notifications = NotificationService()
val orchestrator = core.createNotificationOrchestrator(notifications)

val tasks = TaskListViewModel(
    taskRepository = core.tasks,
    notificationOrchestrator = orchestrator,
)
```

将 `core` 与 `notifications` 保持为宿主的长生命周期依赖；不要在每个 SwiftUI `View` 的 `body` 或重绘中创建数据库。应用确实结束该生命周期时调用 `core.close()`。

## 3. 先迁移读取，再迁移写入

建议每个功能按下列顺序迁移，允许旧 UI 与 KMP 并存：

1. 在一个只读 SwiftUI 页面用 KMP repository/ViewModel 显示 Task 或 Habit。
2. 将 StateFlow 状态桥接为 `@Published` 或 `@Observable` 状态；UI 更新必须回到 MainActor。
3. 迁移创建、编辑、完成、归档和删除等写入操作。
4. 为该页面补 UI 行为测试，再移除等价的原生业务规则。

Task 写入路径应注入同一个 `NotificationOrchestrator`：

| KMP 写入点 | 通知结果 |
| --- | --- |
| 创建/更新 active 且有 `dueAt` 的 Task | 调度或重建任务通知 |
| 完成、归档、放弃、删除 Task | 取消任务通知 |
| 创建/更新 active、启用 `HabitReminder` 的 Habit | 调度或重建习惯通知 |
| 归档、删除或禁用提醒的 Habit | 取消习惯通知 |

`TaskListViewModel`、`TaskDetailViewModel`、`TaskEditorViewModel`、`ArchiveViewModel` 和 `HabitListViewModel` 都接受可选的 `notificationOrchestrator`。已有的 positional 构造调用仍兼容，但没有注入 orchestrator 的页面不会获得共享通知生命周期管理。

## 4. 通知与权限边界

在 iOS 宿主首次需要提醒前请求 `UNUserNotificationCenter` 权限；KMP 的 `NotificationService` 只负责已获授权后的请求调度，不会替 SwiftUI 页面展示权限说明或设置页跳转。

当前 iOS actual 使用 `UNUserNotificationCenter`，但仍是基础实现：Task 通知按 `reminderMinutes` 从“现在”计时，Habit/Daily Summary 为简化的周期 trigger。它尚未实现时区、日历日期解析、精确的 `dueAt - reminderMinutes` 计算和通知 category/action。迁移时保留现有原生高级调度逻辑，直到这些行为被明确迁入 shared/platform actual 并完成设备测试。

## 5. 旧数据迁移策略

不要让新 KMP 数据库自动读取或覆盖 v5 文件。推荐流程：

1. 在旧 iOS 层导出只读快照，并保留原库备份。
2. 用 `SnapshotV2Migrator` 将旧 v2 task/habit/category 文档转换为 `SnapshotV3Root`。
3. 审查 `result.issues`，特别是 legacy habit carrier 和 tombstone 的歧义告警。
4. 由宿主的显式导入器把经审查的 v3 数据写入 KMP repositories，并保留导入审计记录。
5. 只在抽样校验 Task、Habit、记录、分类与删除墓碑一致后，才允许用户切换默认数据源。

`SnapshotV2Migrator` 是无副作用转换器；“v3 snapshot 写入 SQLite”的应用器属于宿主同步/导入层，当前不是自动迁移 API。

## 6. 验收清单

### Shared framework

- [ ] `:shared:linkDebugFrameworkIosSimulatorArm64` 成功。
- [ ] `Shared.xcframework` 已嵌入 Xcode target，且 `-lsqlite3` 已链接。
- [ ] 冷启动、前后台切换后数据库只创建一次，且可读取预期数据。
- [ ] Task/Habit 的写入会同步更新 UI、SQLite 和 changelog。
- [ ] 授权后，用真机验证通知创建、更新、取消以及时区/夏令时行为。

### SwiftUI 行为

- [ ] Task 列表：创建、编辑、完成、归档、删除。
- [ ] Habit 列表：创建、提醒开关、打卡、归档、删除。
- [ ] 旧库与新库并存期间，未授权页面不会写入错误数据源。
- [ ] 关键用户流程有 XCUITest 覆盖，且同一输入与 Android/Harmony 行为对齐。

## 当前工具链限制

`linkDebugFrameworkIosSimulatorArm64` 已通过，因此 shared framework 可以接入 Xcode。当前 CPF Kotlin/Native 2.2.21-0.3.0 与本机 Xcode 26.5 不能链接 `iosSimulatorArm64Test` 的测试可执行文件，报错为 `_Kotlin_KonanStartStub` 未定义。

因此：

- shared common/service 测试目前由 `macosArm64Test` 验证；
- iOS 侧应优先做 framework 集成与 XCUITest；
- 不要把该 Kotlin/Native 测试链接问题解释为 SwiftUI App 或 framework 本身无法链接。

## 相关文档

- [API 参考](API_REFERENCE.md)
- [系统架构与阶段规划](ARCHITECTURE.md)
- [共享 Service 与 ViewModel 验收](../specs/service-and-viewmodel-validation/spec.md)
- [数据库与同步迁移规格](../specs/kmp-persistence-sync/)
