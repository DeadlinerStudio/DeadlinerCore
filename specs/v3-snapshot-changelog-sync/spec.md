# V3 全量快照同步

## 背景

首版“快照 + changelog”实现会将快照重新投影为 changelog entry 后再合并。该投影混入历史日志和同步占位分类，可能用不完整数据覆盖 `Category`，从而破坏 iOS 的预设分类。

## 目标

- 每次 V3 同步只读写一个完整、类型化的 `SnapshotV3Root`。
- 拉取时直接按 V3 实体版本 `(ts, ctr, dev)` 合并快照项，不再把快照转换为 changelog，也不再读取或写出增量 `changes`。
- 将每个实体的 V3 版本与“仅 tombstone”的版本持久化，确保删除不会因下一次快照写回而丢失。
- 快照覆盖 category、task（及 subtasks）、habit（及 records/schedules）、capture、memory 与 profile，并保留 tombstone。
- habit 的实体版本覆盖父对象及 records/schedules 的最新修改；较新的 habit 快照以完整子集合替换本地 records/schedules。
- 保持 `DeadlinerDatabase.webDavChangeLogSync`、其返回类型和 C API 导出完全不变。
- 统一 iOS 与 HarmonyOS 的预设分类删除语义：预设仅作为首次安装种子，允许创建 tombstone 后在所有设备隐藏。
- iOS 必须将历史 capture 的无时区时间按设备本地时区解析，以便 SwiftData 迁移项不会被展示层丢弃。
- 固化分类视觉字段的跨端契约：`iconKey` 只写入共享语义 ID，`colorHex` 只写入大写 `#RRGGBB`。
- 预设分类只在数据库首次为空时写入；之后与普通分类完全一致，可编辑、删除并同步。
- 提供一个原子“恢复预设”操作：仅补回缺失或 tombstone 的预设，不覆盖仍存在的用户编辑。

## 非目标

- 不改变 WebDAV 凭证、路径、HTTP 条件写入或顶层桥接协议。
- 不恢复或修复已经被错误快照覆盖的分类内容；下一次从仍保存正确预设分类的 iOS 设备同步后会按版本恢复。
- 不在本次加入账号隔离或跨账户迁移。

## 用户场景

设备 A 与 iOS 同时同步时，双方携带完整分类列表。每个分类依 V3 版本独立合并，iOS 的预设分类文档不会被本地任务关联产生的占位记录或历史日志覆盖。

## 验收标准

- 写出的 V3 payload 只含 `formatVersion = 3` 与 `snapshot`，不含 changelog `changes`。
- 分类、任务、习惯、capture、memory 与 profile 均按 V3 实体版本独立合并；远端快照中缺失的条目不会删除本地条目。
- 新增或移除习惯打卡、修改计划项都会推进 habit 聚合版本；接收端会移除较新 habit 快照中已不存在的本地打卡与计划项。
- iOS 预设分类的完整文档被直接保存；同步过程不创建或上传分类占位符。
- tombstone 在本地不存在业务行时仍会保留在输出快照中。
- 条件写冲突后重读、重合并、再写入；公共 WebDAV/C API 签名不变。
- 顶层 C API 与 `WebDavChangeLogSyncFacade` 的公共调用签名不变。

## 风险与约束

- 快照会增加单个 WebDAV blob 体积；本期以分类和任务完整性优先。
- 旧的 hybrid payload 只读取其 `snapshot` 字段，显式忽略其中的 `changes`，避免已知有风险的日志再次覆盖数据。
- 不改变 C API 导出签名或 WebDAV 接入层。
- 无时区时间按写入设备本地时区解释；未来 schema 版本再统一迁移为带 UTC 偏移的 RFC3339。
- `iconKey` 不是 SF Symbol 或 Harmony resource 名；各平台负责将共享语义 ID 映射至原生图标。读取历史平台图标名时可兼容，但新建和编辑必须写回语义 ID。
- 分类按实体版本整体 LWW 合并，视觉字段不进行独立字段级合并。
- 不在任何平台的读取路径动态投影或补齐预设；恢复预设必须通过同一个 KMP 数据库事务执行。

## 背景

- 创建日期：2026-08-03
- 功能标识：`v3-snapshot-changelog-sync`
- 需求来源：

## 目标

- 

## 非目标

- 

## 用户场景

1. 

## 验收标准

- 

## 风险与约束

- 
