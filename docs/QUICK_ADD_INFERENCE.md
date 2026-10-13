# 快速添加端侧推理接口

三端原生推理适配和首版语义模型已接入。模型通过项目 GitHub Releases 在线下载，可见进度和规则降级；覆盖范围、真实输出验收与限制见 [模型交付说明](QUICK_ADD_MODEL_DELIVERY.md)。尚未测量真实用户准确率或真机 P95。

## 分层与用法

`QuickAddInferenceEngine` 为公共 KMP 门面，内部 `expect/actual createQuickAddInferenceBackend()` 选择平台。所有加载、推理和释放在 Default dispatcher 下通过 Mutex 串行执行，不在 UI 线程操作原生会话。

```kotlin
val engine = QuickAddInferenceEngine()
// 一般由 QuickAddModelSession 完成下载、校验和加载；以下为底层接口示例。
engine.load(modelPath)
val output = engine.infer(QuickAddModelInput(
    tensors = listOf(QuickAddTensor(
        name = "input_ids", shape = longArrayOf(1, 128),
        type = QuickAddTensorType.INT32, integers = encodedIds,
    ), QuickAddTensor(
        name = "attention_mask", shape = longArrayOf(1, 128),
        type = QuickAddTensorType.INT32, integers = attentionMask,
    )),
    outputNames = listOf("intent_logits", "slot_logits"),
))
// QuickAddCharacterCodec 根据发布清单解码为 QuickAddProposal。
engine.close()
```

示例名称符合已发布模型契约；适配器支持任意命名张量，且要求输入名覆盖模型全部输入。数据为稠密 row-major，shape 为正整数且固定；输入支持 INT32、INT64、FLOAT32；ONNX/MindSpore 输出要求 FLOAT32，Core ML 可将 FLOAT16/DOUBLE 输出转换为 FLOAT32。所有输出在原生结果释放前复制；输入在调度前复制，调用方不能在复制过程中并发修改数组。

Core ML token ID 必须无损落在 INT32 范围，Core ML 导出时输入需设为 INT32；不可悄悄截断 INT64。三端固定输入 shape，不自动 resize 或截断输入。

`MODEL_NOT_CONFIGURED`、`MODEL_LOAD_FAILED`、`INVALID_INPUT`、`INFERENCE_FAILED`、`INVALID_OUTPUT` 通过 QuickAddInferenceException 区分。空路径和未加载明确报未配置；CancellationException 原样传播。重载先构建新会话，失败保留旧模型。close 可重复调用，之后可重新 load。原生计算无法保证立即中断，后续 UI 仍需使用输入 revision 丢弃过期结果。

## Android

使用 `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`，输入 .onnx 文件。初版 CPU、2 线程，不自行强制 NNAPI。环境为 ORT 进程共享环境，只释放本引擎会话/张量，不关闭全局环境。

Core 使用 compileOnly 编译适配器，Android 客户端显式携带运行时，**默认启用**。共享 AAR consumer 规则与客户端均保留 `ai.onnxruntime.**` 的类和成员，避免 R8 删除 JNI 通过名称调用的 `NodeInfo`/`TensorInfo` 等构造方法；仅保留类名不足以防止原生崩溃。规则依据 [ORT Android 官方文档](https://onnxruntime.ai/docs/build/android.html)。默认分别生成四种 ABI 的 APK，避免所有原生库塞入一个安装包；`-PquickAddInferenceAbis=arm64-v8a` 可仅生成 arm64，`-PquickAddUniversalApk=true` 才额外生成通用包。`-PquickAddInferenceRuntime=false` 是明确的规则模式诊断构建，不能视为支持在线模型的版本。模型在线下载，但 Android 引擎必须随 App 打包；实际模型冻结后再按算子裁剪运行时。此前无引擎 24.09 MB 包为规则模式验证包。

## iOS

通过 Kotlin/Native 系统 CoreML 绑定读取已编译 `.mlmodelc` 目录，允许 Core ML 自行选择 compute units。下载协调器可在后台将单文件 `.mlmodel` 编译为持久缓存中的 `.mlmodelc`，不在实时输入路径中编译。MLMultiArray 和 feature provider 在适配器内部转换；NSError 传成共享异常，公共 suspend 方法标注 @Throws。

## HarmonyOS

使用系统 `libmindspore_lite_ndk.so`，无需复制其 SDK 桩库到 App。cinterop 读取 SDK mindspore/*.h；可传 `-PharmonyNativeSysroot=/path/to/native/sysroot`，默认从 DEVECO_SDK_HOME/default/openharmony/native/sysroot 获取，或使用本机 DevEco 路径。

按照官方 Native 流程创建 context、CPU device、model，加载 .ms（OH_AI_MODELTYPE_MINDIR），写入模型拥有的输入张量，执行 OH_AI_ModelPredict，复制命名输出。device info 由 context 管理；先销毁 model，再销毁 context。初版 CPU、2 线程、关闭 FP16；NNRt/CPU 异构配置待模型算子和真机验证后启用。

官方来源经用户指定 HarmonyOS Developer Knowledge MCP 检索：
- [使用 MindSpore Lite 进行模型推理（C/C++）](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/mindspore-lite-guidelines)
- [模型转换](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/mindspore-lite-converter-guidelines)

## 验证范围

共享层用假后端测试状态、校验、失败恢复和取消；另用真实 ONNX、Core ML 与 ARM64 Linux MindSpore 输出运行生产 KMP 解码，v1.1 共 19 条样例、57 条输出检查通过，并检查实际习惯类型、标题和提醒时间。iOS/鸿蒙/Android 客户端构建通过。实际手机上下载 UI、延迟与兼容性尚未验收；macOS 的 KMP 后端仍显式不可用，真实 Core ML 输出由系统模型运行后送入 KMP。

## 在线模型与规则降级

`QuickAddModelSource` 要求 HTTPS 地址、版本标识、准确字节数和 SHA-256。单文件上限 512 MiB；目前支持 Android ONNX、鸿蒙 .ms、iOS .mlmodel。多文件 ONNX、Core ML .mlpackage/压缩包暂不支持。三端已提供真实清单和词表，首次打开 Sheet 下载本平台权重。

`QuickAddModelRepository` 在 App 私有缓存目录中流式写临时文件，核对大小与 SHA-256 后原子提交。读取已有缓存也重新核对，校验通过可离线复用；下载失败清除临时文件。下载包含连接、请求和读取超时，支持 GitHub Releases 到 HTTPS CDN 的重定向，禁止 HTTPS 降级到 HTTP。

`QuickAddDownloadedModel.prepare(source)` 应由后台生命周期任务执行；状态为 RULES_ONLY / PREPARING / READY。下载、编译、加载或推理失败会回到规则模式；取消原样传播。运行时不存在或 source=null 时不下载。关闭前需要取消并 join 准备任务。

`QuickAddLocalRecognizer.recognize()` 先构造规则提案，准备模型期间立即返回规则结果；仅 READY 且版本匹配时尝试端侧推理。`QuickAddSemanticCodec` 负责模型分词和提案解码，未提供 codec 或解码不确定也返回规则提案。三端 UI 已接入 `QuickAddModelSession`：读取随包清单，显示下载百分比/加载/失败/重试，成功自动尝试模型，使用输入版本和编辑保护丢弃旧结果。首版真实清单已入包；清单缺失的诊断构建仍显示未配置并使用规则/AI。v1 字符 BIO 编解码与模型交付契约见 [模型交付说明](QUICK_ADD_MODEL_DELIVERY.md)。

此前移除运行时的规则模式 Android Release APK 实测 24,090,024 字节（24.09 MB），保留四种 ABI，无 ONNX 原生库。相对先前实测 164.46 MB 包减少约 85.35%。生成 app/release/app-release-small-unsigned.apk，需使用原发布密钥签名。

## v2 模型拒绝策略

QuickAddLocalRecognizer 将编码限制/语义解码拒绝返回为 source=MODEL、recognitionState=REJECTED、ready=false，而不是规则结果。仅未准备或运行失败走 QuickAddRecognitionPolicy.fallback；模型忙返回 PENDING。UI 在等待期间禁用提交，AI 由用户触发。旧 manifest 默认为 zh，新清单 codec.locale 支持 zh/en。

## v3 预训练模型协议

清单 `codec.wordPiece` 为可选字段：缺省保持旧字符模型；存在时包含按模型 ID 顺序排列的 WordPiece 词表、大小写规范化开关与预生成的单字符去重音表。共享 KMP 执行 Basic/WordPiece 分词，保留原始 Unicode 字符偏移，加入 CLS/SEP，并将模型 token BIO 输出投影回原始字符，再使用同一提案解码与拒绝策略。两种模型都保留 INT32 [1,128] 输入及既有输出名称/形状。

上下文不截断，未知字符比例、特殊的规范化扩展及非法 BIO 均拒绝。Android 使用 ONNX Runtime，鸿蒙使用系统 MindSpore Lite；iOS 使用 Core ML 的 CPU 浮点路径，避免 GPU 半精度改变置信度与接受判断。训练工程逐条比较 Hugging Face 原始 tokenizer 与 KMP 的完整 ID/mask，并用三种运行时的真实输出比较完整 KMP 提案。

权重仍在线下载，Sheet 显示进度，模型就绪后的语义拒绝不会回退规则。预训练模型来源、训练分区、冻结集与各轮指标保存在独立训练工程的 `docs/pretrained-v3-delivery.md`，合成评估不代表真实用户效果或真机延迟。

## Android APK 35–40 MB 预算（2026-10-10）

保留完整ONNX Runtime与v2在线模型，通过客户端 `packaging.jniLibs.useLegacyPackaging=true` 压缩APK中的原生库。arm64 release实测从58,538,899降至37,115,747字节（37.12 MB），减少21,423,152字节。输出 `app/release/app-arm64-v8a-v2-preview-small-unsigned.apk`，仍需原发布密钥签名。

release构建通过；ZIP完整性、全部原生库解压后字节与旧包完全一致、双语模型清单不变、ONNX库仍在、无嵌入模型文件，以及最终二进制Manifest的extractNativeLibs=true均已验证。本轮只改打包，不重复语义测试。

这是下载APK体积优化，未裁剪运行时；安装后会保留解压后的原生库，安装占用不会同比缩小，可能增加。见[Android官方说明](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)。若之后要降低安装占用，再评估按模型算子裁剪ONNX Runtime。
