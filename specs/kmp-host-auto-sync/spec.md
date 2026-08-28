# KMP Host Build Auto Sync

## 背景

- 创建日期：2026-08-28
- 功能标识：`kmp-host-auto-sync`
- 需求来源：宿主工程可能在未重新部署 Core 二进制时构建，导致 native 代码与 KMP AAR、XCFramework 或 SO 不一致。

## 目标

- Android、iOS 和 HarmonyOS 的宿主构建在消费 KMP 产物之前自动调用 Core 发布脚本。
- 默认从本机相对位置定位 Core，也允许通过 `DEADLINER_CORE_DIR` 明确指定，供 CI 与不同工作区布局使用。
- 复用 Core 的原子部署与字节校验；失败时终止宿主构建并给出可操作错误。
- 每次同步均构建/部署相应平台的 release KMP 产物，避免宿主打入陈旧 Core。

## 非目标

- 不引入远程 Maven、CocoaPods、OHPM 或制品仓库下载。
- 不修改 KMP 的 API、宿主运行时同步逻辑或发布签名配置。
- 不自动 `git pull`、切换分支或修改 Core 以外的源码版本。

## 用户场景

1. 开发者在 Android Studio、Xcode 或 DevEco Studio 直接发起构建；构建先生成并部署对应 KMP 产物，再编译宿主。
2. CI 将 `DEADLINER_CORE_DIR` 指向已检出的 Core 仓库；宿主构建使用该目录而非依赖开发机固定路径。

## 验收标准

- Android `preBuild` 依赖 KMP 自动同步任务，且编译产物引用同步后的 AAR。
- iOS `Deadliner` target 的第一个构建阶段同步 XCFramework，且链接阶段随后使用该目录。
- Harmony `PreBuild` 依赖 Hvigor 同步任务，且 HAP 打包使用同步后的 SO。
- 缺少 Core 路径或发布失败时三端构建均以明确错误失败。
- Android、iOS、HarmonyOS 的一次构建验证均通过。

## 风险与约束

- 自动同步会增加首次构建时间；后续由 Gradle 增量任务复用未变更的 KMP 输出。
- Core 与宿主同时在多个 IDE 中构建时，Gradle 负责 Core 构建序列化，但开发者应避免同时写入同一部署目标。
- iOS 构建脚本只在 macOS 可用；Harmony 的 Core 链接要求调用方提供 `DEVECO_HOME`。
