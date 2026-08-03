# V3 全量快照与增量日志同步 Module Plan

## 模块拆分

1. `sync/snapshot`: 从 SQLDelight 全量读取/序列化 V3 业务快照，转换远端快照为可复用的 changelog entries。
2. `sync`: 将快照导入与增量日志 LWW 合并编排为兼容 payload；WebDAV façade 保持条件写重试和确认时机。
3. `db`: 补齐全量（含软删除）查询，供快照模块读取。

## 平台映射

- 公共 KMP：`shared/src/commonMain/kotlin/com/deadliner/{sync,db}` 与 SQLDelight queries。
- iOS/HarmonyOS/Android：继续通过既有 `DeadlinerDatabase.webDavChangeLogSync` 接入，无新平台文件。

## 文件拆分策略

- 快照组装和快照转日志独立于 `ChangeLogSyncFacade`，避免其继续膨胀。
- 不新增超过 1000 行的核心文件；各职责保持单一。

## 风险点

- 快照时间必须能与旧 changelog 的毫秒时间稳定比较。
- parent aggregate 与子实体日志的排序必须维持现有 category/task/habit 依赖顺序。

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
