# V3 全量快照与增量日志同步 Tasks

## 阶段 1：全量快照规格冻结

- [x] 明确 snapshot-only 与顶层不变约束。
- [x] 明确旧 hybrid payload 忽略 `changes` 的安全迁移策略。

## 阶段 2：基础设施

- [ ] 增加 V3 独立版本/tombstone 读取能力。
- [ ] 将 V3 snapshot store 改为直接版本合并。

## 阶段 3：功能实现

- [ ] 停止生成、读取、确认 V3 增量 changelog。
- [ ] 直接应用完整 category/task/habit/capture/memory/profile 快照。

## 阶段 4：验证与回写

- [ ] 增加“预设分类不被 hybrid log 覆盖”和 tombstone 保留测试。
- [ ] 执行规格、文件规模和 Kotlin 测试校验，并回写结果。

验证结果：`validate_feature_spec.py`、`check_large_core_files.py`、`git diff --check` 和
`./gradlew :shared:macosArm64Test` 均通过。构建仅报告项目既有的 kotlinx.datetime 弃用与 expect/actual Beta 警告。

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
