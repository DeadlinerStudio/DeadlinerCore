# V3 全量快照与增量日志同步 Tasks

## 阶段 1：规格冻结

- [x] 明确 payload 兼容与顶层不变约束。
- [x] 拆分 snapshot、merge、DB 查询职责。

## 阶段 2：基础设施

- [x] 增加全量含 tombstone 的查询与 V3 快照存储。
- [x] 为快照生成稳定的 LWW changelog entries。

## 阶段 3：功能实现

- [x] 在变更合并前导入远端快照，并让写入 payload 带上本地全量快照。
- [x] 补齐 capture、memory、profile 的远端应用。

## 阶段 4：验证与回写

- [x] 增加“快照恢复缺失任务”和旧 payload 兼容测试。
- [x] 执行规格、文件规模和 Kotlin 测试校验，并回写结果。

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
