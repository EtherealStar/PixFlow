# 实现 harness/eval 生产级评估接口与 trace 汇聚层

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/eval` 模块的完整生产级实现，不按 MVP 缩减范围，不实现 Rubrics 评分逻辑，不把 trace 回流到模型上下文，也不让 eval 反向抓取 loop、tools、context 的内部状态。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级 Evaluation Interface。这里的 Evaluation Interface 不是在线评分器，而是为离线评估和问题追溯提供可靠输入的 trace 基础设施。用户和开发者能在一次 Agent 回合结束后查询这一回合使用了什么输入、调用了哪些工具、召回了什么记忆、发生了哪些上下文裁剪、出现过什么错误，并能把这些数据交给后续 `module/rubrics` 做离线评估。

这项工作的可观察结果是：运行 eval 模块测试后，应能看到 `TraceRecorder.begin()` 产生回合级 trace，`commit()` 或 `abort()` 不阻塞主循环而是异步落库，队列满时 trace 被 best-effort 丢弃并计数，所有落入 `agent_trace` 或外置对象的内容都经过统一脱敏，查询和回放接口可以重建回合现场，`ErrorRecorder` 能把回合内错误并入当前 trace，把回合外错误转成指标和结构化日志。

## Progress

- [x] (2026-06-28 18:20+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/harness/eval.md`、`docs/design-docs/design.md`、`docs/design-docs/base/common.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/infra/storage.md` 中与 eval 相关的内容，确认 eval 属于 Wave 2 harness 基础。
- [x] (2026-06-28 18:20+08:00) 已确认用户要求本计划按生产级完整模块实现，不按 MVP 范围设计，并且本轮只撰写计划文档，不写 Java 代码。
- [x] (2026-06-28 18:20+08:00) 已确认 eval 必须自动脱敏 API key、Bearer token、AK/SK、cookie、用户名、邮箱、手机号、本地绝对路径等敏感信息；这必须由 eval 写入路径统一兜底，而不能依赖调用方自觉。
- [x] (2026-06-28 18:20+08:00) 已确认当前仓库尚未存在 `pixflow-eval` Maven 模块，已有 `pixflow-common`、`pixflow-context`、`pixflow-hooks`、`pixflow-state`、`pixflow-infra-storage` 等相邻模块。
- [x] (2026-06-28 17:53+08:00) 新增 `pixflow-eval` Maven 模块，并把它加入根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>`。
- [x] (2026-06-28 17:53+08:00) 扩展 `pixflow-common` 的 `Sanitizer`，补齐结构化敏感字段脱敏能力，并为用户名、邮箱、手机号、cookie、Authorization、accessKeySecret 等场景补测试。
- [x] (2026-06-28 17:53+08:00) 实现 eval 写面 API、回合级累积器、异步缓冲、批量 upsert、状态单向升级和 no-op 降级。
- [x] (2026-06-28 17:53+08:00) 实现 `agent_trace` 持久化映射接口、查询接口、回放接口、大 payload 外置和 7 天保留清理；当前默认仓储为内存实现，生产 MyBatis repository 需在数据库迁移约定落定后替换接入。
- [x] (2026-06-28 17:53+08:00) 实现 `EvalErrorRecorder`，并验证回合内错误并入 trace、回合外错误只打指标和结构化日志。
- [x] (2026-06-28 17:53+08:00) 补齐单元测试和 Maven 验证命令；尚未添加真实 MySQL/MinIO 集成测试。

## Surprises & Discoveries

- Observation: `docs/design-docs/harness/eval.md` 明确要求 eval 消费 `infra/storage` 的共享 `ToolResultStorage`，但当前 `module-dependency-dag-plan.md` 的 Mermaid 图没有画出 `storage --> eval`。
  Evidence: `harness/eval.md` 的“依赖边说明”和“大 payload 外置”都写明 eval 需要消费 storage；实现时应同步更新 DAG 文档，避免后续读者误以为 eval 只依赖 common。

- Observation: 当前 `pixflow-common` 已存在 `Sanitizer`，但能力偏基础，主要遮蔽 Bearer、OpenAI key、阿里云 key 和路径，还没有结构化字段级脱敏用户名、邮箱、手机号、cookie、accessKeySecret 等能力。
  Evidence: `pixflow-common/src/main/java/com/pixflow/common/sanitize/Sanitizer.java` 当前只包含 `BEARER_TOKEN`、`OPENAI_KEY`、`ALIYUN_KEY`、`WINDOWS_PATH` 和 `UNIX_PATH` 规则。

- Observation: 当前 `pixflow-infra-storage` 已提供 `ObjectStorage`、`StorageKeys.toolResult(...)`、`ObjectRef` 等纯 I/O 能力，但没有独立的共享 `ToolResultStorage` 类型。
  Evidence: `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/` 下已有 `ObjectStorage.java` 和 `StorageKeys.java`，后续 eval 可以先基于这些能力实现薄适配，若 storage 模块后续新增共享 `ToolResultStorage`，再替换适配层。

- Observation: 本轮实现期间根 `pom.xml` 同时出现并行新增的 `pixflow-module-file` 模块入口；验证时父 POM 一度因该并行模块状态未稳定而失败，后续确认目录和 POM 存在后 reactor 验证通过。
  Evidence: 最终验证命令 `mvn -pl pixflow-eval -am test` 成功构建 `pixflow-common`、`pixflow-infra-storage` 和 `pixflow-eval`。

## Decision Log

- Decision: eval 新增为独立 Maven 模块 `pixflow-eval`，包名使用 `com.pixflow.harness.eval`。
  Rationale: eval 是 Wave 2 harness 基础件，独立模块能清楚表达它对 `pixflow-common` 和 `pixflow-infra-storage` 的依赖，也能避免它反向依赖 loop、tools、context、rubrics 的实现。
  Date/Author: 2026-06-28 / Codex

- Decision: eval 只记录和回放，不做评分，不触发 Rubrics 在线预警。
  Rationale: `docs/design-docs/harness/eval.md` 把 eval 定义为“为评估提供接口”，评分归 `module/rubrics` 离线阶段。若 eval 在线评分，会把离线闭环并入主循环，破坏主循环秒级响应和模块边界。
  Date/Author: 2026-06-28 / Codex

- Decision: 写路径采用进程内有界缓冲和后台批量刷库，队列满时丢弃 trace 并计数，不同步落库，不阻塞主循环。
  Rationale: trace 是旁路观测记录，不是业务事实源。事实源是 session 的 `message` 和 task 的 `process_result`。为了保护主循环延迟，eval 必须接受少量 trace 缺失。
  Date/Author: 2026-06-28 / Codex

- Decision: `begin()` 投递 `OPEN` 占位记录也走异步队列，而不是同步写库。
  Rationale: 这样严格保持 eval 写路径不阻塞主循环。代价是进程在 `begin()` 后、flusher 刷库前立即崩溃时可能丢失 OPEN 记录。考虑到 eval 是 best-effort，本计划接受这个取舍。
  Date/Author: 2026-06-28 / Codex

- Decision: `ErrorRecorder` 使用当前回合 holder 关联回合内错误，回合外错误不伪造 `agent_trace` 行。
  Rationale: `agent_trace` 是每回合一行的结构化记录。为 HTTP 请求、启动期错误等非回合错误造 trace 行会破坏表语义。回合外错误通过 Micrometer 指标和结构化日志排障即可。
  Date/Author: 2026-06-28 / Codex

- Decision: 所有进入 `agent_trace` 或外置 trace payload 的内容都必须在 eval 统一脱敏，且脱敏发生在外置前。
  Rationale: 外置 MinIO 内容同样是落盘内容。如果只脱敏数据库 preview，而把未脱敏原文写入对象存储，仍然会泄露 API key、用户名等敏感信息。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

本计划已完成首版 Java 实现：新增 `pixflow-eval` 模块，提供被动 trace sink、回合级累积器、异步有界缓冲、查询回放、错误记录 SPI、payload 脱敏外置和保留清理骨架。实现仍保持“记录，不评估”的边界，不依赖 loop/tools/context/rubrics 的具体类型。当前默认 `AgentTraceRepository` 是内存实现，`AgentTraceMapper` 作为生产 MyBatis 接线预留；后续接入真实 `agent_trace` 表时应保持状态单向升级、脱敏前置和 best-effort 非阻塞不变量。

## Context and Orientation

PixFlow 是一个 Java 17 + Spring Boot 3 的多模块 Maven 项目。仓库根目录是 `D:\study\PixFlow`。已有模块包括 `pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-context`、`pixflow-hooks`、`pixflow-state`、`pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-mq`、`pixflow-infra-vector`、`pixflow-infra-ai`、`pixflow-infra-image`、`pixflow-infra-thirdparty` 和 `pixflow-app`。本计划要新增 `pixflow-eval`。

这里定义几个必须理解的词。trace 是一次 Agent 回合的可观测记录，包含模型输入视图、工具调用、记忆召回、上下文裁剪和错误现场。回合是 Agent 对用户请求进行一次或多次思考、工具调用和自然结束的执行单元，用 `conversation_id + turn_no` 标识。best-effort 是指系统尽力记录 trace，但在队列满、数据库慢或对象存储失败时可以丢弃 trace，不能影响主业务流程。回放是指从 `agent_trace` 和外置 payload 重新拼出某个回合当时可观察到的输入输出现场。脱敏是指在落库、日志、对象存储前遮蔽敏感内容，例如 API key、token、用户名、手机号、本地绝对路径。

实现时应优先阅读这些位置，并可用括号中的关键词快速定位对应设计文本。

在 `docs/design-docs/harness/eval.md` 中搜索：`记录，不评估`、`被动 sink`、`异步非阻塞`、`best-effort`、`单表单写者`、`崩溃可见`、`脱敏前置`、`TraceRecorder`、`TurnTrace`、`TraceIngestBuffer`、`TracePayloadCodec`、`ErrorRecorder SPI`、`agent_trace`、`大 payload 外置`、`保留与归档`、`关键不变量`。这些位置是 eval 的权威设计文本，解释本计划所有核心机制。

在 `docs/design-docs/design.md` 中搜索：`Evaluation Interface`、`Rubrics 评估（离线阶段）`、`agent_trace`、`Micrometer/Actuator`、`Spring @Scheduled (+ ShedLock 多节点)`、`Execution Loop`。这些位置解释 eval 在总架构中的目的、它和 Rubrics 的边界、以及基础技术选择。

在 `docs/design-docs/base/common.md` 中搜索：`Sanitizer`、`ErrorRecorder`、`traceId`、`Micrometer Tracing`、`pixflow.error.count`、`Trace 与 Error 日志分离`。这些位置解释为什么 common 只定义错误记录 SPI、traceId 如何跨异步边界传递、以及错误指标的命名。

在 `docs/design-docs/harness/context.md` 中搜索：`ContextSnapshot`、`snapshot 落 trace`、`cheap pipeline`、`destructive compaction`、`大结果外置`、`ToolResultStorage`。这些位置解释 context 的裁剪和快照如何由 loop 转投给 eval，而不是 context 直接依赖 eval。

在 `docs/design-docs/harness/hooks.md` 中搜索：`hooks 自身不写 trace`、`hook span`、`Rubrics 预警不接入在线 hook 链`、`pixflow.hook.dispatch`。这些位置解释 hooks 为什么不依赖 eval，以及 hook 观测数据应由调用方转投。

在 `docs/design-docs/infra/storage.md` 中搜索：`ObjectStorage`、`StorageKeys`、`toolResult`、`对象存储`、`脱敏`。这些位置解释 eval 大 payload 外置应复用 storage 层，而不是新写一套对象存储。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：`Wave 2 harness基础`、`harness/eval`、`eval --> loop`、`eval --> rubrics`、`storage --> context`、`storage --> tools`。这些位置解释 eval 的实现波次和依赖方向。实现时应补充 `storage --> eval`，因为 eval 也消费 storage。

## Plan of Work

第一阶段是更新设计和依赖地基。新增 `pixflow-eval` Maven 模块，加入根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>`。同时更新 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，把 `storage --> eval` 补进 Mermaid 图、波次说明和 Revision Notes。这个阶段的目标是让后续执行者不会误解 eval 的依赖方向。eval 可以依赖 `pixflow-common` 和 `pixflow-infra-storage`，不能依赖 `pixflow-context`、`pixflow-hooks`、`pixflow-tools`、`pixflow-loop`、`pixflow-rubrics`。

第二阶段是扩展统一脱敏能力。修改 `pixflow-common/src/main/java/com/pixflow/common/sanitize/Sanitizer.java`，保留现有 `sanitizeMessage`、`sanitizePath`、`truncate` 兼容行为，同时新增适合 trace 的结构化脱敏入口。建议新增 `sanitizeTraceText(String raw)` 和 `sanitizeTraceValue(String fieldName, Object value)` 这类方法，字段名命中 `apiKey`、`apikey`、`accessKey`、`accessKeyId`、`accessKeySecret`、`secretKey`、`token`、`authorization`、`cookie`、`password`、`username`、`userName`、`email`、`phone` 时必须遮蔽。文本规则必须覆盖 `Bearer ...`、`sk-...`、`LTAI...`、常见 `api_key=...`、`access_key_secret=...`、邮箱、手机号和 Windows/Unix 绝对路径。用户名默认视为敏感，trace 中只允许保留遮蔽值或稳定 hash。这个阶段必须先补 `pixflow-common/src/test/java/com/pixflow/common/sanitize/SanitizerTest.java`。

第三阶段是建立 eval 对外 API 和模型。新增 `pixflow-eval/src/main/java/com/pixflow/harness/eval/api/TraceRecorder.java`、`TurnTrace.java`、`TraceQuery.java`、`TraceReplay.java`。新增 `model` 包，包含 `TraceInput`、`TraceToolCall`、`TraceRecall`、`TracePruneEntry`、`TraceError`、`TurnTraceRecord`、`TurnStatus`、`RuntimeScope`、`TraceExternalPayloadRef`、`ReplayedTurn`、`TraceQueryCriteria`。这些类型必须是 eval 自己的最小记录视图，不能引用 tools、context、permission、hooks 的具体 Java 类型。比如工具分类和权限决策只存字符串或枚举名，不存 `ToolDescriptor` 或 `PermissionDecision` 对象。

第四阶段是实现写面累积器和当前回合 holder。新增 `DefaultTraceRecorder`、`BufferedTurnTrace`、`CurrentTurnTraceHolder`。`DefaultTraceRecorder.begin(conversationId, turnNo, traceId, runtimeScope)` 创建 `BufferedTurnTrace`，把 `OPEN` 占位命令投递到异步缓冲，并返回给 loop。`BufferedTurnTrace` 在一个回合线程内累积 input、tool call、recall、prune 和 error，不做锁。`commit()` 聚合为 `COMMITTED` 记录并投递；`abort(PixFlowException)` 聚合为 `ABORTED` 记录并投递。`CurrentTurnTraceHolder` 用于 loop 在当前回合执行期间绑定 `TurnTrace`，让 `EvalErrorRecorder` 能找到回合内错误。异步边界不能假设 ThreadLocal 自动传播；进入 MQ 或 worker 后必须由调用方重新绑定。

第五阶段是实现异步 ingest 管线。新增 `TraceIngestBuffer`，内部使用有界队列和后台 flusher。队列满时直接丢弃并增加 `pixflow.eval.trace.dropped`，不能回退成同步数据库写入。flusher 按 batch size 或 flush interval 双触发批量刷库。shutdown 时有限 drain，超过 `drain-timeout-on-shutdown` 的剩余记录按丢弃计数处理。这个阶段还要实现 `NoopTraceRecorder`，当配置 `pixflow.eval.enabled=false` 时写面降级为 no-op，但读面仍可查询历史。

第六阶段是实现 payload codec、外置和持久化。新增 `support/TracePayloadCodec.java`，它是唯一允许把 trace model 转成落库 JSON 的入口。codec 必须先序列化，再递归或字段级脱敏，再判断列大小，再决定是否外置。大 payload 外置必须发生在脱敏之后，不能把未脱敏原文写入 MinIO。当前 storage 模块没有共享 `ToolResultStorage` 类型时，先在 eval 内创建薄适配器，基于 `ObjectStorage.put`、`ObjectStorage.getBytes`、`ObjectStorage.delete` 和 `StorageKeys.toolResult(id)` 实现 trace payload 外置；后续 storage 新增共享类型后再替换。新增 `store/AgentTraceEntity.java`、`AgentTraceMapper.java`、`AgentTraceRepository.java`。repository 负责 upsert，唯一键是 `(conversation_id, turn_no)`，状态只能从 `OPEN` 升级到 `COMMITTED` 或 `ABORTED`，不能被旧 `OPEN` 覆盖。

第七阶段是实现读面查询和回放。`TraceQuery` 提供 `getTurn`、`listByConversation` 和 `query`，分页使用 `com.pixflow.common.web.Pagination` 与 `PageResponse`。筛选条件至少支持时间范围、conversationId、traceId、turnStatus、runtimeScope、工具名、是否含 error、是否发生 prune。`TraceReplay` 从 `agent_trace` 解码 JSON 并回读外置 payload。外置对象缺失时返回 preview 和 `missingExternal=true`，不能让整个回放失败。读面只读，不提供修改 trace 的能力。

第八阶段是实现 `EvalErrorRecorder` 和指标。新增 `error/EvalErrorRecorder.java` 实现 `com.pixflow.common.observability.ErrorRecorder`。回合内错误通过 `CurrentTurnTraceHolder` 并入当前 `TurnTrace` 的 error 段，回合外错误只打 `pixflow.error.count{category, code, recovery}` 并写结构化日志，不伪造 `agent_trace` 行。eval 自带的指标只包括错误计数和缓冲健康度，例如 `pixflow.eval.trace.buffer.size`、`pixflow.eval.trace.dropped`、`pixflow.eval.trace.flush.latency`。不要让 eval 变成通用指标代理。

第九阶段是实现保留清理。新增 `retention/TraceRetentionJob.java`，使用 `@Scheduled` 和 ShedLock 多节点单跑，删除 `created_at < now - retention.days` 的 trace 行。删除要分批，避免大事务。如果外置对象是 eval 自己创建的 trace payload，可以在删除 trace 后调用 storage 删除；如果对象可能被多个模块共享或去重引用，必须先确认 storage 是否提供安全释放语义，没有则不要粗暴删除共享对象，而是在计划和代码注释中记录限制。

第十阶段是配置和自动装配。新增 `config/EvalProperties.java` 和 `EvalAutoConfiguration.java`。配置前缀是 `pixflow.eval`，包括 `enabled`、`buffer.capacity`、`flush-batch-size`、`flush-interval`、`flush-threads`、`drain-timeout-on-shutdown`、`column-externalize-threshold`、`schema-version`、`retention.days`、`retention.cleanup-cron`、`retention.cleanup-batch-size`。自动装配必须在缺少数据库或 storage bean 的测试场景下可被替换或禁用。

第十一阶段是补齐测试。测试必须覆盖回合聚合、OPEN 占位、状态单向 upsert、异步非阻塞、队列满丢弃、脱敏、外置、回放、ErrorRecorder、保留清理、schema version 兼容和 no-op 降级。测试应优先使用纯内存 fake repository 和 fake storage 验证核心逻辑，再按项目现有测试策略补 MySQL/MinIO 集成测试。

## Concrete Steps

从仓库根目录开始执行：

    cd D:\study\PixFlow
    git status --short

如果当前工作区已有与 eval 无关的用户改动，不要回滚，只在本计划涉及的文件范围内继续推进。

确认 eval 设计文本位置：

    cd D:\study\PixFlow
    rg -n "记录，不评估|被动 sink|TraceRecorder|TraceIngestBuffer|agent_trace|脱敏前置|ErrorRecorder SPI|大 payload 外置" docs\design-docs\harness\eval.md

确认 common 中错误记录和脱敏位置：

    cd D:\study\PixFlow
    rg -n "Sanitizer|ErrorRecorder|traceId|pixflow.error.count" docs\design-docs\base\common.md pixflow-common\src\main\java

确认 storage 中对象存储和 tool result key 位置：

    cd D:\study\PixFlow
    rg -n "ObjectStorage|StorageKeys|toolResult|ObjectRef" pixflow-infra-storage\src\main\java docs\design-docs\infra\storage.md

实现 common 脱敏增强后先运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-common test

成功时应看到 Maven `BUILD SUCCESS`，并且 `SanitizerTest` 中 API key、Authorization、cookie、用户名、邮箱、手机号、路径等测试全部通过。

新增 eval 模块后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-eval -am test

成功时应看到 Maven `BUILD SUCCESS`。如果 eval 模块依赖的前置模块也被编译，`-am` 会自动构建 `pixflow-common` 和 `pixflow-infra-storage`。

完成所有接线后运行：

    cd D:\study\PixFlow
    mvn test

该命令应证明新增 eval 模块没有破坏已存在的 common、permission、hooks、context、state 和 infra 模块。

## Validation and Acceptance

本计划的验收标准是可观察行为，不是“类存在”。

第一类验收是回合写入。构造一个 `TraceRecorder.begin("conv-1", 1, "trace-1", MAIN)`，依次记录 input、tool call、recall 和 prune，再调用 `commit()`。测试应观察到主线程不等待数据库写入，后台 flusher 最终写入一行 `agent_trace`，状态为 `COMMITTED`，四段 JSON 都是数组，`schema_version=1`，`trace_id=trace-1`。

第二类验收是崩溃可见和状态单向升级。调用 `begin()` 后不 `commit()`，flusher 刷库后应存在 `OPEN` 行。随后同一 `(conversation_id, turn_no)` 写入 `COMMITTED` 后仍只有一行。再投递迟到的 `OPEN`，不能把 `COMMITTED` 回退成 `OPEN`。

第三类验收是异步非阻塞。用一个会阻塞的 fake repository 让 flusher 卡住，调用 `commit()` 仍应快速返回。测试不能依赖精确毫秒，但应断言它没有等待 fake repository 释放。

第四类验收是 best-effort 丢弃。把队列容量设为很小，填满后继续投递 trace。`commit()` 不抛异常，业务线程继续，`pixflow.eval.trace.dropped` 增加。

第五类验收是统一脱敏。构造含有 `Authorization: Bearer abc`、`api_key=sk-1234567890123456`、`accessKeySecret=secret`、`Cookie: sid=abc`、`username=张三`、`email=a@example.com`、`phone=13800138000`、`D:\study\PixFlow\secret.txt` 的 tool input、tool result、error details 和 prompt preview。落库 JSON 和外置 MinIO 对象内容都不能出现原始敏感值。

第六类验收是大 payload 外置。把 `column-externalize-threshold` 设小，构造超阈值 JSON。落库列应只包含引用、preview、size、hash 或 etag 等元数据；外置对象内容应是脱敏后的完整 JSON。回放时能读回完整内容；删除外置对象后回放仍返回 preview 和 `missingExternal=true`。

第七类验收是 ErrorRecorder。回合内抛出的 `PixFlowException` 经 `EvalErrorRecorder.record` 后应并入当前 trace 的 `error_json` 或对应 span。回合外调用 `record` 不应新增 `agent_trace` 行，但应增加 `pixflow.error.count` 并产生结构化日志。

第八类验收是读面查询和回放。`getTurn` 可以按会话和回合取回记录，`listByConversation` 使用分页，`query` 可以按时间、工具名、状态和是否含 error 筛选，`TraceReplay` 可以重建该回合输入、工具调用、召回、裁剪和错误现场。

第九类验收是保留清理。插入 7 天前和 7 天内的 trace 行，运行 `TraceRetentionJob` 后只有过期行被分批删除。若这些行包含 eval 自己创建的外置对象引用，应观察到对象被安全删除或记录为等待后续 storage 引用管理。

## Idempotence and Recovery

本计划推荐 additive changes。新增 `pixflow-eval` 模块、扩展 `Sanitizer`、新增表映射、增加测试，都可以重复运行 Maven 测试，不应修改业务数据。

如果 `Sanitizer` 扩展导致现有 common 测试失败，不要删除旧行为。保留 `sanitizeMessage` 的 1000 字截断和原有凭证/路径规则，在新增 trace 专用入口里扩展更严格规则。

如果 `pixflow-eval` 新模块加入根 `pom.xml` 后 Maven 失败，先检查 `<modules>` 和 `<dependencyManagement>` 是否同时加入 `pixflow-eval`，再检查 `pixflow-eval/pom.xml` 是否只依赖已存在模块。不要为了编译方便让 eval 依赖还不存在的 loop、tools 或 rubrics 模块。

如果没有真实 MySQL 或 MinIO 环境，先用 fake repository 和 fake storage 完成核心测试。后续 Docker/Testcontainers 集成测试按项目现有 Windows Docker 策略补齐。不要让核心逻辑测试依赖外部服务可用性。

如果 storage 的对象去重或引用计数语义不明确，retention 阶段不要实现可能误删共享对象的清理。先删除 DB 过期行并记录外置对象回收限制，再在 `Surprises & Discoveries` 和 `Decision Log` 里写明原因。

## Artifacts and Notes

本计划创建前已确认设计文档中的关键位置如下。

    在 `docs/design-docs/harness/eval.md` 中，`记录，不评估`、`被动 sink`、`TraceRecorder`、`TraceIngestBuffer`、`TracePayloadCodec`、`ErrorRecorder SPI`、`agent_trace`、`大 payload 外置`、`保留与归档` 是最关键的定位词。

    在 `docs/design-docs/design.md` 中，`Evaluation Interface`、`Rubrics 评估（离线阶段）`、`agent_trace`、`Micrometer/Actuator`、`Spring @Scheduled (+ ShedLock 多节点)` 是最关键的定位词。

    在 `docs/design-docs/base/common.md` 中，`Sanitizer`、`ErrorRecorder`、`traceId`、`pixflow.error.count`、`Trace 与 Error 日志分离` 是最关键的定位词。

    在 `docs/design-docs/harness/context.md` 中，`ContextSnapshot`、`snapshot 落 trace`、`cheap pipeline`、`大结果外置` 是最关键的定位词。

    在 `docs/design-docs/harness/hooks.md` 中，`hooks 自身不写 trace`、`hook span`、`Rubrics 预警不接入在线 hook 链` 是最关键的定位词。

    在 `docs/design-docs/infra/storage.md` 中，`ObjectStorage`、`StorageKeys`、`toolResult` 是最关键的定位词。

## Interfaces and Dependencies

最终应存在以下 Maven 模块、包和 Java 类型。实际文件路径应位于项目根目录下的 `pixflow-eval/src/main/java/com/pixflow/harness/eval/...`。

在根 `pom.xml` 中，新增模块和 dependencyManagement 条目：

    <module>pixflow-eval</module>

    <dependency>
        <groupId>com.pixflow</groupId>
        <artifactId>pixflow-eval</artifactId>
        <version>${project.version}</version>
    </dependency>

在 `pixflow-eval/pom.xml` 中，至少依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-infra-storage
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    com.baomidou:mybatis-plus-spring-boot3-starter 或项目已采用的 MyBatis-Plus starter
    io.micrometer:micrometer-core
    org.springframework.boot:spring-boot-starter-test (test scope)

在 `com.pixflow.harness.eval.api` 中定义：

    public interface TraceRecorder {
        TurnTrace begin(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope);
    }

    public interface TurnTrace {
        void recordInput(TraceInput input);
        void recordToolCall(TraceToolCall call);
        void recordRecall(TraceRecall recall);
        void recordPrune(TracePruneEntry entry);
        void recordError(PixFlowException error);
        void commit();
        void abort(PixFlowException error);
    }

    public interface TraceQuery {
        Optional<TurnTraceRecord> getTurn(String conversationId, int turnNo);
        PageResponse<TurnTraceRecord> listByConversation(String conversationId, Pagination page);
        PageResponse<TurnTraceRecord> query(TraceQueryCriteria criteria, Pagination page);
    }

    public interface TraceReplay {
        ReplayedTurn replay(String conversationId, int turnNo);
    }

在 `com.pixflow.harness.eval.model` 中定义：

    TraceInput
    TraceToolCall
    TraceRecall
    TracePruneEntry
    TraceError
    TurnTraceRecord
    TurnStatus
    RuntimeScope
    TraceQueryCriteria
    TraceExternalPayloadRef
    ReplayedTurn

在 `com.pixflow.harness.eval.recorder` 中定义：

    DefaultTraceRecorder
    BufferedTurnTrace
    CurrentTurnTraceHolder
    TraceIngestBuffer
    NoopTraceRecorder

在 `com.pixflow.harness.eval.support` 中定义：

    TracePayloadCodec
    TraceExternalPayloadStorage

在 `com.pixflow.harness.eval.store` 中定义：

    AgentTraceEntity
    AgentTraceMapper
    AgentTraceRepository

在 `com.pixflow.harness.eval.error` 中定义：

    EvalErrorRecorder

在 `com.pixflow.harness.eval.retention` 中定义：

    TraceRetentionJob

在 `com.pixflow.harness.eval.config` 中定义：

    EvalProperties
    EvalAutoConfiguration

数据库表 `agent_trace` 的生产级字段应包括：

    id BIGINT primary key
    conversation_id VARCHAR not null
    turn_no INT not null
    trace_id VARCHAR not null
    schema_version INT not null
    turn_status TINYINT not null
    runtime_scope VARCHAR not null
    input_json JSON null
    tool_calls_json JSON null
    recall_json JSON null
    prune_log_json JSON null
    error_json JSON null
    created_at DATETIME not null
    updated_at DATETIME not null

索引至少包括：

    unique key uk_agent_trace_conversation_turn (conversation_id, turn_no)
    index idx_agent_trace_trace_id (trace_id)
    index idx_agent_trace_created_at (created_at)

配置项应包括：

    pixflow.eval.enabled=true
    pixflow.eval.buffer.capacity=10000
    pixflow.eval.buffer.flush-batch-size=200
    pixflow.eval.buffer.flush-interval=2s
    pixflow.eval.buffer.flush-threads=1
    pixflow.eval.buffer.drain-timeout-on-shutdown=10s
    pixflow.eval.column-externalize-threshold=262144
    pixflow.eval.schema-version=1
    pixflow.eval.retention.days=7
    pixflow.eval.retention.cleanup-cron=0 30 3 * * *
    pixflow.eval.retention.cleanup-batch-size=1000

## Revision Notes

2026-06-28 / Codex: 创建本计划。原因是用户确认 eval 模块按生产级完整实现，不按 MVP 缩减，并要求计划文档说明架构思路、机制以及可用于快速定位设计文档的搜索关键词；同时用户明确要求 trace 自动脱敏 API key、用户名等敏感信息，因此本计划把统一脱敏列为硬性验收要求。

2026-06-28 / Codex: 完成 `pixflow-eval` 首版实现并更新进度。验证命令为 `mvn -f pixflow-common\pom.xml test` 和 `mvn -pl pixflow-eval -am test`；后者覆盖 common、infra-storage、eval 的 reactor 构建。保留事项是生产 MyBatis repository 与真实 MySQL/MinIO 集成测试。
