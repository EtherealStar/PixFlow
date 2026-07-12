# 将 Rubrics 重构为证据约束的四态 Criterion 评估深模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续执行者只依赖当前工作树和本文，就应能理解为什么必须替换现有 v1 评分模型、按顺序完成重构、运行验证并判断行为是否符合设计。实施期间每个停止点都必须更新本文；改变接口、迁移策略或里程碑顺序时，必须同时更新 `Decision Log`、`Progress` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，PixFlow 不再把一个 `process_result` 同时压成图片、文案和决策三个分数，也不再从模型 confidence 推导 0-100 质量分。运营人员或开发者将选择一个人工批准、版本化、只适用于一种 Evaluation Subject 的静态 Rubric Template，运行离线评估，并看到每个原子 Criterion 的 `PASS`、`FAIL`、`INCONCLUSIVE` 或 `NOT_APPLICABLE`、系统构建的 Evidence Pack 引用、三次独立 judge rollout、严格多数结果、Hard Rules 组成的 Quality Gate、透明的 Principle `passRate` 与 coverage。

可观察结果是：同一 Image Result 在 `image-result-quality:2.0.0` 下产生 criterion verdict matrix；任何 Hard Rule 失败得到 `qualityGate=FAILED`，无失败但存在不可判定 Hard Rule 得到 `UNKNOWN`，全部适用 Hard Rule 通过得到 `PASSED`。没有可判定 Principle 时 `passRate` 返回 JSON `null`，不是 `0`。模型编造 Evidence Pack 中不存在的 `E9` 时，该 rollout 被记为无效并贡献 `INCONCLUSIVE`；三次 rollout 没有两个一致的 `PASS` 或两个一致的 `FAIL` 时，最终结果是 `INCONCLUSIVE(JUDGE_DISAGREEMENT)`。正式回归只有在两个 run 使用同一 Evaluation Dataset 版本时才允许执行。Rubrics 结果不会自动写入 memory；只有带当前审核人和完整来源信息的 Promotion 才能创建或强化 memory insight。

本次重构采用“替换 v1 生产路径，保留 v1 历史事实”的迁移方式。`rubrics_score` 与已有行保留为只读历史，新 v2 代码不再写它；旧 `ScoreAggregator`、`DimensionScore`、`DomainScore`、`RubricScore`、`Confidence`、自动 `ScoreFeedbackWriter` 和 `MemoryFeedbackTrigger` 从生产实现删除，不提供双写、旧 API 兼容开关或 `PRIMARY_CHAT` judge 回退。

## Progress

- [x] (2026-07-12 00:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和 `docs/design-docs/exec-plans/` 下全部活动计划。
- [x] (2026-07-12 00:05+08:00) 阅读新版 Rubrics 设计、bounded-context glossary、ADR、总体设计及 AI、memory、eval、hooks、frontend API、file、vision 相邻契约。
- [x] (2026-07-12 00:15+08:00) 核对当前 `pixflow-module-rubrics` v1 源码、迁移、模板和测试，确认设计与实现差距。
- [x] (2026-07-12 00:20+08:00) 创建本 v2 重构 ExecPlan，并记录深模块接口、迁移顺序、设计关键词和验收闭环。
- [x] (2026-07-12 16:13+08:00) 完成 `infra/ai` 独立 Rubrics judge role 契约与 v2 无标量分数领域核心；删除 v1 score/feedback/run/controller 生产链，新增静态 Image Result 模板、canonical hash 注册和四态汇总。
- [ ] 实现人工批准静态模板、typed subject、系统 Evidence Pack 与 Image Result 纵向切片。
- [ ] 实现三次独立 judge rollout、严格多数、四态聚合和原子持久化。
- [ ] 重构 run、HTTP API、事件/定时自动化门，并删除 v1 score 与自动 memory 路径。
- [ ] 实现 Evaluation Dataset、gold-label 校准、同 dataset 正式回归、baseline 与 alert。
- [ ] 实现人工 Promotion，并依次扩展 Copy Result、Task Decision。
- [ ] 完成模块、相邻模块、数据库、自动装配、ArchUnit 和端到端验证，更新本文 living sections。

## Surprises & Discoveries

- Observation: 当前实现仍完整保留被新版设计否决的 v1 路径：模板按 domain/dimension/weight 组织，`ScoreAggregator` 把 `Verdict + Confidence` 映射为 0/20/40/60/80/100，再聚合出 overall/image/copy/decision score。
  Evidence: `pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/score/`、`template/RubricDomain.java`、`template/RubricDimension.java` 和 `src/test/.../ScoreAggregatorTest.java`。

- Observation: judge 当前只调用一次；图片存在时调用 vision，否则文本调用固定使用 `ModelRole.PRIMARY_CHAT`。judge 缺失被 `ItemEvaluator` 写成 `FAIL + LOW`，parser 接受模型自行构造的 evidence type/ref，均违反 v2 契约。
  Evidence: `judge/LlmJudge.java`、`judge/VerdictParser.java`、`run/ItemEvaluator.java`。

- Observation: 当前 `EvaluationRunner` 直接依赖 task 的 MyBatis mapper 和内部 domain entity，并在每个 item 后写 `rubrics_score`、`sku_history.rubrics_score`，run 后自动触发 `MemoryIngestService`。
  Evidence: `run/EvaluationRunner.java`、`feedback/ScoreFeedbackWriter.java`、`feedback/MemoryFeedbackTrigger.java`。

- Observation: 当前模板 registry 用 `(id, version)` 后写覆盖前写，不计算 hash、不检查相同版本内容冲突、不区分 subject type 和 lifecycle，也没有真正加载配置中的用户模板目录。
  Evidence: `template/TemplateRegistry.register` 直接 `Map.put`；`RubricsAutoConfiguration.templateRegistry` 只调用 `loadClasspath`。

- Observation: 新版 `infra/ai` 文档已经定义 `RUBRICS_JUDGE_TEXT`、`RUBRICS_JUDGE_VISION` 和带 role 的 `VisionRequest`，当前 Java 源码仍只有 `PRIMARY_CHAT/VISION/...` 且 `VisionRequest` 没有 role。
  Evidence: 对比 `docs/design-docs/infra/ai.md` §四、§5.2 与 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/model/ModelRole.java`、`vision/VisionRequest.java`。

- Observation: task 已通过 Spring `ApplicationEventPublisher` 发布公开 `TaskCompletedEvent`，Rubrics 可以直接订阅该事件并把工作提交到自己的 executor，不需要作为 `HookCallback` 进入在线 hook 链。
  Evidence: `pixflow-module-task/.../internal/publish/TaskEventPublisher.java` 和 `api/event/TaskCompletedEvent.java`。

- Observation: `agent_trace` 是 best-effort 且默认仅保留七天。Task Decision 的 dataset 必须在保留期内固化 Evidence Pack 所需引用和 hash；缺失 trace 只能得到 `INCONCLUSIVE(MISSING_EVIDENCE)` 或不可重放，不能退化成失败。
  Evidence: `docs/design-docs/harness/eval.md` 可搜索 `best-effort，不是事实源` 和 `7 天`。

- Observation: 计划编写时执行 `mvn -pl pixflow-module-rubrics -am test` 在 124 秒工具时限内未返回结果，因此不能把当前全依赖测试记为已通过。当前模块仅有 `ScoreAggregatorTest`、`VerdictParserTest` 和 `RegressionComparatorTest` 三个直接测试文件，且都验证 v1 语义。
  Evidence: 命令超时退出；`rg --files pixflow-module-rubrics/src/test/java` 的结果。

- Observation: 单模块测试会从本地 Maven 仓库解析旧版 `pixflow-infra-ai`，导致新 judge role 无法反序列化；使用 `-am` 的 reactor 测试可确保 Rubrics 消费当前源码契约。
  Evidence: 首次 `mvn -pl pixflow-module-rubrics ... test` 报 `RUBRICS_JUDGE_VISION` 非法枚举；reactor 重跑后进入模板自身验证并最终通过。

## Decision Log

- Decision: 对外只保留一个深模块 interface `RubricsEvaluationService`，HTTP controller、事件 listener 和 scheduler 都通过它创建或查询 run；模板校验、subject 解析、Evidence Pack、verifier 路由、投票、持久化和汇总都隐藏在 implementation 内。
  Rationale: 调用方只需要学会“以固定模板和 subject/dataset 启动 run并读取结果”，不应理解 evidence 构造、rollout 或数据库布局。删除该模块后，这些复杂性会重新散落到多个调用方，说明该模块通过 interface depth 提供了真实 leverage 和 locality。
  Date/Author: 2026-07-12 / Codex

- Decision: v2 直接替换生产执行路径，不保留 v1/v2 双写。`rubrics_score` 和旧列只读保留；旧 Java score 类型、旧 controller 路由及自动 feedback bean 删除。
  Rationale: 开发阶段没有兼容迁移需求；双写会形成两个评估真相源，并让新 API 仍可能泄漏 scalar score。
  Date/Author: 2026-07-12 / Codex

- Decision: `CriterionVerdict` 是四态领域事实；Principle 仍是一个二元 PASS/FAIL 命题，不存在 Principle 质量分、部分点数或 confidence 权重。`passRate` 只统计适用 Principle 中的 PASS/(PASS+FAIL)，分母为零时持久化和 API 都使用 `null`。
  Rationale: 可判定质量与评估可用性必须分离；把缺 evidence 或 judge 故障映射成 0 会伪造质量结论。
  Date/Author: 2026-07-12 / Codex

- Decision: coverage 分母为适用 criterion 的 `PASS + FAIL + INCONCLUSIVE`；全部 criterion 均 `NOT_APPLICABLE` 时 coverage 也为 `null`。没有适用 Hard Rule 且没有 Hard Rule FAIL/INCONCLUSIVE 时 Quality Gate 为 `PASSED`，同时报告 `applicableHardRuleCount=0`。
  Rationale: 两个零分母统计都不应伪装成 0；Quality Gate 按“所有适用 Hard Rule 均通过、NOT_APPLICABLE 排除”定义执行，并通过计数避免读者误以为实际执行了门禁。
  Date/Author: 2026-07-12 / Codex

- Decision: 生产模板只能来自部署时存在的静态 YAML；`EXPERIMENTAL` 候选可由模型离线起草，但系统不提供运行时 rubric 生成或直接发布端点。相同 `(templateId, version)` 的 canonical hash 不一致时启动失败。
  Rationale: 生产评估必须可复现、可审核；runtime model output 不能成为权威模板。静态文件经人工 review 后改变 lifecycle 是唯一发布路径。
  Date/Author: 2026-07-12 / Codex

- Decision: Evidence Pack ID、type、source ref、hash 和允许给 criterion 的子集全部由系统产生。parser 只接受 `evidenceIds`，未知 ID、禁用 evidence type、空引用或无引用 rationale 使该 rollout 变为 `INCONCLUSIVE(INVALID_EVIDENCE)`。
  Rationale: 模型只能在系统给定的事实集合中引用证据，不能通过输出 JSON 创造事实。
  Date/Author: 2026-07-12 / Codex

- Decision: 每个 LLM criterion 默认发起三次独立 provider 调用；每次调用继续由 `infra/ai` 的 `ModelRetryRunner` 独占 provider retry。Rubrics 不在 rollout 外增加 provider retry，只对三个最终 rollout 做严格多数归并。
  Rationale: provider attempt retry 和 evaluator repeatability 是不同机制；叠加 retry 所有权会放大成本并模糊 rollout agreement。
  Date/Author: 2026-07-12 / Codex

- Decision: Rubrics 文本和视觉调用分别固定使用 `RUBRICS_JUDGE_TEXT` 与 `RUBRICS_JUDGE_VISION`；缺 role 配置或 client 时 fail-closed 为 configuration/evaluator failure，不回退 `PRIMARY_CHAT` 或普通 `VISION`。
  Rationale: judge 与产出模型的逻辑职责、配置和 evaluator version 必须独立，才能识别 self-judging 并可靠回放。
  Date/Author: 2026-07-12 / Codex

- Decision: 正式 baseline 只引用一个已完成、绑定固定 dataset/template/evaluator version 的 run；任意生产 event run 只能提供趋势，不得成为正式 baseline。
  Rationale: subject mix 不同的两个生产 run 不能做有意义的 paired regression。
  Date/Author: 2026-07-12 / Codex

- Decision: Rubrics 直接订阅公开 `TaskCompletedEvent` 并立即提交自己的有界 executor，不实现 `HookCallback`。自动化策略在创建 run 前检查 template lifecycle、显式 enable 和采样/dataset 配置。
  Rationale: task 事件是观察事实；Rubrics 不应进入在线 hook dispatcher 或影响任务终态。事件 adapter 是 interface 外的薄 adapter，不承载评估实现。
  Date/Author: 2026-07-12 / Codex

- Decision: Promotion 先写 Rubrics 审计事实，再通过 memory 的显式 human-reviewed insight interface 写入目标；失败保留 `PENDING/FAILED` 状态并允许幂等重试。请求 body 不接受 reviewer 身份，审核人取自认证上下文。
  Rationale: 人工审核必须可追踪，跨模块写入不能伪装成原子事务，也不能在 memory 失败时丢失来源。
  Date/Author: 2026-07-12 / Codex

## Outcomes & Retrospective

当前仅完成设计和实施计划，尚未修改生产代码。预期完成结果是 v2 criterion evaluation 成为唯一生产路径，Image Result、Copy Result 与 Task Decision 各自拥有独立 subject/evidence 边界；旧 scalar score 只作为历史数据存在；自动化、正式回归和 memory Promotion 均由显式门控保护。

实施结束时，本节必须记录实际新增/删除的接口与表、迁移是否在真实 MySQL 上验证、各测试命令的通过数量、是否存在不可重放 dataset item、以及计划与最终代码的差异。不能只写“build success”。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-module-rubrics` 是离线评估 bounded context；领域语言以 `pixflow-module-rubrics/CONTEXT.md` 为准，标量分数决策以 `pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md` 为准。`docs/design-docs/module/rubrics.md` 是详细目标设计，`docs/design-docs/design.md` §十一是系统级摘要。

本文使用以下术语，实施代码和测试名也应使用这些词，不重新发明 `quality score`、`dimension points` 或 `result row` 等旧语言。

`Evaluation Subject` 是一次评估唯一要判断的对象，只能是 `IMAGE_RESULT`、`COPY_RESULT` 或 `TASK_DECISION` 之一。它保存稳定 identity 和 snapshot hash，不携带其它 subject type 的字段。

`Rubric Template` 是一个 subject type 的人工批准、版本化静态定义。它包含 template id、semantic version、canonical hash、lifecycle state、evaluator specification 和一组 atomic Criterion。

`Criterion` 是一个有 passing anchor、failing anchor、applicability、required evidence types、kind 和 verifier 的原子命题。`Hard Rule` 是显式要求，组成 Quality Gate；`Principle` 是隐式质量期望，但仍是二元 PASS/FAIL 命题。

`Criterion Verdict` 是 `PASS`、`FAIL`、`INCONCLUSIVE` 或 `NOT_APPLICABLE`。后两者是弃权/不可判定状态，不是低质量。

`Evidence Pack` 是系统为一个 Evaluation Subject 构建的不可变证据集合。每项有本地 ID、type、source ref、SHA-256 content hash、capture time 以及受限 excerpt/metadata。模型只能引用 pack 中已经允许给当前 criterion 的 ID。

`Evaluation Dataset` 是带 id/version 的不可变 subject manifest。正式回归在同一 dataset version 上按 subject + criterion 做 paired comparison。

`Promotion` 是人工审核动作，将 Rubrics finding 及其 template/evaluator/evidence provenance 显式写入 memory。普通 evaluation、alert 和 passRate 都不是 memory。

当前 v1 入口主要位于：

- `template/`：`RubricTemplate -> RubricDomain -> RubricDimension`，带 weight；这是待替换的旧 template model。
- `judge/`：单次 `LlmJudge`、允许 confidence/evidence object 的 `VerdictParser` 和混合所有上下文的 `JudgePromptBuilder`。
- `score/`：confidence-to-score 与 domain/overall 聚合，整包删除。
- `run/`：`EvaluationRunner` 直接读 task mapper，`ItemEvaluator` 同时评图片、文案和决策。
- `feedback/`：自动写 sku history 和自动 memory ingest，整包删除并由 `promotion/` 显式流程替代。
- `baseline/`：按 arbitrary run 和 scalar delta 对比，替换为 dataset-paired criterion regression。
- `api/`：暴露 v1 score/baseline 路由，替换为 `frontend/api.md` 中的 v2 routes。
- `persistence/` 与 `V1__create_rubrics_tables.sql`：保存 v1 run/item/score/baseline/alert。V1 文件不能改写；新增 V2 migration 演进表结构并增加 v2 facts。

目标 external seam 是 `RubricsEvaluationService`。它是 controller、task event adapter 和 scheduler 的共同 test surface。建议最终 interface 形状如下；实现时可把 ID 包装为 record，但语义不得扩张：

    public interface RubricsEvaluationService {
        RubricsRunView start(RunEvaluationCommand command);
        RubricsRunView getRun(long runId);
        EvaluationView getEvaluation(long evaluationId);
        List<EvaluationSummaryView> history(SubjectType type, String subjectId);
    }

`RunEvaluationCommand` 只接受 template identity、subject type，以及二选一的 dataset identity 或 subject IDs。调用方不能提交 evidence、verdict、template content 或 evaluator model name。

Implementation 内有三个真实变化 seam：`SubjectSnapshotResolver` 按三个 subject type 有不同 adapter；`EvidencePackBuilder` 按 subject type/evidence type 有不同 adapter；`CriterionVerifier` 有 deterministic rule 与 LLM judge 两个 adapter。`MajorityVerdictReducer`、`EvaluationSummaryCalculator`、`TemplateValidator` 是纯函数/具体类，不为只有一个实现的算法制造 interface。MyBatis repository 是持久化 adapter，其 in-memory test adapter 执行同一 contract tests。

### 设计文档快速定位关键词

从仓库根目录使用 `rg -n "<关键词>" <文件>`。关键词应尽量使用下列原文，以避免被旧 v1 术语带偏。

`docs/design-docs/module/rubrics.md`：

- 总体不变量：搜 `No scalar quality score`、`Evidence is system-owned`、`Evaluation does not automatically become memory`。
- 模板与原子 criterion：搜 `Template lifecycle`、`Atomic criterion`、`one atomic Criterion`、`templateHash`。
- 四态 verdict：搜 `Criterion Verdict`、`Parser errors`、`INCONCLUSIVE`。
- 汇总语义：搜 `Quality Gate, pass rate, and coverage`、`passRate`、`null, never zero`。
- evidence：搜 `Evidence Pack`、`unknown IDs`、`evidencePackHash`。
- judge：搜 `RUBRICS_JUDGE_TEXT`、`RUBRICS_JUDGE_VISION`、`Repeated judgment`、`strict majority`。
- dataset 与回归：搜 `Evaluation Dataset and regression`、`same Evaluation Dataset version`、`paired comparison`。
- 自动化门：搜 `Triggers and automation gates`、`EXPERIMENTAL`、`PRODUCTION`。
- memory：搜 `Memory boundary and Promotion`、`human-reviewed Promotion`。
- 迁移清单与排除项：搜 `Migration from the current implementation`、`Out of scope`、`SFT`、`GRPO`、`DPO`、`RLHF`。

`pixflow-module-rubrics/CONTEXT.md`：

- 统一领域语言：搜 `Evaluation Subject`、`Criterion`、`Hard Rule`、`Principle`、`Evidence Pack`、`Criterion Verdict`、`Quality Gate`、`Evaluation Dataset`、`Promotion`。
- 禁止旧词：搜每个条目下的 `Avoid`。

`pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md`：

- 核心决策：搜标题 `criterion verdicts instead of scalar quality scores`。
- 被否决方案：搜 `Runtime-generated rubrics`、`Confidence-weighted scalar aggregation`、`single process_result`。
- 后果：搜 `Formal regression`、`human explicitly promotes`。

`docs/design-docs/design.md`：

- 系统级 Rubrics：搜 `## 十一、Rubrics`、`11.1 核心方法论`。
- typed subject：搜 `Typed Evaluation Subject`。
- evidence 与 judge role：搜 `Evidence Pack 与 judge`、`RUBRICS_JUDGE_TEXT`。
- 数据模型/回归：搜 `11.5 数据模型与回归`、`rubrics_criterion_result`。
- memory：搜 `11.6 Memory 边界`、`Promotion`。
- 排除项：搜 `11.7 实施顺序与暂不做`、`后训练`。

`docs/design-docs/infra/ai.md`：

- 逻辑 role：搜 `RUBRICS_JUDGE_TEXT`、`RUBRICS_JUDGE_VISION`、`不能隐式回退到 PRIMARY_CHAT`。
- 请求 interface：搜 `ChatRequest`、`VisionRequest`、`Rubrics 必须指定`。
- retry/额度边界：搜 `ModelRetryRunner`、`每个真实 attempt`、`provider attempt`。
- Rubrics 消费契约：搜 `module/rubrics`、`不理解 criterion、Evidence Pack 或投票`。

`docs/design-docs/module/memory.md`：

- 禁止自动写回：搜 `Rubrics 原始 verdict 不能直接调用`、`Rubrics run 完成不自动强化记忆`。
- Promotion：搜 `人工审核的 Rubrics Promotion`、`显式调用记忆入口`、`evidence provenance`。
- v1 兼容列：搜 `旧 rubrics_score 字段仅作 v1 兼容`。

`docs/design-docs/harness/eval.md`：

- eval/rubrics 分工：搜 `记录，不评估`。
- trace 可靠性：搜 `best-effort，不是事实源`。
- Evidence Pack：搜 `只选择与某个 TASK_DECISION criterion 相关的 span`。
- 保留与重放：搜 `7 天`、`INCONCLUSIVE(MISSING_EVIDENCE)`。

`docs/design-docs/harness/hooks.md`：

- task 完成观察事件：搜 `TASK_COMPLETED`、`观察任务完成`。
- Rubrics 自动化边界：搜 `关于 Rubrics 自动化与预警`、`不接入在线 hook 链`、`不阻断任务终态`。

`docs/design-docs/frontend/api.md`：

- 路由总表：搜 `## Rubrics API`。
- 新 run 形状：搜 `手动 dataset run 请求`、`subjectIds`。
- 返回语义：搜 `criterion verdict matrix`、`passRate`、`overallScore`。
- 安全：搜 `不暴露完整原始 agent_trace`。

`docs/design-docs/module/file.md`：

- 模块反向边界：搜 `不依赖` 与 `module/rubrics`。
- 前端出口归属：搜 `结果管理 / Rubrics criterion 报告`。

`docs/design-docs/module/vision.md`：

- Rubrics 不复用 vision：搜 `rubrics 不经本模块`、`直连 infra/ai`。
- 依赖守护：搜 `module/rubrics`、`反向约束`。

`CONTEXT-MAP.md`：

- 上下文关系：搜 `Rubrics Evaluation -> Task Execution`、`does not change task state`。

定位旧实现待删除路径时使用：

    rg -n "ScoreAggregator|DimensionScore|DomainScore|RubricScore|Confidence|PRIMARY_CHAT|ScoreFeedbackWriter|MemoryFeedbackTrigger|rubrics_score" pixflow-module-rubrics pixflow-module-memory

## Plan of Work

### Milestone 1：先对齐 AI role，再建立无标量分数的 v2 领域核心

先在 `pixflow-infra-ai` 实现设计文档已经批准的最小契约。给 `ModelRole` 增加 `RUBRICS_JUDGE_TEXT` 和 `RUBRICS_JUDGE_VISION`；给 `VisionRequest` 增加必填 `ModelRole role`，只允许 vision capability 对应角色，并修改 `DefaultVisionModelClient`、所有调用方、路由配置和测试。应用配置必须为两个 Rubrics role 显式声明 provider/model/options/quota group；缺配置启动失败。不能为了先编译而在 Rubrics 内继续传 `PRIMARY_CHAT`，也不能让 Vision 默认补 `VISION`。

然后替换 `pixflow-module-rubrics/model` 与 `template`。新增 `SubjectType`、`CriterionKind`、`CriterionVerdict`、`CriterionReasonCode`、`QualityGate`、`TemplateLifecycleState`、`EvaluationSubject`、`CriterionResult`、`EvaluationSummary` 等不可变类型。删除 `Confidence`；删除整个 `score/` 包及其测试。测量值保留为 criterion diagnostic metadata，不参与 `passRate` 或 gate。

将 `RubricTemplate` 改为单 subject type 的扁平 criteria 列表，不再包含 domain 和 weight。每个 `Criterion` 必须包含 `key`、`kind`、`statement`、`passAnchor`、`failAnchor`、`evidenceTypes`、`applicability` 和 verifier spec。evaluator spec 必须包含 judge role、rollout count 和 parser schema version。rollout 默认 3，只允许正奇数；高风险模板可显式使用 5。

新增 `TemplateValidator` 与 canonical hash。canonicalization 使用 Jackson tree 递归按 key 排序、统一数组顺序为文件声明顺序，再对 UTF-8 canonical JSON 做 SHA-256。loader 同时扫描 classpath 与配置的 `$PIXFLOW_HOME/rubrics/`；相同 `(id, version)` hash 相同可去重，hash 不同则启动失败。semantic version 使用明确 parser 比较，不按字符串排序。结构校验拒绝重复 key、缺 anchors/evidence/applicability、LLM criterion 无 judge role/rollouts、确定性可验证的 Hard Rule 配 LLM；明显的 compound conjunction 由确定性 lint 拒绝，语义原子性最终仍由人工 review 和 gold-set 校准保证，不调用模型在线裁决模板是否合法。

删除旧 `default.yaml`，新增 `image-result-quality.yaml` v2 静态模板，初始为 `EXPERIMENTAL`。包含 resolution/format Hard Rules 与 background cleanliness/product visibility Principles。文件进入 `VALIDATED` 或 `PRODUCTION` 只能通过人工 review 的版本变更；模型草稿只能生成独立候选文件且保持 `EXPERIMENTAL`，系统没有“生成并发布生产模板”接口。

本里程碑的验收是纯函数测试通过，且生产 Rubrics 代码不再命中 `Confidence`、`ScoreAggregator`、weight 或 scalar score 类型；AI 请求测试明确断言两个 Rubrics role。

### Milestone 2：以 Image Result 纵向切片建立 Subject 与系统 Evidence Pack

先消除 Rubrics 对 task persistence implementation 的穿透。在 `pixflow-module-task/src/main/java/com/pixflow/module/task/api/` 增加一个小的只读 interface，例如 `TaskOutcomeQuery`，返回 immutable successful result snapshot 和 task decision snapshot；adapter 在 task 内部使用 `ProcessResultMapper`/`ProcessTaskMapper`。Rubrics 只 import task public API，不再 import `infra.persistence.*` 或可变 entity。该 interface 不使用 Rubrics 术语，避免 task bounded context 反向理解 criterion。

在 Rubrics 新增 `subject/`。`SubjectSnapshotResolver` 根据 `SubjectType + subjectId` 解析 immutable snapshot，并计算 canonical `subjectSnapshotHash`。Image Result 只接受成功且未删除的 image `process_result.id`，snapshot 包含 result/task identity、对象 location、kind、SKU/group/view 等必要 metadata；不夹带 generated copy 或整个 task history。Copy Result 和 Task Decision adapter 先定义 contract/fake，后续里程碑再启用。

新增 `evidence/`，以 `EvidencePackFactory` 统一排序 ID、hash 和 pack hash。Image adapter 从 task public snapshot 得到 object location，经 `ObjectStorage` 读取 bytes，经 `ImageCodec.probe` 得到宽、高、格式、大小和 alpha metadata；按固定顺序生成 `E1 OUTPUT_IMAGE`、`E2 IMAGE_METADATA` 等 entry。content hash 对原始 bytes 或 canonical metadata 计算 SHA-256。数据库只保存 Evidence Pack hash、授权引用、excerpt/metadata 和对象版本信息，不保存整张图片 bytes。

`ApplicabilityEvaluator` 在 verifier 前运行。criterion 不适用直接得到 `NOT_APPLICABLE` 且不调用 rule/model；适用但缺 required evidence 得到 `INCONCLUSIVE(MISSING_EVIDENCE)`；只有 evidence 完整才进入 verifier。Evidence Pack 对每个 criterion 生成 allowed view，prompt builder 不能看到不相关 entry。

重写 deterministic rule interface，使其接收 `CriterionContext(criterion, subject, evidenceView)` 并返回 verdict、reason、rationale、evidence IDs 和 diagnostics。resolution/format verifier 只读取 `IMAGE_METADATA`；异常、损坏图片或读取失败返回 `INCONCLUSIVE`，不抛出后被 runner 当成 subject failure。Hard Rule FAIL 是质量事实，异常是 evaluator availability 事实。

本里程碑的验收是：不用模型即可对一张 799x800 PNG 得到 resolution FAIL、format PASS、正确 Quality Gate；对象缺失时相关 criterion 是 INCONCLUSIVE；输入中没有 generated copy/trace；所有 evidence IDs 和 hashes 都由系统生成。

### Milestone 3：实现独立 Rollout、Evidence 校验与四态汇总

把当前 `LlmJudge` 拆成具体 orchestration `RepeatedLlmCriterionVerifier`，其内部根据 template subject/evaluator role 选择 `ChatModelClient` 或 `VisionModelClient`。文字请求固定 `RUBRICS_JUDGE_TEXT`，图片请求固定 `RUBRICS_JUDGE_VISION`。每个 criterion prompt 只包含一个 statement、两个 anchors、subject view 和 allowed Evidence Pack entries；输出 schema只有 `verdict`、`rationale`、`evidenceIds`。confidence 和 score 字段若出现，作为 schema noise 记录，不影响结果。

每个 rollout 是一次独立 `client.call`。调用前由 `ModelRouter.resolve(role)` 捕获 provider、model、capability、options，并与 prompt hash、parser schema version、rollout policy 组成 immutable evaluator version。若生产模型 identity 与 judge identity 相同，evaluation 标记 `selfJudged=true`，但不改 verdict。`infra/ai` 内部 retry 仍按 provider attempt 处理；Rubrics 不增加调用重试。

重写 parser：只接受 `PASS`、`FAIL`、`INCONCLUSIVE`，拒绝 judge 输出 `NOT_APPLICABLE`；N/A 只来自系统 applicability。rationale 必须非空且至少引用一个 allowed evidence ID。未知 ID、禁用 type、空引用、parse failure、timeout 或 provider failure都产生一个带 reason code 的 INCONCLUSIVE rollout，并完整记录 error code；它们不抛出到其它 criterion。

新增纯函数 `MajorityVerdictReducer`。分母固定为 configured rollouts，不排除失败 rollout。三次中两个或三个 PASS 得 PASS，两个或三个 FAIL 得 FAIL；其余组合统一为 `INCONCLUSIVE(JUDGE_DISAGREEMENT)`。agreement 是多数 PASS/FAIL 数除以 configured rollouts；没有 PASS/FAIL 多数时仍记录最大同类计数，但它只是 reliability metadata。

新增纯函数 `EvaluationSummaryCalculator`：Hard Rules 计算 `PASSED/FAILED/UNKNOWN`；Principles 计算 nullable passRate；所有适用 criterion 计算 nullable coverage；同时保存四态 counts、applicable Hard Rule count、rollout agreement 和 evidence completeness。它不依赖 Spring、数据库或模型，是调用方和测试共享的唯一汇总 implementation。

本里程碑的验收包括三次调用次数、角色、独立 rollout rows、2-1 多数、1-1-1/1-1-失败无多数、invented evidence、parser failure 和 provider failure。任何路径都不能产生 scalar quality score。

### Milestone 4：迁移事实表、Run 深模块与 v2 HTTP API

不修改已发布的 `V1__create_rubrics_tables.sql`。新增 `V2__migrate_rubrics_to_criterion_verdicts.sql`：扩展 `rubrics_run` 保存 template hash、evaluator version、subject type、dataset identity 和 stats JSON；扩展 `rubrics_run_item` 允许 legacy `result_id` 为空并增加 `(run_id, subject_type, subject_id)` 唯一键、snapshot hash、quality gate、nullable pass rate/coverage、Evidence Pack hash；旧行的新字段允许 null 并视为 legacy。新增 `rubrics_evaluation`、`rubrics_criterion_result`、`rubrics_judge_rollout`、`rubrics_dataset`、`rubrics_dataset_item`、`rubrics_gold_label`、`rubrics_validation_report` 和 `rubrics_promotion`。扩展 baseline/alert 保存 dataset/template/evaluator identity 与 criterion-level details。`rubrics_score` 不删除、不迁移成伪 verdict，也不再写入。

把一次 subject evaluation 的 `rubrics_evaluation + criterion_result + judge_rollout + run_item terminal status` 放在同一 MySQL transaction 中提交。模型调用和对象读取在 transaction 外完成；提交成功后该 `(runId, subjectType, subjectId)` 不覆盖。若进程在提交前崩溃，重跑可能重新消费模型额度但不会产生半套事实；若提交已成功，唯一键和 item terminal status 使恢复直接跳过。

实现 `RubricsEvaluationService` 和具体 `EvaluationRunCoordinator`。run 固定绑定一个 template version、template hash、evaluator version、subject type，以及 dataset 或 explicit subject selection。item 的 `PARTIAL` 表示至少一个 criterion INCONCLUSIVE；不是低质量。单 rollout/criterion/item 失败都局部隔离，run 按 succeeded/partial/failed 汇总为 `SUCCEEDED/PARTIAL/FAILED`。

替换 controllers，严格实现 `docs/design-docs/frontend/api.md` 的 v2 routes。删除 `/scores/by-result`、`/scores/by-sku` 等 v1 score routes。POST run 只能提交 template identity、subject type 以及 dataset 或 subject IDs；服务端解析 snapshot/evidence。evaluation 响应返回 criterion matrix、authorized evidence metadata 和 rollouts，不返回 raw image、完整 prompt、完整 agent trace、`overallScore`、`imageScore`、`copyScore` 或 `decisionScore`。

删除 `feedback/ScoreFeedbackWriter.java`、`feedback/MemoryFeedbackTrigger.java`、RubricsProperties feedback score 阈值和相应 bean。删除 memory 中只被 v1 使用的 `SkuHistoryRubricsScoreCommand` 与 `appendRubricsScore` interface/implementation；保留数据库旧列和历史数据。

本里程碑要在 Testcontainers MySQL 上从 V1 空库迁移到 V2，并另用含 v1 score/run 样例行的数据库执行 V2，证明历史行仍可查询、新 v2 evaluation 可写、旧表没有被新代码更新。

### Milestone 5：把自动化移出 Hook 链，并以 Template Lifecycle 门控

删除 `RubricsTriggerListener implements HookCallback` 和模块对 `pixflow-hooks` 的 Maven 依赖。新增薄 adapter `TaskCompletedEvaluationListener`，直接监听 task 公共 `TaskCompletedEvent`，只做配置/lifecycle/sample admission 与向 Rubrics 自有有界 executor 提交 command；事件发布线程不执行 object read、rule 或模型调用，listener 异常不改变 task terminal state。

新增纯函数 `AutomationAdmissionPolicy`。`EXPERIMENTAL` 只允许 manual calibration/gold-set run；`VALIDATED` 允许手动和显式 opt-in 的 event sample/scheduled dataset；`PRODUCTION` 允许显式启用的 manual/event/scheduled。默认 event 与 scheduler 都 disabled。event run 只作为趋势样本，不自动成为 formal baseline；scheduled formal regression 必须配置固定 dataset id/version。所有跳过都记录低基数 reason 指标。

替换 `RubricsDailyBatchScheduler`：它不再扫描最近 24 小时结果并用 latest default template；它按明确配置的 template version + dataset version 创建 run。配置缺失、template lifecycle 不允许或 validation report 未达标时跳过并记录原因，不自动选择其它模板/模型/dataset。

本里程碑的验收是：发布 TaskCompletedEvent 后在线 dispatcher 中没有 Rubrics HookCallback；EXPERIMENTAL 不创建 event run；disabled 不创建；VALIDATED + explicit config 只入队不阻塞 publisher；scheduler 使用固定 dataset；任何自动 run 都不写 memory。

### Milestone 6：实现 Evaluation Dataset、Gold Label、校准与正式回归

实现 `dataset/`。dataset 创建时保存 immutable `(subjectType, subjectId, subjectSnapshotHash)` manifest、version、description、createdAt 和可选 gold label set version。相同 id/version 内容 hash 不同拒绝；subject 当前 snapshot hash 与 manifest 不同时标为 non-replayable，不静默替换。Task Decision dataset 在 trace 保留期内固化所需 Evidence Pack metadata/hash；底层对象不可用时明确报告不可重放。

实现 gold label 导入和 validation report。每个适用 criterion 至少保存两名 human labels 与 adjudicated label；系统计算 human-human kappa、judge-human accuracy/macro-F1、repeated-judge agreement、INCONCLUSIVE/parser/invalid-evidence/missing-evidence rates，并按 subject category/easy-hard slice 展示。Principle macro-F1 低于 0.75、LLM Hard Rule低于 0.85 或 human kappa 低于 0.60 时 criterion/template 保持 EXPERIMENTAL；lifecycle 变更仍通过静态模板发布，不由运行时自动改 YAML。

重写 `baseline/RegressionComparator`。只接受 dataset id/version、subject snapshots、template/evaluator identity 均可配对的 runs；不同 dataset 直接返回业务错误。按 subject + criterion 计算 PASS rate、`PASS -> FAIL`、`FAIL -> PASS`、Hard Rule failure/Quality Gate transitions、coverage、INCONCLUSIVE、agreement、invalid evidence/parser rate和 sample count；样本不足显示 marker，不制造稳定结论。alert 只由 validated/production criterion 的正式 regression 产生。

baseline 创建必须验证 run 已完成、dataset 存在、template lifecycle 合法、validation report 达标。删除“任意历史 run 设为 active baseline”的 v1 语义；生产 event run 仅可查询 trend。

本里程碑验收使用同一 10-item fake dataset 跑 old/new evaluator，证明 paired transitions 正确；换 dataset version 时 regression 拒绝；丢失一个 snapshot 时报告 non-replayable；未达 calibration threshold 的 template 不能建正式 baseline。

### Milestone 7：实现人工 Promotion，并扩展 Copy Result 与 Task Decision

在 memory 模块新增显式 human-reviewed insight interface，例如 `ReviewedInsightService.promote(ReviewedInsightCommand)`。command 接受已审核文本、关联 SKU、Rubrics provenance 和幂等 key；implementation 直接保存人工批准文本并建立 active index，不再调用 LLM 重新抽取或改写。Rubrics 的 `PromotionService` 验证 evaluation/criterion 存在、reviewer 来自 authenticated principal、template/evaluator/evidence hashes 完整；先插入 `rubrics_promotion=PENDING`，调用 memory 后写 `SUCCEEDED + destinationMemoryId`，失败写 `FAILED` 并允许同一 promotion id 重试。普通 run completion、alert 和 scheduled job 均不能调用此 interface。

完成 Copy Result adapter。stable identity 使用已有 copy artifact identity；如果当前只有 generated copy，则使用 owning result + immutable copy hash 派生稳定 identity。Evidence Pack 只含 COPY_TEXT、product facts、audience/voice，不自动加入图片美学。template 固定 `RUBRICS_JUDGE_TEXT`。

最后完成 Task Decision adapter。identity 是 `process_task.id + immutable proposal/decision revision`。Evidence Pack 可含 requirement、proposal、DAG snapshot、相关 trace span、commerce facts 和 recalled facts，但必须按 criterion 选择最小相关集合，不能把全 trace dump 给 judge。trace 缺失产生 INCONCLUSIVE；决策评估不拿单张图片的美学 verdict 充当 evidence。

为三个 subject type 运行相同 contract tests：subject 不串证据、template subject type 必须匹配、snapshot hash 稳定、evidence refs 系统拥有、summary 四态一致。完成后才允许把 Copy/Task template 从 EXPERIMENTAL 提升到后续人工校准阶段。

### Milestone 8：收尾边界、观测、文档与全仓验证

重写 `RubricsAutoConfiguration`，只装配 v2 beans。必需的 task query、storage、image、AI roles、ModelRouter 和 repositories 缺失时启动失败；测试显式注入 fake。properties 删除 score/baseline delta/feedback 阈值，新增 template dirs、runner concurrency、automation bindings、dataset bindings 和受限 evidence excerpt 大小。

指标只使用低基数标签：subject type、template id/version、criterion key、verdict/reason、judge role/evaluator version、trigger type。日志包含 run/subject/template/evaluator/criterion/trace ID，但不记录 raw bytes、完整 prompt、API key 或完整 trace。API evidence excerpt 经过 authorization 与 `Sanitizer`。

新增 ArchUnit：Rubrics 不被 agent/loop/tools/vision/file 反向依赖；Rubrics 不 import task `infra.persistence`；不 import hooks；vision/file 不 import Rubrics；`score`/`Confidence`/自动 feedback 不再存在；Rubrics 只通过 infra/ai interface 调模型，图片 judge 不经 module/vision。

最后同步实现后发生的必要设计偏差，并更新本计划 Progress、Surprises、Decision Log、Outcomes 与 Revision Notes。不得重写 completed v1 plan；它是历史证据。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。先保存工作树状态，识别用户已有文档改动：

    git status --short
    git diff -- docs/design-docs/module/rubrics.md pixflow-module-rubrics/CONTEXT.md pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md

完成 Milestone 1 后运行：

    mvn -pl pixflow-infra-ai,pixflow-module-rubrics -am -DskipTests compile
    mvn -pl pixflow-infra-ai -Dtest=*ModelRouterTest,*Vision*Test test
    mvn -pl pixflow-module-rubrics -Dtest=TemplateValidatorTest,TemplateRegistryTest,EvaluationSummaryCalculatorTest test
    rg -n "ScoreAggregator|DimensionScore|DomainScore|RubricScore|Confidence|overallScore|imageScore|copyScore|decisionScore" pixflow-module-rubrics/src/main pixflow-module-rubrics/src/test

最后一条预期无输出；若迁移/legacy read model 必须保留旧列名，只能出现在 `persistence/legacy` 和 migration 中，并在本计划记录原因。

完成 Image Result 与 judge 纵向切片后运行：

    mvn -pl pixflow-module-rubrics -Dtest=ImageSubjectSnapshotResolverTest,EvidencePackFactoryTest,ImageEvidencePackBuilderTest,RuleCriterionVerifierTest,RepeatedLlmCriterionVerifierTest,MajorityVerdictReducerTest test
    rg -n "PRIMARY_CHAT|ModelRole.VISION|new EvidenceRef|confidence|score" pixflow-module-rubrics/src/main/java

搜索不应命中 judge role 回退、模型构造 evidence 或 confidence-to-score。`score` 作为普通英语出现在注释时要人工检查，不允许重新形成 quality score。

完成数据库和 run/API 后运行：

    mvn -pl pixflow-module-rubrics -Dtest=RubricsMigrationIntegrationTest,EvaluationPersistenceContractTest,EvaluationRunCoordinatorTest,RubricsApiTest test
    rg -n "scores/by-result|scores/by-sku|RubricsScoreMapper|ScoreFeedbackWriter|MemoryFeedbackTrigger|appendRubricsScore" pixflow-module-rubrics pixflow-module-memory

搜索预期无生产命中。migration test 必须实际启动 MySQL，不能因 Docker 不可用而把 skipped 当成通过。

完成自动化、dataset、回归和 Promotion 后运行：

    mvn -pl pixflow-module-task,pixflow-module-memory,pixflow-module-rubrics -am test
    mvn -pl pixflow-module-rubrics -Dtest=AutomationAdmissionPolicyTest,TaskCompletedEvaluationListenerTest,EvaluationDatasetTest,CalibrationReportTest,PairedRegressionComparatorTest,PromotionServiceTest test
    rg -n "HookCallback|pixflow-hooks|MemoryIngestService|appendRubricsScore" pixflow-module-rubrics/src/main pixflow-module-rubrics/pom.xml

搜索预期无输出。Promotion 应依赖新的显式 reviewed-memory interface，而不是通用自动 ingest。

最终运行：

    mvn -pl pixflow-infra-ai,pixflow-module-task,pixflow-module-memory,pixflow-module-rubrics,pixflow-app -am test
    mvn test
    git diff --check
    git status --short

如果全仓测试被与本计划无关的既有失败阻断，先确保所有触达模块命令独立通过，再把完整失败测试名和首个相关 stack frame 记录到 `Surprises & Discoveries`；不得只写“其他模块失败”。自动测试不得调用真实 provider API，模型行为全部使用 fake client 或本地 fake HTTP server。

## Validation and Acceptance

实现只有同时满足以下可观察行为才算完成。

加载模板时，同一 `image-result-quality:2.0.0` 的 classpath 与用户目录文件 canonical hash 相同只注册一次；内容不同则应用启动失败并指出 id/version/hash conflict。把文件换行从 CRLF 改为 LF 不改变 canonical hash。版本 `2.10.0` 正确晚于 `2.9.0`。

一个 Image Result 只能使用 `subjectType=IMAGE_RESULT` 模板。系统读取对象和 metadata 构建 Evidence Pack，客户端不能在 run request 中提供 evidence。模型返回 `evidenceIds:["E9"]` 而 pack 只有 E1/E2 时，该 rollout 是 `INCONCLUSIVE(INVALID_EVIDENCE)`，不会把 E9 存成合法 ref。

resolution Hard Rule 对 799x800 输出 FAIL；800x800 PASS；对象缺失或 probe 异常 INCONCLUSIVE。任一 Hard Rule FAIL 使 gate FAILED；无 FAIL 但一个适用 Hard Rule INCONCLUSIVE 使 gate UNKNOWN；所有适用 Hard Rule PASS 使 gate PASSED。NOT_APPLICABLE Hard Rule 不参与 gate。

Principle verdict 组合 PASS、FAIL、INCONCLUSIVE、NOT_APPLICABLE 的 passRate 是 0.5，coverage 是 2/3；只有 INCONCLUSIVE/N/A 时 passRate 为 null；全 N/A 时 passRate 与 coverage 都为 null。响应 JSON 必须保留 null，不能由 DTO/default 值转换成 0。

一个 LLM criterion 默认产生三条 rollout 事实和三次独立 client 调用。PASS/PASS/FAIL 得 PASS 且 agreement=2/3；FAIL/FAIL/provider-error 得 FAIL；PASS/FAIL/parser-error 得 `INCONCLUSIVE(JUDGE_DISAGREEMENT)`。provider error 只影响该 rollout/criterion，不把其它 criterion 或 subject 标成 FAIL。

文本与视觉调用分别传 `RUBRICS_JUDGE_TEXT`、`RUBRICS_JUDGE_VISION`。删除任一 role 配置时启动失败或对应 evaluator 明确 unavailable；日志和调用记录中不得出现 Rubrics 使用 `PRIMARY_CHAT`。evaluator version 可以还原 provider/model/options/prompt/parser/rollout policy。

V2 migration 在已有 v1 run/score 样例数据上执行成功。旧 `rubrics_score` 行保持不变；新 run 只写 evaluation/criterion/rollout tables。API 不存在 v1 score routes，v2 response 不包含任何 scalar score 字段。

EXPERIMENTAL template 可手动运行但不能响应 TaskCompleted event、scheduler、formal baseline 或 formal alert。VALIDATED/PRODUCTION 仍需显式 automation enable。事件 listener 返回后 task publisher 不等待模型调用，Rubrics 异常不改变已写入的 task terminal state。

正式 regression 对同一 dataset version 按 subject/criterion 配对并显示 transitions。换 dataset version、subject snapshot hash 不同或 arbitrary production run 时明确拒绝 formal comparison。不可重放 entry 单独报告，不从分母中静默消失。

run 完成、低 passRate、Hard Rule FAIL 或 alert 都不会调用 memory。只有认证用户提交 Promotion 后才出现 `rubrics_promotion` 与 destination memory ID；重复同一幂等 key 不创建第二条 memory；memory 写失败保留可重试审计状态。

Copy Result judge 看不到图片 evidence，Image Result judge 看不到 generated copy，Task Decision judge 只得到与当前 criterion 相关的 requirement/proposal/DAG/trace/fact entries。三个 subject 不合并成一个 overall result。

代码和文档继续明确排除 evaluator SFT、GRPO、DPO、RLHF、任何后训练、训练奖励导出、运行时生产 rubric 生成、自动 memory feedback 与实时 Agent-loop 评分。

## Idempotence and Recovery

所有源码和文档修改使用小范围补丁。不得执行 `git reset --hard`、`git checkout --` 或整体 restore；当前工作树已有用户的设计文档和 domain-modeling 改动，实施必须在其上演进。

数据库迁移只新增 V2 文件，不改 V1 checksum。V2 的 ALTER/CREATE 在 Testcontainers 中从 V1 状态一次执行；Flyway 负责防止重复执行。若 migration 中途失败，在临时测试库重建验证；生产/共享库不得手工删除 v1 历史表。任何需要数据回填的列先 nullable，再由应用只对 v2 新写强制非空，避免伪造 legacy identity。

模板发布以新 semantic version 进行；已发布 id/version 内容不就地改。错误模板修复必须发布新 version。policy/lifecycle 变更也进入 template hash 和审计，不通过运行时覆盖。

item evaluation 在一个最终 transaction 中提交。提交前崩溃可以重跑；提交后重复调度由唯一键和 terminal item 状态跳过。由于重跑可能重新调用 judge，运维日志必须显示 previous attempt 和额度影响，但不得把两次 attempt rollout 混成一个 evaluation。

Evidence Pack 底层对象缺失时不尝试伪造或从模型补全。manual/event run 得 INCONCLUSIVE；dataset replay 标 non-replayable。对象恢复后可新建 run，历史 verdict 不覆盖。

Promotion 使用幂等 key 和显式状态机。memory 成功而 Rubrics 状态更新失败时，重试先按 idempotency 查询 destination，随后补写 SUCCEEDED；不得重复创建 insight。Rubrics facts 已写而 memory 失败时保留 FAILED/PENDING，不删除 evaluation。

移除 v1 Java path 前先用 `rg` 确认调用点。若 app 或前端仍编译依赖旧 score DTO，按 v2 API 同步迁移调用方，不恢复旧 controller 作为临时兼容层。

## Artifacts and Notes

目标调用关系：

    RubricsAdminController / RubricsReportController
    TaskCompletedEvaluationListener / DatasetScheduler
        -> RubricsEvaluationService
            -> EvaluationRunCoordinator
                -> SubjectSnapshotResolver (image | copy | task decision)
                -> EvidencePackBuilder (typed, system-owned)
                -> ApplicabilityEvaluator
                -> CriterionVerifier (rule | repeated LLM)
                -> MajorityVerdictReducer
                -> EvaluationSummaryCalculator
                -> EvaluationPersistence

目标 judge 流程：

    one Criterion + allowed Evidence Pack view
        -> rollout 1: infra/ai role call -> parse -> evidence validation
        -> rollout 2: infra/ai role call -> parse -> evidence validation
        -> rollout 3: infra/ai role call -> parse -> evidence validation
        -> strict majority PASS/FAIL, otherwise INCONCLUSIVE
        -> persist rollout facts + criterion result + agreement

目标 memory 流程：

    evaluation / alert / passRate
        -> remain Rubrics facts

    authenticated human review
        -> rubrics_promotion(PENDING, provenance)
        -> ReviewedInsightService.promote(idempotencyKey, approved text, provenance)
        -> rubrics_promotion(SUCCEEDED, destinationMemoryId)

关键删除清单：

    pixflow-module-rubrics/.../score/
    pixflow-module-rubrics/.../model/Confidence.java
    pixflow-module-rubrics/.../feedback/ScoreFeedbackWriter.java
    pixflow-module-rubrics/.../feedback/MemoryFeedbackTrigger.java
    pixflow-module-rubrics/.../run/RubricsTriggerListener.java
    pixflow-module-memory/.../SkuHistoryRubricsScoreCommand.java
    SkuHistoryService.appendRubricsScore(...)

关键保留但只读的 v1 artifact：

    pixflow-module-rubrics/src/main/resources/db/migration/V1__create_rubrics_tables.sql
    MySQL rubrics_score rows and legacy score columns
    docs/design-docs/exec-plans/completed/rubrics-module-implementation-plan.md

## Interfaces and Dependencies

`pixflow-module-rubrics` 最终依赖 `pixflow-common`、`pixflow-module-task` 的 public query/event contract、`pixflow-eval` read contract、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image`、`pixflow-module-memory` 的 reviewed insight interface、MyBatis/Jackson/Spring Web。删除 `pixflow-hooks` 依赖。Rubrics 不依赖 `module/vision`、`module/file`、`agent`、`harness/loop` 或 `harness/tools`。

核心不可变类型建议固定为：

    public enum CriterionVerdict {
        PASS, FAIL, INCONCLUSIVE, NOT_APPLICABLE
    }

    public enum QualityGate {
        PASSED, FAILED, UNKNOWN
    }

    public record EvaluationSubject(
            SubjectType type,
            String id,
            String snapshotHash,
            Map<String, Object> view) {}

    public record EvidenceEntry(
            String id,
            EvidenceType type,
            String sourceRef,
            String contentHash,
            String excerpt,
            Map<String, Object> metadata,
            Instant capturedAt) {}

    public record EvidencePack(
            String hash,
            List<EvidenceEntry> entries) {}

`view` 与 metadata 必须防御性复制并只包含 allowlisted 标量/嵌套值；如果实现期发现开放 Map 让接口过浅，应按 subject type 拆为 sealed snapshot records，并在 Decision Log 记录。图片 bytes 不放进该公开 record，可由内部 evidence payload handle 在一次 evaluation 生命周期内持有。

内部真实 seam：

    public interface SubjectSnapshotResolver {
        SubjectType subjectType();
        EvaluationSubject resolve(String subjectId);
    }

    public interface EvidencePackBuilder {
        SubjectType subjectType();
        EvidencePack build(EvaluationSubject subject);
    }

    public interface CriterionVerifier {
        VerifierType verifierType();
        CriterionEvaluation verify(CriterionContext context);
    }

`CriterionEvaluation` 对 LLM verifier 包含 rollout 列表，对 rule verifier 包含单个 deterministic observation；两者最终都提供 CriterionVerdict、reason、rationale、evidence IDs 和 diagnostics。调用方不区分底层 AI client 类型。

task public read interface 必须返回 immutable value，不把 mapper/entity 暴露给 Rubrics。memory reviewed insight interface 必须接收服务端确定的 reviewer/provenance 和幂等 key，不接受原始 judge response 作为 insight 文本。

AI interface 约束：

    ChatRequest.role = ModelRole.RUBRICS_JUDGE_TEXT
    VisionRequest.role = ModelRole.RUBRICS_JUDGE_VISION

`infra/ai` 只负责 role routing、attempt retry、concurrency/quota、provider call 和规范化错误；Rubrics 自己拥有 criterion、Evidence Pack、rollout count、parser/evidence validation、majority 和 evaluator version 组装。不得把 Rubrics 投票塞进 `infra/ai`。

## Revision Notes

2026-07-12 / Codex: 创建本计划。原因是 domain-modeling 后的权威设计已从 v1 confidence-weighted scalar scoring 切换为 typed subject、atomic Criterion、四态 verdict、系统 Evidence Pack、三次 judge 多数决、版本化 dataset 正式回归和人工 Promotion，而当前实现仍是完整 v1。计划按 AI role/领域核心、Image Result 纵向切片、judge/persistence、run/API/automation、dataset/regression、Promotion/其它 subject 的顺序直接替换生产路径，并提供设计文档快速定位关键词。

2026-07-12 / Codex: 完成 Milestone 1。直接删除 v1 标量评分、自动 memory feedback、旧 runner/controller/baseline/judge/rule 生产链；保留 V1 migration 与 legacy score persistence 作为只读历史事实。新增独立 judge roles、显式 VisionRequest role、v2 template/criterion/verdict/gate、canonical template hash 与 nullable 汇总语义。审查后补齐 judge role 开发配置与启动期 fail-fast、verifier 组合校验和 SemanticVersion。验证：20 模块 reactor compile 成功；最终触达 reactor 共 84 tests 成功（common 23、storage 15、infra-ai 26、infra-image 16、rubrics 4）。
