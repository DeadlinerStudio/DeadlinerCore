# Core Native 整批测试稳定性 Module Plan

## 模块拆分
1. 复现诊断：原生进程崩溃、采样、整批执行日志与依赖源码。
2. 编译器修复：`runscript/native/NativeShadowStackPatch.java` 只修 CPF 指纹匹配的异常根帧条件；独立 Gradle 脚本在插件应用前选择 build 内工具链副本。
3. 测试宿主：单管道保留全部 Native 输出，避免 KGP 双读取线程争用报告存储；Android JVM 仅替代 Logcat，不放宽其他框架 stub。
4. 验证回写：独立主动 GC 回归、三轮完整批次和同进程十轮、XML/事件数量检查、规模与规格记录。

## 平台映射
生产 commonMain/数据库/同步接口不修改。修补作用于当前版本所有 Native 构建；已链接 iOS Simulator framework、OHOS shared library 验证兼容性，但本轮不部署客户端产物。Android 仅 Core JVM 测试替身与验证，不改宿主看板/全量历史；三端 UI 不在范围。

## 文件拆分策略
回归按 Native 稳定性独立测试文件，不扩张已大文件；诊断和验收独立记录。

## 风险点
GC 非确定性、CPF 编译器升级后指纹变化、KGP 早期属性缓存、输出报告并发、部分构建不能代表客户端全量交付；区分默认配置验收与诊断实验。未知 class 指纹拒绝修补，升级时重新验收并移除已上游修复的补丁。
