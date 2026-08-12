# Repository 驱动的 KMP 同步 Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成评审或自检

## 阶段 2：基础设施

- [x] 确认共享依赖、接口、数据结构
- [ ] 实现 mutation notifier 与数据库级去抖协调器
- [ ] 扩展 V3 同步结果的资源种类

## 阶段 3：功能实现

- [ ] 接入全部 KMP Repository 的事务后通知
- [ ] iOS 移除业务写后原生同步并修复 Capture 刷新
- [ ] HarmonyOS 移除业务写后原生同步并安装 KMP 自动同步配置
- [ ] 大型改动完成后再统一编译

## 阶段 4：验证与回写

- [ ] 补充去抖、pending rerun 与 Capture 跨设备测试
- [ ] 执行规格校验
- [ ] 执行大文件扫描
- [ ] 执行 KMP macOS/iOS/HarmonyOS 编译验证
- [ ] 回写最终实现状态
