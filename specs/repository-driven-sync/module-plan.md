# Repository 驱动的 KMP 同步 Module Plan

## 模块拆分

- `mutation contract`：轻量 `RepositoryMutationNotifier`，由 Repository 在事务成功后调用；默认 no-op 便于独立测试与兼容构造。
- `sync orchestration`：数据库级 `RepositorySyncCoordinator`，负责去抖、单飞、pending rerun、启停与关闭，不依赖具体业务表。
- `sync result`：V3 merge 汇总实际应用的资源种类，供平台刷新对应内存投影。
- `repository integration`：Category、Task、Habit、Capture、Memory、Profile 注入同一 notifier；种子与 remote apply 绕过通知。
- `native adapters`：平台启动/设置只配置同步动作；CRUD adapter 只发布本地 UI change，不再调度云同步。

## 平台映射

- 公共 KMP：`shared/src/commonMain/kotlin/com/deadliner/{db/repository,sync}`。
- iOS：`Core/Application/Services/SyncCoordinator.swift` 安装 KMP 自动同步动作并保留 `syncNow()`；各 KMP store 移除 CRUD 后 `scheduleSync()`；同步结果刷新 Capture。
- HarmonyOS：C API 安装 WebDAV 自动同步配置；ArkTS Repository adapter 移除 CRUD 后主动同步；下拉入口继续调用同步 C API。
- Android：`NewEraDatabaseProvider` 后续可安装 WebDAV 配置；本轮保证公共 KMP 能编译，旧 Android Repository 不强制迁移。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准
- 协调器、mutation contract 与同步结果分别落小文件；不把调度状态塞入 `DeadlinerDatabase.kt` 或各 Repository 重复实现。

## 风险点

- 自动同步动作回调若再次经 Repository 写入，必须使用 remote apply 的静默路径避免回环。
- 原生配置变化需覆盖旧动作；关闭云同步需清除动作并取消 debounce。
- 同步完成后的资源刷新必须包含 Capture/Memory/Profile，不能只发布 Task/Habit/Category。
- 同步期间关闭数据库时，协程不能再访问已关闭 driver。
