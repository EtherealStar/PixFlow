# PixFlow 项目描述实现审计

审计日期：2026-07-11  
审计范围：生产源码、Spring 装配/配置、前端包清单、现有自动化测试文件。  
判定口径：

- **已实现**：生产路径可定位到核心逻辑，且有装配或测试证据。
- **部分实现**：核心组件存在，但描述包含未证实的 provider、运行时接线、性能或可靠性承诺。
- **未证实/占位**：只有接口、读侧协调器、fake、设计意图或测试替身，不能证明用户路径已闭环。
- **未发现**：在生产源码和依赖中未找到对应实现。

## 结论摘要

这份项目描述不能原样视为“全部真实”。可较有把握支持的部分是：Spring Boot/MySQL/Redis(Redisson)/RocketMQ/MinIO/Qdrant/Vue 技术依赖；分片上传与 Redis 会话状态的组件；RocketMQ 解压异步消息；图像处理 DAG 的 IR、校验、展开和执行框架；视觉输入的 resize/格式归一；commerce 查询的单 SKU、类目 benchmark、趋势聚合；MySQL + Qdrant 的混合记忆召回及衰减/强化/遗忘；Rubrics 的 LLM judge 评估闭环。Spring AI 目前是依赖而非实际 API 使用，不能作为核心实现亮点；DAG 默认真实执行适配尚未装配。

不能从代码支持或需要降级措辞的部分是：默认并非明确接入 VLLM（配置默认 custom/DashScope OpenAI-compatible）；“Redis 令牌桶”没有生产证据，找到的是 resilience4j RateLimiter；“100ms/40s”没有 benchmark 或 SLA 证据；checkpoint 是 task 级过期任务重投和 completed-unit 跳过，不等于 Agent token 级整链路恢复；“GB 级弱网稳定性”不但没有容量/故障注入测试，当前前后端整包 hash 算法还不一致；图片 DAG 的模型工具入口、提案和校验存在，但默认生产执行器仍注入抛异常的 storage/thirdparty/pipeline/writer stub，且“智能裁剪”“AI 重绘”不在当前 DAG 白名单中，因此完整图片执行链尚未闭环。

## 逐项核验

### 1. 技术栈

**判定：多数依赖可确认；Spring AI 和 provider 细节需改写。**

- 根 `pom.xml` 声明 Spring AI BOM 2.0.0、MinIO、Redisson、Qdrant、RocketMQ 版本（`pom.xml:53-64`, `pom.xml:239-241`）。
- `pixflow-infra-ai` 依赖 `spring-ai-model`，但 `pixflow-infra-ai/src/main/java` 没有 `org.springframework.ai` 的生产代码引用；模型调用由项目自建的 OpenAI-compatible client 完成。因此“使用 Spring AI”只能说明依赖已引入，不能说明核心调用链基于 Spring AI 实现。
- `docker-compose.yml` 启动 MySQL 8.4、RocketMQ 5.3.0、Qdrant 1.18.2、MinIO（`docker-compose.yml:2-18`, `docker-compose.yml:37-100`）。
- Vue 3 在 `pixflow-web/package.json:24-26`。
- 开发配置使用 MySQL、Redis、MinIO、Qdrant、RocketMQ（`pixflow-app/src/main/resources/application-dev.yml:2-27`）。
- AI 配置默认 provider 是 `custom`，视觉模型默认 `Qwen/Qwen3-VL-32B-Instruct`；另有 DashScope provider（`pixflow-app/src/main/resources/application-dev.yml:28-47`）。代码的视觉 client 明确是 OpenAI-compatible chat completions 的抽象（`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/vision/DefaultVisionModelClient.java:17-28`）。因此“接入 VLLM”只有在外部把 `LLM_BASE_URL` 指向 VLLM 兼容服务时才成立，当前默认配置并未证明使用 VLLM。

### 2. ReAct Agent / Harness 基础设施

**判定：部分实现。**

- AgentLoop 具备显式循环，并根据模型 tool calls 执行工具、写回结果、继续下一轮或完成（`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java:235-336`）。这支持“ReAct 风格循环”的代码事实。
- Harness 的 state/recovery 模块存在 `CheckpointReadPort`、`DefaultRecoveryCoordinator`，能读取已完成单元并返回可跳过集合（`pixflow-state/src/main/java/com/pixflow/harness/state/recovery/DefaultRecoveryCoordinator.java:12-24`）。task auto-configuration 也装配了 checkpoint read adapter（`pixflow-module-task/src/main/java/com/pixflow/module/task/config/TaskAutoConfiguration.java:312-313`）。
- 但现有证据是 task 状态读侧的恢复协调，不足以证明“Agent 异常后从最近 checkpoint 自动恢复整条链路、避免 token 重算”。未找到 AgentLoop 将模型上下文/工具调用 checkpoint 持久化并在失败后重放的生产路径；因此该句应改成“具备任务级完成单元恢复基础设施，Agent 整链路恢复仍需端到端证据”。

### 3. 图片处理工具、DAG 编排、异常节点恢复重试

**判定：提案/校验已实现；生产图片执行链是 scaffold/未闭环。**

- `pixflow-infra-image` 提供 resize、背景、格式转换、压缩、合成等 ImageOp 实现；`pixflow-module-dag` 提供 DAG IR、校验、节点/组执行器，且有 `DefaultImagePipelineTest`、`DagValidatorTest`、`PipelineUnitExecutorTest` 等测试文件。
- DAG 白名单只有 `remove_bg`、`set_background`、`resize`、`compress`、`watermark`、`convert_format`、`compose_group`、`generate_copy`（`pixflow-module-dag/src/main/java/com/pixflow/module/dag/ir/PixelTool.java:11-29`）。没有智能 crop 节点；AI 重绘由独立 imagegen 任务实现，不是该 DAG 的节点。
- AgentLoop 确实把 tool calls 送入 `executeToolCalls` 并在工具结果后继续模型循环（`AgentLoop.java:331-336`, `AgentLoop.java:394`）。
- `SubmitImagePlanHandler` 是真实 `ToolDescriptor`，让模型通过 `submit_image_plan` 提交并校验 DAG，但 handler 本身只把提案放入确认队列，注释明确说明“不执行像素处理”（`pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/SubmitImagePlanHandler.java:22-34`, `SubmitImagePlanHandler.java:49-95`）。这支持“自然语言由 Agent 生成 DAG 提案”，不是一个确定性自然语言解析器。
- `DagAutoConfiguration` 的默认 `PipelineUnitExecutor`/`GroupUnitExecutor` 把 storage reader、background removal、pixel pipeline、result writer 都装成抛 `UnsupportedOperationException` 的 stub（`pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java:86-158`）；在 `pixflow-app/src/main/java` 和 `pixflow-module-task/src/main/java` 未搜索到覆盖这些 executor/ports 的 Bean。因此生产启动时默认不能执行真实像素链。
- 任务状态 checkpoint 能支持 completed units 跳过，但未找到按 DAG 节点写入/恢复的完整故障恢复测试。因此“支持异常节点恢复与重试”应降为“有节点执行和状态恢复组件，端到端恢复未证明”。

### 4. 视觉理解、结构化描述、文案/重绘 Prompt、降采样与格式归一

**判定：视觉分析和预处理已实现；VLLM/文案链路需部分判定。**

- `VisionImagePreprocessor` 使用 `ResizeOp` 限制最长边，并使用 `ConvertFormatOp`/`EncodeSpec` 输出统一格式和质量（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/image/VisionImagePreprocessor.java:25-43`）。
- `DefaultVisionService` 调用视觉 client、构造 prompt、解析结构化 `VisionAnalysisResult`（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/DefaultVisionService.java:83-105`）。
- `ProductCopyExtractor`、`CopyEnrichmentConsumer` 等类存在，说明从视觉结果生成商品文案的业务组件存在；但描述中特指“视觉描述再生成营销文案与图片重绘 Prompt”的完整 Agent 链路和默认 VLLM provider 未在当前配置/调用点证明。

### 5. RocketMQ 异步解压/图片处理和“100ms/40s”

**判定：异步消息路径已实现；性能数字未证实。**

- 上传完成后 `UploadSessionService.complete` 发布 extraction 消息（`pixflow-module-file/src/main/java/com/pixflow/module/file/upload/UploadSessionService.java:151-170`）。
- `ExtractionPublisher` 使用 `MessagePublisher` 发布消息，`ExtractionConsumer` 消费后调用 `ZipExtractor`（`pixflow-module-file/src/main/java/com/pixflow/module/file/ingest/ExtractionPublisher.java:10-23`; `ExtractionConsumer.java:8-19`）。
- commerce API import 也有 publisher/consumer 类，且开发配置含 RocketMQ producer/consumer/retry（`application-dev.yml:18-27`）。
- 没有找到基准测试、监控断言或压测报告能证明“同步阻塞 40s+ 压缩到 100ms 内”。可写“接口提交消息后异步处理，提交阶段不执行解压”；不可写成已验证的 100ms SLA。

### 6. Redis + SHA-256 内容去重/幂等

**判定：组件存在，但当前前后端整包 hash 协议不一致，端到端上传闭环有阻断缺陷。**

- 初始化按 fileHash 查询 READY 包并在 Redis/cache lock 下复用已有上传会话（`UploadSessionService.java:68-101`）。
- 分片流式 SHA-256 校验由 `ChunkInputVerifier` 完成；合并对象时再次用 `DigestInputStream` 计算完整文件 hash 并比对（`UploadSessionService.java:205-230`, `UploadSessionService.java:279-315`）。
- `UploadSessionStore` 使用 `CacheStore` 保存 session、fileHash 映射和 chunk 状态（`UploadSessionStore.java:11-65`）。
- 前端 `sha256File` 计算的是 `SHA256(hex(SHA256(block1)) + hex(SHA256(block2)) + ...)`（`pixflow-web/src/upload/hasher.ts:44-78`），后端 `writeComposedObject` 计算的是原始完整文件字节流的标准 `SHA256(fileBytes)`（`UploadSessionService.java:286-309`）。两者通常不会相等，`complete` 会触发 `FILE_HASH_MISMATCH`。现有前端 hasher 单测只验证前端算法，没有前后端协议集成测试捕获这个问题。
- 因此这里只能确认“去重、分片幂等和完整性校验组件已写出”，不能确认正常上传闭环可用；也不能概括为所有 RocketMQ 消费均由 Redis+SHA-256 去重。

### 7. 分片上传、断点续传、Redis 分片状态、“GB 弱网稳定性”

**判定：协议组件已实现，但 hash 缺陷阻断完成阶段；GB/弱网稳定性不成立。**

- `init` 计算 expected chunks、复用 active upload；`putChunk` 校验 index/size/hash 并返回已上传 chunks；`complete` 合并所有 chunks（`UploadSessionService.java:69-180`）。
- chunk/session 状态放入 `CacheStore`，TTL 由 `UploadSessionStore` 管理（`UploadSessionStore.java:15-61`）。
- 配置限制最大 zip 大小（`UploadSessionService.java:235-255`），但未看到针对 GB 文件、网络中断、重传耗时、吞吐或大文件 E2E 的自动化测试/压测；同时第 6 节的 hash 协议不一致会阻断 `complete`。因此最多可称“实现了分片和续传协议组件”，不可声称已经保障 GB 级弱网稳定性。

### 8. checkpoint 断点恢复、避免重复计算/token

**判定：部分实现/措辞夸大。**

- `DefaultRecoveryCoordinator` 只读取 `CompletedUnits` 并返回可跳过单元（`pixflow-state/src/main/java/com/pixflow/harness/state/recovery/DefaultRecoveryCoordinator.java:19-23`）。
- 当前查到的 production 证据未覆盖模型 prompt、tool result、token usage 或 Agent iteration 的 checkpoint 写入和恢复。现有测试主要验证 coordinator 的读侧行为（`pixflow-state/src/test/java/com/pixflow/harness/state/recovery/RecoveryCoordinatorTest.java:17-44`）。
- 建议改写为“任务级已完成单元可恢复/跳过；Agent token 级断点恢复尚无端到端证据”。

### 9. Redis 令牌桶、指数退避、API 抖动兜底

**判定：指数退避/重试已实现；Redis 令牌桶未发现。**

- AI retry policy 使用指数增长 delay、max delay 和 jitter（`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/resilience/RetryPolicy.java:20-29`）；`ModelRetryRunner` 执行 retry（`ModelRetryRunner.java:21-90`），并有 `ModelRetryRunnerTest`。
- 找到的 rate limiter 是 resilience4j `RateLimiter`，位于 `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/resilience/ThirdPartyResilienceRegistry.java:15-78`，调用模板通过 `RateLimiter.decorateSupplier`（`ThirdPartyCallTemplate.java:12-50`）。未找到 `RRateLimiter`、Redis token bucket、Bucket4j 或 Redis 原子令牌桶生产代码。
- 因此应写“具备进程内/第三方 resilience4j 限流和指数退避”，不要写“基于 Redis 实现令牌桶”。

### 10. 电商数据导入、单商品查询、类目均值、趋势聚合、报告支撑

**判定：查询/导入能力已实现；“报告生成”在此模块仅提供数据结果。**

- REST 入口提供本地文件导入、API 异步导入、作业查询和 query（`pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/web/CommerceController.java:20-52`）。
- 查询服务聚合 SKU、可选类目 benchmark、trend，并返回 freshness/degraded 信息（`CommerceQueryService.java:45-112`）。
- MyBatis mapper 实现 SKU aggregate、类目 benchmark 和趋势 SQL（`CommerceDataMapper.java:30-119`）。
- `FakePlatformApiClient` 明确是本地验证/测试占位并返回空结果（`FakePlatformApiClient.java:5-13`），所以不能把“真实平台数据接入”默认当作已完成。Agent 生成结构化报告的最终组装需另有上层证据，本模块本身输出的是结构化查询结果。

### 11. MySQL + Qdrant 记忆、混合检索、衰减/强化/遗忘

**判定：核心机制已实现，运行依赖和降级路径需说明。**

- `DefaultInsightVectorRepo` 用 `VectorStore` 建 collection、upsert/search/delete，并保存 decay/reinforcement payload；`InsightIngestService` 生成 embedding 后写入向量库（`pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/DefaultInsightVectorRepo.java:22-69`; `InsightIngestService.java:100-108`）。
- `HybridInsightRecallService` 同时执行 vector 与 keyword search，用 `RrfFuser` 融合，并记录 degraded reasons（`HybridInsightRecallService.java:45-76`）。
- `DefaultInsightLifecycleService` 计算半衰期 decay，在阈值下 suppress/expire 并删除向量；`reinforce` 增加 access count/confidence/importance 并重算 decay（`DefaultInsightLifecycleService.java:29-76`）。
- MySQL mapper/entity 侧保存分析 insight、偏好和 SKU 历史。本轮实际运行 `MemoryMySqlQdrantIntegrationTest`，MySQL 与 Qdrant Testcontainers 启动后 2 项集成测试通过；这能证明测试环境中的双存储闭环，但不等于生产部署已连通。

### 12. Rubrics 评估闭环、LLM judge、质量/文案/决策评分

**判定：LLM judge + 规则评分 + 持久化/反馈触发已实现；覆盖面要谨慎。**

- `LlmJudge.judge` 根据是否有图片选择 vision 或文本 chat client，构造 prompt 并解析 verdict（`pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/judge/LlmJudge.java:30-61`）。
- `ItemEvaluator.evaluate` 对 rubric dimensions 执行规则 verifier 或 LLM judge，再由 `ScoreAggregator` 聚合（`pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/run/ItemEvaluator.java:47-68`）。
- `EvaluationRunner` 持久化 run/item、处理失败隔离、完成后触发 memory feedback（`EvaluationRunner.java:135-164`）；存在 daily batch、trigger listener 和 baseline/regression comparator。
- `JudgePromptBuilder` 明确要求 LLM 不返回数值，连续 0-100 分由程序计算（`JudgePromptBuilder.java:12-18`）。因此“使用 LLM judge 评分”成立，但“LLM 直接对图片质量、文案质量、决策质量全覆盖”需以实际 rubric template/生产配置为准，不应只凭模块名概括。

## 验证命令与局限

本审计执行了以下检索和验证命令：

- `Get-Content -Raw docs/design-docs/exec-plans/*.md`（读取当前 active plans；计划标记已实施，但计划文本不是实现证据）。
- `rg -n` 搜索 Maven/前端依赖、provider 配置、AgentLoop、checkpoint、限流/重试、上传 hash/chunk、commerce、memory、rubrics 生产源码。
- `Get-Content -Raw` 阅读上述关键实现文件。
- `pnpm --dir pixflow-web test`：93/93 通过；`pnpm --dir pixflow-web typecheck`：通过。
- `mvn -pl pixflow-module-file test`：21/21 通过，但没有上传前后端 hash 协议集成测试。
- `mvn -pl pixflow-module-vision test`：37/37 通过。
- `mvn -pl pixflow-module-memory test`：14/14 通过，其中 `MemoryMySqlQdrantIntegrationTest` 实际启动 MySQL 与 Qdrant Testcontainers 并通过 2 项集成测试。
- `mvn -pl pixflow-module-rubrics test`：3/3 通过；覆盖聚合、verdict parser、baseline comparator，未直接覆盖 `LlmJudge` 外部模型调用。
- `mvn -pl pixflow-module-dag test`：162 项中 160 通过、2 项失败。失败为 `PendingPlanMapperTest.pendingPlanStatus_enumValues`（测试期望 4 个状态，实际 5 个）和 `PendingPlanServiceTest.confirm_marksPending_asConfirmed`（`pending_plan` 状态竞争）。这意味着 DAG 核心执行器测试大多通过，但确认链当前并非全绿。

本轮由 memory 测试实际启动了 MySQL 与 Qdrant Testcontainers，但没有启动或联调 Redis、RocketMQ、MinIO、VLLM，也没有执行完整 reactor 测试。因此仍无法对整套应用连通性、实际吞吐、100ms/40s、GB 弱网和 VLLM endpoint 作实测保证。

## 建议的公开项目描述修订

建议将高风险表述改为：

> 基于 Spring Boot 的电商运营 Agent，采用 ReAct 风格工具循环和 Harness 运行时基础设施。已实现 Redis 上传会话与分片状态、RocketMQ 异步解压/导入、图像预处理与 DAG 基础执行、OpenAI-compatible 视觉分析、电商指标查询/类目 benchmark/趋势聚合、MySQL + Qdrant 混合记忆召回及生命周期治理，以及 Rubrics 的规则与 LLM judge 评估。任务恢复支持过期任务重投和已成功工作单元跳过。当前上传整包 hash 协议需修复；Spring AI 核心调用、VLLM 默认接入、性能 SLA、GB 弱网稳定性和 Agent token 级断点恢复尚不能作为已验证能力。
