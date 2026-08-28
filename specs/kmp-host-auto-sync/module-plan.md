# KMP Host Build Auto Sync Module Plan

## 模块拆分

- `core-publisher`：既有 `runscript/buildAndDeploy.sh` 继续负责平台二进制构建、原子替换与字节校验。
- `android-hook`：Gradle `Sync` 任务在 `preBuild` 前调用 `core-publisher`，并将 AAR 声明为任务输出。
- `ios-hook`：Xcode Shell Script Build Phase 在 Sources/Frameworks 前调用 `core-publisher`。
- `harmony-hook`：Hvigor 自定义任务作为 `PreBuild` 的前置依赖调用 `core-publisher`。

## 平台映射

- iOS：`Deadliner.xcodeproj/project.pbxproj` 的 `Deadliner` application target；同步到 `Vendor/KMP/shared.xcframework`。
- HarmonyOS：`entry/hvigorfile.ts`；同步到 `entry/libs/arm64-v8a/libDeadlinerCore.so`。
- Android：`app/build.gradle.kts`；同步到 `.deadliner-core/kmp/deadliner-core.aar`。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准

## 风险点

- IDE 构建图在 KMP 文件缺失时可能早于同步任务解析依赖，因此版本库保留当前已部署产物作为首次构建引导。
- Xcode 项目文件需只修改主应用 target，避免 widget/watch 并行重复触发同步。
- Hvigor 插件必须使用本机 SDK 所声明的 `HvigorPlugin` 与 `HvigorTask` API。
