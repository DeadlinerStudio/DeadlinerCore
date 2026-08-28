# Unified Mobile Logging Module Plan

## 模块拆分

- `contract`：统一等级、来源、JSONL schema、文本投影和文件名常量；与 iOS `AppLogRecord` 对齐。
- `kmp-bridge`：KMP 关键边界（持久化、同步、AI、ViewModel）生成稳定事件；Android/Harmony 桥接将其归类为 `KMP`。
- `android-infra`：`AppLog` 单例负责初始化、追加、裁剪、导出与 Logcat 镜像；业务调用只依赖该入口。
- `harmony-infra`：ArkTS `AppLog` 单例负责初始化、追加、裁剪、导出与 HiLog 镜像；业务调用只依赖该入口。
- `presentation`：设置页导出两个诊断格式（若页面已有诊断/分享入口，则接入既有入口）。

## 平台映射

- iOS：`Deadliner/Core/Application/Services/SyncDebugLog.swift` 是格式权威，本次不修改。
- HarmonyOS：`entry/src/main/ets/common/logging/` 放封装；`entryability` 初始化；`common/kmp`、repository、AI、同步和页面错误路径调用封装。
- Android：`app/src/main/java/com/aritxonly/deadliner/logging/` 放封装；`MainActivity` 初始化；KMP bridge、迁移、AI、网络、日历、下载与 UI 关键路径调用封装。
- KMP：`shared/src/*Main/kotlin/com/deadliner/platform/` 保留平台日志抽象并删除临时 stdout；KMP 发布产物由两个宿主应用部署。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准

## 风险点

- 275 个 HarmonyOS 直连调用须作机械迁移并逐文件补导入，随后由编译器校验。
- KMP Native 无法自行获得 ArkTS `filesDir`；Harmony 宿主桥接必须将 KMP 操作事件写入统一封装。
- Android 日志调用中可能含敏感网络字段；迁移时必须使用现有脱敏值或仅记录状态、长度和错误类型。
