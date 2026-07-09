# 重构 agent 记忆召回接缝：删除旧通道体系并接入 module-memory 正式自动召回

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

本计划要解决 `pixflow-agent` 中旧记忆召回体系与 `pixflow-module-memory` 正式召回体系重复导致的编译错误和架构偏离。完成后，Agent 回合入口不再通过 `MemoryChannelProvider`、agent 自己的 `RrfMerger`、agent 自己的 `MemoryItem` 组织记忆，而是在 Prompt 组装前同步调用 `MemoryService.prepareContext(MemoryContextRequest)`。用户可观察到的结果是：`mvn -pl pixflow-agent -am test` 可以通过，且 system prompt 中的 `instruction_memory` 与 `long_term_memory` 来自 `module-memory` 统一产出的 `MemoryContext`，包含用户偏好、SKU 历史和分析结论召回。

这不是兼容性修补。本计划明确删除旧 provider/SPI/RRF 路径，不保留双轨并行。记忆召回的业务算法、降级、trace 和混合检索归 `pixflow-module-memory`；`pixflow-agent` 只负责构造请求、调用门面、把返回内容注入 Prompt。

## Progress

- [x] (2026-07-08 15:25+08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/agent-module-implementation-plan.md`、`docs/design-docs/agent/agent.md`、`docs/design-docs/module/memory.md`，确认设计要求是 `agent` 消费 `module-memory` 的 `MemoryService.prepareContext(...)`，不是在 agent 内重复实现召回算法。
- [x] (2026-07-08 15:35+08:00) 定位当前编译错误的直接原因：`PreferenceRecallProvider`、`SkuHistoryRecallProvider`、`InsightFulltextRecallProvider` 导入了 `com.pixflow.module.memory.recall.MemoryItem`，但 `MemoryChannelProvider` 要求返回 `com.pixflow.agent.memory.MemoryItem`，泛型返回类型不兼容导致 `@Override` 失败。
- [x] (2026-07-08 15:45+08:00) 确认更深层根因：`pixflow-agent` 同时存在 `MemoryChannelProvider`、`MemoryRecallPlanner`、`RrfMerger`、`MemoryItem` 等旧召回模型，而 `pixflow-module-memory` 已提供 `MemoryService`、`MemoryContextRequest`、`MemoryContextBuilder`、`MemoryContext` 和 `MemorySection`。
- [x] (2026-07-08 16:00+08:00) 新建本中文 ExecPlan，明确重构机制、删除范围、实施步骤和验证命令。
- [x] (2026-07-08 03:35+08:00) 修改 `pixflow-agent` 的 Prompt runtime 上下文，使其持有 `com.pixflow.module.memory.context.MemoryContext`。
- [x] (2026-07-08 03:38+08:00) 重写 `MemoryRecallPlanner` 为 `MemoryService.prepareContext(...)` 的薄适配器，保留类名以降低调用面改动，但删除旧 provider/RRF 职责。
- [x] (2026-07-08 03:41+08:00) 删除 `pixflow-agent` 中旧的 `MemoryChannelProvider`、四个 provider、agent 内 `MemoryItem`、agent 内 `MemorySection`、agent 内 `RrfMerger`、agent 内 `TokenEstimator`、旧 `RecallChannel`、旧 `SkuMentionExtractor` 及 RRF 测试。
- [x] (2026-07-08 03:43+08:00) 修改 `PreferenceSection` 与 `LongTermMemorySection`，直接渲染 `MemoryContext` 中的 `user_preferences`、`sku_history`、`analysis_insights` 三个 section。
- [x] (2026-07-08 03:45+08:00) 更新 `AgentOrchestrator` 的构造参数、`recall(...)`、`prepareTurn(...)` 和 `driveTurn(...)`，移除 `List<MemoryChannelProvider>` 注入，并把返回 Map 的 `"recall"` 改为 `"memoryContext"`。
- [x] (2026-07-08 03:47+08:00) 更新单元测试和 ArchUnit 约束，证明 agent 不再持有旧召回 provider，不再直连 `PreferenceService`、`SkuHistoryService`、`InsightDocMapper` 或 module-memory 内部实现包。
- [x] (2026-07-08 04:05+08:00) 运行 `mvn -pl pixflow-agent -am test`，BUILD SUCCESS。agent 模块 33 个测试全绿，reactor 19 个模块全部 SUCCESS。

## Surprises & Discoveries

- Observation: 直接编译错误并不是 `MemoryItem` 字段不一致，而是 Java 泛型返回类型不兼容。
  Evidence: `MemoryChannelProvider.recall` 声明 `Optional<List<com.pixflow.agent.memory.MemoryItem>>`，而三个实现类由于 import 了 module 侧同名类型，方法实际签名变成 `Optional<List<com.pixflow.module.memory.recall.MemoryItem>>`。

- Observation: `module-memory` 已经有完整的自动召回门面，不需要 agent 自己保留四通道 provider。
  Evidence: `pixflow-module-memory/src/main/java/com/pixflow/module/memory/MemoryService.java` 暴露 `prepareContext(MemoryContextRequest)`；`MemoryContextBuilder` 已组织 `user_preferences`、`sku_history`、`analysis_insights` 三个 section；`MemoryAutoConfiguration` 已装配 `RecallSignalExtractor`、`RecallPlanner`、`PreferenceService`、`SkuHistoryService`、`InsightRecallService` 和 `MemoryService`。

- Observation: 当前 agent 的 `LongTermMemorySection` 使用旧 section 名 `insights.vector` 和 `insights.fulltext`，而 module-memory 统一暴露 `analysis_insights`。
  Evidence: agent 侧 `LongTermMemorySection` 遍历 `sku_history`、`insights.vector`、`insights.fulltext`；module-memory 侧 `MemoryContextBuilder.ANALYSIS_INSIGHTS = "analysis_insights"`。

- Observation: 旧 agent `TokenEstimator` 并非只被 RRF 使用，还被 `SessionMemoryExtractor` 用于日志估算。
  Evidence: `rg "TokenEstimator" pixflow-agent/src/main/java` 显示 `SessionMemoryExtractor` 导入 `com.pixflow.agent.memory.TokenEstimator`。实施时将其替换为类内粗略估算方法，避免为 Session Memory 保留旧 memory 类型。

- Observation: `SkuMentionExtractor` 虽不在本计划最初删除清单内，但已经没有引用，且职责与 module-memory 的 `RecallSignalExtractor` 重叠。
  Evidence: `rg "SkuMentionExtractor" pixflow-agent/src` 只命中自身文件。实施时一并删除，避免 agent 继续持有 SKU 抽取和 LLM/Redis 缓存逻辑。

## Decision Log

- Decision: 不采用“修 import 恢复编译”的短修方案。
  Rationale: 短修只能消除当前泛型错误，但会继续保留 agent 自己的召回算法和 module-memory 正式召回算法双轨并行，后续仍会出现边界重复、trace 不一致和 Prompt 注入来源不清的问题。
  Date/Author: 2026-07-08 / Codex

- Decision: `pixflow-module-memory` 是唯一记忆召回业务边界，`pixflow-agent` 只调用 `MemoryService.prepareContext(...)`。
  Rationale: `docs/design-docs/module/memory.md` 明确 memory 模块负责自动规划、偏好召回、SKU 历史召回、分析结论混合召回、RRF、衰减排序和 trace；`docs/design-docs/agent/agent.md` 明确 agent 是薄装配层，不应重复实现业务检索算法。
  Date/Author: 2026-07-08 / Codex

- Decision: 删除旧 `MemoryChannelProvider` 体系，不保留兼容路径。
  Rationale: 用户明确要求“不要为了兼容性安全保留旧代码”，并且旧体系正是编译错误和设计偏离的来源。删除旧路径能让模块边界更清晰，测试也能直接约束单一路径。
  Date/Author: 2026-07-08 / Codex

- Decision: Prompt 注入以 `MemoryContext` 的 section 名为准：`user_preferences` 进入 `instruction_memory`，`sku_history` 和 `analysis_insights` 进入 `long_term_memory`。
  Rationale: 这是 module-memory 已经产出的正式可注入结构，避免 agent 自己再拆分 `insights.vector` 与 `insights.fulltext`。向量和 FULLTEXT 的融合细节应留在 trace，不暴露为 agent prompt section 边界。
  Date/Author: 2026-07-08 / Codex

- Decision: 保留类名 `MemoryRecallPlanner`，不重命名为 `MemoryContextPlanner`。
  Rationale: 现有 agent 调用面和测试已经围绕该类名组织；保留类名能减少无关重命名，但实现已经变为 `MemoryService.prepareContext(...)` 的薄适配器，不再表达 agent 内部通道/RRF 规划。
  Date/Author: 2026-07-08 / Codex

- Decision: 删除旧 `SkuMentionExtractor`，并将 `SessionMemoryExtractor` 对旧 `TokenEstimator` 的依赖替换为局部估算。
  Rationale: SKU 信号提取归 `module-memory` 的 `RecallSignalExtractor`；旧 `TokenEstimator` 属于 agent memory 包，继续保留会让删除旧召回体系不完整。
  Date/Author: 2026-07-08 / Codex

- Decision: 收敛 `AgentProperties.Memory.Recall`，只保留 agent 传给 `MemoryContextRequest` 的 `maxTokens` / `tokenBudget`。
  Rationale: `rrfK`、`maxItems`、`skuPatterns`、`skuLlmFallback`、`categoryFilterEnabled` 属于旧 agent 召回实现或 module-memory 内部召回策略。继续留在 agent 配置会误导后续实现者以为 agent 仍负责 RRF、SKU 抽取或类目过滤。
  Date/Author: 2026-07-08 / Codex

## Outcomes & Retrospective

本计划已实施完成。`pixflow-agent` 不再存在旧召回 provider、agent 内 `MemoryItem` / `MemorySection` / `MemoryRecallResult`、agent 内 RRF 或旧 TokenEstimator。Prompt runtime 上下文现在持有 `module-memory` 的 `MemoryContext`；`PreferenceSection` 渲染 `user_preferences.renderedText`；`LongTermMemorySection` 只渲染 `sku_history` 与 `analysis_insights`，不再暴露 `insights.vector` / `insights.fulltext`。

实际验证结果：

    mvn -pl pixflow-agent -am test
    ...
    [INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Reactor Summary for PixFlow 1.0.0-SNAPSHOT:
    [INFO] PixFlow Agent ...................................... SUCCESS [ 26.034 s]
    [INFO] BUILD SUCCESS

新增或更新的测试覆盖：`MemoryRecallPlannerTest` 验证同步调用 `MemoryService.prepareContext(...)` 并传入 conversationId、turnNo、traceId、userPrompt、attachments、packageId、taskId、skuIds、categoryHints 和 tokenBudget；`MemoryPromptSectionTest` 验证 preference 与 long-term memory section 从 `MemoryContext` 渲染；`AgentArchitectureTest` 新增约束，禁止 agent 依赖 `module-memory` 内部实现包。

遗留风险：`agent.md` 的部分历史叙述仍描述 agent 内 RRF 和 SKU 抽取，这是早期实现计划的残留。当前活跃 ExecPlan 和代码以 module-memory 门面为准；后续若整理总设计文档，应把第六章相应描述更新为“agent 调用 `MemoryService.prepareContext(...)`，RRF/信号提取归 module-memory”。

## Context and Orientation

当前仓库是 Java 21 + Spring Boot 3.5.x 的多 Maven 模块项目。和本计划有关的模块如下。

`pixflow-agent` 是 Agent 决策层装配模块，源码包是 `com.pixflow.agent`。它负责在每个回合开始前加载会话状态、同步召回记忆、加载 Session Memory、渲染 system prompt、投影可见工具 schema，然后构造 `AgentLoop` 跑主循环。关键文件包括：

- `pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java`：Agent 回合入口，当前持有 `MemoryRecallPlanner` 并调用 `recall(...)`。
- `pixflow-agent/src/main/java/com/pixflow/agent/prompt/SectionRenderer.java`：Prompt section 渲染接口和 `PromptRuntimeContext` record。
- `pixflow-agent/src/main/java/com/pixflow/agent/prompt/sections/PreferenceSection.java`：当前从 agent 自己的 `MemoryRecallResult.section("user_preferences")` 渲染用户偏好。
- `pixflow-agent/src/main/java/com/pixflow/agent/prompt/sections/LongTermMemorySection.java`：当前从 agent 自己的 `MemoryRecallResult` 渲染 SKU 历史和 insight 子段。
- `pixflow-agent/src/main/java/com/pixflow/agent/memory/`：当前旧召回体系所在目录，包含 `MemoryChannelProvider`、`MemoryRecallPlanner`、`MemoryItem`、`MemorySection`、`RrfMerger` 和四个 provider。

`pixflow-module-memory` 是自动记忆层，源码包是 `com.pixflow.module.memory`。它负责三类记忆的业务召回和巩固。关键文件包括：

- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/MemoryService.java`：对 agent 暴露的唯一门面，包含 `prepareContext(MemoryContextRequest)`。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemoryContextRequest.java`：Prompt 组装前的自动召回请求，包含 conversationId、turnNo、traceId、userPrompt、attachments、packageId、taskId、skuIds、categoryHints、metadata 和 tokenBudget。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemoryContext.java`：统一召回结果，包含 sections、recallTrace 和 degraded 标志。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemorySection.java`：单个可注入 section，包含 name、renderedText、items、tokenEstimate 和 trace。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemoryContextBuilder.java`：当前已经把召回结果组织成 `user_preferences`、`sku_history`、`analysis_insights` 三个 section。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/config/MemoryAutoConfiguration.java`：Spring 自动装配，已经提供 `MemoryService` bean。

术语说明：

“旧通道体系”指 `pixflow-agent` 内的 `MemoryChannelProvider` 及其四个实现。它把偏好、SKU、向量 insight、FULLTEXT insight 当作 agent 内部通道，并在 agent 内部用 `RrfMerger` 融合。

“正式自动召回体系”指 `pixflow-module-memory` 内的 `MemoryService.prepareContext(...)`。它是设计文档定义的业务边界，负责从用户输入和上下文中提取信号、规划召回类型、调用各类记忆服务、融合分析结论并返回可注入 Prompt 的 `MemoryContext`。

“Prompt 注入”指 `DynamicPromptAssembler` 调用各 `SectionRenderer`，把用户偏好、长期记忆、Session Memory、工具说明等拼进 system prompt。它不应执行数据库、向量检索或 RRF 算法，只消费已准备好的上下文。

## Plan of Work

第一步，修改 agent 的 runtime 上下文类型。打开 `pixflow-agent/src/main/java/com/pixflow/agent/prompt/SectionRenderer.java`，把 `PromptRuntimeContext` 中的 `MemoryRecallResult recall` 字段改为 `MemoryContext memoryContext`，导入 `com.pixflow.module.memory.context.MemoryContext`。字段名建议使用 `memoryContext`，避免继续暗示 agent 自己做 recall result。所有 section 通过 `ctx.memoryContext()` 读取记忆。

第二步，改造 `MemoryRecallPlanner`。当前文件 `pixflow-agent/src/main/java/com/pixflow/agent/memory/MemoryRecallPlanner.java` 不再接收 `List<MemoryChannelProvider>` 和 `RrfMerger`。它应改为持有 `MemoryService`，构造 `MemoryContextRequest`，调用 `memoryService.prepareContext(request)` 并返回 `MemoryContext`。如果为了命名准确，可以把类重命名为 `MemoryContextPlanner`；若重命名会扩大改动面，也可以先保留类名，但其职责必须是 module-memory 门面的薄适配器。该类不得导入 `PreferenceService`、`SkuHistoryService`、`InsightDocMapper`、`InsightRecallService` 等 module-memory 内部服务。

第三步，扩展或替换 `MemoryRecallSignal`。当前 `MemoryRecallSignal` 只包含 userMessage、packageId、currentPackageSkuIds、recentAssistantMessages、categoryHints。新的 planner 构造 `MemoryContextRequest` 还需要 conversationId、turnNo、traceId、attachments、taskId、metadata、tokenBudget。最小可行方案是把 `AgentOrchestrator.recall(...)` 的签名扩展为接收 `RuntimeState state`、`conversationId`、`turnNo`、`packageId`、`skuIds`、`userMessage`、`attachments`，由 planner 创建完整请求。若继续保留 `MemoryRecallSignal`，它也必须包含这些字段，不能靠 null 占位。

第四步，修改 `AgentOrchestrator`。删除字段和构造参数 `List<MemoryChannelProvider> memoryChannelProviders`。`recall(...)` 返回类型改为 `MemoryContext`。在 `prepareTurn(...)` 与 `driveTurn(...)` 中，同步召回应调用新的 planner 并把 `MemoryContext` 放入 `PromptRuntimeContext`。日志字段从 `recallPlanId` 改为 `memoryContext.degraded()`、`memoryContext.sections().size()` 或 `memoryContext.recallTrace()` 中的 plan/trace 信息。`prepareTurn(...)` 返回 Map 的 `"recall"` key 可以改为 `"memoryContext"`，测试也应随之更新。

第五步，修改 Prompt section。`PreferenceSection` 不再导入 `com.pixflow.agent.memory.MemorySection`。它从 `ctx.memoryContext().section("user_preferences")` 读取 module-memory 的 `MemorySection`，如果 section 为空或 `renderedText` 为空就返回空 PromptSection；否则 body 直接使用 `renderedText`，fingerprint 用 section name、tokenEstimate、items id/score 或 trace hash 计算。`LongTermMemorySection` 从 `sku_history` 和 `analysis_insights` 两个 section 渲染，显示标题分别为 `SKU 处理历史` 和 `分析结论`，body 优先使用 module section 的 `renderedText`。不要再引用 `insights.vector`、`insights.fulltext`。

第六步，删除旧代码。删除 `pixflow-agent/src/main/java/com/pixflow/agent/memory/MemoryChannelProvider.java`、`MemoryItem.java`、`MemorySection.java`、`RrfMerger.java`、`TokenEstimator.java`、`PreferenceRecallProvider.java`、`SkuHistoryRecallProvider.java`、`InsightRecallProvider.java`、`InsightFulltextRecallProvider.java`。删除对应测试 `pixflow-agent/src/test/java/com/pixflow/agent/memory/RrfMergerTest.java`。保留 `RecallChannel` 仅在仍有合法用途时保留；如果删除旧 provider 后没有引用，也删除它。

第七步，更新测试。`AgentOrchestratorTest` 不再 mock `MemoryRecallResult` 或 `MemoryChannelProvider`，改为 mock `MemoryRecallPlanner` 返回 `MemoryContext`，或直接 mock `MemoryService` 并测试 planner。新增或修改测试覆盖以下行为：`recall(...)` 会同步调用 `MemoryService.prepareContext(...)`；传入的 `MemoryContextRequest` 包含 conversationId、turnNo、traceId、userPrompt 和 skuIds；`prepareTurn(...)` 会先召回、再加载 Session Memory、再调用 assembler；`PreferenceSection` 能渲染 `user_preferences.renderedText`；`LongTermMemorySection` 能渲染 `sku_history` 与 `analysis_insights`。

第八步，更新架构测试。如果 `AgentArchitectureTest` 当前没有覆盖这条边界，需要新增断言：`com.pixflow.agent..` 不应依赖 `com.pixflow.module.memory.preference..`、`com.pixflow.module.memory.skuhistory..`、`com.pixflow.module.memory.insight..` 的实现细节包；允许依赖 `com.pixflow.module.memory.MemoryService` 和 `com.pixflow.module.memory.context..`。这样可以防止未来再次从 agent 直连 mapper 或内部 service。

## Concrete Steps

在仓库根目录 `D:\study\PixFlow` 执行以下步骤。

首先确认旧引用范围：

    rg "MemoryChannelProvider|PreferenceRecallProvider|SkuHistoryRecallProvider|InsightRecallProvider|InsightFulltextRecallProvider|RrfMerger|com.pixflow.agent.memory.MemoryItem|com.pixflow.agent.memory.MemorySection" pixflow-agent/src

预期能看到旧 provider、旧 RRF 测试、`AgentOrchestrator` 构造参数和两个 Prompt section 的引用。

然后修改 `SectionRenderer`、`MemoryRecallPlanner`、`AgentOrchestrator`、`PreferenceSection`、`LongTermMemorySection` 和测试。编辑完成后删除旧文件。

删除旧文件后再次确认没有旧引用：

    rg "MemoryChannelProvider|PreferenceRecallProvider|SkuHistoryRecallProvider|InsightRecallProvider|InsightFulltextRecallProvider|RrfMerger|com.pixflow.agent.memory.MemoryItem|com.pixflow.agent.memory.MemorySection" pixflow-agent/src

预期输出为空。如果仍有输出，说明旧路径没有完全删除。

确认 agent 只依赖 module-memory 的门面和 context：

    rg "com.pixflow.module.memory" pixflow-agent/src/main/java

允许出现：

    import com.pixflow.module.memory.MemoryService;
    import com.pixflow.module.memory.context.MemoryContext;
    import com.pixflow.module.memory.context.MemoryContextRequest;
    import com.pixflow.module.memory.context.MemorySection;

不允许出现：

    import com.pixflow.module.memory.preference.PreferenceService;
    import com.pixflow.module.memory.skuhistory.SkuHistoryService;
    import com.pixflow.module.memory.insight.InsightDocMapper;
    import com.pixflow.module.memory.insight.InsightRecallService;

最后运行测试：

    mvn -pl pixflow-agent -am test

成功时应看到 Maven `BUILD SUCCESS`。如果失败，应先处理编译错误，再处理单测断言。不得通过重新引入旧 provider 或旧 `MemoryItem` 来修复失败。

## Validation and Acceptance

验收标准分为编译、行为和架构三层。

编译验收：运行 `mvn -pl pixflow-agent -am test` 必须 `BUILD SUCCESS`。当前报错中的 `PreferenceRecallProvider`、`SkuHistoryRecallProvider`、`InsightFulltextRecallProvider` 不应再出现，因为这些类已删除。

行为验收：`MemoryRecallPlanner` 或重命名后的 planner 单测必须证明它调用 `MemoryService.prepareContext(...)`，并传入包含当前会话信息的 `MemoryContextRequest`。`PreferenceSection` 单测必须证明 `user_preferences.renderedText` 被注入 `instruction_memory`。`LongTermMemorySection` 单测必须证明 `sku_history` 与 `analysis_insights` 被注入 `long_term_memory`，且空 section 会被跳过。

架构验收：`rg` 检查和 ArchUnit 测试必须证明 agent 不依赖 module-memory 的内部实现包。允许依赖 `MemoryService` 和 `context` DTO，因为这是正式门面契约。

设计验收：Prompt 注入不再区分 `insights.vector` 和 `insights.fulltext`。向量和 FULLTEXT 是 memory 模块内部的混合召回实现细节，最终只以 `analysis_insights` 的形式进入 agent prompt。

## Idempotence and Recovery

本重构是源代码级删除和替换，重复执行检查命令是安全的。删除旧文件前应先通过 `rg` 确认引用点，并在删除后再次运行 `rg`。如果中途测试失败，不要恢复旧 provider；应沿着新边界修复调用签名、测试 mock 或 Prompt section 渲染逻辑。

如果需要回看旧实现，使用 Git 历史或当前工作树 diff；不要把旧 provider 复制回主路径。若发现 `module-memory` 的 `MemoryContextBuilder` 功能不足，应在 `pixflow-module-memory` 内补足正式门面能力，而不是在 `pixflow-agent` 内加回绕过门面的 mapper/service 访问。

如果 `MemoryService` bean 在某些测试 Spring 上下文中缺失，应修正测试配置或自动配置导入，让 `MemoryAutoConfiguration` 参与装配。不要给 agent 增加“没有 MemoryService 就空召回”的兼容分支，因为本计划要求实现设计应有功能，不保留旧安全路径。

## Artifacts and Notes

当前错误的关键证据：

    com.pixflow.agent.memory.InsightFulltextRecallProvider 不是抽象的, 并且未覆盖 MemoryChannelProvider.recall(MemoryRecallSignal)
    返回类型 java.util.Optional<java.util.List<com.pixflow.module.memory.recall.MemoryItem>>
    与 java.util.Optional<java.util.List<com.pixflow.agent.memory.MemoryItem>> 不兼容

旧边界示例：

    MemoryChannelProvider.recall(...) -> Optional<List<com.pixflow.agent.memory.MemoryItem>>
    PreferenceRecallProvider -> PreferenceService.recallPreferences(...)
    SkuHistoryRecallProvider -> SkuHistoryService.recallBySkuIds(...)
    InsightFulltextRecallProvider -> InsightDocMapper.fulltextSearch(...)
    RrfMerger.merge(...)

新边界示例：

    AgentOrchestrator
      -> MemoryRecallPlanner
      -> MemoryService.prepareContext(MemoryContextRequest)
      -> MemoryContext
      -> PreferenceSection / LongTermMemorySection

`MemoryContextBuilder` 当前 section 名：

    user_preferences
    sku_history
    analysis_insights

这些名字应成为 agent prompt 注入的唯一记忆 section 来源。

## Interfaces and Dependencies

重构后 `pixflow-agent` 应依赖以下 module-memory 接口：

    package com.pixflow.module.memory;

    public interface MemoryService {
        MemoryContext prepareContext(MemoryContextRequest request);
        void ingestAsync(MemoryIngestRequest request);
        void reinforce(MemoryReinforcementEvent event);
    }

重构后 `MemoryRecallPlanner` 的建议形态：

    package com.pixflow.agent.memory;

    public final class MemoryRecallPlanner {
        private final MemoryService memoryService;

        public MemoryContext plan(MemoryRecallSignal signal) {
            MemoryContextRequest request = toRequest(signal);
            return memoryService.prepareContext(request);
        }
    }

如果改名为 `MemoryContextPlanner`，则 `AgentOrchestrator` 字段和测试也同步改名。无论是否改名，该类都不得再接收 provider list 或 RRF merger。

重构后 `SectionRenderer.PromptRuntimeContext` 的建议字段：

    record PromptRuntimeContext(
            RuntimeState state,
            String conversationId,
            Integer turnNo,
            String packageId,
            List<String> attachedSkuIds,
            List<String> mentionedSkuIds,
            List<String> recentAssistantMessageIds,
            String userMessage,
            PlanModeState planMode,
            MemoryContext memoryContext,
            SessionMemoryContent sessionMemory,
            List<ToolDescriptor> visibleTools
    ) {}

重构后不应存在以下 agent 类型：

    com.pixflow.agent.memory.MemoryChannelProvider
    com.pixflow.agent.memory.MemoryItem
    com.pixflow.agent.memory.MemorySection
    com.pixflow.agent.memory.RrfMerger
    com.pixflow.agent.memory.TokenEstimator
    com.pixflow.agent.memory.PreferenceRecallProvider
    com.pixflow.agent.memory.SkuHistoryRecallProvider
    com.pixflow.agent.memory.InsightRecallProvider
    com.pixflow.agent.memory.InsightFulltextRecallProvider

2026-07-08 / Codex: 新建本 ExecPlan。原因是当前 `pixflow-agent` 编译错误暴露出 agent 旧召回体系与 module-memory 正式召回体系重复的问题。用户明确要求不要为了兼容性保留旧代码，因此本计划选择删除旧 provider/SPI/RRF 路径，并把 Prompt 组装前的记忆召回收敛到 `MemoryService.prepareContext(...)`。

2026-07-08 / Codex: 完成本 ExecPlan 的代码实施与验证。原因是用户要求执行计划、编写代码并在完成后更新文档。本次更新记录已删除旧 agent 召回通道体系、Prompt 注入改为消费 `MemoryContext`、ArchUnit 新增 module-memory 内部包边界约束，以及 `mvn -pl pixflow-agent -am test` BUILD SUCCESS 的验证结果。
