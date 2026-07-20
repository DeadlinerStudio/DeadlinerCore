# Service Orchestration and ViewModel Validation Tasks

## 阶段 1：规格冻结

- [x] 定义 Phase 3/5 边界、非目标与宿主 UI 验收责任。
- [x] 冻结 service、platform contract、ViewModel wiring 和测试模块拆分。

## 阶段 2：基础设施

- [ ] 为 `NotificationService` 建立可 fake 的 common gateway。
- [ ] 配置 coroutine test 依赖和测试 scope 注入方式。

## 阶段 3：功能实现

- [ ] 实现 `NotificationOrchestrator` 的 Task/Habit 生命周期与全量刷新。
- [ ] 接入所有 Task 写路径的 ViewModel。
- [ ] 暴露 `DeadlinerDatabase` 的 orchestrator 工厂。

## 阶段 4：验证与回写

- [ ] 编写 common service 策略测试与 macOS ViewModel/SQLite 集成测试。
- [ ] 执行规格校验、大文件扫描与跨端构建矩阵。
- [ ] 回写实际通过项和 iOS test-executable 工具链阻塞。

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
