# 快速添加端侧推理 Tasks

## 阶段 1：规格冻结
- [x] 冻结原生推理范围、共享张量、模型未就绪状态和规则降级。
- [x] 用户指定 HarmonyOS Developer Knowledge MCP，核对系统 C/C++ 推理和官方转换指南。

## 阶段 2：基础设施
- [x] expect/actual：Android ONNX Runtime、iOS Core ML、鸿蒙系统 MindSpore Lite。
- [x] 后台串行加载/推理/释放、取消传播、私有缓存、大小/SHA-256 和原子提交。
- [x] 支持 GitHub Releases 的 HTTPS CDN 重定向，禁止 HTTP 降级。

## 阶段 3：功能实现
- [x] 选择并训练中文字符 CNN 联合意图/BIO 模型，127,180 参数，合成样本训练。
- [x] 同源导出真实 ONNX/Core ML/MindSpore 权重，检查实际输入输出形状、类型及数值。
- [x] v1 字符编解码、概率门限、BIO/多标题/未知字符拒绝、日期周期归一化。
- [x] 三端清单接入、后台准备、即时规则提案和模型就绪后识别。
- [x] 三端可见状态/真实字节进度/失败重试，以及最新输入/用户修改/AI/关闭/保存保护。
- [x] Android 默认内置引擎，按 ABI 分包；用户无需打开隐藏开关。
- [x] 用户授权在项目 GitHub Releases 托管；发布真实权重、词表、清单、许可、训练源码与报告。

## 阶段 4：验证与回写

验收记录（2026-10-10）：
- [x] Core Android Release AAR、完整 iOS Release XCFramework、鸿蒙 Release Shared 构建并同步；逐字节校验。
- [x] Android Release/Debug 客户端、iOS Simulator 客户端、鸿蒙 assembleHap 构建。
- [x] Android 16 项实时提案与异步竞态测试通过；KMP 快速添加 40 项测试通过，其中实际 Release 下载/缓存与真实三格式解码两项显式运行。
- [x] 真实 ONNX/Core ML/ARM64 Linux MindSpore 输出共 42 条（14 样例 × 3 后端）通过生产 KMP 解码。
- [x] 生产下载代码匿名获取公开三端权重，验证重定向、哈希、缓存复用和全部快速添加测试。
- [x] 三端资源包包含真实清单，不包含模型权重；Android arm64 APK 包含 ORT。
- [x] Android Debug 已签名验收包保存 app/release/app-arm64-v8a-quick-add-model-v1-debug.apk，apksigner 校验通过。
- [x] Android arm64 Release 未签名验收包 58,407,463 字节（58.41 MB），保存 app/release/app-arm64-v8a-quick-add-model-v1-unsigned.apk。原 164.46 MB 四 ABI 包相比约缩小 64.5%。正式发布须原发布密钥签名。
- [x] 规格校验、大文件检查与 diff 空白检查。

## 实测范围与保留 TODO
- 初版 17,000 合成训练样本、2,500 同家族随机验证样本。14 手写样例部分与训练模板重合，不是独立真实用户准确率验收。报告明确记录两个新表达的边界错误；生产槽位阈值 0.9 会拒绝它们。
- ONNX 对 PyTorch 单样例最大差 0.0000114；Core ML 对 ONNX 14 样例最大差约 0.023586；ARM64 Linux MindSpore 对 ONNX 最大差约 0.0000229，文本标签一致。KMP 提案解码/拒绝行为三格式一致。
- [ ] 建立独立人工标注/表达家族隔离测试集，校准概率、误接受率、覆盖率及与规则对比。
- [ ] Android/iOS/鸿蒙真机 UI、真实系统版本兼容性与 P95、内存验收。尚无真机证据，不以 host 推理或编译成功冒充。
- [ ] 量化、固定算子 ONNX Runtime 裁剪、鸿蒙 NNRt。

## 历史压包记录

曾生成 24,090,024 字节无 ORT 诊断包，用于规则模式验证；该包不支持在线模型推理，不作为本次交付。现默认恢复 ORT，通过架构分包缩小安装体积。原 app/release 目录曾在保存前已不存在，本轮未删除/覆盖原发布包。

## Android JNI 崩溃修复（2026-10-10 15:15）

- 真机 tombstone 明确为 `NoSuchMethodError: NodeInfo.<init>(String, ValueInfo)`，发生在 `OrtSession_getInputInfo`；旧 Release R8 裁剪了 JNI 调用的构造方法。
- 共享 AAR consumer 规则和 Android 客户端添加官方 `-keep class ai.onnxruntime.** { *; }`。ONNX 1.31.0 自带 consumer 规则仅覆盖 telemetry，不足以保护完整 JNI API。
- 共享 Release AAR 重建、同步；Android Release 重建成功，16 项 QuickAddState 测试通过。最终 R8 mapping 检查 NodeInfo 构造方法及 ORT JNI 类名保留。
- 独立包名、开启 R8 的 Release 探针使用同一模型和保留规则，已在连接的 arm64 Android 真机运行。UI 显示 `PASS: minified JNI metadata and 20 model predictions`；验证 getInputInfo 的 INT32/[1,128] 元数据、输出 shape 和预期 BIO 标签。没有替换用户已安装的 Deadliner。
- 新产物 `app/release/app-arm64-v8a-quick-add-jni-fix-unsigned.apk`，58,522,151 字节（58.52 MB），正式安装/发布需原密钥签名。此证据覆盖原生混淆崩溃点，不等同于完整 App 真机 UI 或性能验收。

## 空格与习惯识别修复
- [x] 冻结截图原句的预期习惯提案与来源提示。
- [x] 规则时间空格兼容与无效时刻回归测试。
- [x] 三端实时提案识别来源标签。
- [x] 新版共用标题池训练、时间空格增强与真实三格式验证（19 样例 × 3 格式）。
- [x] 发布 v1.1.0 Release、三端清单升级与客户端构建。
- [x] v1.1 生产下载、哈希、缓存与全量测试（41 项 KMP，0 失败；Android 16 项状态测试通过）。

- v1.1 实测截图原句：三种格式均返回 HABIT、交报告书、DAILY、15:00、ready=true、source=MODEL。原句、无空格与中文数字、工作日、否定等 19 条针对性样例均保留正确结果或明确拒绝；“帮我记录……”仍低置信度，未宣称覆盖全部自然语言。
- 采用同一合成样本 14+6+3 轮训练，后两阶段强化槽位/周期边界，原 0.9 门槛未降低；训练过程与检查点可复现。针对性调参样例并非独立真实评测。
- 三种实际输出通过生产 KMP 解码；Core ML/ONNX 最大差 0.035198，ARM64 Linux MindSpore/ONNX 最大差约 0.0000191，标签一致。
- Core 三端产物与三端客户端全部构建成功；真实新清单均入包，Android JNI 保留检查通过。v1.1 未签名 APK 58,522,163 字节，路径 app/release/app-arm64-v8a-quick-add-v1.1-unsigned.apk。
- v1.1 Android minified 真机探针构建成功，但设备在安装前断开，未执行本轮真机推理或完整 UI 验收；没有改动用户已安装 Deadliner。

## v3 预训练适配（2026-10-10）
- [x] 可选WordPiece词表、大小写/去重音、原始Unicode偏移、token BIO到字符映射；旧字符模型兼容。
- [x] KMP/macOS快速添加58项、Android快速添加55项测试通过；全量Android另有5项通知mock失败，保留记录。
- [x] 双语ONNX/CoreML/MindSpore真实推理输出及完整生产KMP提案一致。
- [x] GitHub Releases托管、六个公开HTTPS下载/SHA验证、三端新清单。
- [x] Android37.35 MB未签名验收APK；鸿蒙signed debug HAP。
- [x] iOS客户端Release最后产物校验；三端新清单与线上发布逐字节匹配。
- [ ] 中文与英文差<=5点目标：当前6.72点，未达标；独立人工/三端真机性能门禁仍未完成。
