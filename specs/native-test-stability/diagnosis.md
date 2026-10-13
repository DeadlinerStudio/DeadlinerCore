# Native 稳定性诊断证据

## 初始复现

未过滤 `macosArm64Test` 卡住，采样业务线程等待 GC，GC 线程持续 `ThreadRootSet::Iterator::operator++` / `collectRootSetForThread`。卡顿位置会从旧同步迁移变为通知/SQLiter 异常回滚，不能以最后业务函数直接归因。

证据：`/private/tmp/native-stability-baseline-full.log`、`native-stability-baseline-full.sample.txt`、`native-stability-cached-repeat-full.log`、`native-stability-cached-repeat.sample.txt`。

## 已否定的缓存假设

关闭 Native 缓存、保留默认 CMS 后，一次原始完整运行 228 started / 228 finished、零失败/跳过；恢复缓存后一次 Gradle 完整运行也通过，而同进程完整压力运行又卡住。新增主动 GC 用例后，无缓存完整运行仍在 GC 后读取活跃 TaskRepository 时 SIGSEGV（ReadHeapRef，地址 0x8）。因此单轮无缓存通过不证明修复，已撤回缓存设置。

证据：`native-stability-no-cache-raw-full.log`、`native-stability-cached-confirm.log`、`native-stability-fixed-full-1.log`；崩溃 `/Users/aritxonly/Library/Logs/DiagnosticReports/test.kexe-2026-10-13-024518.ips`。

首次无缓存 Gradle 报告发生输出索引重复，后续 Gradle 完整运行正常，不作为 Native 业务用例通过的替代证据。

## GC 对照（已否定为修复方向）

保持 GC 与编译缓存启用，对全部用例及主动 GC 用例对照 CMS / PMCS，均不能修复。最终验收保留默认 CMS 和缓存，不移除压力测试，也不只跑单例。

Kotlin 官方对 CMS 问题建议切换仍执行 GC 的 PMCS：[Native 内存管理](https://kotlinlang.org/docs/native-memory-manager.html)。官方建议是对照依据，不等于本项目根因已经证明。

## 已定位的根因

PMCS 同样失败，默认 CMS、缓存保留、单线程后端、禁用调试 trampoline/内联等对照不能修复。运行时根帧断言在首个无数据库用例 `HabitFocusPolicyTest.targetTypesCannotBeConfusedAndLegacyTaskJsonOmitsNewDefaults` 捕获 `Frame is expected ... but current frame ...`。

对当前 CPF 2.2.21-0.3.0 编译器反编译：`FunctionGenerationContext.gxxLandingpad` 仅在 `restoreFrame && enableStackmap` 时生成 `SetCurrentFrame`。但 `KonanConfig` 明确 `enableStackmap=true` 只支持 `ohos_arm64`。macOS 仍使用 ShadowStack，却在 catch 路径遗漏恢复；反汇编该首失败用例确认存在 EnterFrame/LeaveFrame/CheckCurrentFrame 而 catch 中无 SetCurrentFrame。

上游 2.2.21 `CodeGenerator.kt` 的 gxxLandingpad 无此 Stackmap 条件；`ShadowStack.hpp` 明确异常展开也需要恢复根帧。崩溃位置漂移和活跃对象被回收是未恢复的悬空根帧的后果。

修复限定该方法中的 Stackmap 条件，保留 restoreFrame 原控制，不开启 OHOS-only Stackmap。原 class SHA256 `4b94c8ea05da4dfecdf53e9cf23e2b93d8752e738c1e6efdf327b990664c91a1`；不匹配时拒绝修补。项目 build 内独立 Native home，原 `.konan` 安装与缓存不修改；副本不使用原损坏编译器产生的预编译缓存。

注意 KGP Native 属性在插件应用早期缓存，Native home 必须在 beforeProject 中设置，不能仅在 shared 插件应用后赋 extra 属性。

修后反汇编 catch 路径已出现 `SetCurrentFrame`，随后 `CheckCurrentFrame`；原安装 class 指纹不变。证据：`/private/tmp/native-stability-repaired-catcher-asm.txt`、`native-stability-first-catcher-asm.txt`、`native-stability-codegenerator-javap.txt`、`native-stability-konanconfig-javap.txt`。上游依据：[Kotlin 2.2.21 CodeGenerator.kt](https://github.com/JetBrains/kotlin/blob/v2.2.21/kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt)、[ShadowStack.hpp](https://github.com/JetBrains/kotlin/blob/v2.2.21/kotlin-native/runtime/src/mm/cpp/ShadowStack.hpp)。

## 第二个问题：完整测试报告并发损坏

进程稳定后，重复完整 Gradle 测试仍随机出现 `TestOutputStore.Writer.mark` 的 HashMap 并发修改、重复索引、XML 写失败，甚至 BUILD SUCCESSFUL 同时报告消费错误。KGP 对 stdout/stderr 的两个线程并发写入 Gradle 非线程安全存储。

测试可执行文件前的独立 shell 包装器使用 `exec ... "$@" 2>&1`，只在操作系统层合并输出流；全部日志、参数、过滤器能力、退出码与原二进制保留。既不吞异常，也不规避断言。三轮完整 XML 与同进程事件另行校验，不能仅信 BUILD SUCCESSFUL。

失败证据：`/private/tmp/native-stability-acceptance-full-2.log`、`native-stability-acceptance-full-3.log`；修后连续证据见 [validation.md](validation.md)。

## 诊断模式边界

额外启用 `runtimeAssertionsMode=panic` 后，原异常根帧断言已通过，但 CPF GC 内部 `ExtraObjectData::UnlinkFromBaseObject` 又触发 `ThreadRegistry.hpp:44 Thread is not attached to runtime`。这是非默认全局 panic 实验的独立限制，不声明此实验全部通过，也未保留该开关。默认运行时验收保留 GC、缓存及五项主动 GC 回归，详见验证记录。

## Android Core JVM 测试环境

额外完整 Core JVM 回归的 5 项通知测试失败均来自 `android.util.Log.i not mocked`，不是通知断言或 Native 修复失败。只在 androidUnitTest 提供四个被生产日志调用的 Logcat 方法，输出仍写 stderr；未开启全局 returnDefaultValues，其他 Android 框架错误继续抛出。完整 145 项通过。
