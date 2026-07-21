# Service Orchestration and ViewModel Validation Tasks

## 阶段 1：规格冻结

- [x] 定义 Phase 3/5 边界、非目标与宿主 UI 验收责任。
- [x] 冻结 service、platform contract、ViewModel wiring 和测试模块拆分。

## 阶段 2：基础设施

- [x] 为 `NotificationService` 建立可 fake 的 common gateway。
- [x] 通过构造参数注入 coroutine scope；macOS 测试复用 `runBlocking` context，无需新增测试依赖。

## 阶段 3：功能实现

- [x] 实现 `NotificationOrchestrator` 的 Task/Habit 生命周期与全量刷新。
- [x] 接入所有 Task 写路径的 ViewModel，并在 Habit 列表的创建/删除路径接入。
- [x] 暴露 `DeadlinerDatabase` 的 orchestrator 工厂。

## 阶段 4：验证与回写

- [x] 编写 common service 策略测试与 macOS ViewModel/SQLite 集成测试。
- [x] 执行规格校验、大文件扫描与跨端构建矩阵。
- [x] 回写实际通过项：Android 编译、iOS Simulator 编译/framework 链接、macOS 测试、HarmonyOS CPF 链接均已通过；iOS test executable 继续受 `_Kotlin_KonanStartStub` 工具链错误阻塞。

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
