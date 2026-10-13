## 当前验收版本（2026-10-10）

本轮预训练验收包使用[双语 v3 预发布](https://github.com/DeadlinerStudio/DeadlinerCore/releases/tag/quick-add-v3.0.0-preview)，系统语言 en 选择英文，其他语言暂选中文。中文 ONNX 12.71 MB、英文17.52 MB，均在线下载，不嵌入应用；modelId/SHA 已变更，旧缓存不用于新模型。

模型就绪后的语义拒绝不会回退规则，AI由用户点击触发。共享KMP完成WordPiece及token/字符槽位对齐，iOS Core ML采用CPU浮点路径。整个冻结集与回归集的三格式实际输出及完整生产KMP提案一致，六个公开HTTPS文件已下载核验SHA/大小。

最终冻结构造集：中文1228/1440（85.28%）、英文736/800（92.00%）完整正确提案，各含160条拒绝样例，两组0错误接受。中文达到85%目标，但差6.72点，未达到<=5点目标；没有独立真人金标与三端真机P95，不宣称正式模型门禁通过。完整证据、各轮失败、上游许可记录与训练配方见独立训练工程 docs/pretrained-v3-delivery.md、发布附件 evaluation-evidence.json 与 MODEL_CARD.md。

Android arm64 APK已验证为37,352,123字节（37.35 MB），保留完整ONNX Runtime和JNI名称，无嵌入模型；验收文件 app/release/app-arm64-v8a-v3-preview-small-unsigned.apk，需原发布密钥签名。

以下为旧 v1.1 的历史交付记录，覆盖范围和回退行为已由上述策略替代。

# 在线快速添加模型交付

首版选择自主训练的中文字符卷积模型 `quick-add-zh-v1.1.0`：127,564 参数，联合识别 TASK/HABIT/OTHER 和标题、时间、周期、备注的 BIO 边界。模型从零训练，不使用教师蒸馏或大模型。KMP 统一分词、置信度门限、BIO 解码、日期/周期归一化；三端分别以 ONNX Runtime、Core ML 和系统 MindSpore Lite 推理。

## 权重与下载

用户指定使用公开项目 [GitHub Releases](https://github.com/DeadlinerStudio/DeadlinerCore/releases/tag/quick-add-zh-v1.1.0) 托管。每端单文件权重约 0.5 MB，不打包进 App。清单包含实际大小、SHA-256、固定词表和版本，下载支持 GitHub 的 HTTPS CDN 重定向，禁止降级至 HTTP。三端分别安装同一 `quick-add-model.json`：

- Android：`app/src/main/assets/quick-add-model.json`。
- iOS：`Deadliner/Resources/quick-add-model.json`，主 Bundle 资源。
- 鸿蒙：`entry/src/main/resources/rawfile/quick-add-model.json`。

第一次打开快速添加 Sheet，可见检查缓存、下载百分比、加载和就绪状态；下载失败显示原因和重试，输入继续使用即时规则提案。模型就绪后自动重新识别当前输入，不覆盖用户修改。缓存核验后离线复用，iOS 编译结果也缓存；无需填写 URL 或打开隐藏运行时开关。

## 首版覆盖和限制（历史 v1）

17,000 条合成训练样本，2,500 条同家族随机划分验证样本。三阶段训练共 23 轮（14 轮基础、6 轮字段强化、3 轮周期边界加权），源代码和阶段权重公开可复现。19 条手写验收表达也有部分与训练模板重合，词表包含这些字符。合成集分数不代表真实用户准确率；尚未完成独立人工评测、阈值校准或三端真机 P95。发布附件 `training-report.json` 和 `MODEL_CARD.md` 公开具体成功与失败结果。

首版覆盖普通任务、每日/工作日/星期习惯、时间和备注，并识别否定、疑问、多任务为 OTHER。复杂表达、低置信度、非法 BIO、多标题、未知字符过多和无法归一化的日期/周期继续回退规则或现有 AI 单任务接口，可展开传统添加页。OTHER 提案需确认/AI，不直接保存。

生产门限：意图概率 0.85、前两名差 0.25、所有文本槽位概率 0.9、未知字符比例 0.1。槽位门限根据首批错误提高，仍未经过独立数据校准；高置信度也不保证正确。v1.1 中 `帮我记录后天给爸爸回电话` 的槽位概率仍低于门限，继续回退；`在下周三下午两点归还资料` 的标签与提案现已通过针对性回归。

验收可先输入：`明天下午3点交报告`、`提醒我明天上午9点提交作业`、`帮我把回复邮件安排到后天下午6点`、`每天晚上8点读书`、`每个工作日早上7点跑步`、`交报告，截止到明天，备注：带上电脑`。提案始终由用户确认后保存。

## 可复现流程（历史 v1）

`runscript/quickadd/training_data.py` 生成字符标注样本；`character_model.py` 定义模型；`train_model.py` 训练并导出 ONNX/Core ML；`verify_exports.py` 运行真实模型并生成 KMP/MindSpore 数值校验数据；`create_model_manifest.py` 从实际权重计算清单。

训练环境版本：Python 3.12、PyTorch 2.7.0、NumPy 1.26.4、ONNX 1.18.0、ONNX Runtime 1.22.0、coremltools 8.3.0。MindSpore Lite 2.7.0 官方 converter 将 ONNX 转为 `.ms`。模型和训练脚本采用项目 MIT 许可，不包含用户数据。发布训练源码压缩包可以独立复现，不依赖未提交的应用业务代码。

固定 INT32 `input_ids`、`attention_mask` [1,128]；FLOAT32 `intent_logits` [1,3]，TASK/HABIT/OTHER；FLOAT32 `slot_logits` [1,128,9]，O、B/I TITLE、TIME、REPEAT、NOTE。字符 ID 来自词表，0 PAD、1 UNK，Unicode 补充字符占一个 token，超过上下文不截断。日期归一化仅处理提取的 TIME/REPEAT，不重新解释 TITLE。

## Android 包体积（历史 v1）

默认内置 ONNX Runtime，按 ABI 分包，保留设备覆盖；不默认生成四架构通用 APK。`-PquickAddInferenceAbis=arm64-v8a` 构建手机常用架构；`-PquickAddUniversalApk=true` 另生成通用包。最终包含真实清单的 arm64 未签名 APK 为 58,522,163 字节（58.52 MB），已保留 ORT 原生库。24 MB 的旧诊断包不含引擎，不能作为在线模型交付包。

可继续按 [ONNX Runtime 官方指南](https://onnxruntime.ai/docs/build/custom.html) 裁剪固定算子引擎；当前尚未裁剪。推理 SDK、真实语料评测、量化和真机性能仍需后续优化。

## v1.1 修复范围

任务与习惯共用动作标题池，训练中按周期上下文决定意图；加入数字、点/冒号之间的 ASCII/全角空格增强。新增截图原句和紧凑/中文数字/工作日/否定回归样例；三端提案展示实际 MODEL/LOCAL/AI 来源。原 v1.0 发布保留，客户端使用 v1.1 新清单和内容寻址缓存，不替换旧模型资产。新样例针对已知问题训练并验证，不作为独立真实准确率证明。

## 当前 v3 预训练交付

训练由同级 `DeadlinerQuickAddTraining` 工程管理，使用固定 revision 的中文 UER tiny WWM、英文 BERT tiny 检查点，分别训练业务意图与 BIO 槽位。`pretrained_export` 导出 ONNX/Core ML，官方 MindSpore Lite converter 转为 `.ms`；`benchmark` 以生产 KMP 计算完整提案指标，`parity` 校验各平台实际输出和完整提案一致性。源模型、训练脚本、数据分区、阈值、检查点 SHA 及此前失败轮次均留档。

双语清单使用可选 `codec.wordPiece`，旧字符模型兼容。推理与提案间的 token/字符映射在 KMP 完成，参见[共享推理协议](QUICK_ADD_INFERENCE.md)。模型权重在线下载，不随 APK/HAP/App 打包；Android 保留 ONNX Runtime 和压缩原生库的 ABI 分包，当前预算为 35–40 MB。语义拒绝保持待确认状态，AI 由用户触发。

预训练权重的来源与许可须按上游分别记录，不能沿用首版自行训练模型的 MIT 声明。准确评估、三格式校验和最终安装包大小见训练工程 `docs/pretrained-v3-delivery.md` 与发布附件。
