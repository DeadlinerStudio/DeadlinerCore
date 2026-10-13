# 专注操作闭环 Module Plan

## 模块拆分
1. KMP service/viewmodel：稳定会话确认、原子目标操作、结果补完成、展示资格。
2. 平台桥接：iOS回调命令、OHOS JSON命令，复用已有ABI。
3. UI：原生确认、结果动作/警告；鸿蒙单独滑动动作组件。
4. 验证：SQLite回归、源码/宿主合同、构建和设备证据。

## 平台映射
- Core：service/focus、viewmodel/focus、iosMain/ohosArm64Main bridge。
- Android：ui/focus、Task列表/详情冲突处理、独立资源；不修改 ui/overview。
- iOS：Features/Focus、Home/Persistence，复用原生确认与LiquidGlass。
- 鸿蒙：pages/routes/focus、routes/main、common/kmp，UIContext原生确认与独立Component。

## 文件拆分策略
通用确认单独文件，平台警告/滑动动作按职责拆分；不扩张已有超限基线文件。

## 风险点
确认期间自然到点/目标变更/新会话竞态；重复回调；零秒结束；桥接发布与事件刷新；Android另一对话并发更改。
