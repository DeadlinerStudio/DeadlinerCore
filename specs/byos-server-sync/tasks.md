# BYOS Server Sync Tasks

## 阶段 1：规格冻结

- [x] 盘点 Core SQLDelight 表和 Snapshot V4 覆盖范围。
- [x] 盘点 Server v1 账号、同步、幂等、限流和数据库结构。
- [x] 冻结 V2 通用文档协议、租户边界、游标/墓碑/冲突保证。
- [x] 定义低配服务器默认资源预算和非目标。

## 阶段 2：基础设施

### Server 通用同步基础设施

- [x] 新增 `sync_documents` migration 与 GORM model。
- [x] 新增 syncv2 domain 状态机和事务型 Store 端口。
- [x] 实现账号隔离、行锁、幂等回执、冲突和 append-only pull。
- [x] 实现 `/v2/sync/push`、`/v2/sync/pull` 与 OpenAPI 合同。
- [x] 增加连接池、批量/payload 上限和同步写并发配置。
- [ ] 覆盖 store/domain/HTTP 并发与租户隔离测试。

## 阶段 3：功能实现

### Core 客户端基础设施

- [x] 新增与 Server 对齐的序列化 DTO 和 fixture 测试。
- [x] 新增可注入 transport 与 Ktor Bearer HTTP transport。
- [ ] 新增游标、实体 server version、outbox 的 SQLDelight schema。
- [ ] 实现分页 pull、Snapshot merge、push、conflict 保留与 follow-up pull。
- [ ] 将 Repository 写路径与 durable outbox 在同一 SQL transaction 内连接。

### 完整持久化覆盖

- [ ] 验证 Category、Task/Subtask、Habit/Record、Capture、Memory、MemoryProfile、UserProfile 全覆盖。
- [ ] 为 `conversation_turn` 设计稳定 UID/updated_at/tombstone migration 并升级 snapshot。
- [ ] 明确 diagnostics/changelog/provider state 等本机元数据不上传。

## 阶段 4：验证与回写

- [ ] 运行 Server `go test ./...` 和 race-sensitive 并发测试。
- [ ] 运行 Core 目标测试与统一 KMP 编译批次。
- [ ] 校验规格和核心文件行数。
- [ ] 回写实际接口、验证证据、遗留范围和部署参数。

## 首批交付切片

本轮优先完成 Server V2 通用同步闭环、资源预算，以及 Core 协议/transport 基础。Core durable outbox 与 `conversation_turn` schema migration 在协议 fixture 稳定后继续，避免同时引入服务端协议、客户端数据库和 snapshot 三个 breaking change。

## 当前验证证据（2026-08-30）

- Core `ServerSyncProtocolTest`、`ServerSnapshotMapperTest` 和 Task/Subtask 聚合删除集成测试通过；完整 `:shared:macosArm64Test` 通过，Ktor transport 完成公共源码编译。
- Server `go test ./...` 与 `go vet ./...` 通过；OpenAPI YAML 可解析并包含两个 V2 路由和通用 DTO schema。
- Server domain/app 单测覆盖未知 kind、游标不越过未拉取事件、Core JSON fixture、token device mismatch、payload/batch/page 配置边界。
- MySQL 真实事务故障注入和并发竞争测试尚未补齐，因此“store/domain/HTTP 并发与租户隔离测试”仍保持未完成。
