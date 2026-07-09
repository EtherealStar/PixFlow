# 完善 infra/ai 全部模型 Client 能力

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录 `PLANS.md`。后续执行者必须在推进、暂停、调整方向或完成任一里程碑时同步更新本文档，使一个没有上下文的新贡献者只读本文档也能继续完成工作。

## Purpose / Big Picture

完成本计划后，`pixflow-infra-ai` 将从“只有文本 chat client 可装配”推进为完整的模型调用层：文本对话、视觉理解、源图重绘、文本向量化、候选重排五类 client 都通过统一配置、统一错误模型、统一重试与并发机制暴露给上层模块。用户可以启动 PixFlow app，并让 vision、imagegen、memory、rubrics 等模块在需要模型能力时拿到真实的 Spring bean，而不是依赖测试桩或条件缺席。

这项工作不是把模型调用逻辑塞进业务模块。`infra/ai` 的职责是回答“调用一次模型会发生什么”：组装供应商请求、执行 HTTP 调用、解析结果、归一化错误、记录指标、遵守并发限制。Agent 主循环、工具执行、上下文裁剪、MinIO 读写、Qdrant 存储、任务编排都不属于本模块。

## Progress

- [x] (2026-07-05 +08:00) 阅读 `PLANS.md` 与 `docs/design-docs/infra/ai.md`，确认计划格式、设计边界和必须覆盖的 client 类型。
- [x] (2026-07-05 +08:00) 扫描当前 `pixflow-infra-ai` 源码，确认当前只有 `DefaultChatModelClient` 一个真实实现，`VisionModelClient`、`ImageGenClient`、`EmbeddingClient`、`RerankClient` 仅有接口和 DTO。
- [x] (2026-07-05 +08:00) 创建本 ExecPlan，记录完善所有 client 的机制、里程碑、验证方式和设计文档检索关键词。
- [x] (2026-07-05 +08:00) 实现共享 DashScope HTTP 内核 `DashScopeHttpClient` 和投影工具 `ProviderPayloads`，统一 base-url 归一化、调用期 API key 校验、Authorization header、HTTP 错误映射、usage 解析与图片 bytes→data URI 转换。
- [x] (2026-07-06 +08:00) 完善 `ChatModelClient` 的真流式、tool-call 分片累积和已有阻塞调用兼容。已完成 bytes 图片 data URI 投影、OpenAI-compatible SSE 真流式解析、tool-call 分片累积到 `Completed`、`call(...)` 复用 `stream(...)` 折叠结果，以及首个 `TextDelta` 前/后的重试语义修正。
- [x] (2026-07-05 +08:00) 实现并装配 `VisionModelClient`，作为独立 bean 暴露，底层复用 chat-compatible 调用并使用 `ModelRole.VISION`。
- [x] (2026-07-05 +08:00) 实现并装配 `EmbeddingClient`，支持批量文本校验、DashScope compatible `/embeddings` 调用、向量顺序保持、usage 兜底、指标、并发许可与阻塞透明重试。
- [ ] 实现并装配 `ImageGenClient`。（已完成：默认 bean、源图 bytes→data URI、同步 JSON/base64 响应解析、指标、并发许可与阻塞透明重试；剩余：真实 DashScope 异步任务式提交/轮询到终态。）
- [x] (2026-07-05 +08:00) 实现并装配 `RerankClient`，支持 query/candidates 校验、DashScope rerank HTTP 调用、候选原始 index 保留、usage 兜底、指标、并发许可与阻塞透明重试。
- [ ] 补齐模块级、上层集成级和可选真实 provider 冒烟验证。（已完成：`pixflow-infra-ai -am test`、vision/memory/imagegen/task/app 级 Maven 测试、chat fake provider SSE 契约测试；剩余：embedding/rerank/imagegen fake provider HTTP 契约测试覆盖真实响应投影与可选真实 provider 冒烟。）

## Surprises & Discoveries

- Observation: 当前活跃依赖计划把 `infra/ai` 标记为 “Spring AI + Alibaba 封装（文本/多模态 Qwen-VL/生图 通义万相/嵌入）” 已完成，但源码里只有 `ChatModelClient` 被自动装配。
  Evidence: `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中 `infra/ai` 为 Wave 1 已勾选；`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiAutoConfiguration.java` 只注册 `ChatModelClient`。

- Observation: `DefaultChatModelClient` 已经能把文本和部分图片消息投影到 OpenAI-compatible chat completions 请求，但对 `ChatMessage.BytesImageContent` 明确抛 `MODEL_UNSUPPORTED_CAPABILITY`。
  Evidence: `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java` 的 `imageUrl(...)` 只接受 data URI 和 URL 图片内容。

- Observation: 上层模块对缺失 client 的敏感度不同。`module/vision` 对 `VisionModelClient` 是启动期硬依赖；`module/memory` 对 `EmbeddingClient` 有降级路径但向量写入和重建能力会缺席；`module/imagegen` 在暴露执行器或 task 接线时需要 `ImageGenClient`；`RerankClient` 当前更多是预留增强能力。
  Evidence: `pixflow-module-vision/src/main/java/com/pixflow/module/vision/config/VisionServiceAutoConfiguration.java` 直接注入 `VisionModelClient`；`pixflow-module-memory/src/main/java/com/pixflow/module/memory/config/MemoryAutoConfiguration.java` 使用 `ObjectProvider<EmbeddingClient>` 和 `@ConditionalOnBean(EmbeddingClient.class)`；`pixflow-module-task/src/main/java/com/pixflow/module/task/config/TaskAutoConfiguration.java` 用 `ImageGenClient` 创建 `ImageGenExecutor`。

- Observation: 当前 `ModelRetryRunner#run(...)` 会 materialize 并 collect 整个 Flux 后再发给下游，因此 `ChatModelClient.stream(...)` 即使接入 SSE 也还不能称为真流式。
  Evidence: `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/resilience/ModelRetryRunner.java` 的 `tryAttempt(...)` 使用 `materialize().collectList().flatMapMany(...)`；本次只新增 `runBlocking(...)` 给 embedding/rerank/imagegen 这类无可见 token 的阻塞能力使用。

- Observation: `ModelRetryRunner#run(...)` 已改为边发边处理错误，`DefaultChatModelClient.stream(...)` 已走 OpenAI-compatible `text/event-stream`。
  Evidence: `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/resilience/ModelRetryRunner.java` 使用 `doOnNext` 标记首个 `TextDelta` 并在 `onErrorResume` 中决定透明重试或 `AttemptReset`；`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java` 发送 `stream=true` 并解析 `ServerSentEvent<String>`。

- Observation: 应用级验收命令耗时明显高于默认短超时。
  Evidence: `mvn -pl pixflow-app -am test` 首次 244 秒超时；以 600 秒超时重跑后 5 分 58 秒完成并 `BUILD SUCCESS`。

## Decision Log

- Decision: 先在现有 `WebClient` + DashScope OpenAI-compatible HTTP 路线上完善 provider adapter，不把本计划的完成阻塞在新增 Spring AI Alibaba starter 上。
  Rationale: 当前 `pixflow-infra-ai/pom.xml` 已依赖 `spring-ai-model` 和 `spring-webflux`，实际实现也已经用 `WebClient` 调 provider。保持这一方向可以最小化 Maven 依赖波动，并仍然遵守 `infra/ai.md` 的 provider-neutral 接口边界。未来如果 Spring AI Alibaba starter 稳定可用，可以只替换 provider adapter，不改上层接口。
  Date/Author: 2026-07-05 / Codex

- Decision: 新增共享 provider HTTP 内核，但保留五个能力隔离接口。
  Rationale: Chat、Vision、Embedding、Rerank、ImageGen 都需要 API key 校验、base-url 归一化、HTTP 错误映射、超时、指标、并发许可和脱敏。共享内核减少重复；能力接口保持隔离，避免 memory 看见 imagegen、vision 看见 embedding。
  Date/Author: 2026-07-05 / Codex

- Decision: 视觉 client 复用 chat completions 投影，但作为独立 `VisionModelClient` bean 装配。
  Rationale: `infra/ai.md` 要求 Chat 与 Vision 接口分离，原因是上层消费方不同、逻辑角色不同、并发限额不同。底层可以共享投影与 HTTP 调用，Spring 容器中必须有独立的 `VisionModelClient`。
  Date/Author: 2026-07-05 / Codex

- Decision: 常规自动化测试不得依赖真实 DashScope 网络；真实 provider 冒烟测试只在显式提供 API key 和启用开关时运行。
  Rationale: 本仓库开发环境网络受限，CI 也不能假定有模型凭证。主要正确性应由本地 fake provider HTTP server 和单元测试证明，真实冒烟只验证配置到外部服务的最后一公里。
  Date/Author: 2026-07-05 / Codex

- Decision: `DefaultVisionModelClient` 作为独立 bean 暴露，但内部委托 `ChatModelClient` 并构造 `ModelRole.VISION` 的 `ChatRequest`。
  Rationale: Vision 的公共接口必须与 Chat 隔离，便于上层按能力注入；当前 DashScope 视觉理解仍走 OpenAI-compatible chat completions，多维护一份 HTTP 投影会制造重复和漂移。
  Date/Author: 2026-07-05 / Codex

- Decision: 本轮先为 `DefaultImageGenClient` 实现同步 JSON/base64 结果路径，不在同一改动中补 DashScope 异步任务轮询。
  Rationale: imagegen 的真实 provider 形态比 chat/embedding/rerank 更分散，异步任务提交、状态轮询、终态下载和超时控制需要单独 fake provider 契约测试支撑。本轮先让 Spring 上下文和上层执行器拿到真实默认 bean，并保留接口边界不变。
  Date/Author: 2026-07-05 / Codex

## Outcomes & Retrospective

2026-07-05 / Codex: 完成第一轮默认 client 能力补齐。`pixflow-infra-ai` 新增共享 DashScope HTTP 内核、bytes 图片 data URI 投影、`DefaultVisionModelClient`、`DefaultEmbeddingClient`、`DefaultImageGenClient`、`DefaultRerankClient` 和五类 client 自动装配；缺 API key 仍只在调用期返回 `MODEL_CONFIGURATION_ERROR`，不阻断 Spring context 创建。已通过 `mvn -pl pixflow-infra-ai -am test`、`mvn -pl pixflow-infra-ai,pixflow-module-vision -am test`、`mvn -pl pixflow-infra-ai,pixflow-module-memory -am test`、`mvn -pl pixflow-infra-ai,pixflow-module-imagegen,pixflow-module-task -am test`、`mvn -pl pixflow-app -am test`。剩余关键缺口是真流式 SSE、tool-call 流式分片、imagegen 异步任务轮询、fake provider HTTP 契约测试和可选真实 provider 冒烟。

2026-07-06 / Codex: 完成 `ChatModelClient` 真流式收尾。`DefaultChatModelClient.stream(...)` 现在发送 OpenAI-compatible `stream=true` 请求，解析 `text/event-stream` 的文本 delta、tool-call delta 和 usage；`call(...)` 继续通过 `stream(...)` 折叠终态；`ModelRetryRunner#run(...)` 不再收集整个 Flux，而是在首个 `TextDelta` 前透明重试、首个 `TextDelta` 后发 `AttemptReset` 再重试。新增 `DefaultChatModelClientTest` 覆盖 fake provider SSE 文本流、tool-call 分片和请求投影，新增 `ModelRetryRunnerTest` 覆盖首 token 前/后重试语义。已通过 `mvn -pl pixflow-infra-ai -am test`。剩余关键缺口是 imagegen 异步任务轮询、embedding/rerank/imagegen fake provider HTTP 契约测试和可选真实 provider 冒烟。

## Context and Orientation

`pixflow-infra-ai` 是 Maven 模块，路径为 `pixflow-infra-ai/`，Java 包根是 `com.pixflow.infra.ai`。它只应该依赖 `pixflow-common`、Spring 基础设施、HTTP 客户端、JSON 和 Micrometer，不应该依赖任何 `module/*`、`harness/*`、`agent/*`、`infra/storage`、`infra/vector` 或 `infra/cache` 的具体实现。全局并发通过 `GlobalConcurrencyLimiter` SPI 倒置完成：SPI 定义在 `infra/ai`，实现可由 `infra/cache` 在装配期注入。

当前重要文件如下。

`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiAutoConfiguration.java` 负责注册 Spring bean。当前注册 `ModelRouter`、`RetryPolicy`、`AiMetrics`、`ConcurrencyGuard`、`ModelRetryRunner`、`ToolCallAccumulator` 和五类默认 client bean：`ChatModelClient`、`VisionModelClient`、`EmbeddingClient`、`ImageGenClient`、`RerankClient`。

`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiProperties.java` 使用配置前缀 `pixflow.ai`。其中 `roles.primary-chat`、`roles.vision`、`roles.imagegen`、`roles.embedding`、`roles.rerank` 把逻辑角色映射到 provider、model、capability 和默认参数。逻辑角色是上层选择模型用途的名字，不能在业务模块里写死具体模型型号。

`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/model/DefaultModelRouter.java` 把 `ModelRole` 解析成 `ResolvedModel`，并校验角色期望的 `ModelCapability`。所有 client 都必须通过它读取 provider、model 和超时，不允许绕过配置写死模型。

`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java` 通过 `WebClient` 访问 DashScope OpenAI-compatible `/chat/completions`，能做 SSE 真流式、阻塞折叠调用、错误映射、指标记录和并发许可释放。文本 delta 以 `ChatStreamEvent.TextDelta` 发出，tool-call 分片累积到终态 `Completed`。

`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/vision/VisionModelClient.java`、`imagegen/ImageGenClient.java`、`embedding/EmbeddingClient.java`、`rerank/RerankClient.java` 是能力接口。它们代表上层模块能调用的稳定契约，当前均已有默认实现和自动装配；`ImageGenClient` 仍缺 DashScope 异步任务式提交/轮询路径。

几个关键术语在本文中这样使用。Provider 是模型供应商或兼容模型网关，例如 DashScope 或兼容 OpenAI 协议的模型服务。Adapter 是把 PixFlow 内部请求 record 转成 provider HTTP 请求、再把 provider JSON 响应转回 PixFlow record 的实现类。Data URI 是把图片字节变成 `data:image/jpeg;base64,...` 这种字符串的格式，用于把调用方传入的图片 bytes 放进多模态请求。Rerank 是“重排”，意思是给定一个 query 和一组候选文本，让模型返回每个候选的相关性得分。

## Design Document Search Keywords

执行者需要从设计文档快速定位依据时，优先打开 `docs/design-docs/infra/ai.md` 并搜索这些关键词。

搜索 `为什么 infra/ai 是「模型调用层」而非 Agent 层`，可以确认 ai 不做主循环、不执行工具、不压缩上下文、不写业务状态。

搜索 `核心抽象（能力隔离接口）`，可以定位 `ChatModelClient`、`VisionModelClient`、`ImageGenClient`、`EmbeddingClient`、`RerankClient` 的职责和接口形状。

搜索 `模型路由：逻辑角色 → 供应商型号`，可以定位为什么所有 client 都要通过 `ModelRole`、`ModelRouter` 和 `pixflow.ai.roles.*` 读取模型配置。

搜索 `流式事件模型与 tool-call 累积`，可以定位 `ChatStreamEvent.TextDelta`、`AttemptReset`、`Completed` 和 `ToolCallAccumulator` 的行为要求。

搜索 `韧性与重试：方案 C`，可以定位首次下游发射前后不同的重试策略。

搜索 `错误归一化与源头构造`，可以定位 provider HTTP 错误、鉴权错误、上下文超限、非法 tool arguments 应映射成哪些 `AiErrorCode`。

搜索 `全局并发封顶：SPI 倒置`，可以定位 `GlobalConcurrencyLimiter` 与 `ConcurrencyGuard` 的关系。

搜索 `多模态与生图边界`，可以定位为什么 `infra/ai` 接收图片 bytes / data URI / URL，但不碰 MinIO，生图结果也不落存储。

搜索 `嵌入与重排边界`，可以定位为什么 `EmbeddingClient` 只生成向量，Qdrant 存取属于 `infra/vector`，以及为什么 `RerankClient` 不直接依赖 memory。

搜索 `测试策略`，可以定位本模块必须覆盖的单元测试、fake provider 测试和可选真实 provider 冒烟测试。

还需要用 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 搜索 `infra/ai`、`Wave 1`、`vision`、`imagegen`、`memory`，确认 ai 是基础设施上游模块，不能反向依赖业务模块。

## Plan of Work

第一阶段先整理公共 provider 调用内核。新增 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/provider/` 包，包内放不暴露给上层的 provider 支撑类。建议命名为 `ProviderHttpClient`、`ProviderRequestFactory`、`ProviderResponseParser` 或按 DashScope 细分为 `dashscope/DashScopeHttpClient`、`dashscope/DashScopePayloads`、`dashscope/DashScopeResponses`。这些类只服务 infra/ai 内部，不被上层模块 import。它们负责 base-url 归一化、API key 检查、Authorization header、content-type、timeout、HTTP status 到 `PixFlowException` 的映射、response body JSON 读取和敏感字段脱敏。

公共内核必须复用现有 `ProviderErrorMapper`。所有 provider HTTP 错误都应该在源头构造成 `PixFlowException`，让 `ModelRetryRunner` 可以根据 `RecoveryHint` 和错误类别决定是否重试。不要在 adapter 里抛裸 `WebClientResponseException`、`IllegalStateException` 或 provider SDK 异常给上层。

第二阶段完善 `ChatModelClient`。保留现有阻塞 `call(ChatRequest)` 的行为，同时把消息投影抽到共享工具中，使 chat 和 vision 都能使用。`ChatMessage.TextPart` 投影为文本；`ChatMessage.ImagePart` 中的 `DataUriImageContent` 保持 data URI；`UrlImageContent` 保持 URL；`BytesImageContent` 必须转成 data URI。转换时按 `contentType` 生成 `data:<contentType>;base64,<base64>`，不得记录原始图片 bytes。

随后实现真流式 `stream(ChatRequest)`。对 OpenAI-compatible streaming 响应，发送请求时启用 stream，读取 SSE chunk，遇到文本 delta 时发 `ChatStreamEvent.TextDelta`，遇到 tool-call delta 时交给 `ToolCallAccumulator` 按 index 累积，终止时发 `Completed`。如果 provider 或当前配置不支持流式，可以保留阻塞折叠路径作为 fallback，但测试必须覆盖默认路径的真流式解析。`call(...)` 应继续通过 `stream(...)` 折叠完成结果，避免阻塞和流式两套逻辑漂移。

第三阶段实现 `VisionModelClient`。新增 `DefaultVisionModelClient`，构造依赖与 `DefaultChatModelClient` 保持一致：`AiProperties`、`ModelRouter`、`ModelRetryRunner`、`ConcurrencyGuard`、`AiMetrics`、`ObjectMapper`、`WebClient.Builder` 或共享 provider client。`call(VisionRequest)` 使用 `ModelRole.VISION` 解析模型，把 `VisionRequest.messages()` 投影成多模态 chat completions 请求，工具列表为空，options 来自请求覆盖项。它返回 `ChatResult`，让 `module/vision` 继续负责 JSON 防御性解析。

第四阶段实现 `EmbeddingClient`。新增 `DefaultEmbeddingClient`，使用 `ModelRole.EMBEDDING` 解析模型。`embed(List<String> texts)` 必须校验输入不为空、每项文本非空，然后调用 provider 的 embedding endpoint。响应解析为与输入顺序一致的 `List<EmbeddingVector>`，每个向量做 defensive copy，usage 转为 `TokenUsage`。如果 provider 响应缺少 usage，则使用 `TokenUsage(0, 0, 0)` 而不是空值。它不创建 Qdrant collection，不写向量库，只返回向量。

第五阶段实现 `ImageGenClient`。新增 `DefaultImageGenClient`，使用 `ModelRole.IMAGEGEN` 解析模型。`generate(ImageGenRequest)` 接收源图 bytes、contentType、prompt 和 options，并调用 provider 的源图重绘能力。由于生图 provider API 可能不是 chat completions 形状，adapter 可以使用 provider-specific endpoint，但返回必须收口为 `ImageGenResult(byte[] image, String contentType, TokenUsage usage)`。如果 provider 是异步任务式 API，`generate(...)` 可以在单次调用内部提交任务并轮询到终态，但必须受 `ResolvedModel.timeout()` 限制，轮询错误经 `ProviderErrorMapper` 归一化。它不写 MinIO，不检查 HITL 确认令牌，不创建 task。

第六阶段实现 `RerankClient`。新增 `DefaultRerankClient`，使用 `ModelRole.RERANK` 解析模型。`rerank(String query, List<String> candidates)` 校验 query 非空、候选列表非空，然后调用 provider 的 rerank endpoint。响应必须返回与候选可对应的 `RerankScore` 列表，保留原始候选 index，得分越高代表越相关。它不直接读取 memory，不做 RRF 融合，不写数据库。若 provider 没有 OpenAI-compatible rerank endpoint，可以在 adapter 内保留 DashScope-specific 请求形状，但接口不变。

第七阶段扩展自动装配。修改 `AiAutoConfiguration`，在现有 `chatModelClient` 旁新增 `visionModelClient`、`embeddingClient`、`imageGenClient`、`rerankClient` 四个 `@Bean` 方法，全部带 `@ConditionalOnMissingBean`。这些 bean 不应要求 API key 在启动期存在；API key 缺失应该在真正调用 client 时返回 `MODEL_CONFIGURATION_ERROR`。这样本地开发可以启动 app，调用模型能力时再看到清晰错误。

第八阶段补充测试和上层验证。`pixflow-infra-ai` 内用 JDK 自带 `com.sun.net.httpserver.HttpServer` 或 Reactor 可控 publisher 搭 fake provider，不依赖真实网络。测试覆盖每个 client 的 bean 装配、请求投影、成功响应解析、HTTP 错误映射、缺 API key 行为、并发许可释放和指标记录。上层模块只需要轻量装配测试证明有真实 bean 后能形成完整上下文，不需要在上层重复 provider 协议测试。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。

先确认当前状态，命令如下。

    rg -n "interface .*Client|class Default.*Client|public .*Client .*\\(" pixflow-infra-ai/src/main/java/com/pixflow/infra/ai -S

当前预期会看到只有 `DefaultChatModelClient implements ChatModelClient`，其他 client 只有接口。完成本计划后，预期能看到 `DefaultVisionModelClient`、`DefaultImageGenClient`、`DefaultEmbeddingClient`、`DefaultRerankClient`，以及 `AiAutoConfiguration` 中五类 client 的 bean 方法。

实现公共 provider 工具后，运行：

    mvn -pl pixflow-infra-ai -am test

这一命令应该先覆盖现有测试，并新增 provider URL 归一化、API key 缺失、HTTP 错误映射、data URI 转换的单元测试。若测试失败，先完善 `pixflow-infra-ai` 内部，不要绕到业务模块改代码。

实现 chat 真流式后，新增测试至少覆盖以下行为：provider 发送两个文本 delta，`stream(...)` 下游收到两个 `TextDelta` 和一个 `Completed`；provider 分片发送 tool-call arguments，`Completed.toolCalls` 中参数是合法完整 JSON，并按 index 排序；首个 token 前 provider 429 时透明重试；已发射 token 后 provider 断流时发 `AttemptReset`。

实现 vision 后，运行：

    mvn -pl pixflow-infra-ai,pixflow-module-vision -am test

预期 `pixflow-infra-ai` 的 `AiAutoConfigurationTest` 能断言 `VisionModelClient` 存在；`pixflow-module-vision` 的自动装配测试不再需要只靠本地 fake `VisionModelClient` 才能说明能力层可装配。真实 provider 不可用时，测试仍应使用 fake HTTP server。

实现 embedding 后，运行：

    mvn -pl pixflow-infra-ai,pixflow-module-memory -am test

预期 memory 的向量召回、写入、重建相关装配路径可以在有 `EmbeddingClient` bean 时启用。若 Qdrant 或 MySQL 集成测试按环境跳过，这不是本计划失败；关键是纯单测和自动装配测试必须通过。

实现 imagegen 后，运行：

    mvn -pl pixflow-infra-ai,pixflow-module-imagegen,pixflow-module-task -am test

预期 `DefaultImageGenExecutor` 和 task 中的 imagegen worker 接线能在存在 `ImageGenClient` 时形成完整路径。测试中的 provider 响应应返回小尺寸假图片 bytes，验证结果 bytes 和 contentType 能进入 `ImageGenResult`，但不在 `infra/ai` 内落 MinIO。

实现 rerank 后，运行：

    mvn -pl pixflow-infra-ai -am test

并补一组调用层测试，验证候选 index、分数、usage 和错误映射。若上层暂未消费 `RerankClient`，不要为了演示而把它硬接进 memory；memory 后续是否使用 rerank 是业务排序策略，不属于本计划。

所有 client 完成后，运行：

    mvn -pl pixflow-app -am test

然后在没有 `DASHSCOPE_API_KEY` 的情况下启动 app。预期行为是 Spring context 能创建所有 client bean 并完成启动；只有调用任一模型 client 时才得到 `MODEL_CONFIGURATION_ERROR`。如果启动期因为 API key 缺失失败，说明 client 在 bean 构造期做了外部配置硬校验，应改为调用期校验。

可选真实 provider 冒烟测试只在显式提供环境变量时执行。建议使用环境变量 `DASHSCOPE_API_KEY`、`PIXFLOW_AI_SMOKE=true`。真实冒烟应覆盖 chat、vision、embedding 三类低成本调用；imagegen 成本较高，可以单独加 `PIXFLOW_AI_IMAGEGEN_SMOKE=true` 后才跑；rerank 如果 provider 支持，再用单独开关启用。

## Validation and Acceptance

本计划的验收不是“新增了几个类”，而是每个上层模块能通过自己的稳定接口观察到模型能力。

基础验收：运行 `mvn -pl pixflow-infra-ai -am test`，应看到所有 infra-ai 单测通过。新增测试要能证明五类 client 都能从 `AiAutoConfiguration` 中装配出来，缺 API key 不影响装配，调用时才返回 `MODEL_CONFIGURATION_ERROR`。

Chat 验收：fake provider 输出流式文本时，`ChatModelClient.stream(...)` 立即发出 `TextDelta`；fake provider 输出 tool-call 分片时，`Completed.toolCalls` 中 arguments 是完整 JSON 且顺序稳定；`ChatModelClient.call(...)` 仍能返回完整 `ChatResult`。

Vision 验收：`VisionModelClient.call(...)` 能把包含 `BytesImageContent` 的 `VisionRequest` 投影成 provider 可接收的 data URI 图片消息，并返回 `ChatResult`。`module/vision` 的 `VisionService` 能在 app 上下文中拿到 `VisionModelClient` bean。

Embedding 验收：`EmbeddingClient.embed(List.of("hello"))` 在 fake provider 下返回一条向量，向量维度和 provider 响应一致，且 `module/memory` 在有 `EmbeddingClient` 和 `VectorStore` 时能启用向量相关 bean。

ImageGen 验收：`ImageGenClient.generate(...)` 在 fake provider 下返回图片 bytes 和 contentType；`module/task` 能在有 `ImageGenClient`、`ObjectStorage` 和 `ImagegenProperties` 时装配 `ImageGenExecutor`。

Rerank 验收：`RerankClient.rerank("query", candidates)` 在 fake provider 下返回候选 index 与分数，排序消费方可以按得分使用结果，usage 非空。

App 验收：运行 `mvn -pl pixflow-app -am test` 通过。启动 app 时，`pixflow-infra-ai` 的五类 client bean 都能创建；无 API key 不应阻断启动。

## Idempotence and Recovery

本计划所有代码改动都应是增量式。新增 provider 支撑类和默认 client 实现不会破坏现有接口；`@ConditionalOnMissingBean` 允许测试或部署方覆盖任一 client bean。重复运行 Maven 测试不会修改数据库或外部服务。

如果某个 provider endpoint 形状与预期不一致，不要改上层接口适配 provider。应在 `pixflow-infra-ai` 内新增或调整 provider-specific adapter，并在 `Surprises & Discoveries` 记录证据。上层模块仍只依赖 `ChatModelClient`、`VisionModelClient`、`ImageGenClient`、`EmbeddingClient`、`RerankClient`。

如果真实 provider 冒烟失败但 fake provider 单测通过，先判断是凭证、模型名、base-url、网络还是 provider 能力问题。真实冒烟失败不应导致常规 CI 失败，除非失败暴露出请求投影和设计契约不一致。

如果实施过程中发现 Spring AI Alibaba starter 已可稳定解析，可以在不改变本计划接口的前提下用它实现某些 provider adapter；但必须保留本地 fake provider 测试，并在 `Decision Log` 写明切换原因和影响。

## Artifacts and Notes

当前源码搜索证据如下。

    rg -n "interface .*Client|class Default.*Client|public .*Client .*\\(" pixflow-infra-ai/src/main/java/com/pixflow/infra/ai -S
    pixflow-infra-ai/.../AiAutoConfiguration.java:90:    public ChatModelClient chatModelClient(
    pixflow-infra-ai/.../AiAutoConfiguration.java:112:    public VisionModelClient visionModelClient(ChatModelClient chatModelClient) {
    pixflow-infra-ai/.../AiAutoConfiguration.java:118:    public EmbeddingClient embeddingClient(
    pixflow-infra-ai/.../AiAutoConfiguration.java:129:    public ImageGenClient imageGenClient(
    pixflow-infra-ai/.../AiAutoConfiguration.java:140:    public RerankClient rerankClient(
    pixflow-infra-ai/.../DefaultChatModelClient.java:43:public final class DefaultChatModelClient implements ChatModelClient
    pixflow-infra-ai/.../DefaultVisionModelClient.java:12:public final class DefaultVisionModelClient implements VisionModelClient
    pixflow-infra-ai/.../DefaultEmbeddingClient.java:27:public final class DefaultEmbeddingClient implements EmbeddingClient
    pixflow-infra-ai/.../DefaultImageGenClient.java:22:public final class DefaultImageGenClient implements ImageGenClient
    pixflow-infra-ai/.../DefaultRerankClient.java:26:public final class DefaultRerankClient implements RerankClient

本文档的主题是把 `infra/ai` 完整建设成所有上层模块都能复用的模型调用层，而不是围绕某一个启动现象做局部处理。

## Interfaces and Dependencies

本计划结束时，以下接口保持不变，除非有明确兼容性理由并同步更新所有调用方。

`com.pixflow.infra.ai.chat.ChatModelClient`

    ChatResult call(ChatRequest request);
    Flux<ChatStreamEvent> stream(ChatRequest request);

`com.pixflow.infra.ai.vision.VisionModelClient`

    ChatResult call(VisionRequest request);

`com.pixflow.infra.ai.imagegen.ImageGenClient`

    ImageGenResult generate(ImageGenRequest request);

`com.pixflow.infra.ai.embedding.EmbeddingClient`

    EmbeddingResult embed(List<String> texts);

`com.pixflow.infra.ai.rerank.RerankClient`

    RerankResult rerank(String query, List<String> candidates);

需要新增的默认实现建议如下。

`com.pixflow.infra.ai.vision.DefaultVisionModelClient` 使用 `ModelRole.VISION`。

`com.pixflow.infra.ai.imagegen.DefaultImageGenClient` 使用 `ModelRole.IMAGEGEN`。

`com.pixflow.infra.ai.embedding.DefaultEmbeddingClient` 使用 `ModelRole.EMBEDDING`。

`com.pixflow.infra.ai.rerank.DefaultRerankClient` 使用 `ModelRole.RERANK`。

`com.pixflow.infra.ai.chat.DefaultChatModelClient` 继续使用 `ModelRole.PRIMARY_CHAT` 作为默认角色，但也要正确处理请求中显式传入的其他 chat-compatible role。它不应该成为所有能力的唯一 public bean；vision、embedding、imagegen、rerank 都必须有各自 bean。

所有默认实现必须遵守这些依赖约束：不依赖 `infra/storage`，因为图片和生图源图 bytes 由调用方传入；不依赖 `infra/vector`，因为向量存储属于 vector 模块；不依赖 `infra/cache` 具体实现，只通过 `GlobalConcurrencyLimiter` SPI；不依赖 `module/*` 或 `harness/*`；不记录 API key、Bearer token、图片 bytes、生成图 bytes、embedding 向量全文。

## Change Notes

2026-07-05 / Codex: 创建本文档。原因是当前 `pixflow-infra-ai` 的 client 能力与 `docs/design-docs/infra/ai.md` 的目标范围不一致，需要一份中文、自包含、按 `PLANS.md` 维护的执行计划来说明如何完善所有 client，并给出设计文档检索关键词，方便后续实现者快速定位依据。

2026-07-05 / Codex: 完成第一轮实现并更新本文档。原因是 `infra/ai` 已从只有 chat 默认实现推进到五类 client 均可自动装配，且 vision/memory/imagegen/task/app 验证通过；同时记录本轮未完成的真流式和 imagegen 异步任务轮询，避免后续贡献者误判计划已全部收尾。

2026-07-06 / Codex: 完成 `ChatModelClient` 真流式实现并更新本文档。原因是此前 `stream(...)` 仍把阻塞结果包装为单个 `Completed`，且 `ModelRetryRunner#run(...)` 会收集完整 Flux 后再发，未满足 `infra/ai.md` 对真流式和首次发射边界重试的要求；本轮已补齐实现和 fake SSE provider 测试。
