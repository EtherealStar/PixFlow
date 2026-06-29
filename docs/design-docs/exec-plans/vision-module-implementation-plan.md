# 完整实现 module/vision：VLLM 视觉理解能力层（分析面优先 + 富化作业独立里程碑）

本文是一份活文档。`Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 四个章节必须随工作推进持续更新。

执行本计划时所有架构与边界决策以 [`docs/design-docs/module/vision.md`](../module/vision.md) 为唯一权威。本计划只补充「如何落地」。`PLANS.md` 在仓库根目录，定义 ExecPlan 的格式与自包含原则，本计划必须始终按其规范维护。

## 设计文档关键词定位索引

执行本计划时所有架构与边界决策以 [`docs/design-docs/module/vision.md`](../module/vision.md) 为唯一权威。下列关键词可在该文档内快速定位对应段落：

- 模块定位（VLLM 视觉理解能力层，非循环层）→ 搜 `能力层，不是循环层` 或 `三·一、本期实施拆分`
- 两个能力面边界：分析面 vs 富化作业（阶段 A vs 阶段 B）→ 搜 `三·一` 或 `七、上传期文案抽取`
- 与 imagegen 的关键差异（vision 直接门面 vs imagegen SPI）→ 搜 `与 imagegen 的对称关系` 或 `三·二`
- ArchUnit 14+ 条边界守护（不可 import 的包、不可出现的模型字面量）→ 搜 `三·三` 或 `noModelNameLiterals` 或 `noSwallowedInfraAiErrors`
- VisionImageRef / VisionAnalysisRequest / VisionAssessment / VisionAnalysisResult 数据契约 → 搜 `VisionImageRef` 或 `四、核心抽象`
- 图片预处理管线（降采样/格式归一/EXIF 归正/透明铺底）→ 搜 `五、图片预处理管线` 或 `透明通道铺底`
- VisionPromptBuilder + AssessmentParser JSON 防御性解析 + 降级 rawText → 搜 `九、结构化输出与防御性解析`
- 分析面同步 vs 大批量后台（本期只同步 + 采样）→ 搜 `八、同步与批量边界`
- 富化作业（CopyEnrichmentTopology/Consumer/ProductCopyExtractor/AssetCopyWriteMapper）→ 搜 `七、上传期文案抽取` 或 `CopyEnrichmentConsumer`
- 并发 / 韧性 / 错误归一化（AiErrorCode 透传 + VisionErrorCode 自有 4 条）→ 搜 `十、` 或 `VisionErrorCode`
- 可观测性指标（3 类 + 1 类）→ 搜 `十一、可观测` 或 `pixflow.vision`
- 配置（max-long-edge / images-per-call / enrich.*）→ 搜 `十二、配置`
- 关键风险点（5 条 ArchUnit + 单测守护）→ 搜 `十七、` 或 `只解码一次` 或 `noSwallowedInfraAiErrors`

辅助阅读：[`infra/ai.md`](../infra/ai.md)（`VisionModelClient.call` / 多模态调用 / 全局并发 / 错误源头构造，与 vision 契约见 §十四）、[`infra/storage.md`](../infra/storage.md)（`ObjectStorage.getStream` / `PACKAGES` 桶 / `StorageKeys.packageImage`）、[`infra/image.md`](../infra/image.md)（降采样 / 格式归一 / `ImageCodec` / `ImagePipeline` / alpha 扁平化）、[`infra/mq.md`](../infra/mq.md)（`QueueTopologyBuilder` / 进程内重试 + DLX 延迟 + 终态 DLQ / `ConsumerErrorHandler` SPI）、[`module/file.md`](../module/file.md)（`asset_image` / `asset_copy` 数据底座 / MQ 上传终态消息发布）、[`module/commerce.md`](../module/commerce.md)（`asset_copy` 是 `search`/`read` 数据底座的语义对齐）、[`harness/tools.md`](../harness/tools.md)（§3.3 `agent` 工具 / §十三 可见集合单一来源 / 工具集零令牌）、[`common.md`](../base/common.md)（`ErrorCode` 目录 / `PixFlowException` / `Sanitizer`）、[`exec-plans/imagegen-module-implementation-plan.md`](./imagegen-module-implementation-plan.md)（与本计划对称的 Wave 3 同期模块，SPI 倒置 / Canonical Form / Sentinel 装配边界 / 错误码并入 common 模式可参考）。

## Purpose / Big Picture

完成本计划后 PixFlow 将拥有一个生产级的 **VLLM 视觉理解能力层（`module/vision`）**。它的最终效果是：在用户对话中 agent 透过 `agent(type=vision)` 调用 `VisionService.analyze(...)`，把一组 MinIO 图片引用与看图问题交给 Qwen-VL（或同等多模态）模型，将模型的自由文本输出防御性解析为 `VisionAssessment`（构图 / 卖点 / 卖点要点 / 视觉问题 / 与描述相符判定 / 自评置信度 / rawText 兜底），回填到 agent 工具的 `ToolExecutionResult`；agent 据此判断「主图是否清晰」、「卖点是否到位」、「图文是否相符」并继续决策。同时，上传完成后 vision 模块经 MQ 消费 file 投递的富化消息，按 SKU 把同商品成员图发 VLLM 抽取商品名 / 关键词 / 描述草案，**仅 doc 文案未覆盖的字段** 才写入 `asset_copy`（doc 解析的 CSV/XLSX 文案是权威来源，VLLM 只补缺口），从而让 `search`/`read` 工具在没有 doc 文案的 SKU 上也能拿到可读的描述。

本计划不是 MVP。实现必须覆盖完整的图片预处理管线（降采样到长边 1280px、统一 JPEG、含透明铺白底、EXIF 归正、单图大小上限保护、单图解码失败隔离、单次张数采样 `MAIN_FIRST`）、防御性 JSON 解析（剥代码块、字段缺失可空、解析失败 `parseDegraded=true` 但**不**抛异常）、`infra/ai` 错误全量透传（`MODEL_RATE_LIMITED`/`MODEL_CONTEXT_LIMIT`/`MODEL_NETWORK_ERROR`/`MODEL_PROVIDER_ERROR` 原样上抛、绝不吞）、`VisionErrorCode` 4 条入 `common` 启动期聚合目录（`VISION_NO_DECODABLE_IMAGE` / `VISION_IMAGE_RESOLVE_FAILED` / `VISION_IMAGE_TOO_LARGE` / `VISION_EMPTY_REQUEST`，`VISION_*` 前缀守全局唯一）、ArchUnit 14+ 条边界守护（vision 包根不可 import `infra.ai.chat` / `harness.tools` / `harness.loop` / `agent` / `module.dag` / `module.task` / `module.conversation` / `module.rubrics` / `module.file` / mybatis-plus / jdbc / redisson / messaging / 线程池 / `@Scheduled`，源码字面量禁止出现 `qwen-vl-max` / `dashscope` / `openai` / `anthropic` 等任何具体模型与供应商关键字）、真实 `infra/storage` + 真实 `infra/image` + fake `VisionModelClient` 单测集成，以及独立的富化作业 Testcontainers 集成测试（MinIO + RabbitMQ + MySQL + fake 模型 + fake 图像管线）。

本计划显式拆为两个阶段：阶段 A「分析面」（本期优先上线，让 Wave 5 `agent(type=vision)` 立即可接）与阶段 B「富化作业」（独立里程碑，集成测试独立验证节奏，避免拖累阶段 A 的 CI 反馈）。两者通过 `VisionService.analyze(...)` 复用同一份图片预处理能力。

## Progress

- [x] (2026-06-29 14:05+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`module-dependency-dag-plan.md`、`module/vision.md`、`infra/ai.md`、`infra/storage.md`、`infra/image.md`、`infra/mq.md`、`module/file.md`、`module/commerce.md`、`harness/tools.md`、`exec-plans/imagegen-module-implementation-plan.md`，确认 vision 是 Wave 3 能力层，阶段 A 依赖 `infra/ai + infra/storage + infra/image + common`，阶段 B 再增 `infra/mq` 与 MyBatis-Plus，被 Wave 5 `agent`（`harness/tools` 收集）经 `VisionService.analyze` 调用，阶段 B 富化作业经 `module/file` 的 MQ 解耦。
- [x] (2026-06-29 14:10+08:00) 与用户对齐 4 项生产级决策：① vision 模块本期拆为阶段 A「分析面优先」与阶段 B「富化作业独立」两个独立里程碑；② 图片预处理走 `infra/image` 的 `ImagePipeline`，vision 模块仅允许 `ResizeOp` 与 `ConvertFormatOp` 两个本地像素操作，不可使用 `WatermarkOp` / `ComposeOp` / `SetBackgroundOp`；③ `VisionErrorCode` 4 条入 common 启动期聚合目录（`VISION_*` 命名空间守全局唯一），阶段 B 再加 2 条 `VISION_ENRICH_*`；④ ArchUnit 14+ 条硬守护（不可 import `infra.ai.chat` 等、不可出现模型/供应商字面量）。
- [x] (2026-06-29 14:15+08:00) 同步更新 `module/vision.md`：扩写 §三·一（阶段 A/B 拆分）、§三·二（关键 SPI 与 Bean 列表）、§三·三（ArchUnit 边界守护）、§十七（关键风险点）；同步更新 `module-dependency-dag-plan.md`：DAG 中移除 `tools → vision`、新增 `storage → vision` / `image → vision` / `mq → vision`，Wave 3 vision 任务清单写明两阶段细节。
- [x] (2026-06-29) 阶段 A 里程碑 1：`pixflow-module-vision` Maven 模块骨架已加入根 `pom.xml` 与 `dependencyManagement`，源码包为 `com.pixflow.module.vision`，`mvn -pl pixflow-module-vision -am compile` 可在不依赖 Spring 完整上下文的情况下通过。
- [x] (2026-06-29) 阶段 A 里程碑 2：`VisionImageRef` / `VisionTaskType` / `VisionAnalysisRequest` / `VisionAssessment` / `VisionAnalysisResult` 与浅层校验已实现，相关单测覆盖枚举、可空字段与语义约束。
- [x] (2026-06-29) 阶段 A 里程碑 3：`VisionImageResolver` + `VisionImagePreprocessor` 已实现，降采样、格式归一、透明底铺白与单图失败隔离均已纳入测试。
- [x] (2026-06-29) 阶段 A 里程碑 4：`VisionPromptBuilder` + `AssessmentParser` 已实现，代码块剥离、字段缺失容错与解析降级路径均已覆盖。
- [x] (2026-06-29) 阶段 A 里程碑 5：`VisionService.analyze(VisionAnalysisRequest)` 门面、`MAIN_FIRST` 采样、单图跳过策略与 `AiErrorCode` 透传已实现并通过测试。
- [x] (2026-06-29) 阶段 A 里程碑 6：`VisionErrorCode` 4 条已并入 `common` 启动期聚合目录，`VisionProperties` 三段式配置已落地。
- [x] (2026-06-29) 阶段 A 里程碑 7：`VisionMetrics` 与 `VisionServiceAutoConfiguration` 已完成，且未在 vision 模块中预装配 `VisionToolHandler` / `VisionToolDescriptor`。
- [x] (2026-06-29) 阶段 A 里程碑 8：`VisionArchitectureTest` 已落地，边界守护覆盖 forbidden import、模型/供应商字面量与异常吞噬模式。
- [x] (2026-06-29) 阶段 A 里程碑 9：`VisionServiceAutoConfigurationSentinelTest` 已实现，`mvn -pl pixflow-module-vision -am test` 通过，`mvn -pl pixflow-app -am compile` 通过；`pixflow-app` 全量测试尚未在本次变更中补跑确认。
- [x] (2026-06-29) 阶段 B 里程碑 1：`CopyEnrichmentTopology` 与 `CopyEnrichmentErrorHandler` 的骨架已实现，队列组与消费错误分流已落地。
- [x] (2026-06-29) 阶段 B 里程碑 2：`CopyEnrichmentConsumer`、`ProductCopyExtractor`、`AssetCopyWriteMapper` 的骨架已实现，按 `packageId` / `skuId` 的富化路径已接上分析面复用。
- [x] (2026-06-29) 阶段 B 里程碑 3：`VisionEnrichErrorCode`、`fill-policy` 与消费参数配置已补齐，富化面默认装配的单测也已覆盖。
- [ ] (里程碑 13 待办) 阶段 B 里程碑 4：Testcontainers 集成测试（MinIO + RabbitMQ + MySQL）跑通「file 解压终态 → 投递富化消息 → vision 消费 → 按 SKU 抽取 → gap-fill 写 `asset_copy` → 重投幂等」全链路；doc 文案已在 SKU 不被覆盖；重投同 `packageId` 不产生重复行。
- [ ] (里程碑 14 待办) 阶段 B 里程碑 5：装配回归与端到端验证：`mvn -pl pixflow-app -am test` 全绿；`mvn -pl pixflow-module-vision -am package` 产物含阶段 A 与阶段 B 全部 bean；阶段 B 富化指标 `pixflow.vision.enrich{result=filled|skipped|failed}` 端到端冒烟可观察。

## Surprises & Discoveries

（实施期发现后回填此区）

## Decision Log

（实施期回填决策与理由）

## Outcomes & Retrospective

（每完成一个里程碑或整份计划后回填此区）

## Context and Orientation

### 当前状态

`docs/design-docs/module/vision.md` 已完成生产级细化设计。本轮（2026-06-29）经 4 项设计决策扩写——① 阶段 A「分析面优先」与阶段 B「富化作业独立」拆为两个里程碑（避免富化集成测试拖累阶段 A 的 CI 反馈节奏），② 图片预处理走 `infra/image.ImagePipeline` 仅允许 `ResizeOp` + `ConvertFormatOp` 两个本地像素操作，③ `VisionErrorCode` 4 条入 common 启动期聚合目录并守 `VISION_*` 全局命名空间唯一，④ ArchUnit 14+ 条硬守护覆盖 import 边界与模型/供应商字面量。所有扩写均已写回 `vision.md §三·一 / §三·二 / §三·三 / §十七` 与 `module-dependency-dag-plan.md` 的 DAG 边、Wave 3 任务清单、Revision Notes。

仓库根目录 `pom.xml` 已有 `pixflow-common`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image`、`pixflow-infra-mq`、`pixflow-module-file`、`pixflow-module-commerce` 等依赖可被 `pixflow-module-vision` 引用。`pixflow-module-imagegen` 已先实现并完成 5 个里程碑（82 个单测全绿），本计划在 imagegen 的「SPI 倒置 / Canonical Form / Sentinel 装配边界 / 错误码并入 common / ArchUnit 边界守护」三套约定上保持同构。`pixflow-module-dag` 也已实现完成。`harness/tools` 已实现 ToolRegistry 倒置接缝，Wave 5 的 `agent(type=vision)` 接线在阶段 A 上线后即可对接。

### 关键术语

- **能力层**：本模块对外只暴露「一次（或几次）VLLM 视觉调用 → 结构化结果」能力。它**没有** think-act-observe 循环、**没有** child runtime、**没有** 工具循环。`agent(type=vision)` 在 Wave 5 由 `harness/tools` 的 `SubagentRunner` 包装 vision 的能力，**不**让 vision 依赖 `harness/loop`。
- **`VisionAssessment`**：阶段 A 的核心结果 record。稳定字段：`composition` / `backgroundClean` / `hasWatermark` / `watermarkPosition` / `matchesDescription` / `mismatchReason` / `sellingPoints` / `issues` / `confidence` / `rawText`。所有字段可空，解析降级时仅保证 `rawText` + 低 `confidence`。
- **`parseDegraded`**：当 `AssessmentParser` 拿不到合法 JSON 时为 `true`，`rawText` 是唯一可用内容。**不**抛异常——这是设计决策，让调用方决定是直接用 rawText 还是重发。
- **`Agent-call 路径`**（`VisionService.analyze` 在 turn 内同步调）：agent 在 turn 内调用，量小同步（Qwen-VL 量小，秒级返回）。
- **富化作业**（`CopyEnrichmentConsumer`）：MQ 驱动，整包一次一条消息，整包内 SKU 有界并发调 `VisionService.analyze`，`VisionModelClient` 全局并发由 `infra/ai.GlobalConcurrencyLimiter` 封顶（默认 8）。
- **`MAIN_FIRST` 采样**：当 `images` 总数超过 `images-per-call`（默认 6）时，按 `viewId` 排序保留第一个 + 后续按顺序补足；`imagesSent` 必须等于实际入模型张数。
- **`GAP_ONLY` vs `SKIP_IF_ANY`**（富化作业 `fill-policy`）：前者「该 SKU 无文案条目 / 字段为空才补」；后者「该 SKU 已有任意文案字段则整 SKU 跳过」。默认 `GAP_ONLY`，doc 文案优先。
- **PROCESS_THEN_ACK**（富化作业消费模式）：跑完整包富化才 ack。**与 file 的 upload 不一样**——file 是 process-then-ack（已经在 Wave 2 实现），但 task 的任务分发是 ack-then-process（在 Wave 4 才到）。此处与 file 同款逻辑（一个消息一个完整包工作单元，崩溃后靠 MQ 重投续跑）。
- **`VisionErrorCode`**：阶段 A 4 条 / 阶段 B 2 条，全部 `implements common.ErrorCode`，全部进入 common 启动期聚合目录的「code 唯一性 + i18n 齐全 + category 非空」校验。
- **`$VISION_*$` 命名空间**：本模块全部 `code()` 字符串前缀为 `VISION_` 与 `VISION_ENRICH_`；common 启动期聚合测试断言这两个前缀只允许出现在 `com.pixflow.module.vision..` 包下，防止 commerce / dag / file / rubrics 等未来 PR 误用。
- **`infra.ai.chat..` 不可 import**：vision 必须只看到 `infra/ai` 的 `vision.VisionModelClient` 与 `vision.VisionRequest`，**不能**直接 import `infra/ai/chat/ChatMessage` 或 `ChatRequest`。这是 ArchUnit 硬约束。

### 模块结构与依赖位置

源码包：`com.pixflow.module.vision`。Maven 模块：`pixflow-module-vision`（需加入根 `pom.xml` `<modules>`）。

```
module/vision/
├── VisionService.java                       # 对外门面：analyze(...)（分析面）
├── analyze/
│   ├── VisionAnalysisRequest.java           # 图片引用 + 问题 + 任务/SKU 上下文（record + 浅校验）
│   ├── VisionAnalysisResult.java            # assessment + parseDegraded + usage + imagesSent（record）
│   ├── VisionAssessment.java                # 结构化视觉评估（record，10 字段全可空）
│   ├── VisionImageRef.java                  # 图片引用（ObjectRef + skuId + viewId + hintLabel）
│   ├── VisionTaskType.java                  # enum DESCRIBE / SELLING_POINTS / MATCH_DESCRIPTION / FREEFORM
│   ├── VisionPromptBuilder.java             # 看图任务 system/user prompt 模板
│   └── AssessmentParser.java                # 防御性 JSON 解析 + 降级 rawText
├── image/
│   ├── VisionImageResolver.java             # 引用 → 字节（infra/storage）
│   └── VisionImagePreprocessor.java         # 降采样 + 格式归一（infra/image）→ infra/ai 图片 part
├── error/
│   ├── VisionErrorCode.java                 # 4 条 enum implements common.ErrorCode（阶段 A）
│   └── VisionEnrichErrorCode.java           # 2 条 enum implements common.ErrorCode（阶段 B）
├── metrics/
│   └── VisionMetrics.java                   # 3 类 Micrometer 指标（阶段 A 落地 2 类，第 3 类 class 先建）
└── config/
    ├── VisionProperties.java                # @ConfigurationProperties(pixflow.vision)
    ├── VisionServiceAutoConfiguration.java  # 装配 analyze 面 + image 子包；不装配 VisionToolHandler/Descriptor
    └── architecture/
        └── VisionArchitectureTest.java      # ArchUnit 14+ 条边界守护
```

阶段 B 增量文件（在 `pixflow-module-vision/src/main/java/com/pixflow/module/vision/enrich/` 下）：

```
module/vision/enrich/
├── CopyEnrichmentTopology.java              # QueueTopologyBuilder("pixflow.vision") 声明队列组
├── CopyEnrichmentConsumer.java              # @RabbitListener process-then-ack
├── CopyEnrichmentErrorHandler.java          # 实现 infra/mq.ConsumerErrorHandler
├── CopyEnrichmentMessage.java               # 消息体（仅 packageId）
├── ProductCopyExtractor.java                # extractProductCopy(imageRefs, ctx) → ProductCopyDraft（复用 VisionService）
├── ProductCopyDraft.java                    # record(skuId, productName, keywords, description, confidence)
├── AssetCopyWriteMapper.java                # MyBatis-Plus：按 (package_id, sku_id) upsert
└── FillPolicy.java                          # enum GAP_ONLY / SKIP_IF_ANY
```

依赖方向（实现期严格遵守，违反则单元测试用 ArchUnit 守护）：

```
module/vision ──► infra/ai          (VisionModelClient.call / VisionRequest / ChatMessage from vision package only)
module/vision ──► infra/storage     (VisionImageRef.object → ObjectStorage.getStream 取字节；阶段 B 查 asset_image)
module/vision ──► infra/image       (ImagePipeline: ResizeOp + ConvertFormatOp + EncodeSpec)
module/vision ──► infra/mq          (阶段 B 仅：QueueTopology / ConsumerErrorHandler / @RabbitListener)
module/vision ──► common            (PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer)
module/vision ──► MyBatis-Plus      (阶段 B 仅：AssetCopyWriteMapper)

agent (Wave 5) ───► module/vision   (SubagentRunner type=vision 调 VisionService.analyze)
```

反向硬约束（ArchUnit 测试断言）：`com.pixflow.module.vision..` **不依赖** `infra/ai.chat..` / `harness.tools` / `harness.loop` / `harness.session` / `agent` / `module.dag` / `module.task` / `module.conversation` / `module.rubrics` / `module.file`（编译层） / MyBatis-Plus（阶段 A，阶段 B 放开本模块内使用）/ JDBC / Redisson / `SimpMessagingTemplate` / `java.util.concurrent.Executors` / `@Scheduled`。源码字面量禁止出现 `qwen-vl-max` / `qwen-vl-plus` / `qwen-vl` / `QwenVL` / `gpt-4o` / `gpt-4-vision` / `claude-3-opus` / `claude-3-sonnet` / `dashscope` / `openai` / `anthropic` 等任何具体模型与供应商关键字。

## Plan of Work

按 14 个里程碑推进，阶段 A 包含里程碑 1-9，阶段 B 包含里程碑 10-14。每个里程碑产出可独立验证的中间状态：

### 阶段 A：分析面（本期优先）

**目标**：`VisionService.analyze(...)` 在 fake `infra/ai` + fake `infra/storage` + fake `infra/image` 下完整跑通，`errorPassthrough` 单测锁死 infra/ai 异常透传；4 条 `VisionErrorCode` 入 common 目录聚合；ArchUnit 14+ 条全绿；Sentinel 装配开关验证 `VisionService` 始终暴露、`VisionToolHandler` 默认不暴露。

### 里程碑 1：模块骨架 + ArchUnit 守护（最优先、最易测、零业务依赖）

创建 Maven 模块骨架，加 `pixflow-common` + `pixflow-infra-ai` + `pixflow-infra-storage` + `pixflow-infra-image` 依赖。**先不上任何业务代码**，先把 ArchUnit 守护测试写出来，作为后续 PR 的「编译期硬约束」。预期：`mvn test` 末尾空跑绿，`mvn compile` 不依赖 Spring。

### 里程碑 2：数据契约 record + 浅校验

实现 `VisionTaskType` enum、`VisionImageRef` record、`VisionAnalysisRequest` record（含 `validate()` 浅校验）、`VisionAnalysisResult` record、`VisionAssessment` record（10 字段全可空，符合 design §4.3）、`VisionAnalysisRequestValidator` 纯函数。8 个单测覆盖枚举/可空/字段语义/浅校验失败。

### 里程碑 3：图片预处理管线

实现 `VisionImageResolver`（`infra/storage.ObjectStorage.getStream` + `stat` 走超 `max-image-bytes` 拦截）+ `VisionImagePreprocessor`（走 `infra/image.ImageCodec.decode` 一次 → `ImagePipeline.run` 链式 `[ResizeOp(max-long-edge=1280, mode=FIT, allow-upscale=false), ConvertFormatOp(target=jpeg, quality=0.85)]` → `encode` 一次；`EncodeSpec(flattenBackground=WHITE)`）。单测覆盖：

- 正常 JPEG/PNG/WebP → JPEG 输出。
- 单图超 `max-image-bytes=10MiB` → 该图跳过且记 `issues=too_large`，其余图继续。
- 单图 decode 失败（损坏图）→ 该图跳过且记 `issues=decode_failed`，其余图继续。
- 含 alpha 的 PNG → 输出 JPEG 是白底铺底（采样四角像素非黑）。
- **关键守护**：用计数桩 fake `ImageCodec`，断言 `decode()` 仅调 1 次、`encode()` 仅调 1 次。

### 里程碑 4：prompt + JSON 防御性解析

实现 `VisionPromptBuilder`（`taskType` 不同 → 不同的 system 模板，但**全部**强制模型按 `VisionAssessment` 字段输出 JSON；user message 含问题 + 图片引用元数据 `<skuId>_<viewId>: <hintLabel>`）+ `AssessmentParser`（用 Jackson）：

- 剥离 ```json ... ``` 代码块包裹。
- 剥离多余前后文本（保留最外层 `{...}` 块）。
- 字段缺失置空（不是抛异常）。
- 完全非 JSON → `parseDegraded=true` + 仅填 `rawText` + `confidence=0.2`，**不**抛异常。
- 部分字段为空对象 → 把空字段赋默认值。

10 个单测覆盖代码块/多余文本/字段缺失/非法 JSON/混合大小写键名/置信度低值兜底。

### 里程碑 5：VisionService.analyze 门面 + 采样 + 错误透传

实现 `VisionService.analyze(VisionAnalysisRequest)` 完整管线：

1. 浅校验（空 `images` → 抛 `VISION_EMPTY_REQUEST`）。
2. 按 `MAIN_FIRST` 采样：先按 `viewId` 排序（缺 `viewId` 的图排到末尾），截前 `images-per-call` 张，记 `imagesSent`。
3. 逐图预处理（调 `VisionImageResolver` + `VisionImagePreprocessor`）→ 失败的图跳过并记 `issues`，其余继续；全部失败抛 `VISION_NO_DECODABLE_IMAGE`。
4. `VisionPromptBuilder` 组装 messages（含图片 part）。
5. 调 `VisionModelClient.call(VisionRequest)`（**try-with-resources 取 GlobalConcurrencyLimiter 许可由 `infra/ai` 自管**，vision 内部不开线程池）。
6. `AssessmentParser` 解析响应 → 失败降级 `parseDegraded=true`。
7. 返回 `VisionAnalysisResult(assessment, parseDegraded, usage, imagesSent)`。

**关键守护**：`errorPassthrough_throwsInfraAiExceptionUnchanged` 注入 fake `VisionModelClient` 抛 `PixFlowException(MODEL_RATE_LIMITED, RATE_LIMIT, RETRY, retryAfter)`，断言 `analyze()` 上抛**同一实例**（`assertSame`）。同理 `MODEL_CONTEXT_LIMIT`、`MODEL_NETWORK_ERROR`、`MODEL_PROVIDER_ERROR`。fake `infra/image` 抛 `ImageProcessingException(SOURCE_TOO_LARGE)` → 走 vision 自有错误码路径（不是透传）。

### 里程碑 6：VisionErrorCode 4 条 + 启动期聚合

实现 `VisionErrorCode` enum：

- `VISION_NO_DECODABLE_IMAGE`（category=VALIDATION, recovery=TERMINATE）：本次请求所有图片均无法解码。
- `VISION_IMAGE_RESOLVE_FAILED`（category=DEPENDENCY, recovery=RETRY）：MinIO 取图失败（可重试）。
- `VISION_IMAGE_TOO_LARGE`（category=VALIDATION, recovery=SKIP）：单图超大小上限（逐图跳过）。
- `VISION_EMPTY_REQUEST`（category=VALIDATION, recovery=TERMINATE）：未提供任何图片引用。

并入 `common` 启动期聚合测试（与 imagegen 的 10 条 + dag 的 16 条同款并入），断言 code 唯一、i18n 齐全、category 非空。`VISION_*` 前缀守全局唯一（在 common 聚合目录断言 `VISION_*` 只属于 `com.pixflow.module.vision..` 包）。

### 里程碑 7：Metrics + Properties + 装配

`VisionProperties` `@ConfigurationProperties(prefix="pixflow.vision")`：

- `image.max-long-edge=1280` / `image.output-format=jpeg` / `image.jpeg-quality=0.85` / `image.max-image-bytes=10MiB` / `image.transparent-background=WHITE`。
- `analyze.images-per-call=6` / `analyze.sampling=MAIN_FIRST`（enum：MAIN_FIRST/HEAD）。
- `enrich` 嵌套类（阶段 A 字段保留但默认不启用）：`images-per-sku=2` / `fill-policy=GAP_ONLY` / `consumer-concurrency=2` / `prefetch=1` / `intra-package-parallelism=4` / `expose=false`（Sentinel 开关）。

`VisionMetrics` 提供 3 类 Micrometer 指标（阶段 A 落地 `analyze` 与 `images` 两类调用，enrich 类方法 pre-stub 但不收集）：

- `pixflow.vision.analyze{taskType, parse=ok|degraded}`（Counter）。
- `pixflow.vision.images{stage=received|sent|skipped}`（Counter）；`received` = `images.size()`、`sent` = `imagesSent`、`skipped` = `received - sent`。
- `pixflow.vision.enrich{result=filled|skipped|failed}`（Counter，阶段 B 落地）。

`VisionServiceAutoConfiguration` 装配：`VisionImageResolver` / `VisionImagePreprocessor` / `VisionPromptBuilder` / `AssessmentParser` / `VisionMetrics` / `VisionService`。**不**装配 `VisionToolHandler` / `VisionToolDescriptor`（这两个是 Wave 5 `harness/tools` 接线才出现的，本期只预留 class 不实现）。

### 里程碑 8：ArchUnit 边界守护（14+ 条）

落地 `VisionArchitectureTest` 静态扫描规则：

- 不可 import 的包：`com.pixflow.infra.ai.chat..`、`com.pixflow.harness.tools`、`com.pixflow.harness.loop`、`com.pixflow.agent`、`com.pixflow.module.dag`、`com.pixflow.module.task`、`com.pixflow.module.conversation`、`com.pixflow.module.rubrics`、`com.pixflow.module.file`、`com.baomidou.mybatisplus..`（阶段 A）、`org.apache.ibatis..`、`java.sql..`、`org.redisson..`、`org.springframework.messaging.simp..`、`org.springframework.scheduling.annotation.Scheduled`、具体禁止的 executor 类。
- 不可出现的源码字面量：`qwen-vl-max` / `qwen-vl-plus` / `qwen-vl` / `QwenVL` / `gpt-4o` / `gpt-4-vision` / `claude-3-*` / `dashscope` / `openai` / `anthropic`。
- 方法签名守护：`VisionService.analyze` 不得 `catch (PixFlowException)` + `throw new RuntimeException(e)` 这种吞异常模式。
- 日志脱敏守护：`VisionImageResolver` 全文不出现 `log.*(.*bytes` / `log.*(.*imageBytes` / `log.*(.*sourceBytes` 这类可能泄露字节长度+SKU 的 debug 模式（即使有也得经 `Sanitizer`）。

> ArchUnit 的 `ContainTextCondition` 用 `freeze()` 注册为冻结规则——未来新增违反规则的代码会在该单测里失败。

### 里程碑 9：Sentinel 单测 + 阶段 A 总收

`VisionServiceAutoConfigurationSentinelTest` 用 `ApplicationContextRunner` + 自行 stub `VisionModelClient` / `ObjectStorage` / `ImagePipeline` Bean（不依赖真实 Spring Boot 启动与外部依赖）：

- 默认配置 → `ctx.getBean(VisionService.class)` 不抛；`ctx.getBean(VisionToolHandler.class)` 抛 `NoSuchBeanDefinitionException`（维护 Wave 5 边界）。
- `pixflow.vision.enrich.expose=true` → `ctx.getBean(CopyEnrichmentConsumer.class)` 抛 `NoSuchBeanDefinitionException`（阶段 A 不装配富化作业，阶段 B 才装）。

`mvn -pl pixflow-module-vision -am test` 全绿（阶段 A 阶段约 50-70 个单测）。`mvn -pl pixflow-app -am test` 不退化（仅在 pixflow-app/pom.xml 加 `pixflow-module-vision` 依赖后做）。

### 阶段 B：富化作业（独立里程碑）

**目标**：消费 `module/file` 在解压终态投递的 `CopyEnrichmentMessage{packageId}`，按 SKU 把同商品成员图发 VLLM 抽取商品名 / 关键词 / 描述草案，**仅 doc 文案未覆盖的字段**才写入 `asset_copy`。端到端在 Testcontainers MinIO + RabbitMQ + MySQL 上跑通。

### 里程碑 10：MQ 拓扑 + 错误处理 SPI

`CopyEnrichmentTopology`（`QueueTopologyBuilder("pixflow.vision")`）：

- 主交换机 `pixflow.vision`（direct）/ 主队列 `pixflow.vision.q`（绑定 `vision.copy_enrich`）/ DLX `pixflow.vision.dlx` / 延迟重试队列 `pixflow.vision.retry.q`（per-message TTL）/ 终态死信队列 `pixflow.vision.dlq`。
- 全部 durable + 消息 persistent + 启动期幂等声明。

`CopyEnrichmentErrorHandler` 实现 `infra/mq.ConsumerErrorHandler.onError(envelope, error, retryCount)`：按 `error.recovery` 给 `Retry(backoff)` / `DeadLetter` / `AckDrop`。VisionErrorCode 4 条 + VisionEnrichErrorCode 2 条 + AiErrorCode 全量的映射表。

### 里程碑 11：Consumer + Extractor + Mapper

`CopyEnrichmentConsumer.handle(CopyEnrichmentMessage{packageId})`：

1. 查 `asset_image` 按 `sku_id` 聚合（`SELECT sku_id, image_id, minio_key FROM asset_image WHERE package_id = ?`，按 `sku_id` 分组）。
2. 对每个有图的 SKU：调 `ProductCopyExtractor.extractProductCopy(List<VisionImageRef>, ctx)`（**复用** `VisionService.analyze(...)`，prompt 切到 `VisionTaskType.SELLING_POINTS`，但解析路径走专门的 `ProductCopyParser`，把 `sellingPoints` 重新组合成 `keywords` 与 `description`）。
3. `AssetCopyWriteMapper`：按 `fill-policy` 判定：
   - `GAP_ONLY`：`SELECT product_name, keywords, description FROM asset_copy WHERE package_id=? AND sku_id=?` → 仅 insert/update 空字段。
   - `SKIP_IF_ANY`：若已有任意字段非空，整 SKU 跳过 + `metrics.enrich{result=skipped}`。
4. `INSERT ... ON DUPLICATE KEY UPDATE` 按 `(package_id, sku_id)` 幂等 upsert（同 package 重投不重复）。
5. 单 SKU 抽取失败（如 VLLM 抖动）→ 跳到下一 SKU，**不**阻断整包 ack；记录 `metrics.enrich{result=failed}`。
6. 整包成功 → ack；整包有未处理失败的 SKU 也 ack（部分成功语义）。

### 里程碑 12：VisionEnrichErrorCode 2 条 + 配置 + Sentinel

实现 `VisionEnrichErrorCode` enum：

- `VISION_COPY_EXTRACTION_FAILED`（category=DEPENDENCY, recovery=RETRY）：VLLM 单 SKU 抽取失败。
- `VISION_FILL_POLICY_REJECTED`（category=BUSINESS_RULE, recovery=SKIP）：`SKIP_IF_ANY` 命中，整 SKU 跳过。

并入 common 启动期聚合。`enrich.fill-policy=GAP_ONLY` / `enrich.consumer-concurrency=2` / `enrich.prefetch=1` / `enrich.intra-package-parallelism=4` / `enrich.consumer-timeout=30m` 落地。Sentinel 单测断言 `CopyEnrichmentConsumer` 默认装配（与阶段 A 不同——阶段 A `expose=false`，阶段 B 默认就装）。

### 里程碑 13：Testcontainers 集成测试（MinIO + RabbitMQ + MySQL）

按 `infra/storage` 既有 profile 跳过策略（`windows-docker-npipe` / 环境变量 `DOCKER_HOST`）处理 Docker 不可用：

- Testcontainers MinIO 跑真实 put → 真实对象 → 真实消费端的取字节。
- Testcontainers RabbitMQ 跑真实的「发布 → 拓扑声明 → 消费者接管 → ack」全链路。
- Testcontainers MySQL 跑真实的 `asset_image` / `asset_copy` 读写。
- fake `VisionModelClient`（不调真实 DashScope）喂入固定的模型响应。
- fake `ImagePipeline` 走 fake `ImageCodec` 但保留 `infra/image.ImagePipeline` 的真实解码-编码一次行为。

测试用例：

- 全链路：构造「file 上传 → 真实 file 解压 → 富化消息投递」序列（用 `CopyEnrichmentPublisher` fake 替 `module/file` 发布方），vision 消费 → 真实 MinIO 拉图 → fake VLLM 返回 → 真实 MySQL 写 `asset_copy`。
- doc 文案覆盖：先在 `asset_copy` 写入 `("sku-1", "original-name", "k1,k2", "original-desc")` → 富化消息来 → VLLM 想覆盖「original-name」为「新名字」→ 实际 DB 行不变（**GOP_ONLY 路径**）。
- 重投幂等：同 `packageId` 发布 2 次 → `asset_copy` 行数 == 1，最后一次富化覆盖了空字段（不是覆盖全部）。
- 单 SKU 失败隔离：构造 fake `VisionModelClient` 对某 SKU 抛 `MODEL_RATE_LIMITED` → 该 SKU 失败、其余 SKU 成功 → 整包 ack → DB 行数 == 成功的 SKU 数。

### 里程碑 14：装配回归与端到端冒烟

`mvn -pl pixflow-app -am test` 全绿。`mvn -pl pixflow-module-vision -am package` 产物含阶段 A + 阶段 B 全部 bean。端到端冒烟：

- 启动 pixflow-app，验证 `/actuator/prometheus` 暴露 `pixflow.vision.analyze` / `pixflow.vision.images` / `pixflow.vision.enrich` 三类指标。
- `bean list` 验证 `VisionService` / `CopyEnrichmentConsumer` 都已注册，`VisionToolHandler` 未注册。

## Concrete Steps

### 工作目录

所有命令在 `D:\study\PixFlow`（PowerShell）下运行。

### 阶段 A 里程碑 1 命令序列

    mkdir pixflow-module-vision/src/main/java/com/pixflow/module/vision/{analyze,image,error,metrics,config,config/architecture}
    mkdir pixflow-module-vision/src/test/java/com/pixflow/module/vision/{analyze,image,error,metrics,config,config/architecture}

    # 写入 pom.xml（参考 pixflow-module-imagegen/pom.xml 的依赖结构，加上 infra/image）
    # 在根 pom.xml <modules> 加入 <module>pixflow-module-vision</module>

    # 写 ArchUnit 守护测试（先于任何业务代码）
    # 写空 VisionServiceAutoConfiguration 让 Spring 上下文能加载

    # 验证
    mvn -pl pixflow-module-vision -am compile
    mvn -pl pixflow-module-vision test

预期：`mvn compile` 通过；`mvn test` 在 ArchUnit 空跑下绿（守护测试本身通过但还没有违反案例）。

### 阶段 A 里程碑 2 命令序列

    # 写 analyze/* record + validator + 单测

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 8+, Failures: 0, Errors: 0, Skipped: 0`；ArchUnit 守护全绿。

### 阶段 A 里程碑 3 命令序列

    mkdir pixflow-module-vision/src/test/java/com/pixflow/module/vision/image

    # 写 image/VisionImageResolver + VisionImagePreprocessor + 单测（含计数桩 1+1 编解码守护）

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 18+, Failures: 0, Errors: 0, Skipped: 0`；关键的 `preprocess_decodesOnce_encodesOnce` 测试通过。

### 阶段 A 里程碑 4 命令序列

    # 写 analyze/VisionPromptBuilder + AssessmentParser + 10 个单测

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 30+, Failures: 0, Errors: 0, Skipped: 0`。

### 阶段 A 里程碑 5 命令序列

    # 写 VisionService.analyze（门面 + 采样 + 单图跳过 + 全图失败 throw + VisionModelClient 调 + AssessmentParser 解析 + 返回 result）
    # 写 errorPassthrough_throwsInfraAiExceptionUnchanged 等错误透传单测

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 45+, Failures: 0, Errors: 0, Skipped: 0`；关键路径 fake `VisionModelClient` + fake `infra/storage` + fake `infra/image` 端到端跑通。

### 阶段 A 里程碑 6 命令序列

    # 写 error/VisionErrorCode 4 条 + common 启动期聚合目录断言

    mvn -pl pixflow-module-vision -am test
    mvn -pl pixflow-common -am test

预期：`pixflow-common` 启动期聚合测试绿，4 条 `VISION_*` 全部并入，`VISION_*` 前缀守全局唯一。

### 阶段 A 里程碑 7 命令序列

    mkdir pixflow-module-vision/src/main/java/com/pixflow/module/vision/config/architecture
    mkdir pixflow-module-vision/src/test/java/com/pixflow/module/vision/{metrics,config}

    # 写 config/VisionProperties + metrics/VisionMetrics + config/VisionServiceAutoConfiguration + metrics 单测 + Properties 单测

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 55+, Failures: 0, Errors: 0, Skipped: 0`。

### 阶段 A 里程碑 8 命令序列

    # 写 config/architecture/VisionArchitectureTest（14+ 条 ArchUnit 规则）

    mvn -pl pixflow-module-vision -am test

预期：ArchUnit 14+ 条全绿（任何违反当前代码的规则会以失败形式呈现，但本阶段我们保证不违反）。

### 阶段 A 里程碑 9 命令序列

    # 写 config/VisionServiceAutoConfigurationSentinelTest（ApplicationContextRunner + 自 stub bean）
    # 修改 pixflow-app/pom.xml 加 pixflow-module-vision 依赖

    mvn -pl pixflow-module-vision -am test
    mvn -pl pixflow-app -am test

预期：阶段 A 全部单测全绿（`Tests run: 60+`），`pixflow-app` 装配回归通过。

### 阶段 B 里程碑 10 命令序列

    mkdir pixflow-module-vision/src/main/java/com/pixflow/module/vision/enrich
    mkdir pixflow-module-vision/src/test/java/com/pixflow/module/vision/enrich

    # 写 enrich/CopyEnrichmentTopology + CopyEnrichmentErrorHandler + 单测
    # 写 enrich/CopyEnrichmentMessage（中立 record）

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 70+, Failures: 0, Errors: 0, Skipped: 0`。

### 阶段 B 里程碑 11 命令序列

    # 写 enrich/ProductCopyExtractor（复用 VisionService）+ ProductCopyDraft + AssetCopyWriteMapper（MyBatis-Plus）
    # 写 enrich/FillPolicy enum + CopyEnrichmentConsumer（process-then-ack + 单包内 SKU 并发 + 单 SKU 失败隔离 + 整包 ack）
    # 写假 SQL Profile 跳过 MySQL 真实容器时的单测

    mvn -pl pixflow-module-vision -am test

预期：`Tests run: 80+, Failures: 0, Errors: 0, Skipped: 0`；MyBatis-Plus 仅在 `enrich` 子包内被使用，ArchUnit 不报。

### 阶段 B 里程碑 12 命令序列

    # 写 error/VisionEnrichErrorCode 2 条 + 并入 common 聚合
    # 写 enrich/* 配置键（fill-policy / consumer-concurrency / prefetch / intra-package-parallelism / consumer-timeout）
    # 写 CopyEnrichmentConsumer 默认装配的 Sentinel 单测（与阶段 A 的「默认不装」对称）

    mvn -pl pixflow-module-vision -am test
    mvn -pl pixflow-common -am test

预期：`Tests run: 90+, Failures: 0, Errors: 0, Skipped: 0`；common 启动期聚合测试绿，6 条 `VISION_*` / `VISION_ENRICH_*` 全部并入。

### 阶段 B 里程碑 13 命令序列

    mkdir pixflow-module-vision/src/test/java/com/pixflow/module/vision/integration

    # 写 enrich/CopyEnrichmentMessageTestPublisher（fake file 端发布器）
    # 写集成测试：Testcontainers MinIO + RabbitMQ + MySQL + fake VisionModelClient + 真实 ImagePipeline 跑通全链路
    # 4 个集成用例：全链路 / doc 文案覆盖 / 重投幂等 / 单 SKU 失败隔离

    mvn -pl pixflow-module-vision -am test

预期：集成测试按 env 跳过策略处理；Docker 可用时 `Tests run: 94+, Failures: 0, Errors: 0, Skipped: 0`。

### 阶段 B 里程碑 14 命令序列

    mvn -pl pixflow-app -am test
    mvn -pl pixflow-module-vision -am package
    mvn -pl pixflow-app spring-boot:run

预期：app 装配回归绿；vision 产物含阶段 A + 阶段 B 全部 bean；`/actuator/prometheus` 暴露 `pixflow.vision.*` 三类指标。

## Validation and Acceptance

### 阶段 A 行为级验收（人工可观察）

1. **空请求校验**：`VisionService.analyze(VisionAnalysisRequest(images=List.of()))` 抛 `PixFlowException(VISION_EMPTY_REQUEST, VALIDATION, TERMINATE)`。
2. **单图超大**：fake `infra/storage.stat` 返回 11MiB → 该图跳过 + `issues=[{"code":"too_large"}]`、其余图继续、`parseDegraded=false`（前提是其余图解析正常）。
3. **全部图解码失败**：fake `infra/image.decode` 全失败 → 抛 `VISION_NO_DECODABLE_IMAGE`（不抛底层 `ImageProcessingException`）。
4. **JSON 解析降级**：fake `VisionModelClient` 返回 `"自由文本，无 JSON"` → `parseDegraded=true`、`assessment.confidence <= 0.3`、`assessment.rawText` 含原文、**不**抛异常。
5. **JSON 代码块包裹**：`VisionModelClient` 返回 ```json\n{...}\n``` → 解析正常、字段全填充。
6. **采样（MAIN_FIRST）**：构造 10 张图，含 1 张 `viewId=main`、9 张 `viewId=other-N` → 实际入模型 6 张（`main` 第一，其余按 `viewId` 排序补足），`imagesSent=6`。
7. **错误透传**：fake `VisionModelClient` 抛 `PixFlowException(MODEL_RATE_LIMITED, RATE_LIMIT, RETRY, retryAfter=Duration.ofSeconds(30))` → `assertSame` 上抛**同一实例**、`code()`/`category()`/`recovery()`/`retryAfter()` 全保留。同理 `MODEL_CONTEXT_LIMIT`、`MODEL_NETWORK_ERROR`、`MODEL_PROVIDER_ERROR`。
8. **指标埋点**：`/actuator/prometheus` 暴露 `pixflow.vision_analyze_total{taskType="DESCRIBE",parse="ok"}` 与 `pixflow.vision_images_total{stage="sent"}`；标签不含 `conversationId` / `imageId` 等高基数字段。
9. **Maven 模块存在性**：`mvn -pl pixflow-module-vision -am compile` 通过；根 `pom.xml <modules>` 含 `<module>pixflow-module-vision</module>`。
10. **ArchUnit 守护**：故意在某 `.java` 文件加 `import com.pixflow.infra.ai.chat.ChatMessage;` → `mvn test` 失败；改回后恢复。同样实验对 `qwen-vl-max` 字面量。
11. **Sentinel 装配**：`@SpringBootTest(classes=VisionServiceAutoConfiguration.class)` 默认配置下 `ctx.getBean(VisionService.class)` 不抛、`ctx.getBean(VisionToolHandler.class)` 抛 `NoSuchBeanDefinitionException`。
12. **错误码目录并入**：common 启动期聚合测试绿，4 条 `VISION_*` 全部并入；`VISION_*` 前缀只属于 `com.pixflow.module.vision..` 包。

### 阶段 B 行为级验收

13. **富化消费全链路**：fake `module/file` 发布 `CopyEnrichmentMessage{packageId=42}` → vision 消费 → 查 `asset_image`（fake MySQL）按 SKU 聚合 → fake VLLM 返回 `{productName: "蓝色 T 恤", keywords: "夏装,纯棉", description: "..."}` → `asset_copy` 表新增行。`metrics.enrich{result="filled"} == 1`。
14. **doc 文案覆盖**：在消费前预先写入 `asset_copy(package_id=42, sku_id="A", product_name="原名", ...)` → 消费后 DB 行不变 → `metrics.enrich{result="skipped"} == 1`。
15. **重投幂等**：同 `packageId=42` 发 2 次消息 → `asset_copy` 行数 == 1，不是 2。
16. **单 SKU 失败隔离**：fake VLLM 对 `sku_id=A` 抛 `MODEL_RATE_LIMITED` → 该 SKU 失败 → `B`/`C` 仍消费 → 整包 ack → `metrics.enrich{result="failed"} == 1`、`metrics.enrich{result="filled"} == 2`。
17. **fill-policy=SKIP_IF_ANY**：配置 `enrich.fill-policy=SKIP_IF_ANY` + 预写 SKU 任意字段 → 整 SKU 跳过 → `metrics.enrich{result="skipped"}` 计数。
18. **毒消息 → DLQ**：fake VLLM 持续抛 `MODEL_PROVIDER_ERROR`（5 次 retry 耗尽）→ `CopyEnrichmentErrorHandler` 决策 `DeadLetter` → 消息进入 `pixflow.vision.dlq`，主队列无积压。
19. **指标埋点**：`/actuator/prometheus` 暴露 `pixflow.vision_enrich_total{result="filled|skipped|failed"}` 三种标签；DLQ 深度指标 `pixflow.vision.dlq.depth{queue="pixflow.vision.dlq"}` 经 `infra/mq` 暴露。
20. **整体装配**：`mvn -pl pixflow-app -am test` 全绿；pixflow-app 启动后 `curl http://localhost:8080/actuator/beans | jq` 列出 `visionService` 与 `copyEnrichmentConsumer`，**不**列出 `visionToolHandler`。

### 测试命令

    # 阶段 A 全部单测
    mvn -pl pixflow-module-vision -am test

    # 阶段 B 集成测试（Testcontainers 需要 Docker）
    mvn -pl pixflow-module-vision -am test -Pintegration

    # common 启动期聚合（依赖 VISION_* 4 + 2 条）
    mvn -pl pixflow-common -am test

    # 装配回归
    mvn -pl pixflow-app -am test

    # 端到端冒烟（pixflow-app 启动后）
    mvn -pl pixflow-app spring-boot:run

### 验收判定

- 阶段 A：`mvn -pl pixflow-module-vision -am test` 全绿（含 ArchUnit 守护测试 + Sentinel 装配边界单测 + 4 条错误码目录聚合测试），约 60-90 个单测。
- 阶段 B：`mvn -pl pixflow-module-vision -am test` 全绿（追加约 30 个单测 + 集成测试按 env 跳过策略处理）。
- `mvn -pl pixflow-common -am test` 全绿（6 条 `VISION_*` 全部并入）。
- `mvn -pl pixflow-app -am test` 全绿（vision bean 装配不影响 app 现有测试）。
- `mvn -pl pixflow-module-vision -am package` 产物含 `VisionService` / `VisionImageResolver` / `VisionImagePreprocessor` / `VisionPromptBuilder` / `AssessmentParser` / `VisionMetrics` / `CopyEnrichmentConsumer` / `ProductCopyExtractor` 全部关键 bean；**不**含 `VisionToolHandler` / `VisionToolDescriptor` 自动装配 bean。
- 端到端 20 项人工验收全部通过。

## Idempotence and Recovery

- 所有 `mvn` 命令可重复运行（idempotent）：创建文件用 `New-Item -ItemType Directory -Force`；schema/配置覆盖写不破坏旧内容。
- 单测失败时定位到具体类或方法，修复后 `mvn -pl pixflow-module-vision test -Dtest=具体类` 重跑。
- Testcontainers 集成测试若 Docker 不可用，按 `infra/storage` 已有的 profile 跳过策略（不视为失败）。`mvn test` 在 CI 无 Docker 时跳到「集成全绿、单测全绿、集成标记 skipped」。
- 不需要任何数据迁移：阶段 B 不创建新表，仅复用 `module/file` 已有的 `asset_image` 与 `asset_copy`；`asset_copy` 表的「VISION 来源」行用现有的 `source` 字段（待与 file 端沟通；如未建 source 字段，先以 `package_id + sku_id` 自然键区分）。
- 回滚策略：阶段 A 移除 `pixflow-module-vision` 模块的 Maven 引用即可，不影响其他模块；不修改任何已有模块的代码。阶段 B 同步移除富化作业相关代码 + 释放 `pixflow.vision` 队列组（DLQ 消息保留）。
- `VisionServiceAutoConfiguration` 的 Sentinel 单测是「防回滚失效」的关键——如果未来误把 `VisionToolHandler` 加进 vision 的 `@Bean`，Sentinel 会立即失败。

## Artifacts and Notes

### 关键代码片段参考

**VisionService.analyze 错误透传骨架**（`src/main/java/com/pixflow/module/vision/VisionService.java`）：

    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        request.validate();

        // 1. 采样 MAIN_FIRST
        List<VisionImageRef> sampled = samplingService.sample(request.images(), properties.analyze().imagesPerCall());
        if (sampled.isEmpty()) {
            throw new PixFlowException(VisionErrorCode.VISION_EMPTY_REQUEST);
        }

        // 2. 逐图预处理：失败跳过 + 记 issues，其余继续
        List<ImagePart> parts = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (VisionImageRef ref : sampled) {
            try {
                parts.add(imagePreprocessor.preprocess(imageResolver.resolve(ref), properties));
            } catch (ImageProcessingException e) {
                issues.add("decode_failed: " + e.getMessage());
                metrics.imagesSkipped();
            } catch (StorageException e) {
                issues.add("resolve_failed: " + e.getMessage());
                metrics.imagesSkipped();
            }
        }
        if (parts.isEmpty()) {
            throw new PixFlowException(VisionErrorCode.VISION_NO_DECODABLE_IMAGE);
        }

        // 3. prompt 组装 + 模型调用（infra/ai 错误透传，不吞）
        VisionRequest visionReq = promptBuilder.build(request, parts);
        VisionAnalysisResult aiResult = visionModelClient.call(visionReq);   // 透传 PixFlowException

        // 4. 防御性解析：失败降级但不抛
        AssessmentParser.ParseOutcome outcome = assessmentParser.parse(aiResult.text());
        metrics.analyze(request.taskType(), outcome.degraded());

        return new VisionAnalysisResult(
            outcome.assessment(),
            outcome.degraded(),
            aiResult.usage(),
            parts.size()
        );
    }

**VisionErrorCode 表格与 common 启动期聚合的对接**（`src/main/java/com/pixflow/module/vision/error/VisionErrorCode.java`）：

    public enum VisionErrorCode implements common.ErrorCode {
        VISION_NO_DECODABLE_IMAGE("VISION_NO_DECODABLE_IMAGE", ErrorCategory.VALIDATION, RecoveryHint.TERMINATE,
            "vision.no_decodable_image"),
        VISION_IMAGE_RESOLVE_FAILED("VISION_IMAGE_RESOLVE_FAILED", ErrorCategory.DEPENDENCY, RecoveryHint.RETRY,
            "vision.image_resolve_failed"),
        VISION_IMAGE_TOO_LARGE("VISION_IMAGE_TOO_LARGE", ErrorCategory.VALIDATION, RecoveryHint.SKIP,
            "vision.image_too_large"),
        VISION_EMPTY_REQUEST("VISION_EMPTY_REQUEST", ErrorCategory.VALIDATION, RecoveryHint.TERMINATE,
            "vision.empty_request");

        private final String code;
        private final ErrorCategory category;
        private final RecoveryHint recovery;
        private final String i18nKey;

        VisionErrorCode(String code, ErrorCategory category, RecoveryHint recovery, String i18nKey) {
            this.code = code; this.category = category; this.recovery = recovery; this.i18nKey = i18nKey;
        }

        @Override public String code() { return code; }
        @Override public ErrorCategory category() { return category; }
        @Override public RecoveryHint recovery() { return recovery; }
        @Override public String i18nKey() { return i18nKey; }
    }

**VisionImagePreprocessor 走 infra/image 的 ImagePipeline 骨架**（`src/main/java/com/pixflow/module/vision/image/VisionImagePreprocessor.java`）：

    public ImagePart preprocess(InputStream imageStream, VisionProperties props) {
        // 走 ImagePipeline.run —— infra/image 内置「解码一次、链式 op、编码一次」
        List<ImageOp> ops = List.of(
            new ResizeSpec(props.image().maxLongEdge(), Mode.FIT, false),  // 长边 1280、不放大
            new ConvertFormatSpec(props.image().outputFormat(), props.image().jpegQuality(), WHITE_BG)
        );
        EncodeSpec encode = new EncodeSpec(props.image().outputFormat(), props.image().jpegQuality(), null, Color.WHITE);
        byte[] out = imagePipeline.run(imageStream, ops, encode);
        return new ImagePart(out, props.image().outputFormat().toContentType());
    }

**VisionArchitectureTest 守护片段**（`src/test/java/com/pixflow/module/vision/config/architecture/VisionArchitectureTest.java`）：

    @ArchTest
    static final ArchRule vision_doesNotDependOnInfraAiChat = noClasses()
        .that().resideInAPackage("com.pixflow.module.vision..")
        .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.ai.chat..");

    @ArchTest
    static final ArchRule noModelNameLiteralsInVisionSources = ArchCondition.Factory.create(
        new ContainTextCondition("qwen-vl-max", "qwen-vl-plus", "qwen-vl",
            "gpt-4o", "gpt-4-vision", "claude-3", "dashscope", "openai", "anthropic"));

    @ArchTest
    static final ArchRule vision_package_doesNotDependOnForbiddenModules = noClasses()
        .that().resideInAPackage("com.pixflow.module.vision..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.pixflow.harness.tools",
            "com.pixflow.harness.loop",
            "com.pixflow.agent",
            "com.pixflow.module.dag",
            "com.pixflow.module.task",
            "com.pixflow.module.conversation",
            "com.pixflow.module.rubrics",
            "com.pixflow.module.file");

    @ArchTest
    static final ArchRule vision_phaseA_doesNotUseMyBatisPlus = noClasses()
        .that().resideInAPackage("com.pixflow.module.vision..")
        .and().resideOutsidePackage("com.pixflow.module.vision.enrich..")
        .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus..");

### 关键测试片段参考

**`errorPassthrough_throwsInfraAiExceptionUnchanged` 测试**：

    @Test
    void errorPassthrough_throwsInfraAiExceptionUnchanged() {
        PixFlowException original = new PixFlowException(
            AiErrorCode.MODEL_RATE_LIMITED, ErrorCategory.RATE_LIMIT, RecoveryHint.RETRY,
            Duration.ofSeconds(30), "rate limited");
        when(visionModelClient.call(any())).thenThrow(original);

        VisionAnalysisRequest req = new VisionAnalysisRequest(List.of(REF), "问题", DESCRIBE, Map.of(), "c1", "t1");

        assertThatThrownBy(() -> visionService.analyze(req))
            .isSameAs(original);  // 同一实例
    }

**`preprocess_decodesOnce_encodesOnce` 测试**：

    @Test
    void preprocess_decodesOnce_encodesOnce() {
        CountingImageCodec codec = new CountingImageCodec(fakeJpegBytes());
        VisionImagePreprocessor preprocessor = new VisionImagePreprocessor(codec, props);

        ImagePart part = preprocessor.preprocess(stream, props);

        assertThat(codec.decodeCount()).isEqualTo(1);
        assertThat(codec.encodeCount()).isEqualTo(1);
        assertThat(part.bytes()).isNotEmpty();
    }

### 关键接口契约

**`VisionService`**（`com.pixflow.module.vision.VisionService`）：

    public interface VisionService {
        /** 单次看图分析：图片引用 + 问题 + 任务类型 + 只读上下文 → 结构化评估 + 用量。
         *  同步阻塞（design §10.1「抽样/单图量小则同步跑在 turn 内」）。
         *  不写 process_result、不发进度、不持取消、全图解码失败抛 VisionErrorCode。 */
        VisionAnalysisResult analyze(VisionAnalysisRequest request);
    }

**富化作业 SPI**（阶段 B）：

    public interface CopyEnrichmentPublisher {
        /** 模块/file 调用：在解压终态 READY/PARTIAL 投递富化消息。 */
        void publishCopyEnrichment(long packageId);
    }

### 关键配置

    pixflow:
      vision:
        image:
          max-long-edge: 1280
          output-format: jpeg
          jpeg-quality: 0.85
          max-image-bytes: 10485760     # 10MiB
          transparent-background: WHITE
        analyze:
          images-per-call: 6
          sampling: MAIN_FIRST           # MAIN_FIRST | HEAD
        enrich:
          images-per-sku: 2
          fill-policy: GAP_ONLY          # GAP_ONLY | SKIP_IF_ANY
          consumer-concurrency: 2
          prefetch: 1
          intra-package-parallelism: 4
          consumer-timeout: 30m
          expose: true                   # 阶段 A 默认 false、阶段 B 默认 true

### Revision Notes

2026-06-29 / Kiro: 新增 `module/vision` ExecPlan。基于 `module/vision.md` 现有设计与本轮 4 项设计决策（阶段 A/B 拆分、infra/image 的 ImagePipeline 仅允许 ResizeOp/ConvertFormatOp、`VISION_*` 命名空间守全局唯一、ArchUnit 14+ 条硬守护），按 14 个里程碑拆解（阶段 A 9 个：模块骨架 + ArchUnit → 数据契约 → 图片预处理 → prompt/解析 → 门面 → 错误码 → 配置装配 → ArchUnit 完整 → Sentinel；阶段 B 5 个：MQ 拓扑 → Consumer + Mapper → 错误码 + 配置 → 集成测试 → 装配回归）。所有架构与边界决策以 `module/vision.md` 为唯一权威；本计划只补充「如何落地」。

2026-06-29 / Kiro: 关键词定位索引覆盖 vision.md 全部关键段落（含本轮新增的「阶段 A/B 拆分」「ArchUnit 边界守护」「关键风险点」），执行本计划时按关键词在 `vision.md` 内快速跳转。
