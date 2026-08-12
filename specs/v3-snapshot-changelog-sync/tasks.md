# V3 全量快照与增量日志同步 Tasks

## 阶段 1：全量快照规格冻结

- [x] 明确 snapshot-only 与顶层不变约束。
- [x] 明确旧 hybrid payload 忽略 `changes` 的安全迁移策略。

## 阶段 2：基础设施

- [x] 增加 V3 独立版本/tombstone 读取能力。
- [x] 将 V3 snapshot store 改为直接版本合并。

## 阶段 3：功能实现

- [x] 停止生成、读取、确认 V3 增量 changelog。
- [x] 直接应用完整 category/task/habit/capture/memory/profile 快照。

## 阶段 4：验证与回写

- [x] 增加“预设分类不被 hybrid log 覆盖”和 tombstone 保留测试。
- [x] 执行规格、文件规模和 Kotlin 测试校验，并回写结果。

验证结果：`validate_feature_spec.py`、`check_large_core_files.py`、`git diff --check` 和
`./gradlew :shared:macosArm64Test` 均通过。构建仅报告项目既有的 kotlinx.datetime 弃用与 expect/actual Beta 警告。

## 阶段 5：跨端迁移数据修复

- [x] 复核下载快照：9 条 live capture 中 8 条为无时区历史时间；`preset.study`、`preset.family` 为同步 tombstone。
- [x] iOS 以设备本地时区解析无时区历史 capture。
- [x] HarmonyOS 允许删除预设；后续生命周期在阶段 7 收敛为首次写入数据库的普通分类。
- [x] 完成跨端编译/测试与规格回写。

验证结果：`validate_feature_spec.py`、`check_large_core_files.py`、Core `git diff --check`
与 `./gradlew :shared:compileKotlinOhosArm64` 均通过。iOS `Deadliner` 构建被既有
`DeadlinerWatch Watch App` 的 `DeadlinerDefault.icon` 资源缺失阻断，未出现本次 Swift
改动的编译诊断。

## 阶段 6：分类视觉契约统一

- [x] 冻结 `iconKey` 语义 ID 与 `colorHex` 大写十六进制契约；确认分类仍按整条实体 LWW 合并。
- [x] KMP 分类 mutation 兼容旧 SF Symbol 并只写回语义 ID/规范颜色。
- [x] iOS 将语义图标映射为 SF Symbol，编辑器只提交语义 ID。
- [x] 执行 Core/Harmony 编译、规格及文件规模检查，并回写结果。

验证结果：`CategoryMutationTest`、`compileKotlinOhosArm64`、规格校验、Core 文件规模
扫描及两个仓库的 `git diff --check` 均通过。iOS `Deadliner` scheme 仍被既有 Watch App
的 `DeadlinerDefault.icon` 资源缺失阻断，未出现本次 Swift 改动的编译诊断。

## 阶段 7：预设分类生命周期收敛

- [x] 冻结规则：仅首次空数据库写入；后续预设为普通分类；恢复操作只补回缺失/tombstone 项。
- [x] KMP 数据库初始化种子化及单事务恢复接口。
- [x] 移除 Harmony catalog 的动态预设投影，导出恢复预设原子接口。
- [x] 接入 iOS/Harmony 设置入口并验证跨端编译。

验证记录（2026-08-09）：

- [x] `:shared:compileKotlinOhosArm64`、`:shared:linkReleaseSharedOhosArm64` 与 `DatabaseIntegrationTest` 分类场景通过。
- [x] 已将 release Core 更新到 HarmonyOS `arm64-v8a` 包并执行 `assembleHap`，构建成功。
- [ ] iOS `xcodebuild -scheme Deadliner build` 在本次 Swift 编译前被现有 `DeadlinerDefault.icon` 资源的 `actool` 异常阻断；未报告本次分类改动的 Swift 诊断。

## 阶段 8：习惯打卡聚合同步修复

- [x] habit 快照版本纳入 records/schedules（包括本地 tombstone）的最新更新时间。
- [x] 较新 habit 快照以完整 records/schedules 集合替换本地子项，支持取消打卡同步。
- [x] 增加双数据库新增/取消打卡回归测试并执行 macOS KMP 测试。

验证记录（2026-08-12）：

- [x] `DatabaseIntegrationTest` 的鸿蒙源库 → iOS 目标库新增/取消打卡场景通过。
- [x] `:shared:macosArm64Test` 与 `:shared:compileKotlinOhosArm64` 通过，仅有项目既有的弃用与 expect/actual Beta 警告。

## 阶段 1：规格冻结

- [ ] 完成 `spec.md`
- [ ] 完成 `module-plan.md`
- [ ] 完成评审或自检

## 阶段 2：基础设施

- [ ] 确认共享依赖、接口、数据结构

## 阶段 3：功能实现

- [ ] 按模块分批实现
- [ ] 大型改动完成后再统一编译

## 阶段 4：验证与回写

- [ ] 执行规格校验
- [ ] 执行大文件扫描
- [ ] 回写最终实现状态
