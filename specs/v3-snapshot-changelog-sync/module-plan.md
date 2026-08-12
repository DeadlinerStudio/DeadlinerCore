# V3 全量快照同步 Module Plan

## 模块拆分

1. `sync/snapshot`: 从 SQLDelight 全量读取和直接应用 V3 快照，按 V3 entity version 合并，不经 changelog。
2. `sync`: 将 WebDAV payload 收敛为 snapshot-only；保留既有条件写重试和公共结果类型。
3. `db/bridge`: 复用隔离命名空间中的持久化版本表，补齐只含版本的 tombstone 查询；分类目录只读取数据库中的 live 行。
4. `iOS persistence adapter`: 将无时区 SwiftData 历史 capture 按设备本地时区转换为 `Date`；带偏移与无偏移两种历史格式均可读取。
5. `category contract`: KMP 是分类语义图标 ID 和规范化颜色的唯一写入端；iOS 只在展示层将语义 ID 映射为 SF Symbol，并兼容读取旧 SF Symbol 值。
6. `category presets`: 数据库创建时仅在分类表完全为空时写入 `CategoryPresets`；KMP repository 提供“补回缺失/tombstone 预设”的单事务操作，所有平台通过该原子接口调用。
7. `habit aggregate`: 导出版本取 habit、record 与 schedule 的最大更新时间；应用较新快照时将 records/schedules 视为完整集合并清理缺失子项。

## 平台映射

- 公共 KMP：`shared/src/commonMain/kotlin/com/deadliner/{sync,db}` 与 SQLDelight queries。
- iOS：`Features/Capture/Persistence/KMPCaptureStore.swift` 兼容旧本地时间。
- iOS：`Features/Category/CategoryPresentationModels.swift` 维护语义图标到 SF Symbol 的渲染映射；`TaskCategory` 与 KMP adapter 在写入前规范化历史值。
- iOS：设置页调用 KMP 的恢复预设接口，不保留本地预设副本。
- HarmonyOS：通过既有 C API 接入分类管理，并新增恢复预设的零参数原子接口。
- HarmonyOS：设置入口调用 C API 的恢复预设接口；目录展示只返回数据库中的 live 行。
- Android：继续通过既有 `DeadlinerDatabase.webDavChangeLogSync` 接入，无新平台文件。

## 文件拆分策略

- 快照组装/合并独立于 `ChangeLogSyncFacade`，避免业务 DB 写入回到 changelog 层。
- 不新增超过 1000 行的核心文件；各职责保持单一。

## 风险点

- 版本表必须与 legacy V2 使用隔离 key，不能互相污染。
- 父实体先于 task/habit 聚合应用，避免分类外键引用失败。
- 预设 tombstone 与其他分类 tombstone 一样持久化；只有用户明确执行恢复操作才会重新激活。
- 图标资源名称属于平台实现，不能进入 snapshot；历史 SF Symbol 值必须在 iOS 读取时映射为共享 ID，避免保存时继续扩散。
- 以“分类表有无任何行（含 tombstone）”判断新数据库，避免用户删除全部预设后下次启动被自动恢复。

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
