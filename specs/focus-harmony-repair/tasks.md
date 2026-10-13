# 鸿蒙专注修复 Tasks

## 阶段 1：规格冻结

- [x] 阅读指定deadliner-spec-dev、ArkTS/ArkUI技能及参考、AGENTS。
- [x] 检查源代码、官方SegmentButtonV2/HdsTabs合同与项目材质/主题实现。
- [x] 冻结只改鸿蒙、恢复原生关闭、独立动态组件、水平单行miniBar方案。

## 阶段 2：基础设施

- [x] 拆分动态组件，建立可执行刷新/miniBar状态合同（34项通过，其中17项执行状态/实际宿主方法）。

## 阶段 3：功能实现

- [x] 原生控件、关闭、圆角、向上对齐、材质作用域与颜色。
- [x] miniBar状态保持、图标单行布局与主动点击策略；共享StorageLink避免插槽懒更新。

## 阶段 4：验证与回写

- [x] 宿主18项/新增34项/入口13项合同、规模/差异/规格校验；未改动的全仓库基线仍有SearchPage和DatabaseHelper两项超限。
- [x] 完整批次构建与设备能力检查；签名HAP通过，回写 [acceptance.md](acceptance.md)。首次Node25工具错误后使用DevEco内置Node24复编。
- [ ] 真机重组、关闭、miniBar手势、深浅模式及沉浸光感验收：`hdc list targets`返回`[Empty]`。
