# KMP V6 数据备份

## 背景

Android v5 的设置页直接复制旧 SQLite 文件。业务数据迁移到 KMP 后，该文件不再是数据源，继续导入导出会遗漏 v6 数据并重新引入旧数据库运行时依赖。

## 目标

- 在共享层用现有 `SnapshotV3Root` 提供完整备份的编码与恢复。
- 覆盖 category、task/subtask、habit/record/schedule、capture、Lifi memory、Lifi conversation turns、profile 与 tombstone。
- 恢复语义为事务内“以备份为准替换”，不使用同步的 LWW 合并语义。
- Android 仅负责系统文件选择与字节传输。

## 非目标

- 不兼容或导入 v5 SQLite 备份。
- 不新增平台文件选择 API。
- 不改变 WebDAV、iOS 或 HarmonyOS 的同步与持久化实现。

## 验收标准

- 无效或非 V3 备份在修改本地数据前失败。
- 成功恢复后，本地业务实体、子实体、tombstone 和版本与备份一致；备份后新增的本地实体被移除。
- 恢复操作为单个数据库事务。
- Android 导入成功后刷新共享 ViewModel、通知与自动同步配置。
