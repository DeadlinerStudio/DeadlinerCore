# BYOS Server Sync

## 背景

Deadliner Core 当前以 SQLDelight 保存跨端业务数据，并通过 Snapshot V4 + WebDAV 条件写实现设备间同步。DeadlinerServer 已具备账号、设备、会话、Deadline/Habit 两类旧文档、幂等回执和游标拉取的基础，但尚未覆盖 Core 的完整业务持久化集合，且旧 push 路径没有把“当前文档、变更日志、幂等回执”纳入同一数据库事务。

BYOS（Bring Your Own Server）需要成为 WebDAV 之外的服务端同步方案：客户端始终离线优先，服务器持久保存用户业务数据和删除墓碑，以账号作为租户边界，并在低配机器上通过有界批量、连接池、超时和并发闸门保持稳定。

## 目标

- 服务端以通用资源文档覆盖 Snapshot V4 已支持的全部业务聚合：Category、Task（含 Subtask）、Habit（含 HabitRecord）、Capture、MemoryFragment、MemoryUserProfile、UserProfile。
- 协议允许后续增加新的 `entity_kind`，无需为每一种 Core 实体新建 HTTP 路由或服务类。
- 每个已接受 mutation 在一个数据库事务内完成：幂等判定、基线版本校验、变更追加、当前文档/墓碑写入、回执写入。
- pull 使用账号内单调游标分页，永远返回删除墓碑；客户端只有在完整应用一页后才推进本地游标。
- 所有读写以认证 token 中的 `account_uid` 和 `device_uid` 为准，不信任请求体提供租户身份。
- Core 提供与平台无关的协议 DTO、同步编排和可注入 transport；HTTP transport 使用 Bearer token，密钥仍由宿主传入且不由 Core 持久化。
- 默认资源预算适合低配服务器：有限请求体、有限 mutation 数、有限 pull 页、有限数据库连接和同步写并发。

## 非目标

- 本批不删除旧 `/v1/sync/*` Deadline/Habit API；BYOS 使用新的 `/v2/sync/*` 通用文档协议。
- 本批不做实时 WebSocket 推送、共享清单、跨账号协作或管理员跨租户查询。
- 本批不对用户 payload 建立业务字段索引；payload 作为不透明 JSON 保存，服务端只校验协议包络和大小。
- `migration_diagnostic`、`changelog`、`legacy_v2_version` 和新增的远端同步状态属于本机运行元数据，不作为用户业务数据上传。
- `conversation_turn` 当前缺少跨设备稳定 UID、更新时间和墓碑。本批先记录为后续 schema migration，不能假装已经具备无损多设备同步。

## 用户场景

- 设备 A 离线创建 Task，设备 B 离线编辑 Habit；两台设备重连后以各自 mutation id 重试，服务端只应用一次，并通过账号内游标把双方变更送达另一台设备。
- 同一账号的两个设备同时编辑同一资源时，过期 `base_change_id` 返回 conflict 和服务端最新文档；客户端保留本地版本，合并后以新 mutation 重试，不静默丢弃。
- 两个不同账号使用相同实体 UID 时，数据库行、变更流和回执完全隔离。
- 低配服务器遭遇大批同步时，超大请求被拒绝、批量大小被限制、写请求超过并发预算时快速返回可重试错误，数据库连接数保持上限。

## 协议保证

### Push

- 请求最多包含配置允许数量的 mutation；每项包含稳定 `mutation_id`、`entity_kind`、`entity_uid`、`action`、`base_change_id`、可选逻辑版本和 JSON payload。
- `upsert` 必须有 payload；`delete` 不携带业务 payload但会产生持久墓碑。
- `(account_id, device_uid, mutation_id)` 唯一；相同 mutation 重放原结果。
- 初次创建要求 `base_change_id = 0`；更新/删除要求与当前 `server_change_id` 相同。
- 每项独立事务，批次中的一个 conflict/rejected 不回滚其他合法 mutation。

### Pull

- 变更源是 append-only `sync_changes`，查询键为 `(account_id, change_id)`。
- 返回 `change_id > cursor` 的有序事件；`next_cursor` 等于本页最后一个已返回事件。
- tombstone 永远包含在结果中，客户端不得通过 `include_deleted=false` 跳过删除。
- 客户端应用整页成功后才保存 `next_cursor`；失败时重放同一页必须安全。

### 冲突与收敛

- 服务端不按客户端时间戳覆盖并发写；`base_change_id` 不匹配时返回 conflict。
- Core 使用现有实体逻辑版本和 Snapshot merge 决定本地/远端胜者；本地胜者生成基于最新 server change id 的新 mutation。
- 删除也是带逻辑版本的实体状态，不能因离线旧 upsert 而被静默复活。

## 验收标准

- Server 的通用文档仓储支持所有 Snapshot V4 `entity_kind`，并有账号隔离、幂等重放、冲突、墓碑、分页与并发竞争测试。
- mutation 的 change/document/receipt 具备事务原子性测试；故障注入后不得出现“有游标无文档”或“已改文档无回执”。
- Core DTO 与 Server JSON fixture 双向兼容；Core 测试覆盖多页 pull、重复页、重复 push、conflict 保留本地变更和取消传播。
- 配置校验覆盖数据库连接池、最大 mutation 数、单 payload 大小、同步写并发和超时的安全边界。
- `go test ./...`、Core 目标测试、规格校验和大文件扫描通过。

## 风险与约束

- 当前 Server 旧 Deadline 模型与 Core 新 Task 模型不是同一合同，不能继续扩充旧类型来承载全部 Core 数据。
- MySQL `AUTO_INCREMENT` 是全局递增而非账号内连续；账号游标允许有空洞，只要求严格单调。
- GORM 的事务对象必须传入所有仓储操作；不能在事务中回退使用根 `*gorm.DB`。
- payload 上限必须在 HTTP 解码前和 mutation 校验时双重执行，避免单个合法请求耗尽内存。
- Core Snapshot V4 目前不覆盖 `conversation_turn`；完整覆盖需要先给该表增加稳定 UID、更新时间和墓碑，再升级 snapshot 格式。
