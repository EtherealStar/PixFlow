# 按代码审查报告重构 Agent 会话连续性、记忆、子 Agent、Prompt 缓存与契约边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树、这份计划和代码审查报告，就应能完成重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

这份计划要按代码审查报告彻底修复 `pixflow-agent` 的核心正确性和可靠性问题。完成后，用户在同一个 conversation 中连续对话时，Agent 能看到历史 transcript，turn number 按真实会话递增，resume / continue / fork 不会丢上下文；Session Memory 的保存不会在并发下重复插入、覆盖新摘要或倒退 `lastSummarizedSeq`；`agent` 工具会运行真实 child runtime 或返回结构化错误，不再把 prompt echo 当成功；所有外部输入、配置、public record、skill frontmatter 和 prompt fingerprint 都有稳定、可测试、可追踪的契约。

本计划不是给旧实现打补丁。凡是审查报告指出的旧路径本身就是风险来源的，都要重构删除或替换：删除 `AgentOrchestrator` 每回合新建空 `MessageStore` 的路径，删除硬编码 `turnNo = 1` 或用 `turnNo` 冒充 `seq` 的路径，删除 `SubagentRunner` prompt echo 成功路径，删除 unchecked cast 依赖，删除 `Executors.newFixedThreadPool` 无界队列路径，删除 default-charset fingerprint，删除 `Map.toString()` 参与缓存身份，删除 read-modify-write repository 更新，删除 `IF NOT EXISTS` 版本迁移漂移口径，删除日志只打印 `e.getMessage()` 的错误吞诊断路径。不保留旧 bean、旧宽松配置、旧不稳定 fingerprint 或旧 false-success 行为作为兼容分支。

## Progress

- [x] (2026-07-09 11:35+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和当前 active exec plan，确认本计划必须遵守 active plan 的“开发阶段、清晰范围、不引入无关重构”要求，并放在 `docs/design-docs/exec-plans/`。
- [x] (2026-07-09 11:50+08:00) 阅读代码审查报告，归纳出 conversation continuity、session memory 原子性、subagent false success、配置校验、prompt cache fingerprint、失败归一化、public record 契约、skill persistence、executor/async queue、Flyway migration 十组问题。
- [x] (2026-07-09 12:05+08:00) 阅读 `docs/design-docs/design.md`、`docs/design-docs/agent/agent.md`、`docs/design-docs/harness/session.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/module/memory.md` 和 `docs/design-docs/harness/tools.md`，确认修复必须对齐 message 表唯一写者、每轮 rehydrate、agent 薄装配层、自动记忆召回、工具结构化错误和 child runtime 边界。
- [x] (2026-07-09 12:20+08:00) 读取热点代码：`AgentOrchestrator`、`SessionMemoryService`、`SessionMemoryRepository`、`SubagentRunner`、`AgentProperties`、subagent tools、prompt sections、skill loader/repository 和 migrations，确认报告中的高风险路径仍在当前工作树或已有未完成改动中。
- [x] (2026-07-09 12:35+08:00) 新建本中文 ExecPlan，明确重构机制、实施顺序、旧代码删除范围和验证命令。
- [x] (2026-07-09 13:45+08:00) 重构 Agent turn runtime 主路径：`AgentOrchestrator` 通过 `TranscriptPort.load(conversationId)` seed `MessageStore`，从普通 USER 消息数计算目标 turn number，`streamNewTurn` / `continueTurn` 共享 runtime 构造和驱动路径，异常归一化为 `PixFlowException` 后向上抛，并在 `finally` flush `MessageStore`。
- [x] (2026-07-09 14:10+08:00) 重构 Session Memory 的第一批正确性问题：保存改为 MySQL `INSERT ... ON DUPLICATE KEY UPDATE` 单调推进 `last_summarized_seq`，缓存只在 inserted/advanced 后更新，stale 写入失效缓存；断路器改为每 conversation 计数；提取线程池改由 Spring 托管的有界 `sessionMemoryExtractionExecutor`；提取文本改用 UTF-8 字节长度和 code point 安全截断。
- [x] (2026-07-09 14:30+08:00) 重构 Subagent 第一批正确性问题：删除 `SubagentRunner` prompt echo 成功路径；真实 child runtime 未接通时返回 `subagent_runtime_unavailable` 结构化失败；`VisionSubagentTool` / `ExploreSubagentTool` 增加类型化参数解析，改用 `future.get(timeout, TimeUnit.SECONDS)`，把 invalid input、timeout、interrupt、execution failure 转为结构化 tool error metadata。
- [x] (2026-07-09 14:45+08:00) 收紧部分缓存、诊断和 schema 契约：prompt section hash 输入改为 `StandardCharsets.UTF_8`；skill loader 使用 try-with-resources 并保留 throwable 日志；plan mode tool handler 不再向模型暴露原始异常消息；agent migration 删除 `IF NOT EXISTS`，为 `skill` 和 `session_memory` 增加唯一约束和 check 约束。
- [x] (2026-07-09 14:55+08:00) 更新测试以覆盖当前落地边界：`SubagentRunnerTest` 和 `AgentSubagentAutoConfigurationTest` 验证 runtime unavailable 结构化失败与新 executor；`PlanModeToolHandlerTest` 验证稳定错误码；`AgentOrchestratorTest` 更新到新的 `MemoryContext` / 构造依赖。
- [x] (2026-07-09 15:12+08:00) 运行风险字符串检查，生产 Java / SQL 未命中旧风险路径：`new MessageStore(`、`turnNo = 1`、`Executors.newFixedThreadPool`、`prompt echo`、`future.join()`、`getBytes()`、`Map.toString()`、`IF NOT EXISTS`、`NumberFormatException`、`e.getMessage()`。
- [x] (2026-07-09 15:15+08:00) 运行 `mvn -pl pixflow-agent -am test`，BUILD SUCCESS；agent 模块 40 个测试，0 failures / 0 errors / 0 skipped。
- [x] (2026-07-09 15:20+08:00) 运行 `mvn -pl pixflow-app -am -DskipTests compile`，BUILD SUCCESS；30 个 reactor 模块编译通过。
- [ ] 剩余缺口：真实 child runtime factory 尚未接入；Session Memory 提取仍受 `SessionMemoryPort.scheduleExtraction(conversationId, turnNo)` SPI 限制，尚未拿到真实 message seq 和增量消息；`coveredTurnCount` 仍是近似值；`AgentProperties` Bean Validation、完整 public record 契约、完整 `StableFingerprint` helper、skill `(source,name)` repository API 全面收紧仍未完成。

## Surprises & Discoveries

- Observation: `AgentOrchestrator.streamNewTurn` 和 `continueTurn` 当前都会 `new MessageStore()`，没有从 `harness/session` 的 transcript 事实源恢复历史链。
  Evidence: `rg -n "new MessageStore|continueTurn|streamNewTurn" pixflow-agent/src/main/java/com/pixflow/agent` 命中 `AgentOrchestrator.java` 两个入口。

- Observation: `SessionMemoryService.save` 先 `findByConversationId` 再 `upsert`，并且 `coveredTurnCount` 是读出后加一；两个并发提取可能互相覆盖。
  Evidence: `SessionMemoryRepository.upsert` 先查再 `updateById` / `insert`，没有数据库原子 upsert，也没有按 `lastSummarizedSeq` 条件推进。

- Observation: Session Memory 提取池用 `Executors.newFixedThreadPool(2)`，JDK 默认队列无界；注释声称队列容量 100，但代码没有队列容量。
  Evidence: `SessionMemoryService` 构造函数直接创建 fixed pool，没有 `ThreadPoolExecutor`、`ArrayBlockingQueue` 或 `RejectedExecutionHandler`。

- Observation: `SessionMemoryService.doExtraction` 把 `newMessagesJson` 设为空字符串，并用 `turnNo` 估算 `lastSummarizedSeq`，这会把 message seq 和 turn number 混用。
  Evidence: 代码注释写着“本期简化”和“lastSummarizedSeq 用 turnNo 估算”。

- Observation: `SubagentRunner.runSync` 当前把 prompt echo 成成功结果，这会把未处理工作伪装为 child agent 成功。
  Evidence: `String finalText = "[subagent " + req.type() + "] " + req.prompt(); return SubagentResult.ok(...)`。

- Observation: `VisionSubagentTool` 和 `ExploreSubagentTool` 对 `Map<String,Object>` 参数做 unchecked cast，并直接 `future.join()`。
  Evidence: `VisionSubagentTool` 存在 `(List<String>) args.getOrDefault("imageIds", List.of())` 和 `future.join()`；`ExploreSubagentTool` 存在 `(String)` cast 和 `future.join()`。

- Observation: `AgentProperties` 的类注释声称“无 setXxx”和“不可变”，但实际所有嵌套配置都有 public setter，且可以把嵌套段设为 null。
  Evidence: `AgentProperties` 暴露 `setPrompt(Prompt prompt)`、`setSessionMemory(SessionMemory sessionMemory)` 等 setter，未校验 null 或范围。

- Observation: 多个 prompt section 用 `input.getBytes()` 计算 hash，依赖平台默认 charset；另有 classpath resource 读取未用 try-with-resources。
  Evidence: `rg -n "getBytes\\(\\)|ClassPathResource|getInputStream" pixflow-agent/src/main/java/com/pixflow/agent/prompt` 命中 `ToolPromptSection`、`LongTermMemorySection`、`PreferenceSection`、`ActiveSkillsSection` 和固定 section。

- Observation: `V1__create_skill.sql` 和 `V2__create_session_memory.sql` 使用 `CREATE TABLE IF NOT EXISTS`，这会让 Flyway 版本迁移在表形状漂移时仍标记成功。
  Evidence: `rg -n "IF NOT EXISTS" pixflow-agent/src/main/resources/db/migration` 命中 V1 / V2。

## Decision Log

- Decision: `AgentOrchestrator` 必须从 `harness/session` 的 transcript 事实源恢复 `MessageStore`，不再创建空消息链。
  Rationale: `design.md`、`context.md` 和 `session.md` 明确 MySQL `message` 表是 transcript 唯一事实源，多节点模型要求每轮 rehydrate。空链会让多轮对话、resume、fork 和 memory recall 全部失真。
  Date/Author: 2026-07-09 / Codex

- Decision: turn number 由会话事实源或 conversation dispatch 计算，不能硬编码或用固定值回填。
  Rationale: turn number 进入 hook payload、memory recall signal、trace、日志和持久化键。硬编码 `1` 会污染所有下游信号，导致同会话多轮不可区分。
  Date/Author: 2026-07-09 / Codex

- Decision: Session Memory 保存以 `last_summarized_seq` 为单调推进条件，数据库层原子 upsert，禁止 read-modify-write。
  Rationale: `last_summarized_seq` 是“已经被 session memory 覆盖到哪条普通 message seq”的事实。并发写入只能从旧 seq 推进到新 seq，不能倒退或覆盖更新摘要。
  Date/Author: 2026-07-09 / Codex

- Decision: Session Memory 的断路器按 conversation 隔离，异步执行器由 Spring 托管并有界。
  Rationale: 全局断路器会让一个坏会话影响所有会话；无界 fixed pool 队列会在压测或外部 LLM 卡顿时累积内存。executor 是进程级资源，应由 auto-configuration 和 destroy method 管理。
  Date/Author: 2026-07-09 / Codex

- Decision: `SubagentRunner` 删除 prompt echo 成功路径。真实 child runtime 未接好时，返回 `SubagentResult.error` 并带稳定 error code，不伪装成功。
  Rationale: echo prompt 会泄漏内部提示词，并让调用方把未执行工作当成成功结果。结构化失败比 false success 更安全，也符合 tools 的 handler 异常转 tool error 设计。
  Date/Author: 2026-07-09 / Codex

- Decision: subagent tool 参数先转换为类型化 request DTO，转换失败返回 `invalid_tool_input`，不让 `ClassCastException` 越过工具执行管线。
  Rationale: `tools.md` 明确 schema、工具级 validate、handler 防御三层校验。unchecked cast 让 malformed input 逃出受控错误路径。
  Date/Author: 2026-07-09 / Codex

- Decision: prompt fingerprint 使用 UTF-8、长度前缀、稳定排序和完整渲染输入，不使用 default charset、简单拼接或 `Map.toString()`。
  Rationale: section cache 的身份必须跨平台稳定且覆盖所有影响渲染的输入，否则会产生 stale prompt 或不必要的 cache churn。
  Date/Author: 2026-07-09 / Codex

- Decision: public record / DTO compact constructor 显式校验不变量并防御性复制集合。
  Rationale: 这些类型跨模块传播，不能把无效状态交给下游靠偶发 NPE 或 ClassCastException 暴露。构造期失败更早、更可定位。
  Date/Author: 2026-07-09 / Codex

- Decision: 迁移脚本在开发阶段改为强契约版本，不继续保留 `IF NOT EXISTS` 漂移口径。
  Rationale: 项目仍处开发阶段，审查报告要求数据库迁移具备可执行数据契约。版本迁移静默跳过错误表形状比重置开发库的成本更高。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

本轮已完成代码审查报告中最高风险路径的第一批重构，重点是删除旧 false-success / 空 transcript / 无界 executor / 非结构化错误路径，并让生产代码通过当前聚合验证。

已验证：

    rg -n "new MessageStore\\(|turnNo\\s*=\\s*1|lastSummarizedSeq 用 turnNo|Executors\\.newFixedThreadPool|prompt echo|return SubagentResult\\.ok\\(.*prompt|future\\.join\\(\\)|getBytes\\(\\)|Map\\.toString\\(|IF NOT EXISTS|NumberFormatException|e\\.getMessage\\(\\)" pixflow-agent/src/main/java pixflow-agent/src/main/resources

结果：无命中，说明计划列出的旧风险字符串已从 agent 生产 Java / SQL 路径清除。

    mvn -pl pixflow-agent -am test

结果：BUILD SUCCESS。Reactor 19 个模块通过；`pixflow-agent` 模块 40 个测试通过，0 failures / 0 errors / 0 skipped。该命令包含 Testcontainers 依赖的上游集成测试，当前 Windows Docker 环境可用。

    mvn -pl pixflow-app -am -DskipTests compile

结果：BUILD SUCCESS。Reactor 30 个模块编译通过，`pixflow-app` 装配编译未因 Agent 自动配置、SPI 或 migration 变更失败。

未单独运行 `mvn -pl pixflow-session,pixflow-context,pixflow-agent -am test`；`mvn -pl pixflow-agent -am test` 已覆盖 `pixflow-session`、`pixflow-context` 和 `pixflow-agent` 及其上游依赖测试。

剩余风险：本轮是大计划的阶段性实现，不是全量完成。真实子 Agent runtime、Session Memory 基于真实 message seq 的增量提取、完整配置校验、public record defensive contracts、skill repository `(source,name)` API 全面替换和统一 `StableFingerprint` helper 仍需要后续继续按本计划推进。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。本计划聚焦 `pixflow-agent`，但必须同时触达 `pixflow-context`、`pixflow-session`、`pixflow-tools`、`pixflow-module-memory` 和 `pixflow-conversation` 的接缝，因为审查报告的根因多是跨模块契约没有真正落实。

`pixflow-agent` 是 Agent 决策层装配模块。它的设计定位是“薄装配层”：在每个回合开始时同步记忆召回、加载 Session Memory、组装 system prompt 和可见 tools，然后交给 `harness/loop` 执行。它不应该写 `message` 表，不应该持有模型客户端实现，不应该绕过 tools 执行管线，不应该自己发明持久化事实源。

`pixflow-context` 是运行期工作内存。`MessageStore` 是一个回合内线程封闭对象，持有本回合消息链，并通过 `TranscriptPort` 写穿透给 `pixflow-session`。`MessageStore` 不能作为全局单例，也不能每回合空链启动；它必须在回合开始时从 `TranscriptPort.load(conversationId)` 得到的活动链 seed。

`pixflow-session` 是 `message` 表唯一写者。`message.seq` 是会话内单调序号，`TranscriptPort.load` 返回压缩后活动链，供 context 每轮 rehydrate。conversation 的历史展示可以只读 message 表，但任何新增消息都必须经 context/session 写入。

`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java` 是本计划第一优先级文件。它当前负责 `streamNewTurn` 和 `continueTurn`，但报告指出它每次创建新空 `MessageStore` 并可能硬编码 turn 信息，导致 conversation continuity 丢失。

`pixflow-agent/src/main/java/com/pixflow/agent/sessionmemory/` 是 Session Memory 实现。Session Memory 是会话级摘要记忆，区别于 `module/memory` 的用户偏好、SKU 历史和分析结论 RAG。它应按 message seq 单调覆盖，阈值重入时从 `last_summarized_seq` 后重新计算，而不是把 `coveredTurnCount` 当作当前对话位置。

`pixflow-agent/src/main/java/com/pixflow/agent/subagent/` 是子 Agent 接入。设计文档要求 child runtime 独立装配、工具裁剪、只回最终结果；当前 prompt echo 是占位实现，必须删除。

这里的“重构性修复”指删除错误机制并用新机制替换，而不是在旧路径旁边增加兼容 fallback。例如，不允许“如果无法 load transcript 就回退空 MessageStore 并继续成功”；正确行为是返回结构化错误或让上层按新契约处理空新会话。

## Plan of Work

第一阶段重构 Agent turn runtime。修改 `AgentOrchestrator` 的入口，使 `streamNewTurn` 和 `continueTurn` 共用一个 `TurnRuntimeFactory` 或私有构造流程。该流程必须接收 `conversationId`、可选 user prompt、attachments 和 sink，先取得会话级锁，再从 `TranscriptPort.load(conversationId)` 或 `MessageStore` factory 载入活动链，绑定 conversationId，计算真实 `turnNo`，再追加本轮 user message / attachments。`continueTurn` 不能追加新 user message，但必须复用同一条 rehydrate、Prompt 组装、visible tools、RuntimeState 和 loop 构造路径。删除所有 `new MessageStore()` 空链路径；生产代码里 `MessageStore` 只能由明确的 per-turn factory 创建并 seed。

第二阶段修复 turn number 和 trace / memory signal。新增一个稳定的 turn 计算规则：优先由 `pixflow-conversation` 的 dispatch 层在拿到会话锁后按 message 表普通 user turn 或 conversation turn counter 计算；如果当前还没有 conversation turn 表，则在 session read mapper 暴露 `nextTurnNo(conversationId)`，按已有 USER 消息数 + 1 计算新 turn，`continueTurn` 使用当前未完成 turn number。`RuntimeState.turnNo()`、`MemoryRecallSignal.turnNo()`、hook payload 和 log 都用这一个值。删除硬编码 `1` 和任何用固定值填 turn 的测试替身。

第三阶段接通 transcript 写入与 flush。`AgentOrchestrator` 在 loop 返回或异常退出时必须调用 `MessageStore.flush()`，并在异常路径保留 `TurnTrace.abort` / error recorder 行为。用户消息、assistant 消息、tool results 和 attachments 都通过 `MessageStore` append，最终由 session 落库。测试要证明第一轮写入后第二轮 prompt 包含第一轮 transcript，`continueTurn` 不丢历史也不重复追加 user message。

第四阶段重构 Session Memory repository。把 `SessionMemoryRepository.upsert` 改为数据库原子 upsert。MySQL 可用形态是 `INSERT ... ON DUPLICATE KEY UPDATE`，并在 update 中加单调条件：只有 `VALUES(last_summarized_seq) >= last_summarized_seq` 时才更新 content、hash、source、covered_turn_count 和 updated_at；更严格的方式是新增 `version` 或 `updated_at` 条件，保存前读版本，冲突则重新 load 合并。无论采用哪一种，Java 层都不能再做 `findByConversationId` 后决定 insert/update。返回值必须告诉调用者是 insert、advanced、stale_skip 还是 conflict_retry_exhausted。

第五阶段修复 Session Memory 提取语义。`scheduleExtraction` 的输入改为包含 `conversationId`、本轮 head message seq、turnNo 和 traceId 的 request，而不是只有 `turnNo`。`doExtraction` 从 session/context 读取 `last_summarized_seq` 之后的新 messages，序列化为明确格式传给 `SessionMemoryExtractor`。保存时 `lastSummarizedSeq` 必须等于本次实际覆盖的最高普通 message seq，不得用 turnNo 估算。`computeThreshold` 的 turns 从 `last_summarized_seq` 之后的 USER/ASSISTANT turn 增量重算，tokens 用 UTF-8 或项目 token estimator 估算，不能长期返回 0。

第六阶段重构 Session Memory 异步执行器和断路器。删除 `SessionMemoryService` 内部 `Executors.newFixedThreadPool`，改由 `AgentAutoConfiguration` 或专门的 `AgentSessionMemoryAutoConfiguration` 发布 `sessionMemoryExtractionExecutor`，使用 `ThreadPoolExecutor(core, max, keepAlive, ArrayBlockingQueue(queueCapacity), namedThreadFactory, explicit RejectedExecutionHandler)`，`destroyMethod=shutdown`。Rejected 时返回指标和 warn，不吞。断路器状态按 conversationId 存储，例如 Caffeine/ConcurrentHashMap，或用 Redis / DB 记录；一个会话连续失败不能让其他会话进入 fallback。所有异步任务都必须有超时，超时归一化为 `AGENT_SESSION_MEMORY_EXTRACTION_FAILED`。

第七阶段修复 Session Memory 文本处理。`SessionMemoryExtractor` 和 `SessionMemoryUpdater` 对 max bytes 的处理必须使用 `StandardCharsets.UTF_8`，并按 Unicode code point 安全截断，不能截断 surrogate pair。fallback 内容必须把用户/assistant/tool 片段放进清晰分隔块，避免 Markdown / prompt-control 注入，例如每条消息用长度前缀或 fenced-like but escaped 的 plain text block 表示。日志必须传异常对象 `log.warn("...", e)`，不只记录 `e.getMessage()`。

第八阶段重构 SubagentRunner。删除 `runSync` 的 prompt echo。新增 `ChildRuntimeFactory` 生产实现：创建 child `RuntimeState`、ephemeral `MessageStore`、`ContextEngine`、只读 `ToolRegistry` view 和 child `AgentLoop`，继承父 `CurrentModelContext.systemPrompt`，禁用递归 `agent` 工具，并按 `SubagentRequest.type` 裁剪工具。若真实 child loop 暂时无法接通，`SubagentRunner` 必须返回 `SubagentResult.error("subagent runtime unavailable")`，并让工具结果成为结构化 tool error；不得成功返回 prompt。

第九阶段修复 subagent tools 入参和 join 语义。给 `VisionSubagentTool` 和 `ExploreSubagentTool` 增加 request parser，例如 `SubagentToolArguments.parseVision(Map<String,Object>)` 和 `parseExplore(...)`，逐项用 `instanceof` 校验字符串、列表、枚举和长度，失败返回 `ToolHandlerOutput.error` 或抛受控 `PixFlowException(VALIDATION/SKIP)`。`CompletableFuture.join()` 改为 `orTimeout(timeoutSeconds).handle(...)` 或 `get(timeout, TimeUnit.SECONDS)`，把 `TimeoutException`、`CompletionException` 和 `RejectedExecutionException` 都归一化到 `SubagentResult.error` metadata，不能让 unchecked exception 穿透 handler。

第十阶段收紧 `AgentProperties`。在配置类上启用 Bean Validation 或显式 `validate()`，保证 nested sections 不为 null，pool core/max/queue/keepAlive 合法，timeout、TTL、token、byte、cache size、description length、when-to-use length 都大于 0 且有上限。setter 对 null 嵌套配置直接拒绝，或替换为默认实例但记录明确行为；不要保留“null 导致 NPE”的旧路径。`AgentSubagentAutoConfiguration` 使用这些已验证配置创建有界 executor，并设置 `RejectedExecutionHandler`。

第十一阶段修复 prompt cache fingerprint。统一新增 `StableFingerprint` helper，使用 `MessageDigest SHA-256`、`StandardCharsets.UTF_8`、长度前缀和字段名分隔，支持稳定排序的 Map/List 写入。修改 `WorkspaceStateSection`、`SessionMemorySection`、`LongTermMemorySection`、`ToolPromptSection`、`ActiveSkillsSection`、`PreferenceSection` 等所有 cacheable section，使 fingerprint 包含所有影响 rendered body 的输入，且不使用 `Map.toString()`、默认 charset 或非确定性集合顺序。资源文件读取改为 try-with-resources，并把读取失败归一化为 prompt assembly error。

第十二阶段收紧 public record 和 SPI 契约。`PromptSummary`、`PromptRuntimeContext`、`SubagentResult`、`SkillFrontmatter`、`CacheStats`、`MemoryRecallSignal` 等 public 类型必须在 compact constructor 或 setter 入口校验：必填字符串非空、计数非负、成功结果不能同时有 error、失败结果必须有 safe error、集合 defensive copy 且元素非 null、metadata key 非空且 value 是 JSON 可序列化简单值。删除或修正与实现不一致的 Javadoc。

第十三阶段修复 skill loading 和 persistence。`SkillLoader` 按 `(source, name)` 唯一键 upsert，不允许 BUILTIN 覆盖 PROJECT / TEAM 同名 skill。启动扫描时先检测重复 BUILTIN skill name，重复直接报 `AGENT_SKILL_LOAD_INVALID` 并拒绝本次同步，而不是按 classpath 顺序覆盖。`SkillRepository.deleteByName` 改为一条 SQL delete by `(source, name)`，update/delete 返回 row count 并在 0 行时暴露给调用方。`SkillFrontmatterParser` 拒绝重复 required keys，version overflow 转 `SkillParseException`，不抛 raw `NumberFormatException`。

第十四阶段修复失败归一化和诊断。`TurnStoppedSessionMemoryHook`、`SubagentRunner`、`ForkChildSummarizationPort`、skill loader、memory hooks 等所有 catch 块都要保留异常对象进日志。`ForkChildSummarizationPort` 对 child error、blank output 和 exception 不返回 empty successful summary；它应返回失败并增加 circuit breaker 计数，让 context 走确定性 fallback。`AgentOrchestrator` 捕获非 `PixFlowException` 后若已经归一化，就抛归一化后的异常，不再 rethrow 原始 runtime exception 绕过 web/SSE 错误渲染。

第十五阶段修复 migrations 和 schema contract。因为项目处于开发阶段，直接把 `pixflow-agent/src/main/resources/db/migration/V1__create_skill.sql` 和 `V2__create_session_memory.sql` 改为强契约迁移，删除 `IF NOT EXISTS`。`skill` 表增加 `(source, name)` 唯一约束、`source` check、`version > 0` check、非空审计列默认或由应用管理的明确说明。`session_memory` 表增加 `conversation_id` 唯一、`last_summarized_seq >= 0`、`covered_turn_count >= 0`、`content_hash` 非空、必要时 `version` 字段。若本地已有旧 dev 数据库，执行者需要按项目开发流程 clean 重建，不能让漂移 schema 混过验证。

第十六阶段更新设计文档。更新 `docs/design-docs/agent/agent.md` Revision Notes，记录本次重构把 conversation continuity 改为 transcript rehydrate、Session Memory 改为原子 seq 推进、SubagentRunner 删除 echo、prompt fingerprint 稳定化和配置契约收紧。必要时更新 `docs/design-docs/harness/session.md` 和 `docs/design-docs/harness/context.md` 的接缝说明，明确 agent 通过 per-turn factory 获取 seeded `MessageStore`。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认旧风险路径：

    rg -n "new MessageStore\\(|turnNo\\s*=\\s*1|lastSummarizedSeq 用 turnNo|Executors\\.newFixedThreadPool|prompt echo|return SubagentResult\\.ok|future\\.join\\(\\)|getBytes\\(\\)|Map\\.toString\\(|IF NOT EXISTS|NumberFormatException|e\\.getMessage\\(\\)" pixflow-agent/src/main/java pixflow-agent/src/main/resources

实施完成后，同一命令不应在生产 Java / SQL 里命中旧风险路径。允许命中测试中验证旧行为不存在的字符串，允许命中本计划文档，不允许命中生产实现。

第一组测试覆盖 conversation continuity：

    pixflow-agent/src/test/java/com/pixflow/agent/AgentOrchestratorConversationContinuityTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/AgentOrchestratorContinueTurnTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/TurnDispatchServiceTest.java

这些测试应证明：第一轮 user / assistant / tool messages 经 session flush 后，第二轮 rehydrate 能看到；`turnNo` 从 1 到 2 递增；`continueTurn` 不追加新的 user message；异常路径仍 flush 或明确 abort，不产生半成功。

第二组测试覆盖 Session Memory：

    pixflow-agent/src/test/java/com/pixflow/agent/sessionmemory/SessionMemoryRepositoryTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/sessionmemory/SessionMemoryServiceConcurrencyTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/sessionmemory/SessionMemoryExtractorTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/config/AgentSessionMemoryAutoConfigurationTest.java

这些测试应覆盖：两个并发 save 对同 conversation 的不同 seq 不会倒退；stale seq 被跳过；提取 request 使用真实 message seq；UTF-8 max bytes 不切坏 emoji 或中文；executor 队列满时走 rejected handler；A 会话断路不影响 B 会话。

第三组测试覆盖 Subagent：

    pixflow-agent/src/test/java/com/pixflow/agent/subagent/SubagentRunnerTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/subagent/tools/VisionSubagentToolTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/subagent/tools/ExploreSubagentToolTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/summarization/ForkChildSummarizationPortTest.java

这些测试应覆盖：`SubagentRunner` 不 echo prompt；真实 child runtime 成功时返回 child final text；runtime unavailable 返回 error；malformed `imageIds` / `prompt` / `type` 不抛 ClassCastException；future timeout 和 rejected execution 被归一化；summarization blank/error 不返回成功空摘要。

第四组测试覆盖 prompt/cache/records/skill/config：

    pixflow-agent/src/test/java/com/pixflow/agent/prompt/StableFingerprintTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/prompt/PromptContractsTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/skill/SkillLoaderTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/skill/SkillFrontmatterParserTest.java
    pixflow-agent/src/test/java/com/pixflow/agent/config/AgentPropertiesValidationTest.java

这些测试应覆盖：相同输入跨 JVM 默认 charset 仍得相同 fingerprint；字段顺序变化不影响 Map fingerprint；影响渲染的输入变化必然改变 fingerprint；record 拒绝 invalid state；重复 skill frontmatter key 被拒绝；PROJECT/TEAM skill 不被 BUILTIN 同名覆盖；非法 pool size / TTL / token / bytes 在启动期失败。

运行模块验证：

    mvn -pl pixflow-agent -am test
    mvn -pl pixflow-session,pixflow-context,pixflow-agent -am test
    mvn -pl pixflow-app -am -DskipTests compile

如果迁移脚本改动触发 Flyway checksum 或本地开发库形状冲突，开发环境按当前项目约定重建数据库。不要为了通过迁移而重新加入 `IF NOT EXISTS`。

收尾检查：

    rg -n "new MessageStore\\(|turnNo\\s*=\\s*1|lastSummarizedSeq 用 turnNo|Executors\\.newFixedThreadPool|prompt echo|return SubagentResult\\.ok\\(.*prompt|future\\.join\\(\\)|getBytes\\(\\)|Map\\.toString\\(|IF NOT EXISTS|NumberFormatException" pixflow-agent/src/main/java pixflow-agent/src/main/resources
    rg -n "catch \\(.*\\) \\{[^}]*e\\.getMessage\\(\\)" pixflow-agent/src/main/java
    rg -n "CREATE TABLE IF NOT EXISTS" pixflow-agent/src/main/resources/db/migration

预期第一条不命中旧生产路径，第二条不命中只丢消息不传异常对象的 catch，第三条不命中。

## Validation and Acceptance

Conversation continuity 验收：启动一个 fake model / fake tool 的两轮对话。第一轮用户说“记住我刚上传的是 A 包”，第二轮用户问“我刚才上传的是什么”。第二轮 prompt 的 model-visible messages 必须包含第一轮 user / assistant 或压缩摘要，`MemoryRecallSignal.turnNo` 为 2，trace 里的 turn number 也是 2。旧实现会因为空 `MessageStore` 看不到第一轮内容；新实现必须通过测试证明已修复。

Resume / continue 验收：`continueTurn(conversationId)` 在已有未完成 turn 或 fork 场景中重建同一活动链，不追加新的 USER message，不把 turn number 重置为 1。hook payload、runtime state、trace 和 logs 使用同一个 turn number。

Session Memory 验收：构造同一 conversation 的两个并发提取，较旧的 `lastSummarizedSeq=10` 和较新的 `lastSummarizedSeq=20` 同时保存。最终 DB 行必须覆盖到 20，content/hash 与 seq=20 的摘要一致；旧写不会覆盖新写，`coveredTurnCount` 不会重复加成。重新进入会话时，从 seq=21 开始计算增量。

Subagent 验收：调用 `agent(type=vision)` 时，如果 child runtime fake 返回“视觉总结”，工具结果包含该总结；如果 child runtime 未装配或超时，工具结果是 `error=true` 的结构化错误。任何情况下都不能返回原始 prompt echo 作为成功结果。

Malformed input 验收：给 `VisionSubagentTool` 传 `imageIds="not-list"` 或 `prompt=123`，handler 不抛 `ClassCastException`，而是返回 `invalid_tool_input`，metadata 包含 `errorCategory=VALIDATION` 和 `recovery=SKIP`。

Prompt cache 验收：同一 `MemoryContext`、tool schema 和 skills 在不同 JVM 默认 charset 环境下 fingerprint 相同；改变 rendered body 依赖字段时 fingerprint 改变；Map 输入顺序不同但语义相同不改变 fingerprint。

配置验收：`pixflow.agent.subagent.pool.core-size=0`、`max-size < core-size`、`queue-capacity=0`、`session-memory.cache.ttl-seconds=-1`、`session-memory.max-content-bytes=0` 都在 Spring context 启动阶段失败，并给出可定位错误。

Skill 验收：存在 BUILTIN 和 PROJECT 同名 skill 时，BUILTIN 同步只更新 BUILTIN 行，不覆盖 PROJECT 行；重复 BUILTIN skill name 被拒绝并记录结构化错误；frontmatter 里重复 `name` 或 version overflow 被解析为 `SkillParseException`。

Migration 验收：新建空 dev DB 运行 Flyway 后，`skill` 表和 `session_memory` 表具有唯一约束、check 约束或等价应用约束；已有错误形状的表不会被 `IF NOT EXISTS` 静默跳过。

## Idempotence and Recovery

本计划可以分阶段实施。每个阶段完成后运行对应模块测试，失败时不要恢复旧风险路径，而要沿新边界修复调用点或测试替身。

重构 `AgentOrchestrator` 时，如果某些测试依赖空 `MessageStore`，应改测试为 seeded in-memory `TranscriptPort` 或 fake session factory；不要在生产构造路径保留“空链 fallback”。新 conversation 的合法空历史由 `TranscriptPort.load` 返回空列表表达。

Session Memory 数据库更新必须幂等。重复提交同一个 request 不应重复增加 `coveredTurnCount`；stale request 应返回 stale_skip 并刷新 cache 为 DB 最新内容。缓存写失败不能覆盖 MySQL 事实源，下一次 load 应从 MySQL 重建。

Subagent child runtime 如果依赖尚未完全具备，应先实现 structured unavailable error，再逐步接真实 child loop；不要用 prompt echo 维持“成功”。这样会让功能暂时降级但不会污染业务结果。

迁移脚本改动发生在开发阶段。若本地开发库已有旧 Flyway checksum，执行者应按项目开发数据库重建流程处理；不要为了迁就旧库而保留 `IF NOT EXISTS` 或弱约束。若用户明确要求保护某个已有库，先备份，再补一个显式修正迁移，而不是静默跳过。

## Artifacts and Notes

审查报告对应的最高风险证据：

    AgentOrchestrator:
        streamNewTurn(...) -> new MessageStore()
        continueTurn(...) -> new MessageStore()

    SessionMemoryRepository:
        if (findByConversationId(...).isPresent()) updateById(...)
        else insert(...)

    SessionMemoryService:
        Executors.newFixedThreadPool(2, ...)
        newMessagesJson = ""
        save(conversationId, content, turnNo)

    SubagentRunner:
        String finalText = "[subagent " + req.type() + "] " + req.prompt();
        return SubagentResult.ok(finalText, null, 0);

    VisionSubagentTool / ExploreSubagentTool:
        unchecked casts from Map<String,Object>
        future.join()

    prompt sections:
        md.digest(input.getBytes())

    migrations:
        CREATE TABLE IF NOT EXISTS skill
        CREATE TABLE IF NOT EXISTS session_memory

目标机制摘要：

    turn runtime:
        conversation lock -> TranscriptPort.load -> seed MessageStore -> compute real turnNo -> append current input -> loop -> flush -> trace

    session memory:
        read latest seq -> extract seq range -> bounded executor -> timeout -> atomic upsert if seq advances -> cache after DB -> per-conversation circuit breaker

    subagent:
        typed arguments -> child runtime factory -> read-only tool view -> child AgentLoop or structured unavailable -> timeout/error normalized -> ToolExecutionResult

    prompt/skill/contracts:
        stable UTF-8 fingerprint -> defensive public records -> source/name skill identity -> strict migrations -> startup configuration validation

## Interfaces and Dependencies

本计划完成后，应存在一个 per-turn runtime factory 或等价接口。具体名字可按代码风格调整，但生产路径必须表达以下语义：

    public interface AgentTurnRuntimeFactory {
        AgentTurnRuntime create(String conversationId,
                                TurnStartMode mode,
                                List<Attachment> attachments,
                                AgentEventSink sink);
    }

    public record AgentTurnRuntime(
        RuntimeState state,
        MessageStore messageStore,
        ContextEngine contextEngine,
        int turnNo
    ) {}

`AgentOrchestrator` 不应直接 `new MessageStore()`；它应依赖 factory 或 `MessageStoreFactory`：

    MessageStore store = messageStoreFactory.rehydrate(conversationId);
    store.bindConversation(conversationId);

Session Memory repository 应提供原子保存结果：

    enum SessionMemorySaveResult {
        INSERTED,
        ADVANCED,
        UNCHANGED,
        STALE_SKIPPED
    }

    SessionMemorySaveResult saveIfAdvances(SessionMemory memory);

如果使用 MyBatis XML 或注解 SQL，SQL 必须以 `(conversation_id)` 唯一键做 upsert，并保证 `last_summarized_seq` 不倒退。

Session Memory executor 应由 Spring 管理：

    public static final String SESSION_MEMORY_EXECUTOR_BEAN = "sessionMemoryExtractionExecutor";

    @Bean(name = SESSION_MEMORY_EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = SESSION_MEMORY_EXECUTOR_BEAN)
    ExecutorService sessionMemoryExtractionExecutor(AgentProperties properties)

`SubagentRunner` 不应有 prompt echo 分支。它应依赖 child runtime：

    public interface ChildRuntimeFactory {
        ChildRuntime create(SubagentRequest request);
    }

    public record ChildRuntime(
        AgentLoop loop,
        MessageStore messageStore,
        RuntimeState runtimeState
    ) {}

若 child runtime 无法创建：

    return SubagentResult.error("Subagent runtime is unavailable");

工具参数解析应集中：

    public record VisionSubagentArguments(String prompt, List<String> imageIds) {}
    public record ExploreSubagentArguments(String prompt) {}

    static VisionSubagentArguments parseVision(Map<String, Object> args)

Prompt fingerprint helper 应集中：

    public final class StableFingerprint {
        public StableFingerprint field(String name, String value);
        public StableFingerprint field(String name, long value);
        public StableFingerprint list(String name, List<String> values);
        public StableFingerprint map(String name, Map<String, ?> values);
        public String sha256();
    }

Public record constructors 必须防御性复制：

    public PromptSummary {
        sectionDigests = List.copyOf(Objects.requireNonNull(sectionDigests, "sectionDigests"));
        if (totalChars < 0) throw new IllegalArgumentException("totalChars must be >= 0");
    }

Skill 表的身份应为 `(source, name)`，repository API 不再用全局 name 更新：

    Optional<Skill> findBySourceAndName(SkillSource source, String name);
    int updateBySourceAndName(Skill skill);
    int deleteBySourceAndName(SkillSource source, String name);

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是代码审查报告指出 `pixflow-agent` 存在会话连续性丢失、turn 状态损坏、Session Memory 并发覆盖、子 Agent false success、配置与公共契约缺失、prompt fingerprint 不稳定、skill persistence 歧义、executor 无界队列和 migration 弱契约等问题。用户要求中文计划、说明修复机制与思路，并要求重构性完全修复、不保留旧代码。因此本计划选择删除旧风险路径，按设计文档收敛为 transcript rehydrate、真实 turn number、原子 seq 推进、有界托管 executor、真实 child runtime 或结构化错误、类型化 tool input、稳定 fingerprint、严格 public contracts、source/name skill identity 和强迁移契约。

2026-07-09 / Codex: 记录第一批实现与验证结果。已落地 Agent turn runtime rehydrate、Session Memory 原子单调保存与有界 executor、Subagent structured unavailable error、typed tool arguments、UTF-8 fingerprint 输入、部分日志诊断和强 migration 约束，并通过 `mvn -pl pixflow-agent -am test` 与 `mvn -pl pixflow-app -am -DskipTests compile`。保留真实 child runtime、message seq extraction、完整配置/record/skill/fingerprint 契约作为后续缺口，避免把阶段性完成误标为计划全量完成。
