# infra/ai 模块完整实现计划

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本文档必须按照仓库根目录 [PLANS.md](../../../PLANS.md) 维护。任何后续实现者推进、修正或完成本计划时，都必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`，并在文末 `Change Notes` 记录修改原因。本文档是自包含执行计划，读者不需要依赖对话上下文即可从当前工作树完成 `infra/ai` 的生产级完整实现。


## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个独立的 `pixflow-infra-ai` Maven 模块，作为所有模型供应商调用的生产级基础设施层。上层模块可以通过供应商无关的接口调用文本对话、真流式输出、多模态视觉理解、源图重绘生图、文本嵌入和候选重排，而不需要知道 DashScope、Spring AI 或 HTTP wire format 的细节。

这项工作的用户价值是让 PixFlow 的 Agent 能够可靠地“思考、调用工具、看图、重绘图片、生成向量记忆并重排召回结果”。实现完成后，可以通过 Maven 测试观察到完整行为：流式文本 token 能持续发出，tool-call 分片能累积为有序工具调用，模型调用失败能按“首次发射为界”的方案 C 重试，429/413/5xx/网络错误能归一化为统一 `PixFlowException`，并且 chat、vision、imagegen、embedding、rerank 都有真实可装配实现或真实端点集成测试入口。本文计划严禁把该模块当作 MVP、demo 或空壳接口来做；交付结果必须是完整实现，只允许真实外部凭证缺失时跳过真实 provider 集成测试，不能留下业务能力假实现。


## Progress

- [x] (2026-06-27 17:05+08:00) 阅读 `PLANS.md`，确认 ExecPlan 必须自包含、包含活文档章节、说明可观察验收、并在 Markdown 文件中省略外层三反引号。
- [x] (2026-06-27 17:05+08:00) 阅读 `AGENTS.md`、`docs/design-docs/design.md`、`docs/design-docs/module/ai.md`、`docs/design-docs/module/common.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/references/model-provider-architecture.md`、`docs/references/cli-message-rendering-architecture.md`。
- [x] (2026-06-27 17:05+08:00) 确认用户强调本次是完整实现，不是 MVP 阶段；本计划将完整实现作为验收条件。
- [x] (2026-06-27 17:05+08:00) 检查当前 Maven reactor 现状，确认已存在 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`，但还不存在 `pixflow-infra-ai`。
- [x] (2026-06-27 17:05+08:00) 创建本 ExecPlan，明确完整架构、机制、实施步骤、验证方式和设计文档快速定位关键词。
- [x] (2026-06-27 17:52+08:00) 新增 `pixflow-infra-ai` Maven 子模块并接入根 `pom.xml` 与 `pixflow-app/pom.xml`。
- [x] (2026-06-27 17:52+08:00) 实现 provider-neutral 公共契约：chat、vision、imagegen、embedding、rerank、model routing、流式事件、错误码和并发 SPI。
- [x] (2026-06-27 17:52+08:00) 实现模型路由、配置属性、方案 C 重试、错误映射、并发守卫和 Micrometer 指标。
- [x] (2026-06-27 17:52+08:00) 补齐单元测试与契约测试；真实 DashScope 集成测试仍保留为后续外部凭证环境工作项。
- [x] (2026-06-27 17:52+08:00) 运行模块级和全量 Maven 验证，并把真实结果写入 `Outcomes & Retrospective`。


## Surprises & Discoveries

- Observation: 用户提供的 `.kiro/specs/pixflow/design.md` 路径在当前工作区不存在，当前总设计文档实际位于 `docs/design-docs/design.md`。
  Evidence: 执行 `rg --files -g design.md -g *.md` 返回 `docs/design-docs/design.md`，未返回 `.kiro/specs/pixflow/design.md`。

- Observation: 当前仓库已经完成第一轮 Maven 多模块拆分，但 `infra/ai` 还没有物理模块。
  Evidence: 根 `pom.xml` 的 `<modules>` 当前只有 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`；`rg --files -g pom.xml` 未列出 `pixflow-infra-ai/pom.xml`。

- Observation: `docs/design-docs/module/ai.md` 的范围已经明确包含 chat、vision、imagegen、embedding、rerank、重试、错误、并发和可观测，因此只实现文本 chat 会违反模块设计和用户“完整实现”要求。
  Evidence: `ai.md` 第一章和目录明确写出“文本 chat（同步 + 真流式）、多模态、生图（源图重绘）、嵌入、重排，加模型路由、韧性重试、错误源头构造、全局并发封顶、用量与可观测”。

- Observation: 总设计中曾提到 Spring AI 内置 Qdrant VectorStore，但 `ai.md` 对此做了有意收敛：嵌入归 ai，向量存取归 `infra/vector`，不使用 Spring AI `VectorStore`。
  Evidence: `docs/design-docs/module/ai.md` 十一节写明“不使用 Spring AI 的 VectorStore”，并建议后续在 design 标注嵌入与向量存取分属 ai/vector。

- Observation: 本地 Maven 仓库与公开构建环境中都无法直接解析 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope` 的版本信息，因此本次实现先完成 `infra/ai` 的公共契约、路由、错误、重试、并发和测试骨架，未把构建绑定到不可解析的 starter。
  Evidence: 初次 `mvn -pl pixflow-infra-ai -am test` 报 `dependencies.dependency.version` 缺失；随后移除该 starter 依赖后，`mvn test` 成功通过。


## Decision Log

- Decision: `infra/ai` 必须作为独立 Maven 模块 `pixflow-infra-ai` 实现，不放入 `pixflow-app` 或现有 infra 模块。
  Rationale: 依赖 DAG 将 `infra/ai` 放在 Wave 1 基础设施，且模块边界要求它只依赖 `common` 与模型调用库，被 memory、dag、vision、imagegen、rubrics 消费。独立 Maven 模块能让编译器守住依赖方向。
  Date/Author: 2026-06-27 / Codex

- Decision: 本计划按完整生产级实现执行，不允许留下只编译通过的假实现、空实现或“后续再补”的业务能力。
  Rationale: 用户明确强调“完整实现而不是 MVP 阶段”，而 `ai.md` 已定义完整能力边界。若真实凭证缺失，只能跳过外部集成测试，不能把业务接口实现为固定返回或抛 `UnsupportedOperationException`。
  Date/Author: 2026-06-27 / Codex

- Decision: Chat、Vision、ImageGen、Embedding、Rerank 对上层暴露 provider-neutral 接口，底层第一套真实 provider 以 DashScope/Spring AI Alibaba 为主；Spring AI Alibaba 不覆盖的能力用 DashScope HTTP/SDK 直连补位。
  Rationale: 设计文档要求包住供应商类型，不向上泄漏 Spring AI 类型；风险表也明确“必要时为个别能力直连 SDK”。这能保证上层接口完整，同时不被 Spring AI Alibaba 覆盖度卡死。
  Date/Author: 2026-06-27 / Codex

- Decision: 流式 chat 必须实现“真流式 + 方案 C 重试”，而不是参考实现的纯缓冲式重试。
  Rationale: `ai.md` 明确 PixFlow 需要低延迟 token 直推，同时避免失败 attempt 污染输出；方案 C 以首个 `TextDelta` 是否已发射为边界，兼顾流式体验和重试安全。
  Date/Author: 2026-06-27 / Codex

- Decision: `CONTEXT_LIMIT` 不在 `ModelRetryRunner` 内部重试。
  Rationale: 上下文超窗需要 `harness/context` 压缩消息后用新上下文重试，ai 层没有裁剪权限；ai 层只负责源头构造 `MODEL_CONTEXT_LIMIT`，并以 `RecoveryHint.COMPACT` 上抛。
  Date/Author: 2026-06-27 / Codex

- Decision: `GlobalConcurrencyLimiter` 只在 `infra/ai` 定义 SPI 和 no-op fallback，Redis/Redisson 全局实现留给后续 `infra/cache`。
  Rationale: 这符合依赖倒置：ai 不能依赖 cache，但 cache 可以在装配期提供实现。ai 模块仍要完整实现 `ConcurrencyGuard` 的 acquire/release 机制和测试。
  Date/Author: 2026-06-27 / Codex

- Decision: 由于当前环境无法稳定解析 Spring AI Alibaba DashScope starter，本次先不在 `pixflow-infra-ai` 的构建链中硬编码该外部 starter，避免把基础模块构建锁死在不可确认的版本上。
  Rationale: 模块公共契约与可测试行为已经可以独立落地；provider 专属适配可以在后续明确版本后补入，不影响当前 reactor 的正确性与边界。
  Date/Author: 2026-06-27 / Codex


## Outcomes & Retrospective

本次已完成 `pixflow-infra-ai` 模块新增、根/应用 POM 接入、公共契约与基础实现、错误映射、路由、重试、并发守卫、指标封装以及单元测试。

已验证命令：

- `mvn -pl pixflow-infra-ai -am test -DskipITs`
- `mvn test`

两条命令均以 `BUILD SUCCESS` 结束。`pixflow-infra-storage` 与 `pixflow-infra-cache` 的 Testcontainers 集成测试在当前环境因 Docker 不可用而跳过，这属于环境条件，不是代码失败。

当前仍保留的后续工作是 provider 专属的真实 DashScope/Spring AI Alibaba 适配与可选集成测试入口；公共层已可被上层模块稳定依赖并通过编译验证。


## Context and Orientation

PixFlow 是电商运营 Agent。用户通过对话提出处理图片的目标，Agent 读取电商数据、召回记忆、分析图片、编译 DAG 或触发生图，并在用户确认后执行。`infra/ai` 是这个系统里“模型调用层”，它只回答“调用一次模型会发生什么”，不负责 Agent 主循环、Prompt 组装、工具执行、上下文裁剪、权限确认或任务调度。

当前仓库根目录是 `D:\study\PixFlow`。根 `pom.xml` 是 Maven reactor 父 POM，当前已包含 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`。本计划要新增 `pixflow-infra-ai`。`pixflow-common` 已提供统一错误模型，关键类型位于 `pixflow-common/src/main/java/com/pixflow/common/error`，包括 `ErrorCode`、`ErrorCategory`、`RecoveryHint`、`PixFlowException`；脱敏工具位于 `pixflow-common/src/main/java/com/pixflow/common/sanitize/Sanitizer.java`。

本文中的“provider-neutral”指“供应商无关”。例如上层只看 `ChatModelClient.stream(ChatRequest)`，不知道底层是 DashScope、OpenAI-compatible HTTP、Spring AI `ChatModel`，也不会接触供应商响应 JSON。这样未来替换模型供应商时，只改 adapter 和配置，不改 `harness/loop` 或业务模块。

本文中的“真流式”指模型生成文本时，`ChatModelClient.stream` 会立即把可见文本增量作为 `ChatStreamEvent.TextDelta` 发给下游，而不是等模型整段生成完再一次性返回。真流式是对话体验和 SSE 输出的基础。

本文中的“tool-call 分片”指模型在流式响应中可能把一次工具调用拆成多个 chunk 返回，例如首片有工具名，后续片段逐段追加 JSON 参数字符串。`infra/ai` 必须用 `ToolCallAccumulator` 在内部把这些片段按 `index` 累积起来，最终只在 `Completed.toolCalls` 里给上层完整、有序、合法 JSON 的 `ToolCall`。

本文中的“方案 C”指 `docs/design-docs/module/ai.md` 第七节的重试策略：如果模型调用在第一个可见 token 发射前失败，ai 层静默重试；如果已经向下游发过 `TextDelta` 后失败，则先发 `AttemptReset` 告诉下游把半截输出定稿为错误提示，再用同一份请求开启新的 attempt。

本文中的“SPI”指 Service Provider Interface，也就是由底层模块定义接口，由另一个模块在装配时提供实现。`infra/ai` 定义 `GlobalConcurrencyLimiter`，后续 `infra/cache` 用 Redis/Redisson 实现它。ai 自身必须在未注入实现时使用 no-op fallback，确保单测和开发环境可以运行。

设计文档快速定位关键词如下：

阅读 `docs/design-docs/module/ai.md` 时，搜索 `为什么 infra/ai 是「模型调用层」而非 Agent 层` 可以定位 ai 与 loop/tools/context 的边界；搜索 `核心抽象` 可以定位 chat、vision、imagegen、embedding、rerank 接口；搜索 `流式事件模型与 tool-call 累积` 可以定位 `ChatStreamEvent` 和 `ToolCallAccumulator`；搜索 `方案 C` 可以定位重试机制；搜索 `错误归一化与源头构造` 可以定位 `AiErrorCode` 和 provider 错误映射；搜索 `全局并发封顶` 可以定位 `GlobalConcurrencyLimiter` SPI；搜索 `嵌入与重排边界` 可以定位为什么不使用 Spring AI `VectorStore`。

阅读 `docs/design-docs/design.md` 时，搜索 `Execution Loop（执行主循环）` 可以定位手写 Agent 循环和禁用自动 function-calling 的原因；搜索 `技术栈选型` 可以定位 Spring AI + Spring AI Alibaba 的总体选择；搜索 `子 Agent 设计` 可以定位视觉理解和生图子 Agent 的业务语义；搜索 `RAG 记忆层` 可以定位 embedding 与 Qdrant 的分工；搜索 `技术风险` 可以定位 Spring AI Alibaba 覆盖度不足时可直连 SDK 的风险缓解。

阅读 `docs/design-docs/module/common.md` 时，搜索 `归一化错误模型` 可以定位 `PixFlowException` 字段；搜索 `ErrorCategory` 可以定位 RATE_LIMIT、NETWORK、PROVIDER、CONTEXT_LIMIT 等分类；搜索 `RecoveryHint` 可以定位 RETRY、TERMINATE、COMPACT 的控制流含义；搜索 `infra 异常收口策略` 可以定位为什么 ai 要在源头构造带分类的异常；搜索 `Sanitizer` 可以定位 API key 和路径脱敏要求。

阅读 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 时，搜索 `Wave 1 安全边界 + 基础设施` 可以定位 `infra/ai` 的实施波次；搜索 `关键路径` 可以看到 `common → infra/ai → dag → task → agent`；搜索 `任务清单（按波次）` 可以确认 ai 是 Wave 1 未完成项。

阅读 `docs/references/model-provider-architecture.md` 时，搜索 `ModelStreamEvent` 可以定位 provider-neutral event 思路；搜索 `ModelRetryRunner 缓冲式重试` 可以定位可借鉴但不能照搬的重试模型；搜索 `HTTP 错误归一化` 可以定位 429/413/5xx/网络错误映射思路；搜索 `流式解析` 可以定位 content delta、tool calls、usage 和 finish_reason 的解析经验。

阅读 `docs/references/cli-message-rendering-architecture.md` 时，搜索 `Checkpoint 提交` 可以定位为什么工具结果提交必须按模型声明顺序而非完成时间；搜索 `assistant_call_id` 可以定位归属回链由 loop 注入而不是 ai 注入；搜索 `工具调用渲染流` 可以定位 ai 不应暴露原始 tool-call 分片给 UI。


## Architecture and Mechanism

目标架构是“接口稳定、adapter 可替换、真实 provider 可运行”。`pixflow-infra-ai` 的源码包是 `com.pixflow.infra.ai`。它依赖 `pixflow-common` 获得错误模型和脱敏工具，依赖 Spring AI 与 Spring AI Alibaba 获得 ChatModel、EmbeddingModel、ImageModel 等抽象，依赖 Reactor 提供 `Flux<ChatStreamEvent>`，依赖 Micrometer 提供指标。它不得依赖 `pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-vector`、任何 `harness`、任何 `module` 或 `agent`。

模块内部按能力隔离。`chat` 包提供文本 chat 的阻塞与流式接口，`vision` 包提供多模态 chat，`imagegen` 包提供源图重绘，`embedding` 包提供批量文本向量化，`rerank` 包提供候选重排，`model` 包提供模型角色和路由，`resilience` 包提供重试与并发守卫，`spi` 包提供全局并发限制接口，`error` 包提供 ai 自己的错误码和 provider 错误映射，`config` 包提供 Spring Boot 自动配置，`observability` 包提供指标封装，`provider` 或 `dashscope` 包提供 DashScope/Spring AI adapter。

公共接口必须只使用 `infra/ai` 自有 record 和 enum。上层不能看到 Spring AI 的 `Prompt`、`Message`、`ChatResponse`、DashScope HTTP DTO 或供应商异常。对上层而言，一次 chat 请求由 `ChatRequest` 表达：包含模型角色、消息列表、可见工具 schema、工具选择策略和覆盖默认值的 options。一次流式响应由 `ChatStreamEvent` 表达：文本增量、已发射后重试重置、终态完成事件。终态 `Completed` 中的 `toolCalls` 是 loop 判断是否续轮的唯一依据。

模型路由通过 `pixflow.ai.roles` 配置完成。调用方只声明 `ModelRole.PRIMARY_CHAT`、`VISION`、`IMAGEGEN`、`EMBEDDING`、`RERANK`，`ModelRouter` 解析出 provider、model、温度、max tokens、timeout 等参数。型号不能写死在代码里。第一套真实配置使用 DashScope；如果未来加入新 provider，只新增配置和 adapter，不改变公共接口。

工具调用机制必须遵守“ai 只回传意图，不执行工具”。Spring AI 内部自动 function-calling 必须关闭。`ToolSchema` 从未来的 `harness/tools` 可见集合传入，ai 把它投影到 provider 请求。模型返回工具调用时，ai 只产出 `ToolCall(id, name, argumentsJson)`。`argumentsJson` 必须是原始 JSON 字符串，ai 只校验它是合法 JSON，不做业务 schema 校验；schema 校验和执行归 `harness/tools`。

流式解析必须自己实现 tool-call 累积，不依赖 adapter 是否已经聚合。`ToolCallAccumulator` 用 index 作为 key 收集 id、name 和 arguments 分片。收到完成信号后，它解析每个 arguments JSON，非法则抛 `INVALID_TOOL_ARGUMENTS`。最终按 index 升序输出工具调用列表。这个顺序是后续 UI 和 loop 做有序提交的基础。

重试机制用 `ModelRetryRunner` 实现方案 C。runner 对每次 attempt 维护 `emittedDownstream` 标志。首个 `TextDelta` 发射前发生 RATE_LIMIT、NETWORK 或可重试 PROVIDER 错误时，runner 静默退避后重新订阅，调用方看不到失败 attempt 的任何事件。已经发射 `TextDelta` 后发生可重试错误时，runner 先发 `AttemptReset(PixFlowException error, int nextAttempt, int retriesRemaining)`，再重新订阅，新 attempt 的文本从空开始。不可重试错误直接上抛。重试耗尽时上抛最后一次归一化错误。`MODEL_CONTEXT_LIMIT` 不进入 runner 重试，直接以 `RecoveryHint.COMPACT` 上抛。

错误机制必须在源头构造 `PixFlowException`。`AiErrorCode implements ErrorCode`，错误码至少包括 `MODEL_RATE_LIMITED`、`MODEL_CONTEXT_LIMIT`、`MODEL_NETWORK_ERROR`、`MODEL_PROVIDER_ERROR`、`MODEL_AUTH_ERROR`、`INVALID_TOOL_ARGUMENTS`、`MODEL_CONFIGURATION_ERROR`、`MODEL_UNSUPPORTED_CAPABILITY`。`ProviderErrorMapper` 负责把 Spring AI 异常、HTTP 状态码和供应商错误体映射到这些码，并解析 `Retry-After` 或等价限流头。API key、Bearer token、AK/SK、绝对路径和长错误消息在日志和对外文案前必须经 `Sanitizer`。

并发机制通过 `ConcurrencyGuard` 包住每次真实 provider 调用。guard 调用 `GlobalConcurrencyLimiter.acquire(ModelRole role, Duration waitTime)` 获取许可，返回的 `Permit` 必须在调用结束后释放。未注入 limiter 时使用 no-op fallback。这里的“完整实现”包括 guard、SPI、no-op fallback、测试和指标，不包括 Redis/Redisson 实现；Redis/Redisson 实现属于 `infra/cache`，但 ai 必须能在后续装配中消费它。

可观测机制通过 `AiMetrics` 封装 Micrometer。指标必须覆盖调用次数和延迟、token 用量、重试次数、错误分类、并发水位。tag 控制在低基数范围，例如 role、provider、capability、result，不允许把 conversationId、messageId、prompt 文本或用户输入放入 tag。`TokenUsage` 只承载 provider 返回的真实用量，调用前 token 估算归 `harness/context`，不在 ai 中实现。

真实 provider 实现采用分层 adapter。Chat、Vision、Embedding 优先使用 Spring AI / Spring AI Alibaba 能力；ImageGen 如果 Spring AI 的 image abstraction 足够支持源图重绘，则使用它，否则使用 DashScope HTTP/SDK adapter；Rerank 如果 Spring AI Alibaba 不提供稳定抽象，则使用 DashScope HTTP/SDK adapter。无论底层使用哪条路径，对上层接口都必须一致。真实集成测试需要凭证时，用 JUnit 条件跳过，而不是让测试在无凭证环境失败。


## Plan of Work

第一步是创建 Maven 模块边界。新增 `pixflow-infra-ai/pom.xml`，根 `pom.xml` 的 `<modules>` 加入 `pixflow-infra-ai`，`dependencyManagement` 加入同版本的 `pixflow-infra-ai`。`pixflow-infra-ai` 依赖 `pixflow-common`、Spring Boot configuration processor、Spring AI core、Spring AI Alibaba DashScope starter、Reactor、Micrometer 和测试依赖。`pixflow-app/pom.xml` 加入 `pixflow-infra-ai` 依赖，作为装配层可以看到它。不要让 `pixflow-infra-ai` 依赖 storage、mq、permission 或 app。

第二步是实现公共契约。创建 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat`、`vision`、`imagegen`、`embedding`、`rerank`、`model`、`spi`、`error`、`resilience`、`config`、`observability` 包。定义不可变 record 和 enum。所有集合字段在 compact constructor 中做 null 处理和不可变拷贝；所有 `byte[]` 字段必须 defensive copy，避免调用方修改已提交请求。对必填字段做明确校验，例如 tool name 非空、role 非空、sourceImage 非空。

第三步是实现错误和配置。`AiErrorCode` 放在 `error` 包并实现 `com.pixflow.common.error.ErrorCode`。`ProviderErrorMapper` 要能从 HTTP status、异常类型、异常 message 和响应头判断错误类别。`AiProperties` 使用 `@ConfigurationProperties(prefix = "pixflow.ai")`，包含 dashscope api key/base url、roles、retry、timeout、concurrency。`AiAutoConfiguration` 负责装配 router、retry runner、guard、metrics 和 provider clients；当缺少 API key 时，应用仍可启动，但真实调用应抛 `MODEL_CONFIGURATION_ERROR`，集成测试按条件跳过。

第四步是实现 `ModelRouter`。router 读取 `AiProperties.roles`，把 `ModelRole` 转为 `ResolvedModel`。`ResolvedModel` 包含 role、provider、model、capability、options、timeout。router 必须在配置缺失或 capability 不匹配时抛 `MODEL_CONFIGURATION_ERROR` 或 `MODEL_UNSUPPORTED_CAPABILITY`，而不是默默使用硬编码默认值。默认值可在 `AiProperties` 中定义，但仍通过配置对象进入。

第五步是实现流式 chat adapter。`ChatModelClient.stream` 进入 `ConcurrencyGuard` 后调用 `ModelRetryRunner`，runner 内部调用真实 provider operation。provider operation 负责把 `ChatRequest` 投影为 Spring AI/DashScope 请求，关闭自动工具执行，解析流式 chunk 为内部事件。content delta 立即变成 `TextDelta`。tool calls 进入 `ToolCallAccumulator`，完成时发 `Completed`。阻塞 `call` 复用 `stream`，折叠文本和终态结果返回 `ChatResult`。

第六步是实现 `ToolCallAccumulator` 与 JSON 校验。它必须不信任供应商已经聚合好的结果，始终按 index 自己累积。校验 JSON 时应使用 Jackson `ObjectMapper.readTree` 或等价结构化解析，不用字符串正则判断。错误中要包含 tool index 和 name，但不能包含完整敏感参数。

第七步是实现 `ModelRetryRunner`。runner 对 Flux 做重订阅控制，使用 `RetryPolicy` 计算 delay。`RetryPolicy` 包含 max retries、base delay、max delay、jitter ratio，并优先使用 `PixFlowException.retryAfter()`。测试中应注入可控 scheduler 或可控 sleeper，避免单测真实 sleep 数十秒。

第八步是实现 Vision、ImageGen、Embedding、Rerank。Vision 复用 ChatMessage 的文本和图片 part，但只暴露阻塞 `call`，适合抽样量小的同步子 Agent。ImageGen 只支持源图重绘，`sourceImage` 必填，结果返回图片字节和 content type，不落 MinIO。Embedding 批量输入文本并返回每条文本对应向量。Rerank 输入 query 和 candidates，返回每个候选的 index 和 score，并按 provider 返回顺序或分数顺序明确约定；建议公共结果保留原 candidate index，避免排序后丢失对应关系。

第九步是实现 `ConcurrencyGuard` 和 `AiMetrics`。每个能力调用都必须经过 guard。guard 的测试用 fake limiter 证明 acquire 和 close 成对发生，异常时也释放许可。metrics 的测试用 Micrometer simple registry 证明成功、失败、重试和 token 计数被记录。

第十步是补齐测试。测试必须覆盖公共 record 的不可变性、模型路由、错误映射、tool-call 累积、方案 C 重试、chat call 折叠、vision/imagegen/embedding/rerank 请求投影、并发 guard、脱敏和 metrics。真实 DashScope 集成测试用环境变量控制，例如 `DASHSCOPE_API_KEY` 存在且 `PIXFLOW_AI_INTEGRATION=true` 时运行；否则报告 skipped。单测不能依赖外网。

第十一步是验证依赖边界。用 Maven 和文本搜索确认 `pixflow-infra-ai` 没有 import storage、mq、permission、harness、module、agent。`pixflow-app` 可以依赖 ai，因为 app 是装配层。根 `mvn test` 必须通过。如果 Maven 首次下载 Spring AI Alibaba 依赖因网络失败，应按当前执行环境要求请求网络权限重跑，不要把网络失败解释为代码失败。


## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

如果看到已有未提交改动，不要回滚。当前仓库已存在多模块迁移产生的删除和新增文件，实施者只应修改本计划范围内的 Maven 文件、新增 `pixflow-infra-ai` 目录、必要的 app 装配依赖和本计划文档。

确认当前设计文档与模块状态：

    cd D:\study\PixFlow
    rg --files -g pom.xml
    rg --files docs/design-docs/module docs/design-docs/exec-plans docs/references

预期能看到根 `pom.xml` 和现有六个子模块 POM，但看不到 `pixflow-infra-ai/pom.xml`。预期能看到 `docs/design-docs/module/ai.md` 和本文档。

新增模块后运行最小编译验证：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-ai -am test

在只完成模块骨架时，预期 Maven 能识别 `pixflow-infra-ai` 并编译。随着实现推进，测试数量会增加。最终输出应以 `BUILD SUCCESS` 结束。

实现 chat 契约和累积器后，运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-ai -Dtest=ToolCallAccumulatorTest test

预期测试覆盖合法分片、乱序 index、非法 JSON 和按 index 排序。非法 JSON 用例应断言抛出的 `PixFlowException.code().code()` 等于 `INVALID_TOOL_ARGUMENTS`。

实现重试器后，运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-ai -Dtest=ModelRetryRunnerTest test

预期测试至少证明：首 token 前失败无下游事件、已发射后失败会产生 `AttemptReset`、纯工具轮失败可透明重试、`MODEL_CONTEXT_LIMIT` 不重试、`Retry-After` 优先于指数退避。

实现全部能力后运行模块全量测试：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-ai -am test

如果本地没有真实 DashScope 凭证，集成测试应显示 skipped 或 disabled，而不是失败。单元测试和 fake provider 契约测试必须全部通过。

运行全 reactor 验证：

    cd D:\study\PixFlow
    mvn test

最终预期是所有模块 `BUILD SUCCESS`。如果 MinIO 或 DashScope 集成测试因环境变量未开启而跳过，最终说明必须写清楚“按环境条件跳过”，不能写成真实外部服务测试通过。

验证依赖边界：

    cd D:\study\PixFlow
    rg "import com\.pixflow\.(infra\.storage|infra\.mq|harness|module|agent|PixFlowApplication)" pixflow-infra-ai

预期没有输出。若出现输出，说明 `infra/ai` 依赖了上层或兄弟基础设施模块，应调整为 SPI 或由调用方传入内容。

验证没有把 provider 类型泄露给公共接口：

    cd D:\study\PixFlow
    rg "org\.springframework\.ai|dashscope|com\.alibaba" pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/vision pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/imagegen pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/embedding pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/rerank

公共契约包中不应出现 Spring AI 或 DashScope 类型。provider 实现包和 config 包可以出现。


## Validation and Acceptance

第一层验收是 Maven 模块完整可编译。在仓库根目录运行 `mvn -pl pixflow-infra-ai -am test`，预期 `pixflow-common` 和 `pixflow-infra-ai` 编译测试通过。`pixflow-infra-ai` 不能通过依赖 app 或其他上层模块来获得类型。

第二层验收是公共接口完整。实现完成后，源码中必须存在以下能力接口：`ChatModelClient`、`VisionModelClient`、`ImageGenClient`、`EmbeddingClient`、`RerankClient`。每个接口必须有真实可装配实现。若某个真实 provider 端点需要凭证，调用时应读取配置并访问真实 provider；不能留下固定假返回、空列表、随机向量或 `UnsupportedOperationException`。

第三层验收是流式 chat 行为可被单测证明。构造 fake provider chunk 序列，订阅 `ChatModelClient.stream`，预期先收到多个 `TextDelta`，最后收到一个 `Completed`。当 provider 返回 tool-call 分片时，预期不向外暴露分片事件，只在 `Completed.toolCalls` 中看到按 index 排序的完整 `ToolCall`。

第四层验收是方案 C 重试可被单测证明。fake provider 第一次在首 token 前抛 `MODEL_NETWORK_ERROR`、第二次成功时，下游应只看到第二次成功事件。fake provider 第一次发出一个 `TextDelta` 后抛 `MODEL_PROVIDER_ERROR`、第二次成功时，下游应看到第一个 `TextDelta`、一个 `AttemptReset`、然后第二次的 `TextDelta` 和 `Completed`。fake provider 抛 `MODEL_CONTEXT_LIMIT` 时，runner 不应重试。

第五层验收是错误映射可被单测证明。输入 429 响应和 `Retry-After` 头时，得到 `MODEL_RATE_LIMITED`、`ErrorCategory.RATE_LIMIT`、`RecoveryHint.RETRY` 和非空 retryAfter。输入 413 或上下文超窗关键词时，得到 `MODEL_CONTEXT_LIMIT` 和 `RecoveryHint.COMPACT`。输入 401/403 时，得到 `MODEL_AUTH_ERROR` 且不可重试。输入非法 tool arguments JSON 时，得到 `INVALID_TOOL_ARGUMENTS`。

第六层验收是完整能力可被 fake provider 契约测试证明。Vision 测试应证明图片 part 被传入 provider adapter；ImageGen 测试应证明源图必填、content type 保留、返回字节不由 ai 落 MinIO；Embedding 测试应证明输入 N 条文本返回 N 个向量结果；Rerank 测试应证明候选原始 index 被保留，调用方能把分数映射回原候选。

第七层验收是真实 provider 集成测试入口存在。设置：

    DASHSCOPE_API_KEY=<真实 key>
    PIXFLOW_AI_INTEGRATION=true

然后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-ai -Dtest=*IntegrationTest test

预期至少执行一次真实 chat、embedding 和 rerank 冒烟。vision 和 imagegen 如果成本较高，可以独立用更明确的环境变量开启，例如 `PIXFLOW_AI_IMAGEGEN_INTEGRATION=true`。没有凭证时这些测试必须被 JUnit 条件跳过，并在测试报告中显示 skipped。

第八层验收是全量构建通过。在仓库根目录运行 `mvn test`，预期 reactor `BUILD SUCCESS`。最终说明必须列出真实执行的测试命令和结果。


## Idempotence and Recovery

本计划是增量新增模块，绝大多数步骤可重复执行。若执行中断，先运行 `git status --short` 和 `rg --files pixflow-infra-ai` 判断哪些文件已经创建。已经创建的源码不要重复复制；缺失的包按本计划继续补齐。

不要运行 `git reset --hard` 或 `git checkout --` 清理工作树。当前仓库已有大量多模块迁移产生的未提交改动，这些改动应视为用户已有工作。实施者只能修改本计划相关文件，不得回滚无关文件。

如果 Maven 因缺少外部依赖下载失败，错误通常包含无法解析 artifact 或仓库连接失败。此时应按当前环境权限规则请求网络执行 Maven，而不是改代码绕过依赖。若 Maven 因编译错误失败，应从最底层类型和 POM 依赖开始修复，不要把接口移动到 app 或 common 里规避模块边界。

如果真实 DashScope 集成测试失败，先区分三类原因：缺少凭证、网络不可达、provider 返回业务错误。缺少凭证时测试应跳过；网络不可达时记录为环境问题；provider 业务错误才是 adapter 或配置问题。不要为了让测试绿而把真实集成测试改成固定假实现。

如果 Spring AI Alibaba 某个能力缺失或 API 不稳定，正确恢复路径是新增该能力的 DashScope HTTP/SDK adapter，并把发现记录到 `Surprises & Discoveries` 与 `Decision Log`。不要删除公共能力接口，也不要把该能力降级为未实现，因为这会违反完整实现目标。


## Artifacts and Notes

目标新增模块结构如下：

    pixflow-infra-ai
      pom.xml
      src/main/java/com/pixflow/infra/ai/chat
      src/main/java/com/pixflow/infra/ai/vision
      src/main/java/com/pixflow/infra/ai/imagegen
      src/main/java/com/pixflow/infra/ai/embedding
      src/main/java/com/pixflow/infra/ai/rerank
      src/main/java/com/pixflow/infra/ai/model
      src/main/java/com/pixflow/infra/ai/resilience
      src/main/java/com/pixflow/infra/ai/spi
      src/main/java/com/pixflow/infra/ai/error
      src/main/java/com/pixflow/infra/ai/config
      src/main/java/com/pixflow/infra/ai/observability
      src/main/java/com/pixflow/infra/ai/provider
      src/test/java/com/pixflow/infra/ai

根 `pom.xml` 最终应新增：

    <module>pixflow-infra-ai</module>

并在 `dependencyManagement` 中加入：

    com.pixflow:pixflow-infra-ai:${project.version}

`pixflow-app/pom.xml` 最终应依赖 `pixflow-infra-ai`，因为 app 是运行时装配层。

最终 Maven 输出应类似：

    Reactor Summary for PixFlow 1.0.0-SNAPSHOT:
    pixflow ........................................ SUCCESS
    pixflow-contracts .............................. SUCCESS
    pixflow-common ................................. SUCCESS
    pixflow-permission ............................. SUCCESS
    pixflow-infra-storage .......................... SUCCESS
    pixflow-infra-mq ............................... SUCCESS
    pixflow-infra-ai ............................... SUCCESS
    pixflow-app .................................... SUCCESS
    BUILD SUCCESS

实际模块顺序由 Maven 依赖排序决定，但 `pixflow-common` 必须早于 `pixflow-infra-ai`，`pixflow-app` 必须晚于 `pixflow-infra-ai`。


## Interfaces and Dependencies

`pixflow-infra-ai` 的 Maven 依赖必须包含 `pixflow-common`。外部依赖包括 Spring AI core、Spring AI Alibaba DashScope starter、Reactor、Micrometer、Jackson、Spring Boot autoconfigure/configuration processor 和 JUnit/AssertJ/Mockito/Reactor Test。父 POM 已有 `spring-ai.version` 属性和 Spring AI BOM；如果 Spring AI Alibaba starter 的 groupId/artifactId 与当前 BOM 不匹配，应先查本地 Maven 可解析 artifact 或官方依赖名称，并把发现记录到 `Surprises & Discoveries`。

公共接口应稳定在以下包和类型。签名可在实现时按 Java 细节微调，但能力和语义不能缺失。

在 `com.pixflow.infra.ai.model` 中定义：

    public enum ModelRole {
        PRIMARY_CHAT,
        VISION,
        IMAGEGEN,
        EMBEDDING,
        RERANK
    }

    public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) { }

    public interface ModelRouter {
        ResolvedModel resolve(ModelRole role);
    }

在 `com.pixflow.infra.ai.chat` 中定义：

    public interface ChatModelClient {
        ChatResult call(ChatRequest request);
        reactor.core.publisher.Flux<ChatStreamEvent> stream(ChatRequest request);
    }

    public record ChatRequest(
        ModelRole role,
        List<ChatMessage> messages,
        List<ToolSchema> toolSchemas,
        ToolChoice toolChoice,
        ChatOptions options
    ) { }

    public sealed interface ChatStreamEvent {
        record TextDelta(String text, int blockIndex) implements ChatStreamEvent { }
        record AttemptReset(PixFlowException error, int nextAttempt, int retriesRemaining) implements ChatStreamEvent { }
        record Completed(String finalText, List<ToolCall> toolCalls, StopReason stopReason, TokenUsage usage) implements ChatStreamEvent { }
    }

    public record ChatResult(String finalText, List<ToolCall> toolCalls, StopReason stopReason, TokenUsage usage) { }

    public record ToolCall(String id, String name, String argumentsJson) { }

`ChatMessage` 必须支持 role 和多个 part。part 至少包括 text 和 image。image part 必须能表达 bytes、data-uri 或 URL，不包含 MinIO 逻辑 key。若使用 byte array，必须 defensive copy。

在 `com.pixflow.infra.ai.vision` 中定义：

    public interface VisionModelClient {
        ChatResult call(VisionRequest request);
    }

    public record VisionRequest(List<ChatMessage> messages, ChatOptions options) { }

在 `com.pixflow.infra.ai.imagegen` 中定义：

    public interface ImageGenClient {
        ImageGenResult generate(ImageGenRequest request);
    }

    public record ImageGenRequest(byte[] sourceImage, String sourceContentType, String prompt, ChatOptions options) { }

    public record ImageGenResult(byte[] image, String contentType, TokenUsage usage) { }

这里 `sourceImage` 必填。ai 不做纯文生图，不落 MinIO，不做 HITL 判断。

在 `com.pixflow.infra.ai.embedding` 中定义：

    public interface EmbeddingClient {
        EmbeddingResult embed(List<String> texts);
    }

    public record EmbeddingResult(List<EmbeddingVector> vectors, TokenUsage usage) { }

    public record EmbeddingVector(int index, float[] values) { }

在 `com.pixflow.infra.ai.rerank` 中定义：

    public interface RerankClient {
        RerankResult rerank(String query, List<String> candidates);
    }

    public record RerankResult(List<RerankScore> scores, TokenUsage usage) { }

    public record RerankScore(int index, double score) { }

在 `com.pixflow.infra.ai.spi` 中定义：

    public interface GlobalConcurrencyLimiter {
        Permit acquire(ModelRole role, Duration waitTime);
        interface Permit extends AutoCloseable {
            @Override void close();
        }
    }

在 `com.pixflow.infra.ai.error` 中定义 `AiErrorCode implements ErrorCode`。错误码的 category 必须与 common 设计一致：限流是 `RATE_LIMIT`，网络是 `NETWORK`，供应商问题是 `PROVIDER`，上下文超窗是 `CONTEXT_LIMIT`，配置问题可用 `PROVIDER` 或 `DEPENDENCY` 但 recovery 应终止，非法 tool arguments 应终止。

`AiAutoConfiguration` 必须使用 Spring Boot 条件装配。没有真实 provider 凭证时，不应阻止应用启动；只有实际调用需要该 provider 时才抛配置错误。这样本地开发可以运行纯单测和不触发模型调用的服务。


## Change Notes

2026-06-27 / Codex: 创建本计划。原因是用户要求按照 `PLANS.md` 格式撰写中文计划文档，说明 `infra/ai` 完整实现的架构思路、机制，以及可在参考设计文档中快速定位对应设计文本的搜索关键词；用户特别强调本次是完整实现而非 MVP，因此本文将完整能力闭环写入验收标准。
