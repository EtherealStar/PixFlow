# 完整实现 module/memory 自动记忆模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 memory 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。

## Purpose / Big Picture

完成本计划后，PixFlow 将具备生产级自动记忆能力：每次 Agent 组装 Prompt 前，系统会自动召回用户偏好、当前 SKU 的处理历史、相关分析结论，并把这些记忆作为 memory section 注入 Prompt。模型不再拥有 `recall_memory` 工具，也不需要自己判断该召回什么类型的记忆。每轮结束后，系统通过生命周期 Hook 触发后台记忆抽取和巩固；分析结论记忆会随着时间和使用情况衰减、强化、抑制或过期，Qdrant 只保存 active 记忆索引，MySQL 仍是事实源。

这个能力可以通过测试和一个小型服务场景观察：构造一批用户偏好、SKU 历史和中文分析结论，调用 `MemoryService.prepareContext(...)`，应返回分区清晰、自动规划、按衰减排序后的 `MemoryContext`；再触发一次模拟 `TURN_STOPPED` 接线，后台调用 `ingestAsync(...)` 抽取新结论，MySQL 出现 `analysis_insight` 行，Qdrant 出现对应 active point。最后运行生命周期维护任务，低分或过期记忆转为 `SUPPRESSED` / `EXPIRED`，Qdrant point 被删除。

## Progress

- [x] (2026-06-28 23:55+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md` 和 `docs/design-docs/module/memory.md`，确认 memory 是 Wave 2 模块，依赖 `pixflow-infra-ai`、`pixflow-infra-vector`、`pixflow-common` 和 MySQL，不依赖 hooks/agent。
- [x] (2026-06-28 23:58+08:00) 与用户确认 memory 设计方向：删除 `recall_memory` Agent 工具，改为系统自动召回；Hook 只触发、不承载业务实现；分析结论记忆必须支持衰减和遗忘。
- [x] (2026-06-29 00:05+08:00) 更新 `docs/design-docs/module/memory.md`，固化自动召回、Prompt 注入、`ACTIVE` / `SUPPRESSED` / `EXPIRED` 生命周期和 `TURN_STOPPED` 异步巩固触发点。
- [x] (2026-06-29 00:15+08:00) 创建本执行计划，明确实现架构、机制、文件改动顺序、验证命令和设计文档快速定位关键词。
- [x] (2026-06-29 00:25+08:00) 同步删除其它设计文档中把 memory 描述为 `recall_memory` Agent 工具的旧表述，包括 `design.md`、`module-dependency-dag-plan.md`、`context.md`、`eval.md`、`state.md`、`permission.md` 和 `commerce.md`。
- [x] (2026-06-28 18:57+08:00) 创建 `pixflow-module-memory` Maven 模块并加入根 `pom.xml` 的 `<modules>` 与 dependencyManagement；新增 Spring Boot AutoConfiguration imports。
- [x] (2026-06-28 18:57+08:00) 实现 memory 核心 DTO、配置、自动召回规划、Prompt memory context 构建、RRF 融合、衰减排序和纯单元测试。
- [ ] 实现 MySQL 实体、Mapper、schema 测试资源和用户偏好 / SKU 历史服务。（已完成：实体、Mapper、MyBatis 服务、`schema.sql` 和 Noop 兜底；剩余：真实 MySQL mapper/service 集成测试。）
- [x] (2026-06-28 19:14+08:00) 实现分析结论混合召回、RRF 融合、衰减排序、active-only 过滤和降级策略；fake 单测覆盖向量 + 关键词融合、向量失败退化关键词、关键词失败退化向量。
- [ ] 实现异步抽取巩固、MD5 去重强化、冲突抑制、MySQL → Qdrant 双写和补偿标记。（已完成：LLM JSON 抽取、MD5 去重强化、新结论 MySQL insert 后 embedding + Qdrant upsert、冲突 id 抑制入口；剩余：向量写失败补偿标记和更完整冲突检测。）
- [ ] 实现生命周期维护服务、Qdrant active 同步和重建入口。（已完成：半衰期衰减、reinforce、SUPPRESSED/EXPIRED 状态转换、非 active 删除 Qdrant point、定时维护入口、MySQL ACTIVE 行重建 upsert 入口；剩余：孤儿 point 清理和 active payload 周期同步补偿。）
- [ ] 在接线测试模块或 memory 测试替身中验证 `TURN_STOPPED` Hook 只触发 `ingestAsync`，不在 hooks core 写业务逻辑。
- [x] (2026-06-28 19:30+08:00) 运行 `mvn -pl pixflow-module-memory -am test`，memory 模块 12 个单测通过；运行 `mvn -pl pixflow-app -am test`，app reactor 通过，说明 memory 加入 app 依赖后不破坏装配。

## Surprises & Discoveries

- Observation: `module-dependency-dag-plan.md` 曾把 memory 建模为 `recall_memory→memory` Agent 工具，这与最新 memory 设计冲突；该旧表述已同步删除。
  Evidence: 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `自动记忆` 可以看到 memory 现在被描述为 Prompt 组装前自动召回与注入；全局搜索 `recall_memory` 只剩 `module/memory.md` 和本计划中“删除该工具”的决策记录。

- Observation: 仓库当前没有 `pixflow-module-memory` 目录，根 `pom.xml` 的模块列表也未包含该模块。
  Evidence: 仓库根目录列出了 `pixflow-module-file`、`pixflow-infra-ai`、`pixflow-infra-vector` 等模块，但没有 `pixflow-module-memory`；根 `pom.xml` 的 `<module>` 列表中也没有 memory 模块。

- Observation: `pixflow-infra-ai` 已有 `EmbeddingClient`、`ChatModelClient` 和 `RerankClient` 抽象；`pixflow-infra-vector` 已有 `VectorStore`、`VectorPoint`、`ScoredPoint`、`VectorFilter`；memory 不需要先改 infra 抽象即可实现。
  Evidence: 在 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/embedding/EmbeddingClient.java`、`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/ChatModelClient.java` 和 `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/VectorStore.java` 中可以看到这些接口。

- Observation: 附件文件名 `SKU999_main.png` 在首轮测试中未被提取为 SKU，因为初始正则使用右侧单词边界，Java 正则把下划线视为单词字符。
  Evidence: `mvn -pl pixflow-module-memory -am test` 首次运行失败于 `RecallPlannerTest.extractsSkuFromAttachmentAndReducesInsightTopNWhenBudgetIsTight`；将 SKU 正则改为负向前瞻起始匹配后，第二次运行通过。

- Observation: MyBatis-Plus 当前 `BaseMapper` 同时存在单条 `insert(T)` 和批量 `insert(Collection<T>)` 重载，Mockito 测试里裸 `insert(any())` 会出现编译期歧义。
  Evidence: `mvn -pl pixflow-module-memory -am test` 在 `InsightIngestServiceTest` 编译阶段报 “对insert的引用不明确”；将 matcher 改成 `any(AnalysisInsight.class)` 后测试通过。

- Observation: 当前 `VectorStore` 抽象支持 `ensureCollection`、`upsert`、`search`、`get`、`delete`、`deleteByFilter`，但没有列出 collection 全量 point id 的接口。
  Evidence: `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/VectorStore.java` 没有 scan/list 方法。因此 `InsightIndexRebuildService` 先实现从 MySQL ACTIVE 行 re-embed + upsert，孤儿 point 清理需要后续扩展 infra/vector 或通过运维侧 collection 重建完成。

## Decision Log

- Decision: 不实现 `recall_memory` Agent 工具，也不保留工具路由或兼容层。
  Rationale: 记忆召回应由系统自动规划。让模型决定“该不该召回记忆、召回哪类记忆”会把系统级上下文责任下放给非确定性模型，导致漏召回和不可解释。当前尚未写代码，没有兼容旧工具设计的必要。
  Date/Author: 2026-06-29 / Codex

- Decision: memory 模块只暴露 `prepareContext(...)`、`ingestAsync(...)` 和 `reinforce(...)` 这类服务入口，不依赖 `harness/hooks` 或 `agent`。
  Rationale: `module/memory` 是 Wave 2 基础数据模块，只依赖 infra 和 common。Hook 触发与 Prompt 注入属于更高层接线，反向依赖会破坏依赖 DAG。
  Date/Author: 2026-06-29 / Codex

- Decision: `TURN_STOPPED` 是异步记忆抽取的推荐触发点，`ASSISTANT_MESSAGE_COMPLETED` 只作为观察事件，不作为主抽取点。
  Rationale: `AssistantMessageCompleted` 可能出现在中间轮，后面仍有 tool call 和续轮；`TURN_STOPPED` 表示本轮 Agent 已自然结束，更符合“彻底完成消息后抽取记忆”的语义。
  Date/Author: 2026-06-29 / Codex

- Decision: 分析结论记忆不采用无限 ADD-only 堆积，而是新增为主，同时支持去重强化、冲突抑制、衰减和过期。
  Rationale: 生产级记忆必须随着时间遗忘，否则旧结论会长期污染召回。MySQL 保留审计，Qdrant 只保存 active 索引，既可遗忘又可重建。
  Date/Author: 2026-06-29 / Codex

- Decision: `InsightIndexRebuildService` 第一版只负责从 MySQL ACTIVE 行重建 active vector point，不在 memory 模块内清理孤儿 point。
  Rationale: 当前 `VectorStore` 没有列出 Qdrant collection 全量 point id 的抽象。为了不让 memory 直接依赖 Qdrant client，先保持依赖边界；孤儿清理留给后续 infra/vector 扩展或运维重建流程。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

当前已完成 memory 模块主要在线路径的第一版实现。仓库新增 `pixflow-module-memory`，并接入根 Maven reactor 和 `pixflow-app` 依赖。模块当前具备可注入的 `MemoryService`、`MemoryContextBuilder`、确定性召回信号提取、召回规划、RRF 融合、最终排序、偏好/SKU/analysis_insight 实体和 Mapper、MyBatis 结构化服务、MySQL schema、向量 + 关键词混合 insight 召回、LLM JSON 抽取、异步巩固入口、MD5 去重强化、MySQL → Qdrant active upsert、生命周期维护、ACTIVE 行索引重建入口和 Noop 兜底服务。`mvn -pl pixflow-module-memory -am test` 已通过，memory 模块 12 个单测通过；`mvn -pl pixflow-app -am test` 也通过。剩余主要工作是真实 MySQL FULLTEXT 集成测试、Qdrant + MySQL 端到端集成测试、向量写失败补偿标记、孤儿 point 清理能力和 Hook 接线测试。

## Context and Orientation

仓库是 Maven 多模块 Spring Boot 项目，根目录是 `D:\study\PixFlow`。当前已有基础模块 `pixflow-common`、`pixflow-infra-ai`、`pixflow-infra-vector`、`pixflow-hooks`、`pixflow-eval` 等，但还没有 `pixflow-module-memory`。memory 是 Wave 2 业务数据模块，与 file、commerce 并列，先于 Agent 编排层实现。

本计划里的“自动召回”是指系统在 Prompt 组装前调用 memory 服务，把相关记忆查出来并注入 Prompt，而不是让模型调用工具。“Prompt” 是发给大模型的上下文文本。“Hook” 是生命周期事件总线，比如 `TURN_STOPPED` 表示一轮 Agent 自然结束；Hook 只能触发 memory 服务，不能把抽取、写库等业务逻辑写在 hooks core 里。“Qdrant active 索引”是指 Qdrant 只保存可召回的 `ACTIVE` 分析结论向量；被抑制或过期的记忆仍留在 MySQL 审计，但从 Qdrant 删除。“RRF” 是 Reciprocal Rank Fusion，一种按排名融合两路召回结果的方法，公式是 `score(d)=Σ 1/(k + rank_i(d))`。

实现时需要重点参考以下设计文档。为了快速定位，每个文档后面列出建议搜索关键词。

在 `docs/design-docs/module/memory.md` 中搜索：

    记忆召回是系统责任
    MemoryService.prepareContext
    RecallPlanner
    MemoryContext
    analysis_insight.status
    ACTIVE
    SUPPRESSED
    EXPIRED
    InsightLifecycleService
    TURN_STOPPED
    自动注入 Prompt
    衰减排序
    active 索引同步

在 `docs/design-docs/infra/ai.md` 中搜索：

    EmbeddingClient
    ChatModelClient
    RerankClient
    ModelRole
    嵌入与重排边界
    toolChoice
    infra/ai 不依赖

在 `docs/design-docs/infra/vector.md` 中搜索：

    VectorStore
    ensureCollection
    upsert
    search
    VectorFilter
    point id = MySQL 行 id
    只做稠密向量检索
    MySQL 一致性

在 `docs/design-docs/harness/hooks.md` 中搜索：

    TURN_STOPPED
    ASSISTANT_MESSAGE_COMPLETED
    异步副作用由回调自理
    Hook 只触发
    首批业务 hook 的归属

在 `docs/design-docs/harness/eval.md` 中搜索：

    recall_json
    agent_trace
    TraceRecorder
    回放

在 `docs/design-docs/base/common.md` 中搜索：

    ErrorCategory
    RecoveryHint
    Sanitizer
    DEPENDENCY
    错误与降级

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：

    Wave 2
    module/memory
    自动记忆
    Prompt 注入

注意：`module-dependency-dag-plan.md` 已同步为自动记忆表述，不再把 memory 作为 Agent 工具。实现时以 `module/memory.md` 和本计划为准。

## Plan of Work

先创建 Maven 模块 `pixflow-module-memory`。在根 `pom.xml` 的 `<modules>` 中加入 `pixflow-module-memory`，并在 dependencyManagement 中加入该模块的版本管理。新模块 `pixflow-module-memory/pom.xml` 依赖 `pixflow-common`、`pixflow-infra-ai`、`pixflow-infra-vector`、MyBatis-Plus Spring Boot 3 starter、Spring Boot validation、Spring Boot autoconfigure、Micrometer 测试需要的依赖，以及 JUnit/Mockito/AssertJ。源码根包使用 `com.pixflow.module.memory`。如果项目已有统一 auto-configuration 习惯，创建 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 指向 `com.pixflow.module.memory.config.MemoryAutoConfiguration`。

然后实现纯 DTO 和配置。创建 `MemoryService` 接口或类，暴露 `MemoryContext prepareContext(MemoryContextRequest request)`、`void ingestAsync(MemoryIngestRequest request)`、`void reinforce(MemoryReinforcementEvent event)`。创建 `MemoryProperties`，字段覆盖 `pixflow.memory.prompt`、`pixflow.memory.insight.recall`、`pixflow.memory.insight.rank`、`pixflow.memory.insight.lifecycle`、`pixflow.memory.ingest.pool`。这一步不连接数据库，只要配置绑定和 DTO 构造可测试。

接着实现自动召回规划。`RecallSignalExtractor` 从 `MemoryContextRequest` 的用户输入、附件元数据、package/task context 中提取 SKU、类目词、处理意图和指标词。第一版用确定性规则，不调用 LLM。`RecallPlanner` 每轮必启用偏好召回；检测到 SKU 时加入 SKU 历史召回；检测到类目、处理目标或指标词时生成 insight query 和 filters。用纯单测覆盖“有 SKU、有类目、无 SKU 但有处理意图、token 预算紧张”等场景。

然后实现 `RrfFuser` 和 `MemoryRanker`。`RrfFuser` 只关心 ranked list，不关心来源。`MemoryRanker` 把 RRF 分、confidence、importance、decay_score、recency、reinforcement boost 组合成最终排序。排序分数写入 `MemoryItem`，但 Prompt 注入时只使用简洁来源摘要，不把内部权重喂给模型。

再实现 MySQL 模型和服务。创建 `UserPreferenceEntity`、`SkuHistoryEntity`、`AnalysisInsightEntity`，以及 `UserPreferenceMapper`、`SkuHistoryMapper`、`InsightDocMapper`。测试资源中提供 schema SQL，包含 `analysis_insight.text` FULLTEXT 索引、`content_hash` 唯一索引或普通索引、`status`、`expires_at`、`related_sku`、`category` 的查询索引。`PreferenceService` 全量读取偏好，`SkuHistoryService` 按 SKU 列表读取近期历史，`InsightKeywordSearch` 用 `InsightDocMapper` 做 FULLTEXT 关键词召回。

实现向量仓库封装。`InsightVectorRepo` 依赖 `VectorStore`，负责集合名、payload 字段、active point upsert/delete/search。启动时调用 `ensureCollection(collection, dim, COSINE)`。如果当前 `EmbeddingClient` 没有直接暴露维度，第一版可在首次 embed 样本文本后确定维度，或者由 `MemoryProperties` 提供可选覆盖；该决定要在实现时记录到本计划的 Decision Log。

实现 `InsightRecallService`。它接收 planner 生成的 insight query 和 filters，先用 `EmbeddingClient.embed(List.of(query))` 生成 query vector，再并行或顺序执行向量召回和 FULLTEXT 召回。第一版可以顺序执行，接口保留并行优化空间。任一路失败时按 memory 设计降级。两路结果进入 `RrfFuser`，再进入 `MemoryRanker`，最终返回 active、未过期、分数达标的 topN。

实现 `MemoryContextBuilder`。它调用 `RecallPlanner`、`PreferenceService`、`SkuHistoryService`、`InsightRecallService`，合并成 `MemoryContext`。`MemoryContext` 应包含三个 section：`user_preferences`、`sku_history`、`analysis_insights`。每个 section 带可注入文本、原始 item、召回计划、候选统计、降级信息和 trace payload。token 裁剪先用配置项估算字符数或简单 token 预算，后续 Agent 层接入真实 context token 管理。

实现异步抽取巩固。`InsightExtractor` 通过 `ChatModelClient.call(...)` 做单次 LLM 抽取，不接完整 Agent runtime。抽取请求必须包含用户 prompt、最终回答、自动召回过的记忆、工具观察、电商证据、相关 SKU/类目、traceId。第一版测试用 fake `ChatModelClient` 返回固定 JSON，再解析成 `ExtractedInsight`。`InsightIngestService` 取近邻 active 结论作为去重/冲突上下文，调用 extractor，规范化文本，计算 MD5，命中 hash 时强化已有记忆，不新增；新记忆先写 MySQL，再 embed，再 upsert Qdrant。若新结论与旧近邻冲突，第一版用 extractor 输出的 `conflictsWith` id 或规则字段把旧结论转 `SUPPRESSED`，并删除 Qdrant point。

实现生命周期服务。`InsightLifecycleService` 定时计算 decay_score。建议第一版采用半衰期公式：距离 `last_reinforced_at` 或 `created_at` 越久，分数按 `0.5^(ageDays / halfLifeDays)` 下降，再乘以 confidence 和 importance 修正。低于 suppress 阈值的 active 记忆转 `SUPPRESSED`，低于 expire 阈值或超过 `expires_at` 的转 `EXPIRED`，并调用 `InsightVectorRepo.delete(id)`。`reinforce(...)` 更新 `last_reinforced_at`、`access_count`、confidence/importance 上限和 decay_score。

最后做接线验证，但不把业务写进 hooks core。可以在 `pixflow-module-memory` 测试中创建一个轻量 `MemoryConsolidationHook` 测试替身，模拟 `TURN_STOPPED` payload，断言它只调用 `MemoryService.ingestAsync(...)` 并立即返回 `HookResult.noop()`。真正生产接线应放在 Agent 或 app 组装层；如果本阶段尚无 `pixflow-agent` 模块，不要为了测试而创建完整 Agent 模块。

## Concrete Steps

所有命令默认在仓库根目录 `D:\study\PixFlow` 执行。

第一步查看工作树，避免覆盖用户改动：

    git status --short

预期：如果有与 `pixflow-module-memory`、根 `pom.xml` 或 `docs/design-docs/module/memory.md` 相关的未提交改动，先阅读差异并与其共存；不要回滚用户改动。

第二步创建 memory 模块骨架。新增目录：

    pixflow-module-memory/pom.xml
    pixflow-module-memory/src/main/java/com/pixflow/module/memory/
    pixflow-module-memory/src/test/java/com/pixflow/module/memory/
    pixflow-module-memory/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

同时修改根 `pom.xml`，在 `<modules>` 中加入：

    <module>pixflow-module-memory</module>

如果根 `pom.xml` 有 dependencyManagement 模块清单，也加入 `com.pixflow:pixflow-module-memory:${project.version}`。

第三步先让空模块编译：

    mvn -pl pixflow-module-memory -am test

预期：Maven 能识别模块，测试阶段成功。早期可以只有一个 context smoke test。

第四步实现 DTO、配置和自动召回规划纯逻辑，然后运行：

    mvn -pl pixflow-module-memory -am test

预期：`MemoryProperties` 绑定测试、`RecallSignalExtractorTest`、`RecallPlannerTest`、`RrfFuserTest`、`MemoryRankerTest` 通过。这一步不需要 Docker。

第五步实现 MySQL 实体、Mapper 和结构化服务。若项目没有统一迁移工具，先在 `pixflow-module-memory/src/test/resources/schema-memory.sql` 提供测试 schema。运行：

    mvn -pl pixflow-module-memory -am test

预期：用户偏好全量读取、SKU 历史按 SKU 读取、analysis_insight 的 hash 查询和 active 查询通过。若使用 MySQL FULLTEXT 集成测试，需要 Docker；无 Docker 时这些测试按条件跳过，纯 mapper 测试用 fake repository 保底。

第六步实现向量封装和混合召回。运行普通单测：

    mvn -pl pixflow-module-memory -am test

预期：fake `EmbeddingClient`、fake `VectorStore`、fake `InsightKeywordSearch` 下能验证向量失败降级、关键词失败降级、两路 RRF 融合、active-only 过滤、token topN 裁剪。

第七步在 Docker 可用时运行 MySQL + Qdrant 集成测试。Windows 本地如果 Testcontainers 需要指定 Docker 入口，优先使用仓库父 POM 已提供的 Windows Docker profile；如果已有 profile 名称是 `windows-docker-npipe` 或 `windows-docker-tcp`，使用其中可用的一种：

    mvn -pl pixflow-module-memory -am test -Pwindows-docker-npipe

预期：真实 MySQL FULLTEXT 能召回中文词面样本，真实 Qdrant 能按向量和 filter 返回 active point。若 Docker 不可用，测试应显示集成测试被跳过，普通单测仍通过。

第八步实现异步抽取、巩固和生命周期维护。运行：

    mvn -pl pixflow-module-memory -am test

预期：fake LLM 返回的原子结论会写入 MySQL fake/测试库，随后 upsert Qdrant fake；重复 hash 不新增而强化；冲突会把旧记忆转 `SUPPRESSED` 并调用 vector delete；过期维护会把低分记忆转 `EXPIRED`。

第九步做 app 或接线层装配测试。如果当前没有 Agent 模块，只在 memory 模块中提供测试替身，证明 hook 只触发。若 app 已能装配 memory，则运行：

    mvn -pl pixflow-app -am test

预期：Spring context 可启动，`MemoryService` 可注入，Agent 可见工具集合中没有 `recall_memory`。如果 Agent/tool registry 尚未实现，则把这项记录为后续 Wave 5 接线验收，不阻塞 memory 模块自身完成。

第十步运行相关模块测试：

    mvn -pl pixflow-module-memory,pixflow-infra-ai,pixflow-infra-vector,pixflow-hooks,pixflow-eval -am test

预期：memory 相关单测全部通过，已有 infra/hook/eval 测试不因新增模块退化。

## Validation and Acceptance

验收必须证明可观察行为，而不只是代码编译。

自动召回验收：在单元测试或集成测试中预置用户偏好 `background=white`，SKU `SKU123` 的两条处理历史，以及三条 `analysis_insight`。调用 `MemoryService.prepareContext(...)`，请求里包含用户 prompt “帮我处理 SKU123 的连衣裙主图，优先提升点击率”。结果应包含三个 section：偏好 section 有白底偏好，SKU 历史 section 只包含 `SKU123`，analysis section 包含与“连衣裙/主图/点击率”相关的 active 结论。测试应断言没有任何 `recall_memory` 工具调用参与。

混合召回验收：准备一条只靠关键词命中的中文结论和一条只靠向量 fake 命中的结论。调用 insight recall 后，两个候选都进入 RRF 融合结果，并按配置 topN 输出。向量 fake 抛异常时，结果退化为 FULLTEXT；关键词 fake 抛异常时，结果退化为向量。

衰减遗忘验收：创建一条 `ACTIVE` 但 `last_reinforced_at` 很久以前、confidence 低的记忆。运行 `InsightLifecycleService.maintain()` 后，该行应转为 `SUPPRESSED` 或 `EXPIRED`，并且 fake `InsightVectorRepo.delete(id)` 被调用。再次 `prepareContext(...)` 不应返回该记忆。

异步巩固验收：构造 `MemoryIngestRequest`，fake `ChatModelClient` 返回一条 JSON 结论。调用 `ingestAsync(...)` 后等待测试 executor 完成，应看到 MySQL 插入 `analysis_insight(status=ACTIVE)`，fake `EmbeddingClient.embed(...)` 被调用，fake `VectorStore.upsert(...)` 使用同一个 id。再次提交同样结论，应强化已有行，不新增第二行。

Hook 边界验收：在测试中构造 `TURN_STOPPED` payload，调用接线层 hook。断言 hook 返回 `HookResult.noop()`，且只调用 `MemoryService.ingestAsync(...)` 一次。测试中不得在 `pixflow-hooks` core 下添加任何 memory 业务类。

无工具验收：如果 Tool Registry 已存在，测试 Agent 可见工具集合，断言不存在名为 `recall_memory` 的工具。如果 Tool Registry 尚未实现，则在本计划 Outcomes 中记录该验收迁移到 Wave 5 Agent 接线阶段。

## Idempotence and Recovery

本计划的实现应可重复运行测试。重复创建同一个 schema 时测试数据库应先清理或使用独立容器。重复调用 `ingestAsync(...)` 处理同一条规范化文本时，不应插入重复 `analysis_insight`，而应命中 `content_hash` 并强化已有行。重复运行生命周期维护任务应是幂等的：已 `SUPPRESSED` 或 `EXPIRED` 的行保持状态，重复删除 Qdrant point 不应报不可恢复错误。

向量写入失败时，MySQL 已经是事实源，服务应记录补偿状态或至少记录 warn 和指标。后续重建入口可以从 MySQL `ACTIVE` 行重新 upsert Qdrant。删除或抑制记忆时，先更新 MySQL 状态，再尝试删除 Qdrant point；如果 Qdrant 删除失败，MySQL 状态仍然表示该记忆不应召回，在线召回的 MySQL FULLTEXT 路径必须过滤非 active 状态，避免降级时召回已遗忘内容。

如果 Docker 不可用，Testcontainers 集成测试必须按条件跳过，而不是失败；普通单元测试必须仍然覆盖 planner、ranker、降级、生命周期和 fake 双写逻辑。

## Artifacts and Notes

核心调用链示意：

    Prompt 组装前
      -> MemoryService.prepareContext(request)
      -> RecallSignalExtractor 提取 SKU/类目/意图
      -> RecallPlanner 规划偏好、SKU 历史、分析结论召回
      -> PreferenceService 全量读偏好
      -> SkuHistoryService 按 SKU 读历史
      -> InsightRecallService 做向量 + FULLTEXT + RRF + 衰减排序
      -> MemoryContextBuilder 生成 user_preferences / sku_history / analysis_insights
      -> Agent Prompt 组装层注入 memory section
      -> harness/eval 写 recall_json

异步巩固链路示意：

    TURN_STOPPED
      -> MemoryConsolidationHook.handle(...)
      -> MemoryService.ingestAsync(MemoryIngestRequest)
      -> InsightIngestService 后台执行
      -> InsightExtractor 调 ChatModelClient.call(...)
      -> MD5 去重 / 强化 / 冲突 SUPPRESSED
      -> INSERT/UPDATE analysis_insight
      -> EmbeddingClient.embed(...)
      -> VectorStore.upsert(active point) 或 delete(non-active point)

生命周期链路示意：

    @Scheduled maintenance
      -> 查询 ACTIVE analysis_insight
      -> 计算 decay_score
      -> 低于 suppress-threshold 转 SUPPRESSED
      -> 低于 expire-threshold 或 expires_at 到期转 EXPIRED
      -> 删除非 ACTIVE 的 Qdrant point
      -> trace/metrics 记录维护结果

建议的 `analysis_insight.status` 稳定枚举值：

    ACTIVE
    SUPPRESSED
    EXPIRED

建议的集成测试中文样本：

    夏季连衣裙白底主图点击率高于场景图。
    低饱和蓝色背景适合家居类 SKU，提高停留时长。
    SKU123 历史上使用 800x800 白底图后加购率提升。

## Interfaces and Dependencies

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/MemoryService.java` 定义：

    package com.pixflow.module.memory;

    import com.pixflow.module.memory.context.MemoryContext;
    import com.pixflow.module.memory.context.MemoryContextRequest;
    import com.pixflow.module.memory.ingest.MemoryIngestRequest;
    import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;

    public interface MemoryService {
        MemoryContext prepareContext(MemoryContextRequest request);
        void ingestAsync(MemoryIngestRequest request);
        void reinforce(MemoryReinforcementEvent event);
    }

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemoryContextRequest.java` 定义包含以下信息的 record：conversationId、turnNo、traceId、userPrompt、attachments、packageId、taskId、skuIds、categoryHints、metadata。`skuIds` 和 `categoryHints` 可以为空，由 `RecallSignalExtractor` 补充。

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/context/MemoryContext.java` 定义包含以下信息的 record：conversationId、turnNo、List<MemorySection> sections、Map<String,Object> recallTrace、boolean degraded。`MemorySection` 至少包含 name、renderedText、items、tokenEstimate。

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/recall/RecallPlanner.java` 定义：

    public interface RecallPlanner {
        RecallPlan plan(MemoryContextRequest request, RecallSignals signals);
    }

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/recall/RrfFuser.java` 定义纯算法类，输入两个或多个 ranked list，输出按 RRF 分数排序的合并结果。该类不得依赖 Spring、MySQL、Qdrant 或 AI。

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/recall/MemoryRanker.java` 定义最终排序服务，使用 `MemoryProperties.Insight.Rank` 权重计算 final_score。

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/AnalysisInsightStatus.java` 定义：

    public enum AnalysisInsightStatus {
        ACTIVE,
        SUPPRESSED,
        EXPIRED
    }

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/InsightVectorRepo.java` 定义：

    public interface InsightVectorRepo {
        void ensureCollection(int dimension);
        void upsertActive(AnalysisInsightEntity insight, float[] vector);
        List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter);
        void delete(String insightId);
    }

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/InsightKeywordSearch.java` 定义：

    public interface InsightKeywordSearch {
        List<MemoryItem> search(String query, InsightFilter filter, int topK);
    }

在 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/InsightLifecycleService.java` 定义：

    public interface InsightLifecycleService {
        void maintain();
        void suppress(String insightId, String reason);
        void expire(String insightId, String reason);
        void reinforce(MemoryReinforcementEvent event);
    }

memory 模块必须使用现有底层接口，不得直接使用 Qdrant client、Spring AI provider 类型或 hooks registry：

    com.pixflow.infra.ai.embedding.EmbeddingClient
    com.pixflow.infra.ai.chat.ChatModelClient
    com.pixflow.infra.vector.VectorStore
    com.pixflow.infra.vector.VectorFilter
    com.pixflow.common.error.PixFlowException
    com.pixflow.common.sanitize.Sanitizer

Hook 接线层如果在本阶段需要测试，只能依赖 `MemoryService`，不得把抽取和写库逻辑放进 `pixflow-hooks`。

## Change Note

2026-06-29 / Codex: 初次创建计划。本文把最新 memory 设计整理为可执行实现规格，明确删除 `recall_memory` 工具、采用系统自动召回、Hook 只触发异步巩固，并加入衰减遗忘和 Qdrant active 索引机制。

2026-06-29 / Codex: 根据用户要求同步删除其它设计文档中的旧工具设计引用。更新范围包括总体设计、模块依赖 DAG、context/eval/state/permission/commerce 文档，并修正本计划中的发现记录与快速定位关键词。

2026-06-28 / Codex: 开始 Java 实现。新增 `pixflow-module-memory`，完成首批自动召回核心、配置、context DTO、RRF/排序、MySQL 实体/Mapper/结构化服务和单元测试；下一步继续补 schema、真实 keyword/vector 混合召回和生命周期管线。

2026-06-28 / Codex: 继续实现 memory 模块。新增 insight keyword/vector 混合召回、Qdrant repo 封装、LLM 抽取器、异步巩固服务、生命周期维护服务、定时维护接线和 schema；`mvn -pl pixflow-module-memory -am test` 通过，memory 模块 12 个单测通过。

2026-06-28 / Codex: 将 `pixflow-module-memory` 接入 `pixflow-app` 依赖，并新增 `InsightIndexRebuildService` 的 ACTIVE 行重建入口。由于 `VectorStore` 暂无全量 point 扫描接口，孤儿 point 清理延后到 infra/vector 扩展或运维重建流程。验证 `mvn -pl pixflow-app -am test` 通过。
