# V3 全量快照同步

## 背景

首版“快照 + changelog”实现会将快照重新投影为 changelog entry 后再合并。该投影混入历史日志和同步占位分类，可能用不完整数据覆盖 `Category`，从而破坏 iOS 的预设分类。

## 目标

- 每次 V3 同步只读写一个完整、类型化的 `SnapshotV3Root`。
- 拉取时直接按 V3 实体版本 `(ts, ctr, dev)` 合并快照项，不再把快照转换为 changelog，也不再读取或写出增量 `changes`。
- 将每个实体的 V3 版本与“仅 tombstone”的版本持久化，确保删除不会因下一次快照写回而丢失。
- 快照覆盖 category、task（及 subtasks）、habit（及 records/schedules）、capture、memory 与 profile，并保留 tombstone。
- 保持 `DeadlinerDatabase.webDavChangeLogSync`、其返回类型和 C API 导出完全不变。

## 非目标

- 不改变 WebDAV 凭证、路径、HTTP 条件写入或顶层桥接协议。
- 不恢复或修复已经被错误快照覆盖的分类内容；下一次从仍保存正确预设分类的 iOS 设备同步后会按版本恢复。
- 不在本次加入账号隔离或跨账户迁移。

## 用户场景

设备 A 与 iOS 同时同步时，双方携带完整分类列表。每个分类依 V3 版本独立合并，iOS 的预设分类文档不会被本地任务关联产生的占位记录或历史日志覆盖。

## 验收标准

- 写出的 V3 payload 只含 `formatVersion = 3` 与 `snapshot`，不含 changelog `changes`。
- 分类、任务、习惯、capture、memory 与 profile 均按 V3 实体版本独立合并；远端快照中缺失的条目不会删除本地条目。
- iOS 预设分类的完整文档被直接保存；同步过程不创建或上传分类占位符。
- tombstone 在本地不存在业务行时仍会保留在输出快照中。
- 条件写冲突后重读、重合并、再写入；公共 WebDAV/C API 签名不变。
- 顶层 C API 与 `WebDavChangeLogSyncFacade` 的公共调用签名不变。

## 风险与约束

- 快照会增加单个 WebDAV blob 体积；本期以分类和任务完整性优先。
- 旧的 hybrid payload 只读取其 `snapshot` 字段，显式忽略其中的 `changes`，避免已知有风险的日志再次覆盖数据。
- 本次不修改 ArkTS/UI 或 C 导出层。

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
