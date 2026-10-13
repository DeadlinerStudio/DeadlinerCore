# 快速添加 Module Plan

## 模块拆分
1. shared/domain/quickadd：单条提案、同步解析与 JSON 契约。
2. shared/lifi/utility：AI 提案转换，复用已有单类型提取接口。
3. 平台偏好与 Sheet：持久化、输入生命周期、确认保存与传统编辑器衔接。
4. HarmonyOS 独立 C ABI + NAPI 桥；iOS 直接导出共享 Kotlin 接口。

## 平台映射
- Android：app/src/main/java/com/aritxonly/deadliner/ui/quickadd；localutils/GlobalUtils；AddDDLActivity。
- iOS：Deadliner/Features/Main/Sheets；Features/Settings；DeadlinerCoreSupport/Bridge。
- HarmonyOS：entry/src/main/ets/pages/routes/main/modify/quickadd；common/local；entry/src/main/cpp。

## 文件拆分策略
解析器、契约、AI 转换、Sheet 各自独立。已有大入口文件只放路由接线，禁止把解析器堆入入口。

## 风险点
二进制更新顺序，AI 请求乱序，日期与中文数字边界，保存重复点击，展开保留草稿。

## 实际接入
Android 使用现有添加 Activity 承载 ModalBottomSheet；保存与草稿转换独立于 Activity。iOS 两种主界面共用 AddEntrySheetView，展开后直接复用已有编辑器。HarmonyOS 包装原有两个 Sheet，用 allowQuickAdd=false 避免展开后重新进入快速模式；独立 C++ 文件注册新增 NAPI 导出。

## 已有文件规模
新增功能文件均低于 1000 有效行。Android 添加入口经过初始化职责抽取保持在 1000 行；Core 原有持久化 C ABI、HarmonyOS 原有主界面/NAPI 入口已超过阈值，本次仅作接线，解析与新桥接均独立文件。未改动其他已有大文件的职责。

## Android 后续 UI 接入
Android 已改为首页浮动输入容器与显式 AI 按钮，详情见 Android specs/fluid-quick-add。2026-10-09 本轮只修正 shared/domain/quickadd/QuickAddParser 的失败标记与不支持语义日程检测；不改变公共接口。

## 纯数字输入反馈
本地标题必须包含文字；纯数字/符号（如 123）返回 ready=false、needsAI=true。保留带数字的文字标题与纯文字标题本地识别。Android 四属性横行、默认时间与编辑资格由 UI 状态模块管理，DTO 不变；新增回归验证。

## Android AI HTTP 错误反馈
Android KtorLlmClient 启用 expectSuccess，将非成功 HTTP 响应作为带状态码的异常抛出，避免鉴权失败被误报为 JSON 解析失败。Android 快速添加映射状态码与安全提示，不展示响应正文或凭据。API/DTO 不变，不改其他平台实现。
