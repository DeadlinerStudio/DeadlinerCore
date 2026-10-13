# 快速添加 Tasks

## 阶段 1：规格冻结
- [x] 确认共享核心与三端仓库位置、AI 接口和表单。
- [x] 完成规格与模块拆分。

## 阶段 2：基础设施
- [x] KMP 本地解析、单条提案与 AI 转换。
- [x] 三端持久化开关、共享接口桥接。

## 阶段 3：功能实现
- [x] 三端实时 Sheet、确认与传统表单展开。
- [x] 防抖、过期请求过滤、错误状态、重复保存防护。

## 阶段 4：验证与回写
- [x] 日期/习惯/不支持表达单测；完成实质实现后统一编译。
- [x] 大文件检查、规格回写与结果记录。

## 验证结果（2026-10-09）
- 快速添加 macOS Native 测试：12 项通过，无失败；全量测试曾中断，不计为通过。
- KMP Android Release AAR、iOS Release XCFramework 与 HarmonyOS Release 动态库构建通过；三端最终产物同步并通过字节校验。
- Android `compileDebugKotlin`、iOS Simulator `xcodebuild`、HarmonyOS `assembleHap` 均通过。
- 规格校验、四仓库 `git diff --check` 与大文件扫描完成；新增模块低于 1000 有效行，既有超限文件及接线情况记录于 module-plan.md。
- 未进行真机交互验收或真实 AI 服务请求；仍需确认键盘、Sheet 展开与保存后的端到端体验。

## Android AI 提示反馈修复（2026-10-09）
- [x] 非空缺名称的输入标记 needsAI，避免 ready/needsAI 同为 false。
- [x] 睡前、下班后、下下周等不支持日程不再当作普通标题静默接受。
- [x] QuickAddParserTest macOS Native 14 项通过，无失败；Android 自动构建并同步 Release AAR，Android 快速添加/定位/材质 13 项通过。
- 不改变解析 API / DTO；纯标题无需 AI、空输入不触发 AI。其他平台未在本轮重新部署二进制。

## 纯数字输入反馈
本地标题必须包含文字；纯数字/符号（如 123）返回 ready=false、needsAI=true。保留带数字的文字标题与纯文字标题本地识别。Android 四属性横行、默认时间与编辑资格由 UI 状态模块管理，DTO 不变；新增回归验证。

纯数字反馈验证：QuickAddParserTest 的 macosArm64Test 共 15 项通过，包含 123、全角数字、符号与带文字数字的对照。Android 编译及 15 项回归通过，Core AAR 已同步，仅构建 Android 二进制。

## Android AI HTTP 错误反馈
Android KtorLlmClient 启用 expectSuccess，将非成功 HTTP 响应作为带状态码的异常抛出，避免鉴权失败被误报为 JSON 解析失败。Android 快速添加映射状态码与安全提示，不展示响应正文或凭据。API/DTO 不变，不改其他平台实现。

Android HTTP 反馈验证：expectSuccess=true 的客户端已编译进同步 AAR；Android compileDebugKotlin 及 26 项相关测试通过。真实网络 HTTP 状态未作请求验证，未变更其他平台。
