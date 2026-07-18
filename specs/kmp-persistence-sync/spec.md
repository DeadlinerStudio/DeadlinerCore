# Deadliner NEW ERA KMP 持久化与同步层

## 背景

- 创建日期：2026-07-18
- 功能标识：`kmp-persistence-sync`
- 需求来源：Deadliner NEW ERA 技术预览版 SDD。

Deadliner v5.0 的 DDL 与 HABIT 曾共用并逐步拆分数据结构，关联残留会导致同步时的数据一致性问题。本技术预览版建立一个与现有用户数据库隔离的 Kotlin Multiplatform 持久化与同步核心，供 Android、iOS 和 HarmonyOS 原生运行时接入。

## 目标

- 在 `shared` 中以 CPF-KMP-CMP 的 Kotlin `2.2.21-0.3.0` 与 SQLDelight `2.2.1-0.3.0` 定义新 Schema、类型安全查询与数据库封装。
- 让 Task 与 Habit 成为完全独立的聚合；两者没有外键、link 表或镜像字段。
- 将本地业务写入和 changelog 记录放入同一数据库事务，供同步层作为唯一数据入口读取。
- 提供基于 LWW 的可扩展同步核心和传输协议，不将 HTTP 或业务表操作耦合到同步引擎。
- 提供 Android、iOS 与 HarmonyOS 的驱动工厂边界及最小集成示例；HarmonyOS 使用 CPF 的 `ohosArm64` 原生 target 与 SQLDelight `NativeSqliteDriver`。
- 以 `shared/src/commonMain/kotlin/com/deadliner/domain` 为数据库的唯一领域契约；每个持久化字段必须可逆映射到该目录中的模型。Android/iOS 对齐结论与最终 DDL 见 `android-ios-reconciliation.md`。
- 提供 macOS Arm64 的真实 SQLite 集成测试 target，作为开发机上的快速数据库验证路径。
- 定义类型化的 `SnapshotV3Root` 作为新的全量同步快照：Task、Habit、Category 及后续领域聚合在同一文件中携带各自的实体版本与墓碑。
- 提供纯 commonMain 的 v2→v3 迁移器，可分别导入 iOS 现存的任务 `snapshot-v2.json`、习惯 `habit-snapshot-v2.json`、分类 `category-snapshot-v2.json`，并对不能无损解释的旧字段产生可检查的迁移告警。
- 预览数据库固定命名为 `deadliner_new_era.db`，不触碰 v5.0 数据库。

## 非目标

- 不实现 UI、现有 v5.0 数据迁移或数据库升级脚本。
- 不实现自建后端、HTTP/WebDAV 调用、认证或重试调度。
- 不在本轮把快照直接写入业务数据库；v3 的编码、解码、校验与 v2 投影保持为独立同步契约，数据库导入编排由后续 `RemoteChangeApplier` 接入。
- 不实现 CRDT、字段级合并等高级冲突策略；本轮仅提供确定性的 LWW。
- 不实现 HarmonyOS RDB 到 SQLDelight `SqlDriver` 的适配器；本预览版使用 CPF SQLDelight fork 已支持的原生 SQLite 驱动。

## 用户场景

1. Android 的预览构建创建 `AndroidDatabaseDriverFactory` 与共享数据库后，DDL 和 Habit 仓储在一次事务中写入记录及其待同步 changelog。
2. 同步调用方把待同步 changelog 交给 `SyncTransport`，收到服务端确认后标记对应日志为已同步，并把远端记录交给 `RemoteChangeApplier`。
3. 两个设备修改同一记录时，`LastWriteWinsConflictResolver` 按客户端时间、设备 ID、变更 ID 的顺序稳定决定胜者。
4. 用户的 WebDAV 中仍保存三份 iOS v2 快照时，KMP 调用方将它们解码并传给迁移器，得到一份可编码为 v3 的统一快照；旧式 DDL 任务、子任务、独立习惯及分类的 UID、版本和删除语义不丢失。

## 验收标准

- `./gradlew :shared:compileKotlinMetadata` 能生成 SQLDelight API 并编译 commonMain（执行环境具备 Gradle 后）。
- Schema 含 `task`、`task_subtask`、`habit`、`habit_record`、`category`、`changelog` 及 SDD 所列索引；Task 与 Habit 表没有彼此的外键、link 或残留关联字段。每个同步实体保留 UID、更新时间和墓碑，删除不通过业务仓储物理级联。
- Task、Habit、Category 仓储的本地写操作均与 `ChangeLogRepository.appendLocal` 在同一 SQL 事务内执行。
- `SyncEngine` 只依赖 changelog 仓储、冲突解析器、远端应用端口与传输协议，不导入任何业务查询接口。
- Android、iOS 和 HarmonyOS 创建的数据库文件名均为 `deadliner_new_era.db`；HarmonyOS 产出 `ohosArm64` native shared library，不加载 KMP JVM/JAR 产物。
- `androidApp` 与 `iosApp` 包含不改变 UI 的调用示例和 iOS `-lsqlite3` 链接说明。
- `ohosArm64Main` 的 factory 接收调用方提供的绝对持久化目录，并通过 CPF SQLiter `extendedConfig.basePath` 与无路径文件名创建 `NativeSqliteDriver`。创建失败必须抛出错误，绝不能降级为内存数据库。
- `:shared:macosArm64Test` 在 macOS 上通过 `NativeSqliteDriver` 执行 Category、Task/Subtask、Habit/Record 的写入、读取和 changelog 集成验证；删除路径验证墓碑而非业务硬删除。
- `SnapshotV3Codec` 可对 v3 JSON 编码/解码并拒绝重复 UID、墓碑携带文档、UID 不匹配等不合法快照；v2 迁移测试覆盖任务、子任务、习惯记录、分类、墓碑和不支持的旧字段告警。

## 风险与约束

- 这是新模块和新数据库格式；仓库初始化时没有 Git 历史，无法按团队守门规则创建“破坏性变更前”基线提交。
- Android 的 `Context` 与 iOS 的文件系统是平台能力，不能泄漏到 commonMain。
- SQLDelight 的 `.sq` 根目录必须与 `com.deadliner.db` 完全一致；生成代码不可手写。
- CPF-KMP-CMP 是 Beta 工具链；Kotlin、SQLDelight、serialization、datetime 与 coroutines 必须采用相互兼容的 OH 变体和其 Maven 仓库，不能混用官方版本。
- HarmonyOS native library 需要通过 NAPI/C++ 装载；本模块只负责 `ohosArm64` 产物与数据库 API，ArkTS UI 不在本轮范围。
- SQLDelight 的初始 Schema 将替换旧 DDL/link 预览结构，因预览数据库尚未发布，不提供虚假的兼容迁移；新数据库文件仍保持隔离命名。
- 预览版包体不引入 Rust/C++ FFI；最终二进制增量需在各平台集成工程中测量。
- 本次环境默认 JDK 11；构建必须显式切换到 JDK 17+。2026-07-18 的 JDK 21 生成尝试受 Maven TLS handshake 中断，尚未得到完整编译证据。
- iOS v2 任务快照和独立习惯/分类快照缺少部分 v3 字段。迁移必须采用确定性默认值，绝不能将旧 `note` 中无法解析的习惯元数据静默伪装为精确配置。
