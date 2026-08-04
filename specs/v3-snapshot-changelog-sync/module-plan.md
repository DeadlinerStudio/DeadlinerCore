# V3 全量快照同步 Module Plan

## 模块拆分

1. `sync/snapshot`: 从 SQLDelight 全量读取和直接应用 V3 快照，按 V3 entity version 合并，不经 changelog。
2. `sync`: 将 WebDAV payload 收敛为 snapshot-only；保留既有条件写重试和公共结果类型。
3. `db`: 复用隔离命名空间中的持久化版本表，补齐只含版本的 tombstone 查询。

## 平台映射

- 公共 KMP：`shared/src/commonMain/kotlin/com/deadliner/{sync,db}` 与 SQLDelight queries。
- iOS/HarmonyOS/Android：继续通过既有 `DeadlinerDatabase.webDavChangeLogSync` 接入，无新平台文件。

## 文件拆分策略

- 快照组装/合并独立于 `ChangeLogSyncFacade`，避免业务 DB 写入回到 changelog 层。
- 不新增超过 1000 行的核心文件；各职责保持单一。

## 风险点

- 版本表必须与 legacy V2 使用隔离 key，不能互相污染。
- 父实体先于 task/habit 聚合应用，避免分类外键引用失败。

## 模块拆分

- `contract`：
- `domain`：
- `presentation`：
- `infra`：

## 平台映射

- iOS：
- HarmonyOS：
- Android：

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准

## 风险点

- 
