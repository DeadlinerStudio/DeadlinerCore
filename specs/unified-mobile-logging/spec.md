# Unified Mobile Logging

## 背景

- 创建日期：2026-08-28
- 功能标识：`unified-mobile-logging`
- 需求来源：iOS 已使用持久化的诊断日志；Android、HarmonyOS 与 KMP 仍有分散的系统日志调用，无法导出统一诊断附件。

## 目标

- Android、HarmonyOS 与 KMP 产生 iOS 同名的 `Diagnostics/deadliner-diagnostics.log` 和 `Diagnostics/deadliner-diagnostics.jsonl`。
- JSONL 每行是 schemaVersion 为 1 的 JSON 对象：`schemaVersion`、`timestamp`、`sessionID`、`processID`、`level`、`category`、`message`、可选 `context`。
- 纯文本投影遵循 iOS 格式：`[ISO-8601][Native|KMP][module] level=... message/context`，字段按键名排序。
- 保留 14 天且总量上限为 8 MiB；日志写入失败不得中断用户操作。
- 所有 Android `android.util.Log` 与 HarmonyOS `console.*` / `hilog.*` 调用只允许存在于统一封装实现中。
- 对启动、KMP 初始化/调用、数据迁移、同步、AI、任务/习惯/捕获操作与关键原生错误路径记录隐私安全事件。

## 非目标

- 不上传诊断文件、不增加远程日志服务、不记录 API Key、授权令牌、密码、完整用户输入或完整网络载荷。
- 不改变 iOS 的实现或日志文件历史。

## 用户场景

1. 用户在 Android 或 HarmonyOS 设置页导出文本/JSONL 诊断文件，能够与 iOS 文件用同一解析器分析。
2. 开发者排查 KMP 初始化、同步或 AI 失败时，可以按 `KMP` 来源、稳定事件名和安全上下文定位问题。
3. 应用重启后保留近期诊断，超期或超量记录会被裁剪。

## 验收标准

- Android Debug 编译成功；除 Android 日志封装外，主源码没有 `android.util.Log` 或 `Log.d/i/w/e/v/wtf`。
- HarmonyOS 编译成功；除 HarmonyOS 日志封装外，主源码没有 `console.log/info/warn/error` 或 `hilog/HiLog`。
- 两个平台均在应用私有 `filesDir/Diagnostics` 下生成上述两个文件；一条 JSONL 记录能生成与 iOS 格式一致的文本行。
- KMP 关键调用由平台桥接以 `KMP` 来源写入同一对文件。
- 每次应用启动均记录 `app.environment`：设备型号、厂商、系统名称/版本/API 与应用版本；不记录设备唯一标识符。

## 风险与约束

- HarmonyOS ArkTS 禁止使用 `any`、类型断言和动态键访问；封装使用显式类/类型和同步文件 API。
- Android 和 HarmonyOS 是独立工作区，KMP 产物必须先部署到两端再进行最终构建。
- 既有工作区改动属于用户；本功能只接触日志基础设施、日志调用点、必要的设置导出入口与规格文档。
