# V3 全量快照与增量日志同步

## 背景

当前 WebDAV V3 同步只保存一个合并后的 changelog blob。两个设备同时同步、重试或历史日志不完整时，远端无法作为任务全集恢复来源，可能造成任务不可见或丢失。

## 目标

- 每次 V3 同步将完整业务状态快照和未压缩的增量 changelog 一并写入同一个内部 payload。
- 拉取时先用快照补齐缺失实体，再按增量日志做 LWW 合并。
- 快照覆盖 category、task（及 subtasks）、habit（及 records/schedules）、capture、memory 与 profile，并保留 tombstone。
- 保持 `DeadlinerDatabase.webDavChangeLogSync`、其返回类型和 C API 导出完全不变。

## 非目标

- 不改变 WebDAV 凭证、路径、HTTP 条件写入或顶层桥接协议。
- 不在本次加入服务端日志裁剪、账号隔离或跨账户迁移。

## 用户场景

用户在设备 A 创建任务，设备 B 同时同步且远端增量日志出现缺口；下一次任一设备同步时，远端完整快照仍携带该任务，任务会被恢复，而后续日志继续按 LWW 收敛。

## 验收标准

- 写出的 payload 同时含 V3 `snapshot` 与 `changes`，且旧的纯日志 payload 仍可读取。
- 远端快照含有而远端 changes 缺失的任务，能被本地恢复。
- 快照和增量日志冲突时使用现有 timestamp/device/id LWW 规则。
- 同步成功后只确认本次本地 pending IDs；条件写失败重读重合并后再确认。
- 顶层 C API 与 `WebDavChangeLogSyncFacade` 的公共调用签名不变。

## 风险与约束

- 快照会增加单个 WebDAV blob 体积；本期以数据完整性优先。
- tombstone 必须进入快照，避免旧设备反向复活已删除数据。
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
