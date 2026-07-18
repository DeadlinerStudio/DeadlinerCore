# Deadliner NEW ERA KMP 持久化与同步层 Module Plan

## 模块拆分

- `domain`：`shared/src/commonMain/kotlin/com/deadliner/domain` 保存 Task、Habit、Category、Memory、AI proposal、版本和 change set。这里不依赖 SQLDelight。
- `schema`：`shared/src/commonMain/sqldelight/com/deadliner/db` 保存 Category、Task/Subtask、Habit/Record/Schedule、Capture、Memory/Profile 与 changelog 的全量表定义和查询；所有同步实体都有 UID、更新时间和墓碑；SQLDelight 生成的 API 是唯一 SQL 访问面。
- `persistence`：`shared/src/commonMain/kotlin/com/deadliner/db` 提供驱动、数据库生命周期、changelog 以及 Category、Task、Habit 仓储。仓储负责把业务 SQL 与日志 SQL 放进同一事务。
- `sync-contract`：`shared/src/commonMain/kotlin/com/deadliner/sync/protocol` 定义传输、远端应用端口和协议 DTO，不包含网络库。
- `sync-domain`：`shared/src/commonMain/kotlin/com/deadliner/sync` 定义变更模型、LWW 冲突解析器和同步编排，仅依赖 changelog 接口与同步端口。
- `snapshot-contract`：`shared/src/commonMain/kotlin/com/deadliner/sync/snapshot` 定义 `SnapshotV3Root`、聚合快照项、版本映射、JSON codec 和结构校验；它只依赖 `domain` 与 serialization，不触碰 SQLDelight 或网络。
- `snapshot-migration`：同目录的 `v2` DTO 与迁移器负责读取三类 iOS v2 文件，映射为 v3，并以 issue 列表显式报告字段降级或不能解释的数据。
- `platform-bridge`：各平台 source set 只负责建立 `SqlDriver`、暴露设备标识；不得在平台层书写业务 SQL。HarmonyOS 使用 CPF `ohosArm64Main` 和 `NativeSqliteDriver`，调用方提供绝对持久化目录。

## 平台映射

- iOS：`shared/src/iosMain/kotlin/com/deadliner/db` 提供 `NativeSqliteDriver`；`iosApp` 的 README 说明将共享 framework 链接入现有 `Core/Data` 层，以及 `-lsqlite3` 设置。
- HarmonyOS：`shared/src/ohosArm64Main/kotlin/com/deadliner/db` 提供 `NativeSqliteDriver` factory；`harmonyApp` 只保存 native library 装载集成说明，ArkTS UI 不修改。
- Android：`shared/src/androidMain/kotlin/com/deadliner/db` 提供 `AndroidSqliteDriver`；`androidApp` 的 sample 在现有 Android `data` 层实例化工厂，UI 不改。
- macOS：`shared/src/macosArm64Main` 提供临时文件 SQLite factory，`shared/src/macosArm64Test` 运行真实 driver 集成测试。
- 快照：KMP commonMain 为 Android、iOS、HarmonyOS 提供完全相同的 JSON 契约；iOS 现有 `Core/Domain/Models/*SnapshotV2.swift` 仅作为迁移输入，不修改其线上 v2 文件格式。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准
- Schema 按实体分为 `AppDatabase.sq`、`Task.sq`、`TaskSubtask.sq`、`Habit.sq`、`HabitRecord.sq`、`HabitSchedule.sq`、`Category.sq`、`Capture.sq`、`Memory.sq`、`ChangeLog.sq`，避免一个 SQL 文件同时承载查询和同步规则。
- 同步职责按 `ChangeLog.kt`、`ConflictResolver.kt`、`SyncEngine.kt`、`protocol/SyncProtocol.kt` 分离。远端表写入以接口注入，避免 `SyncEngine.kt` 膨胀或接触业务表。
- 快照按 `SnapshotV3.kt`（契约）、`SnapshotV3Codec.kt`（编码与校验）、`v2/LegacySnapshotV2.kt`（旧 DTO）、`v2/SnapshotV2Migrator.kt`（迁移规则）拆分；不把 JSON 解析、映射和同步传输塞入一个文件。

## 风险点

- SQLDelight 生成 API 的方法/字段名受 `.sq` 标签约束，修改 SQL 后必须编译生成代码验证。
- KMP 的 Android 工厂需要 `Context`；共享 `DatabaseDriverFactory` 仅声明共同创建能力，平台构造参数在 actual 声明中定义。
- CPF SQLDelight fork 的 `NativeSqliteDriver` 是本轮 HarmonyOS 依赖；其 `name` 不接受路径，持久化目录必须通过 SQLiter `extendedConfig.basePath` 传入。升级前必须在 API 17+ 真机或模拟器验证 CRUD、事务、外键级联。
- 预览 Schema 无旧版本可迁移；迁移目录仅记录后续迁移约定，不能伪造不可验证的 `.sqm`。
- v2 的任务快照与习惯快照可能同时含同 UID 的“旧习惯承载 DDL”和“独立 Habit”；迁移以独立习惯快照为权威，并报告被跳过的旧承载项，避免重复创建习惯。
