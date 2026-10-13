# 快速添加端侧推理 Module Plan

## 模块拆分
1. commonMain/domain/quickadd/inference：张量 DTO、公共串行引擎、内部 expect/actual 后端。
2. androidMain：ONNX 会话与张量资源生命周期。
3. iosMain：Core ML 加载、MLMultiArray 和命名 feature 转换。
4. ohosArm64Main + nativeInterop：MindSpore Lite cinterop、上下文、固定形状张量与推理。
5. commonTest：契约、状态与失败恢复；文档：模型契约、许可、真实验收与保留 TODO。

## 平台映射
所有适配位于 Shared 模块内；Android 客户端通过文件 AAR 使用共享库，需要显式携带 ONNX Runtime 依赖；iOS 使用系统 CoreML；鸿蒙链接系统 mindspore_lite_ndk；ArkTS 负责下载状态和实时提案。

## 文件拆分策略
契约、引擎、平台后端独立文件，不改已有大桥接入口。本轮新增文件均应低于 1000 行。

## 风险点
合成训练的语义覆盖有限；三端模型转换数值差异、Core ML INT64 无损转换、原生指针所有权、raw AAR 不传递依赖、鸿蒙 SDK 路径与系统能力。

## 实际实现
公共门面使用内部 expect/actual 后端工厂，统一串行调度；平台后端只执行张量。Android 消费仓库已补齐 raw AAR 的 ONNX 依赖。HarmonyOS cinterop 直接链接系统 Native API，ArkTS/NAPI 独立桥负责会话接线。iOS 新接口已通过 Swift typecheck。新增代码均不超过 110 行；扫描仅报告既有 1257 行持久化桥，未向其添加代码。

## 下载与压包模块
commonMain/domain/quickadd/model：清单、流式下载、SHA-256、缓存、规则降级编排，文件分别拆分。androidMain 使用 java.io 文件读写；iosMain、macOS 与 ohosArm64Main 共用 posix 文件实现；iOS 独立模型编译入口。Android 后端工厂与 ONNX 类隔离，允许运行时缺失；compileOnly 依赖与应用打包开关独立。

## 自动识别接线
共享模型清单、字符 BIO codec、会话门面分别拆文件；Android 使用应用生命周期模型服务和 QuickAddState 独立本地识别任务；iOS 使用共享 Swift 模型服务和 task revision；鸿蒙新增独立 KMP C API + NAPI async worker，与 AI worker 分离，不在主线程等待下载/推理。模型清单由 native assets/Bundle/rawfile 安装；资源缺失使用规则，实际发布清单和权重已交付；版本更新以新 Release 和内容 hash 缓存承接。

## 本轮训练与语义回归
训练/导出与数值检查脚本放 runscript/quickadd，各文件独立；KMP parser 修复时间空格，macOS 验收消费真实三格式张量并检查类型、标题、提醒时间，独立于共享 host 假后端。三端提案 UI 只增加识别来源标签，沿用已有样式与最新输入保护。
