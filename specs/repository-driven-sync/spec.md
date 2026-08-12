# Repository 驱动的 KMP 同步

## 背景

- 创建日期：2026-08-12
- 功能标识：`repository-driven-sync`
- 需求来源：Capture 已进入 V3 远端快照但 iOS 未刷新内存投影；各原生 Repository adapter 重复负责写后同步，容易漏接或双重触发。

## 目标

- KMP Repository 是所有业务实体增删改后的自动同步触发点。
- 数据库事务提交后发出一次 mutation signal，由 KMP 去抖协调器异步执行同步；网络 I/O 不进入 SQL 事务。
- 连续写入合并为一次同步；同步进行期间发生的新写入在当前轮结束后再执行一次，不能丢失。
- 原生层只安装/更新同步配置、消费同步结果与远端数据变化，不再在 CRUD 后调用 `scheduleSync()`。
- 手动下拉继续直接触发一次立即同步。
- Capture 被远端应用后必须发布 `.capture` 变化并刷新 `CaptureStore`。

## 非目标

- 不改变 V3 WebDAV/iCloud payload、路径或 LWW 合并规则。
- 不在 Repository 的读取操作后触发同步。
- 不要求未配置或已关闭云同步的数据库自动联网。
- 本轮不实现 Android WebDAV 凭据 UI；Android 可在接入 KMP Repository 时复用同一协调器。

## 用户场景

1. 用户在鸿蒙新建 Capture、任务或习惯后，Repository 提交本地事务，KMP 自动去抖同步，无需 ArkTS 再触发。
2. 用户在 iOS 连续编辑多项数据，只产生合并后的同步轮次；远端 Capture 合并后 Capture 页面自动刷新。
3. 用户手动下拉时立即同步，即使没有本地 mutation 也会拉取其他设备数据。

## 验收标准

- Category、Task、Habit（含 record/schedule）、Capture、Memory、Profile 的业务 mutation 均在事务成功后通知 KMP 同步协调器。
- 初始化种子、远端 apply、迁移导入可以显式静默，不能形成同步回环。
- KMP 协调器支持去抖、单飞和 pending rerun；失败不回滚已提交的本地 CRUD。
- iOS/HarmonyOS 不再从业务写路径调用原生 `scheduleSync()`；手动刷新仍调用立即同步。
- V3 同步结果包含本轮实际应用的资源种类，至少能区分 Capture，以驱动原生内存投影刷新。
- Capture 从鸿蒙源库同步到 iOS 等价目标库的集成测试通过。

## 风险与约束

- Repository API 当前为同步函数；自动同步只能异步调度，不能把网络结果作为 CRUD 返回值。
- WebDAV 凭据与 iCloud transport 仍由平台安全存储/系统能力提供；KMP 只持有进程内配置或同步动作。
- 协调器生命周期跟随 `DeadlinerDatabase`，关闭数据库时必须取消待执行任务。
- 保持现有公开同步 façade 可用，避免在脏工作区引入 breaking change；因此无需创建保护提交。
- 所有核心文件保持低于 1000 行，不增加巨型 Repository 基类。
