# Core 整批稳定性验收

日期：2026-10-13。范围仅 Core 工具链/测试宿主/回归，不含 Android 新看板、概览或全量历史、三端 UI。

## 完整 Native 回归

最终命令：`JAVA_HOME=<JDK21> ./runscript/verifyNativeRegression.sh`。

| 验收 | 数量 | 结果 |
| --- | ---: | --- |
| 未过滤 Gradle 全量第 1 轮 | 233 | 0 失败/错误/跳过，XML 完整，40 秒（含构建） |
| 未过滤 Gradle 全量第 2 轮 | 233 | 0 失败/错误/跳过，XML 完整，7 秒（含构建） |
| 未过滤 Gradle 全量第 3 轮 | 233 | 0 失败/错误/跳过，XML 完整，7 秒（含构建） |
| 原 Native 二进制，同进程完整重复 10 轮 | 2330 | started=finished=2330，0 失败/忽略，正常退出 |

最终证据目录：`/private/var/folders/tx/fc8y3krd7msbpb6r8v7m_q9m0000gn/T/deadliner-native-regression.NoWDrn`。保留 `full-1/2/3.log`、各轮 `results-1/2/3` XML，以及 `same-process-10.log`。汇总 `/private/tmp/native-stability-final-runner-verified.log`，脚本正常退出。此前 `BvYDrv` 完整重复也通过，最终验收以此轮为准。

补验中间一轮三次 Gradle 测试通过，但运行中的 shell 脚本被编辑，Bash 读取偏移变化导致后半段 EOF，未运行压力轮。该运行 `/private/tmp/native-stability-final-runner.log` 不计入完整验收；固定脚本后重新跑完上述全部步骤。

第一轮重新生成构建元信息；后两轮仅复用元信息，均 `macosArm64Test --rerun`，无 `--tests` 过滤。默认 CMS、缓存与默认运行时配置保留，无 GC 禁用、测试拆进程隔离或断言吞噬。原始 228 项全部保留，新增 5 项主动 GC 回归：构造器/copy 抛异常、普通 Kotlin 异常、协程异常、符号化异常、SQLite 回滚/V5 同步/数据库关闭后 GC。

Gradle 测试进程仅合并 stdout/stderr；真实二进制显式注册为测试任务输入，保留代码变化后的自动重跑能力。同进程压力轮直接运行原二进制，不经过包装器。报告校验同时排除输出消费/并发/重复索引/XML 写失败，不能以 BUILD SUCCESSFUL 单独认定通过。

## 跨平台与工程检查

- Android Core：`:shared:testDebugUnitTest --rerun -x generateKmpBuildMetadata --offline`，145 项，0 失败/错误/跳过。日志 `/private/tmp/native-stability-android-core-final.log`。只新增 JVM Logcat 测试替身，其他框架 stub 保持严格。
- iOS Simulator release framework 与 OHOS release shared library 最终链接成功，BUILD SUCCESSFUL（27 分 27 秒，含两目标编译/优化/链接，保留既有编译警告）；命令 `:shared:linkReleaseFrameworkIosSimulatorArm64 :shared:linkReleaseSharedOhosArm64 -x generateKmpBuildMetadata --offline` 显式设置 `DEVECO_HOME=/Applications/DevEco-Studio.app/Contents` 和 JDK21，日志 `/private/tmp/native-stability-final-native-links.log`。
- `git diff --check`、bash/sh 语法、Native 稳定性规格校验通过；本轮修改文件均未引入 1000 有效行超限。
- 修前/修后 catch 反汇编与原安装 class SHA256 检查通过：修后恢复 SetCurrentFrame，原 `.konan` class 指纹不变，详见 [diagnosis.md](diagnosis.md)。
- 修补守门负测通过：未知 class 指纹被拒绝，原工具链不能作为修补目标。临时检查源 `/private/tmp/DeadlinerNativePatchGuardCheck.java`，不进入产品产物。

## 交付边界

项目构建默认使用 `build/native-toolchains` 内修复副本，只支持指定版本/精确 class 指纹；未知指纹拒绝修补，不污染原工具链。修复对今后的所有 Core Native 构建生效，Android/JVM 编译器不变。升级 CPF 后需确认上游是否已修、移除不再需要的补丁，再跑完整回归。

本轮未执行部署脚本，未替换 iOS 客户端完整 XCFramework 或鸿蒙客户端 `.so`；只验证 Core 的生产链接。需要下一次完整构建部署后客户端才消费修复。未声明三端客户端 UI/真机验收完成。

非默认全局 runtimeAssertionsMode=panic 仍有 CPF GC 内部线程断言限制，已记录，不作为通过项；默认完整回归及显式 GC 压力均通过。本轮不修改其他会话的现有业务变更，不提交整仓脏工作区。
