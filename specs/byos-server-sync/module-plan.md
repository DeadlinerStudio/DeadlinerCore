# BYOS Server Sync Module Plan

## 模块拆分

### DeadlinerCore

- `sync/server/model`：V2 push/pull DTO、实体种类、状态和错误合同，仅依赖 kotlinx.serialization。
- `sync/server/transport`：平台无关 `DeadlinerServerTransport` 及 Ktor HTTP 实现；Bearer token 只存在于一次 transport 配置中。
- `sync/server/engine`：pull → Snapshot merge → push → follow-up pull 编排、分页、幂等与 conflict 重试边界。
- `sync/server/state`：SQLDelight 保存 provider/account 作用域的 cursor、每实体 server change id 和待重试 mutation；业务写与 outbox 的最终原子化在后续批次完成。
- `sync/snapshot`：继续作为实体逻辑版本、墓碑和聚合序列化的唯一来源，不复制 Task/Habit 业务规则。

### DeadlinerServer

- `internal/domain/syncv2`：通用 mutation/change/document/receipt 规则与服务端状态机，不依赖 GORM/HTTP。
- `internal/domain/syncv2/port`：单一事务型 `Store` 端口，避免业务层跨多个非事务仓储拼接。
- `internal/infra/repo/syncv2`：GORM 事务、行锁、账号条件、append-only change 查询和通用文档 upsert。
- `internal/app/service/syncv2`：从认证上下文解析租户/设备，执行批量上限、payload 上限和同步写并发闸门。
- `internal/app/transport/http`：`/v2/sync/push` 与 `/v2/sync/pull` JSON 映射，不包含冲突决策。
- `db/migrations/mysql/0002_generic_sync.sql`：`sync_documents` 与必要索引；复用并收紧 `sync_changes`、`mutation_receipts`。
- `internal/config` / `internal/infra/persistence/gorm`：连接池和同步资源预算。

## 平台映射

- KMP 公共代码位于 `shared/src/commonMain/kotlin/com/deadliner/sync/server`，Android/iOS/HarmonyOS 不实现各自冲突算法。
- Android/iOS/HarmonyOS 只负责提供 server URL、access token、device metadata 和触发时机。
- Server 的 HTTP 与 Kitex v1 保持兼容；V2 首批只开放 HTTP，待合同稳定后再决定是否生成 Kitex IDL。

## 文件拆分策略

- DTO、状态机、HTTP handler、GORM store 和配置分别成文件；任何单文件接近 700 行时优先继续按职责拆分。
- 通用 payload 保持 `json.RawMessage`/序列化 JSON，不在 handler 中复制 Core 实体结构。
- 测试按 domain、store、HTTP contract、Core engine 分开，避免一个超大集成测试文件。

## 数据与索引

- `sync_documents` 唯一键：`(account_id, entity_kind, entity_uid)`。
- 当前态扫描索引：`(account_id, entity_kind, server_change_id)`；主要 pull 仍走 `sync_changes(account_id, change_id)`。
- 回执唯一键保留 `(account_id, device_uid, mutation_id)`。
- 所有 tenant 查询必须显式包含 `account_id`；仅凭 `entity_uid` 的仓储 API 不允许存在。

## 资源预算

- 默认 DB `maxOpenConns=8`、`maxIdleConns=4`，连接 lifetime/idle time 可配置。
- 默认单 push 最多 100 mutations、单 payload 256 KiB、请求体 1 MiB、pull 100/最大 500。
- 默认同步写并发 8；超过预算快速返回 503 + `Retry-After`，不在内存排无限队列。
- HTTP 保留 read/write/idle timeout，并增加 read-header timeout。

## 风险点

- 旧 v1 service 的多仓储写入不具备统一事务语义，V2 不应复用其 `handleMutation`。
- 仅保存当前文档而不以 `sync_changes` 拉取会破坏分页和重放保证。
- 仅把 outbox 放在内存会在进程退出时丢离线 mutation；Core 最终必须持久化。
- `conversation_turn` 迁移会升级数据库和 snapshot 格式，应作为独立保护提交处理。
