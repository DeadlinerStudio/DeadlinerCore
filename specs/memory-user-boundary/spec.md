# Memory User Boundary

## 背景

当前 `KmpMemoryStoreAdapter` 把 LiFi 的可覆盖用户画像文本写入设置页的 `UserProfileRepository`，导致 AI 记忆更新会覆盖设置中的昵称。两者语义不同：设置 User 是账号展示资料，Memory User 是 LiFi 长期记忆中的画像摘要。

## 目标

- 设置 User 继续由 `UserProfileRepository` 和 `SettingsViewModel` 独立管理。
- Memory User 由 Memory 持久化独立管理，并参与 Memory 快照读写。
- LiFi 不再读取或修改设置昵称、头像和等级。

## 非目标

- 不迁移已被错误写入设置昵称的历史 AI 画像文本。
- 不保留通过设置 User 读写 Memory User 的兼容路径。
- 不增加平台侧业务 API。

## 用户场景

用户修改设置中的昵称后，LiFi 可以独立更新对用户的长期画像；两项数据互不覆盖。

## 验收标准

- `saveUserProfile/getUserProfile` 只访问 Memory 存储。
- Settings User 修改前后不受 LiFi Memory User 写入影响。
- Memory snapshot 能往返保存画像文本。
- KMP 集成测试和 Android 接入编译通过。

## 风险与约束

- 这是数据库 schema 变更，必须提供 SQLDelight migration。
- 共享构造入口需要同步更新，但不得改变其他端的业务行为。
- 现有无关的 HarmonyOS bridge、runscript 和 specs 改动必须保留且不混入提交。
