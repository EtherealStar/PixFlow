# module/vision —— 视觉理解能力层（VLLM，Wave 3）

> 本文是 PixFlow 完整重写阶段 `module/vision` 模块的设计文档，对应 `design.md` 第十章 §10.1「视觉理解子 Agent」、第五章 §5.2（Agent 级 `agent` 工具）、第十一章 Rubrics（图片质量评估的能力边界澄清），以及 `module-dependency-dag-plan.md` 的 **Wave 3 子能力**。
> 范围：把「图片引用 + 问题 → 结构化视觉评估」的 VLLM 能力收口成一个**无 think-act-observe 循环**的领域能力层；并承载上传期「同 SKU 图片 → VLLM 抽取商品名/描述 → 写 `asset_copy`」的富化作业（MQ 驱动）。
> 配套阅读：`infra/ai.md`（§5.2 `VisionModelClient`、§9 全局并发、§10 多模态边界、§14 契约）、`infra/storage.md`（`ObjectStorage` / `StorageKeys`）、`infra/image.md`（降采样与格式归一）、`infra/mq.md`（富化作业的可靠消费）、`module/file.md`（`asset_image` / `asset_copy` 数据底座与上传管线）、`harness/tools.md`（§3.3 `agent` 工具、§2.3 数据底座抽取边界）、`common.md`（错误归一化、`Sanitizer`）。本文不涉及 MVP 既有实现，从生产级需求重新推导。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、职责边界：能力层 vs 编排层](#二职责边界能力层-vs-编排层)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [三·一、本期实施拆分（阶段 A：分析面 优先；阶段 B：富化作业 独立里程碑）](#三一本期实施拆分阶段-a分析面-优先阶段-b富化作业-独立里程碑)
- [三·二、关键 SPI 与 Bean 列表](#三二关键-spi-与-bean-列表)
- [三·三、ArchUnit 边界守护（编译期硬约束）](#三三archunit-边界守护编译期硬约束)
- [四、核心抽象与数据契约](#四核心抽象与数据契约)
- [五、图片预处理管线](#五图片预处理管线)
- [六、看图分析能力（agent 路径）](#六看图分析能力agent-路径)
- [七、上传期文案抽取（富化作业，MQ 驱动）](#七上传期文案抽取富化作业mq-驱动)
- [八、同步与批量边界](#八同步与批量边界)
- [九、结构化输出与防御性解析](#九结构化输出与防御性解析)
- [十、并发、韧性与错误归一化](#十并发韧性与错误归一化)
- [十一、可观测](#十一可观测)
- [十二、配置](#十二配置)
- [十三、对其他模块的契约](#十三对其他模块的契约)
- [十四、测试策略](#十四测试策略)
- [十五、对 design.md 的细化](#十五对-designmd-的细化)
- [十六、暂不考虑](#十六暂不考虑)
- [十七、关键风险点（实施期必须守护的细节）](#十七关键风险点实施期必须守护的细节)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`module/vision` 处于依赖 DAG 的 **Wave 3 子能力**。它把对 VLLM（Qwen-VL 类）的视觉理解调用收口成一组**供消费方调用的领域能力**，不自带 Agent 主循环、不自管权限、不暴露 Agent 工具。它被两类消费方使用：`agent` 层的子 Agent runner（`agent(type=vision)`，Wave 5）与上传期文案富化作业（本模块自带，Wave 3）。

模块专属设计原则：

1. **能力层，不是循环层**。`module/vision` 只提供「一次（或少数几次）VLLM 视觉调用 → 结构化结果」的能力，**没有 think-act-observe 循环**。这与波次约束自洽：`harness/loop` 在 Wave 4、vision 在 Wave 3，vision 不可能依赖 loop。design.md §10.1 的 vision 职责（看构图/卖点、理解图片内容、判断图文是否相符）本就窄，不需要工具循环。
2. **子 Agent 的隔离语义由 agent 层实现，不在本模块**。`tools.md §3.3` 的「独立 child runtime、只读硬约束、只回最终 `ToolExecutionResult`」是 `agent` 层 `SubagentRunner`（Wave 5）的职责。本模块只被它调用，产出领域结果 `VisionAssessment`，由 runner 映射成 `ToolExecutionResult`。
3. **`explore` 不属于本模块**。`agent(type=explore)` 要用 `search`/`read` 做关联 SKU 探索，需要工具循环，归 `agent` 层 runner 复用只读工具实现。本模块只服务 `type=vision`。
4. **不直产工具结果、不依赖 `harness/tools`**。vision 返回领域对象，结果预算/外置/`ToolExecutionResult` 包装统一由 tools 执行管线在 `agent` 工具返回时处理。本模块对 `harness/tools` **零依赖**（见 [§十五](#十五对-designmd-的细化) 对 DAG `tools → vision` 边的修正）。
5. **图片解析与预处理自包含**。vision 接受图片**引用**（`ObjectRef`/`StorageKeys`），自己经 `infra/storage` 取字节、经 `infra/image` 降采样与格式归一，再交 `infra/ai` 的 `VisionModelClient`。`infra/ai` 不碰 MinIO（ai.md §10），引用解析责任落在本模块，调用方只给引用。
6. **无状态（分析路径）**。看图分析不落库、不写 MinIO；结果交调用方使用。唯一的持久化发生在文案富化作业路径（写 `asset_copy`），且与分析能力清晰分离。
7. **成本是硬指标**。VLLM 按分辨率/图数计费，预处理强制降采样 + 限制单次图数 + 采样，全局并发经 `infra/ai` 的 `GlobalConcurrencyLimiter` 封顶。
8. **rubrics 不经本模块**。Rubrics 的图片质量评估直连 `infra/ai` 的 `VisionModelClient`（ai.md §14），不复用 `module/vision`；两者语义可对齐但不互相依赖。

---

## 二、职责边界：能力层 vs 编排层

把 design.md「视觉理解子 Agent」按波次约束切成两层，避免 vision 反向依赖 loop/agent：

| 关注点 | 归属 | 波次 | 本文是否覆盖 |
|---|---|---|---|
| 图片引用解析、降采样、格式归一、VLLM 调用、结构化评估 | **module/vision** | Wave 3 | ✅ 是 |
| 上传期同 SKU 图 → VLLM 抽取商品名/描述 → 写 `asset_copy` | **module/vision（enrich 子流程）** | Wave 3 | ✅ 是 |
| `agent(type=vision)` 的子 Agent 隔离、child runtime、只读硬约束、`ToolExecutionResult` 映射 | `agent` 层 `SubagentRunner` | Wave 5 | ❌ 否（仅定义被调契约） |
| `agent(type=explore)` 关联 SKU 探索（用 search/read 工具循环） | `agent` 层 runner | Wave 5 | ❌ 否（明确不属本模块） |
| 图片质量 Rubrics 评分 | `module/rubrics`（直连 infra/ai） | Wave 6 | ❌ 否（明确不经本模块） |

本模块对外提供**两个能力面**：

- **分析面（同步）**：`VisionService.analyze(VisionAnalysisRequest) → VisionAnalysisResult`，供 `agent` 层 runner 在 turn 内调用。
- **富化面（异步）**：MQ 驱动的 `CopyEnrichmentConsumer`，消费 file 在解压完成后投递的富化消息，调用内部 `extractProductCopy(...)` 并把抽取文案写入 `asset_copy`。

---

## 三、模块结构与依赖位置

Maven 模块：`pixflow-module-vision`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.vision`

```
module/vision/
├── VisionService.java                 # 对外门面：analyze(...)（agent 路径）
├── analyze/
│   ├── VisionAnalysisRequest.java     # 图片引用 + 问题 + 任务/SKU 上下文
│   ├── VisionAnalysisResult.java      # 评估结果 + 用量 + 是否降级解析
│   ├── VisionAssessment.java          # 结构化视觉评估（见 §四）
│   ├── VisionImageRef.java            # 图片引用（ObjectRef/StorageKey + 元数据）
│   ├── VisionPromptBuilder.java       # 看图任务 system/user prompt 模板
│   └── AssessmentParser.java          # 防御性 JSON 解析 + 降级到 rawText
├── image/
│   ├── VisionImageResolver.java       # 引用 → 字节（infra/storage）
│   └── VisionImagePreprocessor.java   # 降采样 + 格式归一（infra/image）→ infra/ai 图片 part
├── enrich/
│   ├── CopyEnrichmentDestination.java # 声明 pixflow-vision topic / COPY_ENRICH tag / consumer group
│   ├── CopyEnrichmentConsumer.java    # process-then-ack 消费富化作业
│   ├── CopyEnrichmentErrorHandler.java# 实现 infra/mq 的 ConsumerErrorHandler
│   ├── CopyEnrichmentMessage.java     # 消息体（仅 packageId）
│   ├── ProductCopyExtractor.java      # extractProductCopy(imageRefs, ctx) → ProductCopyDraft
│   ├── ProductCopyDraft.java          # 抽取出的 product_name/keywords/description 草案
│   └── AssetCopyWriteMapper.java      # 仅 gap-fill 写 asset_copy（doc 文案优先）
├── error/
│   └── VisionErrorCode.java           # implements common.ErrorCode
└── config/
    └── VisionProperties.java          # @ConfigurationProperties(pixflow.vision)
```

依赖方向：

```
module/vision ──► infra/ai       （VisionModelClient：多模态调用、全局并发、错误源头构造）
module/vision ──► infra/storage  （VisionImageRef → 字节；富化作业读 asset_image 对应对象）
module/vision ──► infra/image    （送 VLLM 前降采样 + 格式归一，控成本）
module/vision ──► infra/mq       （仅 enrich 子流程：消费富化作业、DLQ）
module/vision ──► common         （ErrorNormalizer / Sanitizer / ApiResponse）
module/vision ──► MySQL / MyBatis-Plus（仅 enrich 子流程：gap-fill 写 asset_copy）

agent（SubagentRunner, Wave 5）──► module/vision   （analyze 能力；映射 ToolExecutionResult）
```

**不依赖**：`harness/tools`（不直产工具结果，见 §十五）、`harness/loop`/`harness/*`、`module/file`（富化作业经 MQ 解耦 + 共享表，不编译依赖 file）、`module/dag`、`agent`（反向：agent 依赖 vision）。

> 分析面只需 `infra/ai + infra/storage + infra/image + common`；`infra/mq` 与 MySQL 仅富化面用到。两面在同一模块但依赖清晰可分。

---

## 三·一、本期实施拆分（阶段 A：分析面 优先；阶段 B：富化作业 独立里程碑）

原设计文档将分析面与富化作业并列在同一模块，但生产级落地时**两类实现的验证节奏与失败成本不一致**。本期显式拆成两个独立阶段：

### 阶段 A：分析面（本期优先上线）

**目标**：在 Wave 3 期内让 `VisionService.analyze(...)` 完整可调用，使 Wave 5 `agent(type=vision)` 在接线时能立即接入；`agent(vision)` 的 tool error 路径在阶段 A 即可在 fake `VisionModelClient` 上跑通。

**范围**：

- `VisionImageRef` / `VisionAnalysisRequest` / `VisionAssessment` / `VisionAnalysisResult` 数据契约。
- `VisionImageResolver`（`infra/storage`）+ `VisionImagePreprocessor`（走 `infra/image` 的 `ImagePipeline`，仅允许 `ResizeOp` + `ConvertFormatOp`）。
- `VisionPromptBuilder` + `AssessmentParser`（防御性解析 + 降级到 `rawText`）。
- `VisionService.analyze(...)` 门面 + 采样（`MAIN_FIRST`）+ 单图跳过策略。
- `VisionErrorCode` 4 条入 `common` 启动期聚合目录。
- `VisionProperties`（`max-long-edge=1280` / `images-per-call=6` / `max-image-bytes=10MiB` / `jpeg-quality=0.85` / `transparent-background=WHITE`）。
- `VisionServiceAutoConfiguration`（**不装配** `VisionToolHandler` / `VisionToolDescriptor`——这部分是 Wave 5 的事）。
- `VisionMetrics` 3 类 Micrometer 指标。

**不**在阶段 A 出现的文件：`CopyEnrichmentTopology` / `CopyEnrichmentConsumer` / `ProductCopyExtractor` / `AssetCopyWriteMapper` / `CopyEnrichmentErrorHandler` / `VisionEnrichErrorCode`。

**验证节奏**：所有单测 < 1s 启动（纯函数 + fake `VisionModelClient` + fake `infra/storage` + fake `infra/image`）；ArchUnit 守护全绿；Sentinel `VisionService` 默认装配确认。

### 阶段 B：富化作业（独立里程碑）

**目标**：消费 file 在解压终态投递的 `CopyEnrichmentMessage`，按 SKU gap-fill 写 `asset_copy`，doc 文案优先、VLLM 仅补缺口；端到端在 Testcontainers MinIO + RocketMQ + MySQL 上跑通。

**范围**：

- `CopyEnrichmentDestination`（`pixflow-vision` topic / `COPY_ENRICH` tag / `pixflow-vision-enricher` consumer group）。
- `CopyEnrichmentMessage`（仅 `packageId`）。
- `CopyEnrichmentConsumer`（process-then-ack + 单包内 SKU 有界并发）。
- `ProductCopyExtractor`（**复用** `VisionService.analyze(...)`，不重写 prompt builder；同一能力面两个消费者）。
- `AssetCopyWriteMapper`（gap-fill + 幂等 upsert + doc 文案优先）。
- `CopyEnrichmentErrorHandler`（VLLM 抖动 → Retry；毒消息 → DLQ；单 SKU 失败 → SKIP，不阻断整包 ack）。
- `VisionEnrichErrorCode` 2 条（`VISION_COPY_EXTRACTION_FAILED` / `VISION_FILL_POLICY_REJECTED`）入 `common` 聚合。
- `fill-policy` 配置（`GAP_ONLY` / `SKIP_IF_ANY`）。

**验证节奏**：Testcontainers 集成测试启动 > 10s 可接受；阶段 A 完成后**单独安排**一个里程碑做阶段 B，CI 反馈节奏不与阶段 A 串扰。

### 拆分依据（与「不拆分」对照）

| 维度 | 拆分（推荐） | 不拆分 |
|---|---|---|
| 集成测试启动 | 阶段 A < 1s，阶段 B > 10s，按需跑 | 全量 > 10s，CI 反馈慢 |
| 富化失败时影响 | 只阻断阶段 B 后续；阶段 A 仍可上线 | 分析面测试被富化侧 flakiness 拖垮 |
| 共享表的可演化性 | `asset_copy` 的 VISION 来源独立可演进 | 与 `doc 文案`/`commerce 导入` 混入同一写入路径 |
| Wave 5 接入 | 阶段 A 上线后 `agent(vision)` 即可对接 | 必须等富化作业完成才能上线 agent(vision) |

> 阶段 A 与阶段 B **共享** `VisionService.analyze(...)` 与 `VisionImagePreprocessor`——文案抽取的 VLLM 调用就是分析面能力的复用，不引入第二套图片预处理路径。

---

## 三·二、关键 SPI 与 Bean 列表

本节是 §三 顶层目录结构的**实施期硬清单**，列出每个类在阶段 A/B 的归属、是否可被外部装配、是否纳入 ArchUnit 守护。

### 3·1 SPI 接口（imagegen 侧只定义，调用方在 Wave 4-5 实现）

阶段 A **无新增 SPI**（分析面只依赖 `infra/ai` / `infra/storage` / `infra/image` 既有接口）。阶段 B 引入一条：

- `CopyEnrichmentFanoutStrategy`（`module/vision/enrich/spi`）——按 `(packageId, skuId)` 把消息分发到具体富化执行单元。**这是预留**，阶段 B 决定是否实现。

### 3·2 内部 Bean（Spring 装配，阶段 A 必需）

| Bean 名 | 阶段 | 装配开关 | 备注 |
|---|---|---|---|
| `VisionService` | A | 始终装配 | 门面 `analyze(...)` |
| `VisionImageResolver` | A | 始终装配 | 引用 → 字节 |
| `VisionImagePreprocessor` | A | 始终装配 | 降采样 + 格式归一 |
| `VisionPromptBuilder` | A | 始终装配 | prompt 组装 |
| `AssessmentParser` | A | 始终装配 | JSON 防御性解析 |
| `VisionProperties` | A | `@ConfigurationProperties` | `pixflow.vision.*` |
| `VisionServiceAutoConfiguration` | A | 始终装配 | `@Configuration` 入口 |
| `VisionMetrics` | A | 始终装配 | 3 类 Micrometer 指标 |
| `CopyEnrichmentDestination` | B | 始终装配 | `pixflow-vision` topic / `COPY_ENRICH` tag / consumer group |
| `CopyEnrichmentConsumer` | B | 始终装配 | process-then-ack |
| `CopyEnrichmentErrorHandler` | B | 始终装配 | `ConsumerErrorHandler` 实现 |
| `ProductCopyExtractor` | B | 始终装配 | 复用 `VisionService.analyze` |
| `AssetCopyWriteMapper` | B | 始终装配 | gap-fill 写 `asset_copy` |

### 3·3 不在 vision 模块装配的 Bean（Wave 5 才出现）

- `VisionToolHandler`（`ToolHandler` 实现）—— Wave 5 `agent/type=vision` 接线时由 `module/agent` 贡献。
- `VisionToolDescriptor`（`ToolDescriptor` `@Bean`）—— 同上。

vision 模块**不预埋**这两个 bean 的 `@Bean` 形态，避免 Wave 5 接线时与早期版本命名冲突。

### 3·4 与 imagegen 的对称关系

| 维度 | imagegen（已实现） | vision（本期） |
|---|---|---|
| 提案侧 | `ImagegenPlanToolHandler` + `ImagegenPlanDescriptor` | 阶段 A 不做，Wave 5 才出现 |
| 执行侧 | `ImageGenExecutor` SPI | `VisionService.analyze` 直接暴露，不走 SPI |
| 富化作业 | 无 | 阶段 B 的 `CopyEnrichmentConsumer` |
| 错误码 | `ImagegenErrorCode` 10 条 | `VisionErrorCode` 4 条（A）+ 2 条（B） |
| Sentinel 装配边界 | `DefaultImageGenExecutor` 默认不暴露 | `VisionService` **始终**暴露（门面要被 agent(vision) 调用） |

> 关键差异：imagegen 的执行侧（`ImageGenExecutor`）是 SPI，由 `module/task` 装配；vision 的执行侧（`VisionService`）是直接门面，由 `agent` 层在 Wave 5 直接注入。这与 vision 的定位（「**纯能力层**」vs imagegen 的「**纯能力 + 异步执行外壳由 task 拼接**」）一致。

---

## 三·三、ArchUnit 边界守护（编译期硬约束）

与 imagegen 的 ArchUnit 14 条对齐，vision 模块需要以下边界守护。**违反任一条即测试失败**，保障依赖方向不被未来 PR 静默打破。

### 3·3·1 vision 模块对其他模块的「不可依赖」清单

vision 包根 `com.pixflow.module.vision..` **不得**直接 import 以下类型：

- `com.pixflow.infra.ai.chat..`（`ChatMessage` / `ChatRequest` / `ChatResult`）—— vision 只应看到 `VisionRequest` / `VisionModelClient.call`；若 vision 内部出现 `ChatMessage` 的 import，说明绕过了 `infra/ai` 的窄接口契约。
- `com.pixflow.harness.tools..` —— vision 不直产工具结果。
- `com.pixflow.harness.loop..` / `com.pixflow.harness.session..` —— vision 不是 Agent runtime。
- `com.pixflow.agent..` —— 反向依赖（agent 依赖 vision）。
- `com.pixflow.module.dag..` / `com.pixflow.module.task..` / `com.pixflow.module.conversation..` / `com.pixflow.module.rubrics..` —— 业务下游不反向依赖。
- `com.pixflow.module.file..`（**编译层**）—— 富化作业经 MQ 解耦，vision 不直连 file 实体。
- `com.baomidou.mybatisplus..` / `org.apache.ibatis..` / `java.sql..` / `org.redisson..` / `org.springframework.messaging.simp..` —— 不持有线程池 / MQ / Redisson / WebSocket。
- `java.util.concurrent.Executors` / `ScheduledExecutorService` —— vision 不开线程池（并发封顶由 `infra/ai` 的 `GlobalConcurrencyLimiter` 负责）。
- `org.springframework.scheduling.annotation.Scheduled` —— vision 不放定时任务（投递缺口由 `module/file` 的 `PublishGapRescan` 收口）。

### 3·3·2 vision 模块对具体模型名的「不可出现」清单

`com.pixflow.module.vision..` 任何 Java 源文件不得出现以下字符串字面量：

- `qwen-vl-max` / `qwen-vl-plus` / `qwen-vl` / `QwenVL` / `gpt-4o` / `gpt-4-vision` / `claude-3-opus` / `claude-3-sonnet` —— 型号由 `infra/ai` 的 `pixflow.ai.roles.vision` 配置承载，vision 内部**绝**不写死。
- `dashscope` / `openai` / `anthropic` —— 供应商关键字必须封在 `infra/ai` 内部。

> 实施方式：在 `architecture/VisionArchitectureTest` 用 `ArchUnit` 的 `FreezingFreeArchRule.freeze()` + 自定义 `ContainTextCondition` 扫描源码，命中即 `assertFailure`。

### 3·3·3 阶段 A/B 的可测边界

- 阶段 A：所有单测 < 1s 启动，不依赖 Spring 完整上下文（用 `MockitoJUnitRunner` 或 `AssertJ` 直接构造）。
- 阶段 B：集成测试用 `Testcontainers` 启动 MinIO + RocketMQ + MySQL + fake `VisionModelClient` + fake `infra/image`；按 `infra/storage` 既有 profile 跳过策略处理 Docker 不可用。

### 3·3·4 Sentinel 装配边界

- 阶段 A：`VisionServiceAutoConfiguration` 始终装配 `VisionService`；**不**装配 `VisionToolHandler` / `VisionToolDescriptor`。Sentinel 单测用 `ApplicationContextRunner` + 自行 stub `infra/ai` / `infra/storage` / `infra/image`，断言「`VisionService` bean 存在但 `VisionToolHandler` bean 不存在」。
- 阶段 B：`CopyEnrichmentConsumer` 始终装配；装配开关 `pixflow.vision.enrich.expose=false` 时不启动（用于阶段 A 期灰度上线），`true` 时启动（默认行为）。

---

## 三·四、本节与 design.md / DAG 的同步

| 同步项 | 同步位置 | 状态 |
|---|---|---|
| 移除 `tools → vision` 边 | `module-dependency-dag-plan.md` §二 | 本轮同步 |
| 新增 `storage → vision` / `image → vision` / `mq → vision`（仅富化）边 | 同上 | 本轮同步 |
| 阶段 A/B 拆分说明 | `module-dependency-dag-plan.md` §三 vision 任务清单 | 本轮同步 |
| vision 不直产工具结果 | `module/vision.md` §十五第 8 项细化（已有） | 沿用 |
| `VisionErrorCode` 4+2 条并入 common 聚合 | `common.md` §十一 | 待 common 同步 |
| vision 不依赖 `infra/ai.chat` 包 | `infra/ai.md` §十四（与 vision 契约） | 待 infra/ai 同步 |

---

## 四、核心抽象与数据契约

### 4.1 VisionImageRef —— 图片引用（不传字节给调用方）

```java
public record VisionImageRef(
    ObjectRef object,        // MinIO 引用（bucket + key），由 vision 自行解析
    String skuId,            // 可空：该图所属 SKU，用于 prompt 上下文与排序
    String viewId,           // 可空：视图编号（main/side/back...）
    String hintLabel         // 可空：调用方对该图的标注（如 "主图"）
) {}
```

调用方只给引用与上下文标注，**不预解析字节**；解析、降采样、归一在本模块内完成（见 [§五](#五图片预处理管线)）。

### 4.2 VisionAnalysisRequest / Result（分析面）

```java
public record VisionAnalysisRequest(
    List<VisionImageRef> images,   // 1..N，受单次上限约束
    String question,               // 看图任务（由 agent 层 runner 给出）
    VisionTaskType taskType,       // DESCRIBE / SELLING_POINTS / MATCH_DESCRIPTION / FREEFORM
    Map<String, Object> context,   // 可空：SKU 文案、类目、电商指标摘要等只读上下文
    String conversationId,         // 可空：仅用于 trace 关联，不做会话状态
    String traceId
) {}

public record VisionAnalysisResult(
    VisionAssessment assessment,   // 结构化评估
    boolean parseDegraded,         // true=结构化解析失败，仅 rawText 可用
    TokenUsage usage,              // 透传自 infra/ai
    int imagesSent                 // 实际送模型张数（采样后）
) {}
```

### 4.3 VisionAssessment —— 稳定的结构化评估

覆盖 design.md §10.1 的三类用途（构图/卖点、内容理解、图文相符判断），字段语义与确定性处理建议、rubrics 可对齐：

```java
public record VisionAssessment(
    String composition,                 // 构图与卖点呈现的自然语言描述
    Boolean backgroundClean,            // 背景是否干净（去背景/白底建议的依据）
    Boolean hasWatermark,
    String watermarkPosition,           // 可空
    Boolean matchesDescription,         // 图文是否相符（context 提供描述时才有意义）
    String mismatchReason,              // 可空：不相符时的原因
    List<String> sellingPoints,         // 结构化卖点要点
    List<String> issues,                // 视觉问题（模糊/遮挡/低质等）
    double confidence,                  // 0..1，模型自评 + 解析可信度修正
    String rawText                      // 兜底：模型原始输出，解析降级时唯一可用
) {}
```

- 字段**全部可空/可降级**：`toolChoice=AUTO`、本期无强制 JSON（ai.md §16），解析失败时只保证 `rawText` + `confidence` 低值（见 [§九](#九结构化输出与防御性解析)）。
- `matchesDescription` 仅在 `context` 带商品描述时有判定意义，否则置空。

### 4.4 ProductCopyDraft —— 富化抽取草案（富化面）

```java
public record ProductCopyDraft(
    String skuId,
    String productName,    // 可空
    List<String> keywords, // 可空
    String description,    // 可空
    double confidence
) {}
```

富化作业把它按 **gap-fill** 策略写入 `asset_copy`（见 [§七](#七上传期文案抽取富化作业mq-驱动)）。

---

## 五、图片预处理管线

送 VLLM 前的预处理是成本/质量/兼容性的关键，全部在本模块完成：

```
VisionImageRef(object)
  → VisionImageResolver：ObjectStorage.getStream(object) 取字节（受单图大小上限保护）
  → VisionImagePreprocessor（infra/image）：
       ① 解码（TwelveMonkeys 补格式）
       ② 降采样：长边缩到 max-long-edge（默认 1280px），保持长宽比，不放大
       ③ 格式归一：统一编码为 VLLM 兼容格式（默认 JPEG，质量可配；含透明通道时按规则铺底或转 PNG）
       ④ 产出归一后的字节 + contentType
  → 组装 infra/ai 的图片 part（data-uri / bytes，自包含，不用预签名 URL）
  → 多图按 images-per-call 上限与采样策略裁剪，拼进 VisionRequest.messages
```

要点：

- **自包含 payload**：用 data-uri/bytes 而非预签名 URL，避免依赖 MinIO 对模型侧网络可达，且与 `infra/ai` 「图片内容由调用方传入」契约一致。
- **降采样在 vision 内**：需要 `infra/image`，因此**补 `image --> vision` 依赖边**（见 [§十五](#十五对-designmd-的细化)）。理由是成本是生产级硬指标，复用 Wave 1 已有的 `infra/image` 比在上传期预生成预览变体更简单、对 file 零侵入。
- **单图大小/总图数上限**：超限的单图跳过并记 `issues`，不让单张坏图拖垮整次调用；总图数超 `images-per-call` 时按采样策略保留（默认按 `viewId` 取主图优先、再补足）。
- **解码失败隔离**：单图解码失败 → 该图跳过 + 在结果 `issues` 标注，其余图继续；全部失败才上抛错误。

---

## 六、看图分析能力（agent 路径）

```
agent 层 SubagentRunner（type=vision, Wave 5）
  → 构造 VisionAnalysisRequest（图片引用 + 问题 + 只读上下文）
  → VisionService.analyze(req)
       ├─ 预处理图片（§五）
       ├─ VisionPromptBuilder 组装多模态 messages（系统约束 JSON 输出 + 用户问题 + 图片 part）
       ├─ infra/ai.VisionModelClient.call(VisionRequest)   // 阻塞，量小同步（design §10.1）
       ├─ AssessmentParser 解析 → VisionAssessment（失败降级 rawText）
       └─ 返回 VisionAnalysisResult（含 usage / imagesSent / parseDegraded）
  → runner 映射成 ToolExecutionResult（content=评估摘要, metadata=结构化字段/usage）
  → tools 执行管线统一做结果预算/外置（超阈值外置 MinIO，模型见引用+预览）
```

- **只读与递归 agent 禁用由 tools/permission 层强制**，不在 vision 内：vision 是被调能力，不构造 `PermissionSubject`、不判断是否放行（`tools.md §3.3`、`subagent-architecture.md` 的只读硬约束在权限层）。
- **child runtime 的中间消息不回父链**：这是 runner 的职责；vision 只回最终结构化结果。
- **Plan 模式可见**：`agent` 工具在 Plan 模式可见（`tools.md §3.3`），vision 作为其能力天然可在 Plan 模式被调，无副作用。

---

## 七、上传期文案抽取（富化作业，MQ 驱动）

落地 `tools.md §2.3` 的「上传后同 SKU 图发 VLLM 抽取商品名/描述写 `asset_copy`」固定流程。由于 `module/file`、`module/commerce` 均在 **Wave 2**、且不依赖 `infra/ai`/`module/vision`，该流程**不能**在 file/commerce 内实现。采用 **MQ 解耦 + Wave 3 消费者** 方案：

```
module/file（Wave 2）解压终态 READY/PARTIAL
  → 经 infra/mq 发布 CopyEnrichmentMessage{packageId}    // file→mq 已存在，零编译依赖 vision
         │
         ▼ （MQ 解耦）
module/vision（Wave 3）CopyEnrichmentConsumer（process-then-ack）
  → 查 asset_image：按 sku_id 聚合，取每个 SKU 缺文案的成员图（采样 images-per-sku）
  → 仅对「无 doc 文案或文案字段有缺口」的 SKU：
       ProductCopyExtractor.extractProductCopy(imageRefs, ctx)
         → 预处理图片（§五）→ VisionModelClient → ProductCopyDraft
  → AssetCopyWriteMapper：gap-fill 写 asset_copy（doc 文案优先，仅补空字段）
  → ack（整包富化完成）
  异常 → CopyEnrichmentErrorHandler 判 Retry / DeadLetter
```

设计要点：

- **触发解耦**：file 只多发一条富化消息（`file → mq` 边已存在），**不依赖 vision**；消费者在 vision 侧（`mq → vision` 边，本模块新增，见 §十五）。这与 file.md 既有的「process-then-ack + 一个包一条消息」范式同构。
- **doc 文案优先，VLLM 只补缺口**：file 的 `CsvCopyDocParser`/`ExcelCopyDocParser` 写入的文档文案是权威来源；VLLM 抽取只填补「该 SKU 无文案条目」或「字段为空」的缺口，**不覆盖已有 doc 文案**。`fill-policy` 可配（`GAP_ONLY`（默认）/ `SKIP_IF_ANY`）。
- **共享表不共享代码**：vision 用自有 `AssetCopyWriteMapper` 写同一张 `asset_copy`，避免 `vision → file` 编译依赖。写入走幂等 upsert（`(package_id, sku_id)` 维度），重投不产生重复。
- **幂等续跑**：process-then-ack + gap-fill + 幂等 upsert 让 MQ 重投天然续跑——已写过文案的 SKU 再次进入时按 `fill-policy` 跳过。
- **失败隔离**：单 SKU 抽取失败 → 记录跳过、其余 SKU 继续；整包可确认成功（部分成功）。VLLM 持续不可用/毒消息 → 经 `ConsumerErrorHandler` 重试耗尽进 `pixflow-vision` 对应 consumer group 的 DLQ。
- **不阻塞上传与对话**：富化是后台作业；`search`/`read` 在富化未完成时读到的是 doc 文案或空，富化完成后增量补齐。

---

## 八、同步与批量边界

- **本期分析面只做同步**：`VisionService.analyze` 在调用线程内阻塞完成（design.md §10.1「抽样/单图量小则同步跑在 turn 内」）。这对 agent turn 内的少量看图足够。
- **「大批量降级后台子任务」本期不做**：design.md §10.1 的后台降级需要 `module/task`/MQ 任务编排（Wave 4），vision 是 Wave 3，无法依赖。本期通过 `images-per-call` 上限 + 采样控制单次规模；真正的大批量后台视觉任务推迟到 task 就绪后由编排层接入（见 [§十六](#十六暂不考虑)）。
- **富化面本就异步**：富化作业天然走 MQ，不受上面同步约束影响；它消费的是整包，按 SKU 串行/有界并发抽取。

---

## 九、结构化输出与防御性解析

`infra/ai` 本期 `toolChoice=AUTO`、不做强制 structured-output（ai.md §16），因此 vision 拿到的是自由文本，必须防御性处理：

1. **Prompt 约束输出形状**：`VisionPromptBuilder` 在 system/user prompt 中明确要求按固定 JSON 字段输出（对应 `VisionAssessment`），并给出「只输出 JSON、不要解释」的约束。
2. **`AssessmentParser` 容错解析**：剥离 ```json 代码块包裹、容忍多余文本、字段缺失按可空处理。
3. **解析降级**：解析失败 → `parseDegraded=true`，`assessment` 仅填 `rawText` + 低 `confidence`，**不抛异常**。调用方（agent runner）据此决定是否重问或直接用 rawText。
4. **不向模型暴露内部分数**：`confidence` 等是给调用方/trace 用的，注入回主 Agent 的内容应是可读摘要而非可被操纵的指令。

> 若后续 `infra/ai` 引入 JSON mode / structured-output，vision 可切到强约束模式，`AssessmentParser` 退化为直读；接口不变。

---

## 十、并发、韧性与错误归一化

- **全局并发封顶**：VLLM 调用经 `infra/ai` 的 `GlobalConcurrencyLimiter`（config `pixflow.ai.concurrency.vision`，默认 8）封顶，vision 不自造分布式限流。
- **模型级重试/退避/熔断**：由 `infra/ai` 的 `ModelRetryRunner` 负责（ai.md §7）；vision 不重复造重试。
- **错误源头已归一**：`infra/ai` 在源头构造带 `category`/`retryAfter` 的 `PixFlowException`（限流/网络/供应商/上下文）。vision 自有错误码 `VisionErrorCode implements common.ErrorCode` 仅覆盖本模块语义（如图片全部解码失败、引用解析失败）。
- **两路调用方各自处理失败**：
  - 分析面：vision 把可恢复失败如实上抛 `PixFlowException`，由 `agent` 层 runner/tools 执行管线归一化为结构化 tool error 回填模型，**不崩主循环**；解析降级走 §九（不算失败）。
  - 富化面：失败交 `CopyEnrichmentErrorHandler`（实现 `infra/mq.ConsumerErrorHandler`）按 `recovery` 给 `RetryDecision`（抖动 `Retry`→耗尽 `DeadLetter`；单 SKU `SKIP` 类不冒泡，整包仍确认成功为部分成功）。
- **降级**：MinIO 取图失败 → 该图跳过/上抛（视全图是否可用）；VLLM 不可用 → 分析面上抛、富化面重试入 DLQ。所有对外文案经 `Sanitizer`（API key 等禁止泄露）。

| code | category | recovery | 场景 |
|---|---|---|---|
| `VISION_NO_DECODABLE_IMAGE` | VALIDATION | TERMINATE | 单次请求内所有图片均无法解码 |
| `VISION_IMAGE_RESOLVE_FAILED` | DEPENDENCY | RETRY | MinIO 取图失败（可重试） |
| `VISION_IMAGE_TOO_LARGE` | VALIDATION | SKIP | 单图超大小上限（逐图跳过） |
| `VISION_EMPTY_REQUEST` | VALIDATION | TERMINATE | 未提供任何图片引用 |

> 模型限流/网络/上下文/供应商类错误**不在本表**：它们由 `infra/ai` 的 `AiErrorCode` 归一，vision 直接透传。

---

## 十一、可观测

- **ai 维度指标**由 `infra/ai` 出（`pixflow.ai.call{role=vision}`、tokens、retry、concurrency）。
- **vision 维度补充指标**：`pixflow.vision.analyze{taskType, parse=ok|degraded}`、`pixflow.vision.images{stage=received|sent|skipped}`、`pixflow.vision.enrich{result=filled|skipped|failed}`；不放高基数 tag（如 conversationId）。
- **trace**：分析面的 tool 维 trace 由 `agent` 层 runner 经 `ToolTraceSink` 记录（vision 不依赖 eval）；vision 仅在结果 metadata 暴露 `imagesSent`/`parseDegraded`/`usage` 供 runner 写 trace。
- **富化面**：消费日志携带 `packageId`/`traceId`（`infra/mq` trace 透传约定），记录每包填充/跳过/失败的 SKU 计数。
- **图片字节不入日志/trace**：只记引用、尺寸、张数等元数据。

---

## 十二、配置

`@ConfigurationProperties(prefix = "pixflow.vision")`：

```yaml
pixflow:
  vision:
    image:
      max-long-edge: 1280            # 降采样长边目标像素（不放大）
      output-format: jpeg            # 送 VLLM 的归一格式
      jpeg-quality: 0.85
      max-image-bytes: 10MiB         # 单图原始字节上限，超限跳过
      transparent-background: WHITE  # 透明通道铺底策略 WHITE | PNG_KEEP
    analyze:
      images-per-call: 6             # 单次分析最多送模型张数（成本约束）
      sampling: MAIN_FIRST           # 超出时采样策略：MAIN_FIRST（主图优先）| HEAD
    enrich:
      images-per-sku: 2              # 每个 SKU 抽取文案时送模型的成员图数
      fill-policy: GAP_ONLY          # GAP_ONLY（仅补空）| SKIP_IF_ANY（已有任意文案则跳过）
      topic: pixflow-vision
      tag: COPY_ENRICH
      consumer-group: pixflow-vision-enricher
      consumer-concurrency: 2        # 同时富化的包数（process-then-ack）
      intra-package-parallelism: 4   # 单包内并发抽取的 SKU 数（有界）
      consume-timeout: 30m           # COPY_ENRICH 的消费超时（部署侧同步 RocketMQ）
```

- 模型型号不在此配置：VLLM 型号由 `infra/ai` 的 `pixflow.ai.roles.vision` 承载（design.md「型号配置化」）。
- Topic / Tag / Consumer Group 由 `CopyEnrichmentDestination` 声明，配置仅用于覆盖默认命名，不在代码里散落 MQ 目的地字符串（`infra/mq.md §四/§十`）。

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `infra/ai` | 经 `VisionModelClient.call(VisionRequest)` 做多模态调用；图片内容由 vision 解析好（bytes/data-uri）传入；模型级重试/并发/错误归一在 ai；vision 透传 `AiErrorCode` |
| `infra/storage` | `VisionImageRef.object` → `ObjectStorage.getStream` 取字节；vision 只读不写（富化面也不写对象，仅写 MySQL） |
| `infra/image` | 送 VLLM 前降采样 + 格式归一；新增 `image → vision` 依赖边 |
| `infra/mq` | 仅富化面：`CopyEnrichmentDestination` 声明 `pixflow-vision` topic、`COPY_ENRICH` tag 与 `pixflow-vision-enricher` consumer group；`CopyEnrichmentConsumer` process-then-ack；`CopyEnrichmentErrorHandler` 实现 `ConsumerErrorHandler`；新增 `mq → vision` 依赖边 |
| `module/file` | 经 MQ 解耦：file 在解压终态发布 `CopyEnrichmentMessage{packageId}`（`file → mq` 已存在，不编译依赖 vision）；vision 读 `asset_image`、gap-fill 写 `asset_copy`（共享表、不共享代码，doc 文案优先） |
| `module/commerce` | 富化补齐的 `asset_copy` 是 `search`/`read` 的数据底座；commerce 只读 `asset_copy`，与 vision 无直接调用关系 |
| `agent`（Wave 5） | `SubagentRunner(type=vision)` 调 `VisionService.analyze`，把 `VisionAssessment` 映射成 `ToolExecutionResult`；只读/递归禁用/结果预算在 runner+tools+permission，不在 vision |
| `module/rubrics`（Wave 6） | **不经本模块**：rubrics 图片质量评估直连 `infra/ai.VisionModelClient`；语义可对齐 `VisionAssessment` 但无依赖 |
| `common` | 错误归一化 / `Sanitizer` / `ApiResponse`；`VisionErrorCode implements ErrorCode` 并入启动期目录聚合 |
| `pixflow-app` | 装配 vision 的富化 consumer 与 bean；分析面由 agent 层注入调用 |

**反向约束**：不依赖 `harness/*`（含 `harness/tools`）、`agent`、`module/dag`、`module/task`、`module/rubrics`、`module/file`（编译层）。

---

## 十四、测试策略

- **`VisionImagePreprocessor` 单测**：降采样长边正确、不放大、长宽比保持；格式归一（含透明通道铺底/转 PNG）；超大小单图跳过且记 `issues`。
- **`VisionImageResolver` 单测**：引用 → 字节；取图失败归一为 `VISION_IMAGE_RESOLVE_FAILED`（可重试）。
- **采样单测**：图数超 `images-per-call` 时按 `MAIN_FIRST`/`HEAD` 裁剪，`imagesSent` 正确。
- **`AssessmentParser` 单测（核心）**：合法 JSON → 完整 `VisionAssessment`；代码块包裹/多余文本容错；字段缺失置空；非法输出 → `parseDegraded=true` 且仅 `rawText`、低 confidence、**不抛异常**。
- **`VisionService.analyze` 契约测试**：以 fake `VisionModelClient` 替身验证 prompt 组装、图片 part 数量、结果映射与 usage 透传；全图解码失败 → `VISION_NO_DECODABLE_IMAGE`；空请求 → `VISION_EMPTY_REQUEST`。
- **错误透传测试**：infra/ai 抛限流/上下文超限 → vision 原样上抛（不自行重试、不吞）。
- **富化作业集成测试（Testcontainers：RocketMQ + MinIO + MySQL）**：发富化消息 → 消费 → 按 SKU 抽取 → gap-fill 写 `asset_copy`；doc 已有文案的 SKU 不被覆盖；重投幂等（已填充 SKU 跳过）。
- **`fill-policy` 单测**：`GAP_ONLY` 仅补空字段、`SKIP_IF_ANY` 已有任意文案则整 SKU 跳过。
- **`CopyEnrichmentErrorHandler` 测试**：VLLM 抖动 → `Retry`；毒消息 → `DeadLetter`；单 SKU 失败不阻断整包 ack。
- **依赖约束测试**：断言 vision 对 `harness/tools`、`module/file`、`agent` 零编译依赖（用 fake 替身验证能力面无需上层）。
- **错误码目录一致性**：`VisionErrorCode` 并入 `common` 启动期聚合（code 唯一 + i18n 齐全 + category 非空）。

---

## 十五、对 design.md 的细化

本模块对 `design.md` 及相邻设计文档做如下**细化**，需同步记录：

1. **vision 切成「无循环能力层 + agent 层编排」两层**：`design.md §10.1` 的「视觉理解子 Agent」中，VLLM 视觉理解**能力**落在 `module/vision`（Wave 3，无 think-act-observe 循环）；子 Agent 隔离语义（独立 child runtime、只读硬约束、只回 `ToolExecutionResult`）由 `agent` 层 `SubagentRunner`（Wave 5）实现。`agent(type=explore)` 不属于本模块。
2. **修正 DAG `tools → vision` 边**：`module-dependency-dag-plan.md` 的 `tools --> vision` 应**移除**。vision 不直产工具结果、不依赖 `harness/tools`；它返回领域对象 `VisionAssessment`，由 `agent` 层 runner 映射为 `ToolExecutionResult`，结果预算/外置由 tools 执行管线在 `agent` 工具返回时统一处理。
3. **新增 `storage → vision` 边**：vision 自己解析图片引用为字节（`infra/ai` 不碰 MinIO，ai.md §10），故依赖 `infra/storage`。
4. **新增 `image → vision` 边**：送 VLLM 前的降采样与格式归一（成本/兼容硬指标）复用 `infra/image`。
5. **新增 `mq → vision` 边（仅富化面）**：上传期文案抽取作为 MQ 驱动的富化作业由 vision 侧消费者承载；file 在解压终态发布富化消息（`file → mq` 已存在），二者经 MQ 解耦，无 `file → vision` 编译依赖。
6. **上传期 VLLM 文案抽取的归属与时序明确**：`tools.md §2.3` 所述「同 SKU 图抽取商品名/描述写 `asset_copy`」因 file/commerce 在 Wave 2、不依赖 infra/ai，**不在 Wave 2 实现**；改由 vision（Wave 3）的富化作业完成，doc 文案优先、VLLM 仅 gap-fill。
7. **本期分析面同步、大批量后台推迟**：`design.md §10.1` 的「大批量降级后台子任务」需 `module/task`/MQ 任务编排（Wave 4），本期 vision 仅同步 + 单次图数上限 + 采样控制规模，大批量后台视觉任务推迟到 task 就绪后由编排层接入。
8. **rubrics 不经 vision**：图片质量 Rubrics 评估直连 `infra/ai.VisionModelClient`（ai.md §14 已述），与 `module/vision` 无依赖；本文据此固化边界。

> 以上为对既有设计的补充落地，不改变 `design.md §5.2` 两套工具分离、§6.3 HITL、§十六 暂不考虑范围。建议同步更新 `module-dependency-dag-plan.md` 的 vision 依赖边（移除 `tools→vision`，新增 `storage/image/mq → vision`）。

---

## 十六、暂不考虑

- **大批量后台视觉子任务**：依赖 `module/task`/MQ 任务编排（Wave 4），本期只同步 + 采样，后续接入。
- **视觉结果缓存**：`(图片指纹, 问题)` → 评估的缓存本期不做（对齐 ai.md §16 对嵌入/生图缓存的暂缓口径），出现成本瓶颈再评估。
- **强制 JSON / structured-output**：随 `infra/ai` 本期能力，先用 prompt 约束 + 防御性解析；ai 引入 JSON mode 后再切强约束。
- **视觉识别分组 / 自动 SKU 绑定**：分组与 SKU 绑定由文件名编号决定（`design.md §9.5`、`module/file`），vision 不做图像内容识别分组。
- **生图**：生成式重绘是 `module/imagegen` 的职责（`submit_imagegen_plan` 提案 + 带外执行），不放进只读的 vision。
- **视频/多帧理解**：随 `design.md §16` 本期不做视频处理。
- **富化作业覆盖 doc 文案**：本期 doc 文案权威、VLLM 仅 gap-fill；「VLLM 文案择优替换 doc 文案」需评分支撑，本期不做。

---

## 十七、关键风险点（实施期必须守护的细节）

本期实施 vision 模块时，以下五处细节**不是性能优化**，而是「代码能编译但生产环境会出事」的隐性缺陷。每一处都用单测或 ArchUnit 守护，越早入测越早发现。

### 17·1 模型名称必须在 vision 外部

**风险**：开发者直接调用 `new VisionRequest(messages, options)` 时把模型字符串 `qwen-vl-max` 写到 `VisionProperties` 或 `VisionService` 内部某处——破坏了「型号由 `infra/ai` 的 `pixflow.ai.roles.vision` 配置承载」的边界。未来换模型（OpenAI 多模态 / Claude vision）必须全量替换 vision 源码而非改 YAML。

**守护**：ArchUnit `VisionArchitectureTest.noModelNameLiterals` 扫描 `com.pixflow.module.vision..` 全部 `.java` 文件，断言源码字面量不含 `qwen-vl-max` / `qwen-vl-plus` / `qwen-vl` / `gpt-4o` / `claude-3-*` / `dashscope` / `openai` / `anthropic`。

### 17·2 必须「只解码一次，只编码一次」

**风险**：vision 内部为了「先 EXIF 归正再降采样」连续两次 `ImageCodec.decode`，中间插入显式的 byte[] 缓冲——违背 `infra/image` 的「解码一次、编码一次」约定，造成两倍内存峰值与两倍 decoder 初始化开销。批量调用 6 张图时尤其明显。

**守护**：`VisionImagePreprocessorTest.preprocess_decodesOnce_encodesOnce` 用计数桩 fake `ImageCodec`，断言 `decode()` 仅被调 1 次、`encode()` 仅被调 1 次。如果未来 vision 需要多步处理，**必须**走 `ImagePipeline.run(source, [op1, op2], encode)` 链路而非自行调 `decode/encode`。

### 17·3 vision 不得吞 infra/ai 的 PixFlowException

**风险**：开发者在 `VisionService.analyze` 内加了 `try { ... } catch (PixFlowException e) { log.warn(...); throw new RuntimeException(e); }`——丢失 `category`/`retryAfter`/`code` 等归一化信号；上层 `agent runner` 收到一个非 `PixFlowException` 后无法按 `RecoveryHint` 决策，是「硬拒绝」还是「压缩后重试」。

**守护**：
- `VisionServiceAnaylzeTest.errorPassthrough_throwsInfraAiExceptionUnchanged` 注入 fake `VisionModelClient` 抛 `PixFlowException(MODEL_RATE_LIMITED, RATE_LIMIT, RETRY)`，断言 `analyze()` 上抛**同一实例**（`assertSame`）而非新异常。
- ArchUnit `VisionArchitectureTest.noSwallowedInfraAiErrors` 在 `VisionService.analyze` 方法体上做静态扫描，禁止 `catch (PixFlowException` + `throw new RuntimeException` 这种签名模式。

### 17·4 错误码命名空间必须全局唯一

**风险**：vision 写 `VISION_NO_DECODABLE_IMAGE`，commerce 的某次重构写出 `VISION_NO_DECODABLE_IMAGE`（碰巧用 VISION 前缀指代「视野/视图」），common 启动期聚合测试失败但**报错信息模糊**，定位是哪个模块违约困难。

**守护**：
- `VisionErrorCode` 4 条（阶段 A）+ `VisionEnrichErrorCode` 2 条（阶段 B）的 `code()` 字符串前缀统一为 `VISION_*` 与 `VISION_ENRICH_*`。
- 在 `common` 模块的 `ErrorCodeCatalogTest` 加守卫断言「`VISION_*` 前缀只允许 `com.pixflow.module.vision..` 包下出现」（同上 `IMAGEGEN_*` 已有的 10 条同样守护）。

### 17·5 图片字节不出日志

**风险**：开发者在 `VisionImageResolver` 调试时加了 `log.debug("resolved {} bytes for {}", bytes.length, imageRef.skuId())`——图片字节本身没入日志但**长度值+SKU**可以推断出大批量内容；在 debug 级别被 ELK / Loki 索引后即等同于「知道系统看了哪些图」。

**守护**：`VisionImageResolverTest.noLogStatementsContainByteArrays` 扫描 `VisionImageResolver` 全部方法体，断言不出现 `log.*(.*bytes` / `log.*(.*imageBytes` / `log.*(.*sourceBytes` 模式。如果元数据进日志，必须经 `common.Sanitizer` 脱敏。

> 这五条与 imagegen 在执行期发现的 `StorageKeys.generated` long/String 缺口、Mockito 流复用、AssertJ wildcard 不兼容等 risk 是同类——前者是设计期就预见到并显式守护的，后者是实施期才浮现的。

---

## Revision Notes

2026-06-29 / Kiro: 新增 `module/vision` 设计文档。确立「vision = 无 think-act-observe 循环的 VLLM 视觉理解能力层」「子 Agent 隔离语义归 agent 层 SubagentRunner（Wave 5）」「`explore` 不属本模块」「rubrics 直连 infra/ai 不经 vision」等边界；分析面同步、富化面（上传期文案抽取）经 MQ 解耦由 vision 消费者承载（doc 文案优先、VLLM gap-fill）；图片预处理（降采样/格式归一）在本模块完成。对 `module-dependency-dag-plan.md` 提出 vision 依赖边修正：移除 `tools→vision`，新增 `storage→vision`、`image→vision`、`mq→vision`（待同步）。

2026-06-29 / Kiro: 按生产级落地讨论结果扩写：① 显式拆分阶段 A「分析面优先」与阶段 B「富化作业独立里程碑」，明确阶段 A 上线后 Wave 5 `agent(vision)` 即可对接；② 新增 §三·二 关键 SPI 与 Bean 列表，明示 vision 阶段 A 不装配 `VisionToolHandler` / `VisionToolDescriptor`（Wave 5 才出现）；③ 新增 §三·三 ArchUnit 边界守护 14+ 条（vision 包不可 import `infra.ai.chat`、`harness.tools`、`harness.loop`、`agent`、`module.dag/task/conversation/rubrics/file`、`mybatis-plus/jdbc/redisson/messaging`、线程池、`@Scheduled`；不可出现 `qwen-vl-max` / `dashscope` / `openai` 等模型/供应商字面量）；④ 新增 §十七 关键风险点 5 条（模型名锁定、1+1 编解码、错误透传、命名空间、日志脱敏）；⑤ 与 imagegen 的关键差异（vision 的 `VisionService` 是直接门面，imagegen 的 `ImageGenExecutor` 是 SPI；vision 由 agent 直接注入，imagegen 由 task 装配）。

2026-07-02 / Codex: 同步 `infra/mq.md` 的 RocketMQ 目标设计，将富化作业从 RabbitMQ 队列组改为 RocketMQ `pixflow-vision` topic、`COPY_ENRICH` tag 与 `pixflow-vision-enricher` consumer group；移除 prefetch / consumer_timeout 等 RabbitMQ 专属表述，保留 MQ 解耦、process-then-ack、gap-fill 幂等与 DLQ 失败出口语义。
