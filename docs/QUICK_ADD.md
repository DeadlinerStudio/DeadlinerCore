# Quick add contract

`com.deadliner.domain.quickadd.QuickAddParser.parse(text, preferredKind, now, timezone)` runs synchronously and returns one presentation-only `QuickAddProposal`. No database or network access is needed. `parseJson(text, preferredKind, timezone)` exposes the same stable JSON to Swift. HarmonyOS calls `deadliner_quick_add_parse_json` with `{text, kind, timezone, now}`; its explicit timestamp keeps the buffer-sizing and buffer-writing passes deterministic across midnight.

Draft fields: `kind` (`TASK` / `HABIT`), `title`, nullable `dueAt`, `note`, `period`, `targetCount`, `weekdaysMask`, nullable `reminderTime`, `needsAI`, `ready`, `source`, `message`. Dates are local ISO dates/times. A title-only task has no invented deadline; a date-only task uses 23:59. Weekday masks use Monday bit 0 through Sunday bit 6. Weekly/monthly recurrence without specific execution days uses all days plus a weekly/monthly target window.

Clients synchronously replace the preview on each edit, disable confirmation unless `ready && !loading && !saving`, keep model rejection non-submittable and call AI only on explicit user action, and invalidate pending requests when editing, clearing, expanding or dismissing. AI uses the existing single-type editor extraction API and `QuickAddAiResult`; zero/multiple results cannot be confirmed. iOS uses `IosQuickAddBridge`, HarmonyOS uses `deadliner_quick_add_extract_json` (config JSON and request JSON). The C bridge retains the existing utility cache between sizing/writing and clears it after a successful write.

The switch is persisted independently: Android SharedPreferences, iOS AppStorage/UserDefaults, HarmonyOS Preferences + AppStorage. All use `quick_add_enabled`, default false. It is device-local and does not alter KMP database/sync schemas.

Client source lives in the sibling native repositories. Build/deploy matching KMP binaries before compiling clients. See `specs/quick-add/` for implementation and validation notes.

端侧语义模型后端适配见 [QUICK_ADD_INFERENCE.md](QUICK_ADD_INFERENCE.md)。当前仅完成推理基础设施，模型与语义编排尚未接入现有解析流程。
