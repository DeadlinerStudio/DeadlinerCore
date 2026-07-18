# Deadliner NEW ERA KMP 持久化与同步层 Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成需求与现有空仓库的自检
- [x] 冻结统一 v3 快照和三类 iOS v2 输入的兼容范围

## 阶段 2：基础设施

- [x] 确认共享依赖与运行时边界
- [x] 冻结 Task 与 Habit 的完全解耦领域模型
- [x] 以 Android Model 为语义基线，与 iOS 的分类/解耦模型完成对齐，并写出最终 DDL（`android-ios-reconciliation.md`）
- [x] 建立 Gradle KMP/SQLDelight 项目骨架和预览数据库命名

## 阶段 3：功能实现

- [x] 将 Schema、领域对象和仓储收敛到 Android/iOS 对齐后的最终 DDL（统一墓碑）。
- [x] 实现 Category、Task/Subtask、Habit/Record/Schedule 仓储与 changelog 事务。
- [x] 添加 macOS Arm64 `NativeSqliteDriver` 集成测试并在本机执行。
- [x] 将 Harmony JVM/RDB 预案迁移为 CPF `ohosArm64` 原生 target 与 `NativeSqliteDriver`。
- [ ] 在 HarmonyOS API 17+ 运行数据库 CRUD、事务、外键验证。
- [ ] 大型改动完成后再统一编译（已尝试；见“验证记录”中的外部 Maven TLS 阻塞）
- [x] 实现 v3 快照契约、codec/校验器和 v2→v3 迁移器（见 `snapshot-v3.md`）。
- [x] 为 v3 JSON round-trip 与三类 v2 迁移增加 commonTest 覆盖。

## 阶段 4：验证与回写

- [x] 执行规格校验
- [x] 执行大文件扫描
- [x] 回写最终实现状态

## 验证记录

- 2026-07-18：`validate_feature_spec.py specs/kmp-persistence-sync` 通过。
- 2026-07-18：运行团队大文件脚本；该脚本只覆盖 ArkTS（本模块没有 `.ets` 文件），结果通过。另对全部 `shared` Kotlin/SQL 文件计数，最大文件为 99 行，低于 1000 行阈值。
- 2026-07-18：以 Gradle 8.10.2 和本机 Zulu JDK 21 执行 `:shared:generateCommonMainAppDatabaseInterface`。首次确认默认 JDK 11 不满足 SQLDelight 2.2.1 的 JDK 17+ 要求；切换 JDK 21 后，外部 Maven 的 `core-2.2.1.jar`、`sql-psi core-0.7.2.jar` 与 `netty-handler` 下载被远端 TLS handshake 终止。生成/编译没有完成，不能将其标记为已验证。
- 2026-07-18：领域模型基础已新增。当前仓库没有 `gradlew`，执行 `./gradlew` 失败；系统也未安装 `gradle` 命令，尚不能运行 common/iOS/Android 编译和测试。
- 2026-07-18：依据 CPF `kmp-thirdlibdemo/sqldelight-demo` 完成 `ohosArm64`、SQLDelight `NativeSqliteDriver`、CPF Maven 仓库和兼容依赖版本迁移。使用 Gradle 8.10.2 + Zulu JDK 21 启动 `:shared:generateCommonMainAppDatabaseInterface :shared:compileKotlinMetadata`；CPF 远程依赖解析持续无输出，已停止该挂起进程。规格校验通过；完整 native 构建和 API 17+ 设备验证仍待可用的 CPF Gradle 网络环境。
- 2026-07-18：根据 `domain` 完成初始数据库迁移，删除旧 DDL/link 表和仓储，增加 Category、Task/Subtask、Habit/Record/Schedule、Capture、Memory/Profile 表；Category、Task、Habit 仓储的写入与 changelog 同事务。新增 `macosArm64Test` 的真实 SQLite 集成测试和 `bash runscript/testMacDatabase.sh` 命令。使用 Zulu JDK 21 启动该测试，但 CPF Maven 依赖解析无输出而挂起，已停止；尚无通过的运行时测试证据。
- 2026-07-18：以 `/Users/aritxonly/Codes/Android/Deadliner` 的 `DDLItem`、`Habit`、`HabitRecord`、`TaskStateMachine` 和 SQLite 表为语义基线，与 iOS 提取模型完成对齐；最终取舍记录于 `android-ios-reconciliation.md`。领域对象、SQL 表和 Category/Task/Habit 仓储已统一为 UID + 时间戳 + 墓碑模型，任务状态机恢复 Android 的幂等操作语义。`validate_feature_spec.py` 通过，最终 `AppDatabase.sq` 可由本机 SQLite 解析；核心大文件检查通过。
- 2026-07-18：使用本机 Zulu JDK 21 在受许可环境运行 `:shared:compileKotlinMetadata`，Gradle 在打印单次 daemon 启动信息后未生成 `shared/build` 中的 SQLDelight 文件，也未报告可诊断的编译结果。完整 KMP/SQLDelight 编译与 macOS 原生集成测试继续标记为未验证，等待 CPF Maven 依赖解析环境恢复。
- 2026-07-18：配置 Tencent Gradle distribution mirror、Aliyun 公共 Maven/Google/Gradle Plugin mirrors，并将 CPF 仓库限制为 Kotlin、Kotlinx、SQLDelight 和 Touchlab 的 HarmonyOS fork 依赖；启用 AndroidX、10 秒 HTTP 超时与低并发解析。首次运行下载 Kotlin/Native 工具链及 LLVM 平台库后，`JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/gradle-deadliner-home ./gradlew :shared:macosArm64Test --offline --no-daemon --console=plain` 通过。该测试执行真实 NativeSqliteDriver 的 Category、Task/Subtask、Habit/Record、changelog 与墓碑删除验证。
- 2026-07-18：真实 SQLDelight 生成确认 CPF fork 不支持 `ON CONFLICT ... DO UPDATE`；所有持久化写入改为同一事务内的 `INSERT OR IGNORE` 加 `UPDATE`，避免 `REPLACE` 对 Category 外键产生删除副作用。CPF SQLiter 的数据库 `name` 禁止路径，macOS/HarmonyOS factory 改为无路径文件名与 `extendedConfig.basePath` 组合，macOS 集成测试据此通过。
- 2026-07-18：新增统一 `SnapshotV3Root`、严格 JSON codec/校验器和三类 iOS v2 DTO/迁移器。`bash runscript/testMacDatabase.sh` 在 macOS Arm64 通过：共 8 个测试（领域 4、数据库 1、Snapshot v3 3），0 failures；`validate_feature_spec.py specs/kmp-persistence-sync` 通过。当前仓库没有团队的大文件扫描脚本，已对所有 `shared` Kotlin/SQL 文件统计，新增迁移器 424 行，未超过 1000 行阈值。
