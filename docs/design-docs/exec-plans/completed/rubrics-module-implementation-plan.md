# 完整实现 `pixflow-module-rubrics`：离线评分、基线回归、可追溯写回与前端/PM 自助 API

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/module/rubrics.md`、`docs/design-docs/design.md`、`docs/design-docs/api.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`，并参考 `infra/ai.md`、`infra/storage.md`、`infra/image.md`、`harness/eval.md`、`harness/hooks.md`、`module/task.md`、`module/memory.md` 的现有契约。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

执行完成后，PixFlow 将具备 Wave 6 的离线评估闭环：用户或 PM 可以通过 `/api/rubrics/runs` 手动对一批 `process_result` 运行评分，也可以在任务完成后由 `TASK_COMPLETED` 观察事件自动触发评分；系统会把每条结果的图片质量、文案质量、决策质量拆成维度级 PASS/FAIL 证据，再由程序按模板权重聚合为 0-100 分，落库到 `rubrics_score`，写回 `sku_history.rubrics_score`，并在低分/高分高频模式出现时异步投递记忆抽取。用户可查询评分明细、运行进度、基线对比和退化告警。

## Purpose / Big Picture

这项工作把 PixFlow 从“能执行图片处理任务”推进到“能事后知道处理质量如何，并把经验反馈给下一轮 Agent”的闭环阶段。完成后，运营人员不需要人工翻看每张图和每段建议，就能看到结构化评分、扣分理由、证据引用和相对基线的退化维度；开发者也能通过稳定测试验证 LLM judge 输出只用于二元判定，所有连续分数由程序确定性计算。

从实现上看，rubrics 不进入 Agent 主循环，不注册工具，不在线阻断任务。它是一个独立 Maven 模块 `pixflow-module-rubrics`，依赖已完成的 `pixflow-eval`、`pixflow-module-task`、`pixflow-module-memory`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image` 与 `pixflow-hooks`。其核心机制是“模板定义评分维度与权重，LLM 或规则给出 PASS/FAIL，聚合器负责算分，runner 负责批处理、断点续跑和隔离失败”。

## Progress

- [x] (2026-07-01 19:20+08:00) 已按 `AGENTS.md` 读取 active exec plans，确认 `module/rubrics` 是当前 Wave 6 中唯一未完成的后端闭环模块，且不阻塞主链路。
- [x] (2026-07-01 19:20+08:00) 已读取 `PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/module/rubrics.md`、`docs/design-docs/design.md` 与 `docs/design-docs/api.md` 中 rubrics 相关设计。
- [x] (2026-07-01 19:20+08:00) 已核对当前代码接口：`TraceQuery`、`ObjectStorage`、`ChatModelClient`、`VisionModelClient`、`ImageCodec`、`TaskLifecyclePayload`、`ProcessResultMapper`、`SkuHistoryService` 与 `MemoryIngestService`。
- [ ] 新增 `pixflow-module-rubrics` Maven 模块，接入父 `pom.xml`、dependencyManagement、AutoConfiguration imports、mapper 扫描与配置属性。
- [ ] 落地 rubrics 数据模型与迁移：`rubrics_score`、`rubrics_run`、`rubrics_run_item`、`rubrics_baseline`、`rubrics_alert`。
- [ ] 落地模板加载、本地 YAML 内置模板、模板版本注册和模板 API。
- [ ] 落地 LLM judge、规则验证器、维度结果模型与程序聚合器。
- [ ] 落地 EvaluationRunner、断点续跑、任务完成 hook 监听、每日批量调度和手动 REST 触发。
- [ ] 落地基线对比、退化告警、评分查询和评分写回 memory 的接缝。
- [ ] 补齐单元测试、集成测试、ArchUnit 边界守护和 app 编译验证。

## Surprises & Discoveries

- Observation: `docs/design-docs/module/rubrics.md` 写到 `module/memory` 应提供 `SkuHistoryRepository.appendScore(...)`，但当前代码只有 `SkuHistoryService.recallBySkuIds(...)`，没有写回评分的公开方法。
  Evidence: `pixflow-module-memory/src/main/java/com/pixflow/module/memory/skuhistory/SkuHistoryService.java` 目前只暴露 recall 方法；本计划把“补一个最小评分写回 API”列为实现步骤。

- Observation: `harness/hooks` 设计把 `TASK_COMPLETED` 定义为纯观察事件，且明确 Rubrics 预警不进入在线 hook 链。
  Evidence: `docs/design-docs/harness/hooks.md` 可搜 `关于 Rubrics 预警`；实现中 listener 只能异步创建 rubrics run，不能同步阻断或改写任务状态。

- Observation: `harness/eval` 的 trace 是 best-effort 旁路记录，不是事实源。
  Evidence: `docs/design-docs/harness/eval.md` 可搜 `best-effort，不是事实源`；实现中图片/文案评分必须以 `process_result` 为事实源，trace 缺失时决策质量维度应降级或跳过，不能让整条评分失败。

## Decision Log

- Decision: 新增模块命名为 `pixflow-module-rubrics`，包名 `com.pixflow.module.rubrics`。
  Rationale: 现有业务模块均采用 `pixflow-module-*` Maven 命名，设计文档的 `module/rubrics` 应按同一命名规则落地。
  Date/Author: 2026-07-01 / Codex

- Decision: LLM judge 永远只返回 `PASS` / `FAIL`、`HIGH` / `MEDIUM` / `LOW` 置信档位、理由和证据；不得返回 0-100 连续分。
  Rationale: 这是 `module/rubrics.md` 与 `design.md` §11 的核心方法论，可降低 LLM 连续打分漂移。连续分数必须由 `ScoreAggregator` 程序计算。
  Date/Author: 2026-07-01 / Codex

- Decision: 对 memory 的评分写回做最小公开 API 扩展，而不是让 rubrics 直接引用 memory 的 MyBatis mapper。
  Rationale: `module/rubrics` 依赖 `module/memory` 是允许的，但直接使用 `SkuHistoryMapper` 会穿透 memory 内部持久化边界。新增 `appendRubricsScore` 一类服务方法更符合当前模块边界。
  Date/Author: 2026-07-01 / Codex

- Decision: 事件驱动触发使用 `HookCallback` 监听 `HookEvent.TASK_COMPLETED`，只投递异步 run，不在 hook 调用线程内执行评分。
  Rationale: hooks 文档规定 `TASK_COMPLETED` 是观察事件；rubrics 是离线流程。hook listener 只做轻量入队，避免拖慢 task worker 终态派发。
  Date/Author: 2026-07-01 / Codex

## Outcomes & Retrospective

待完成。实现结束后应记录最终新增模块、API、测试数量、验证命令输出、与设计文档的偏差，以及 memory 写回 API 是否需要在后续设计文档中补充。

## Context and Orientation

当前仓库根目录是 `D:\study\PixFlow`。父 Maven 工程 `pom.xml` 已包含 `pixflow-module-task`、`pixflow-module-memory`、`pixflow-eval`、`pixflow-hooks`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image` 和 `pixflow-app`，但尚未包含 `pixflow-module-rubrics`。

几个术语在本计划中的含义如下：

- `process_result`：任务执行结果事实表，由 `pixflow-module-task` 写入。rubrics 对每条成功结果评分，读取字段包括 `id`、`task_id`、`sku_id`、`output_minio_key`、`generated_copy`、`branch_id`、`status`。
- `agent_trace`：`pixflow-eval` 写入的 Agent 回合 trace 表。它用于决策质量评估，但设计上 best-effort，可缺失。缺失时只能让相关维度跳过或标低置信，不能影响图片/文案事实评分。
- `RubricTemplate`：本地 YAML 模板，定义 domain、dimension、权重、verifier 类型、LLM prompt、PASS/FAIL anchors 和规则参数。
- `dimension`：一个评分检查项，例如背景干净度、分辨率合规、文案是否流畅、决策是否有数据支撑。
- `verifier`：给 dimension 产出 PASS/FAIL 的实现。`llm` verifier 调 `ChatModelClient` 或 `VisionModelClient`；`rule` verifier 调本地 Java 规则类。
- `DomainScore`：某一类质量的聚合分，例如 `IMAGE_QUALITY`、`COPY_QUALITY`、`DECISION_QUALITY`。
- `baseline run`：某一次历史评估运行，被指定为基线后可与当前 run 对比，计算维度 delta 和退化告警。

当前实际接口约束：

- `pixflow-eval` 暴露 `com.pixflow.harness.eval.api.TraceQuery`，可按 `TraceQueryCriteria` 分页查询 `TurnTraceRecord`。
- `pixflow-infra-storage` 暴露 `ObjectStorage.getBytes(ObjectLocation)` 和 `getStream(ObjectLocation)`；`process_result.output_minio_key` 只有桶内 key，rubrics 需要根据结果来源选择 `BucketType.RESULTS` 或 `BucketType.GENERATED`。
- `pixflow-infra-ai` 暴露 `ChatModelClient.call(ChatRequest)` 和 `VisionModelClient.call(VisionRequest)`；图片内容必须由调用方从 storage 取出后放入请求，infra/ai 不碰 MinIO。
- `pixflow-infra-image` 暴露 `ImageCodec.probe(InputStream)`、`decode(InputStream)`、`encode(RasterImage, EncodeSpec)`；规则验证器可用 `probe` 做格式、尺寸、alpha 快速判断，涉及像素比例时再 decode。
- `pixflow-hooks` 暴露 `HookCallback`、`HookEvent.TASK_COMPLETED` 和 `TaskLifecyclePayload`；rubrics listener 应是一个 bean，由 hooks registry 自动调度。
- `pixflow-module-memory` 目前只通过 `SkuHistoryService.recallBySkuIds(...)` 读历史，通过 `MemoryIngestService.ingestAsync(...)` 抽取 insight。rubrics 需要在 memory 模块补一个最小写回方法，用于把评分 append 到 `sku_history` 或更新对应历史记录。

## 设计文档快速定位关键词

执行本计划时，先用下面关键词在参考文档里定位设计文本。推荐命令形式是在仓库根目录运行 `rg -n "<关键词>" <文件>`。

`docs/design-docs/module/rubrics.md`：

- 模块定位与 Wave 6 范围：搜 `Rubrics 离线评估`、`Wave 6`
- 核心方法论：搜 `LLM judge 做二元判定`、`程序做数值聚合`
- 不做项：搜 `职责边界与不做什么`、`不在 Agent 工具层暴露`
- 模块结构：搜 `模块结构与依赖位置`
- 核心 record：搜 `JudgeVerdict`、`RubricScore`、`RubricsRun`
- 模板 YAML：搜 `RubricTemplate 与版本管理`、`模板结构`
- judge prompt：搜 `Judge Prompt 结构`
- 聚合公式：搜 `程序聚合`
- 规则验证器：搜 `RuleVerifier`、`ResolutionRuleVerifier`
- 决策质量维度：搜 `决策质量精细化拆解`
- 基线回归：搜 `基线对比与回归检测`
- 三层写回：搜 `三层写回`、`rubrics_score → sku_history`
- 触发方式：搜 `调度与触发`、`TaskCompleted`
- 测试策略：搜 `ScoreAggregatorTest`、`ArchUnit`

`docs/design-docs/design.md`：

- 总体定位：搜 `Rubrics 为离线评估闭环`
- 离线评估章节：搜 `## 十一、Rubrics`
- 数据模型：搜 `rubrics_score(id`、`新增辅助表`
- 三层写回：搜 `### 11.6 三层评分写回`
- 任务结果事实表：搜 `process_result`
- 记忆表：搜 `sku_history`、`analysis_insight`

`docs/design-docs/api.md`：

- API 列表：搜 `## Rubrics API`
- 手动运行请求：搜 `POST /api/rubrics/runs`
- 评分规则口径：搜 `Rubrics 评分规则`
- trace 暴露限制：搜 `Trace 回放`

`docs/design-docs/exec-plans/module-dependency-dag-plan.md`：

- Wave 6 当前任务：搜 `Wave 6`
- rubrics 依赖边：搜 `eval --> rubrics`、`hooks --> rubrics`、`storage --> rubrics`
- 核心方法论摘要：搜 `LLM judge 做二元 PASS/FAIL`

`docs/design-docs/harness/eval.md`：

- eval 不评分：搜 `记录，不评估`
- trace 可靠性：搜 `best-effort，不是事实源`
- rubrics 读面：搜 `TraceQuery`、`离线消费`

`docs/design-docs/harness/hooks.md`：

- 任务完成事件：搜 `TASK_COMPLETED`
- Rubrics 不在线预警：搜 `关于 Rubrics 预警`
- task 派发契约：搜 `任务终态派发`

`docs/design-docs/infra/ai.md`：

- 文本模型接口：搜 `ChatModelClient`
- 多模态接口：搜 `VisionModelClient`
- storage 边界：搜 `infra/ai 不依赖 infra/storage`
- rubrics 消费契约：搜 `module/rubrics`

`docs/design-docs/infra/storage.md`：

- 对象存储接口：搜 `ObjectStorage`
- key 与 bucket 边界：搜 `StorageKeys`
- 纯 I/O 原则：搜 `纯 I/O，无业务语义`

`docs/design-docs/infra/image.md`：

- 元信息探测：搜 `ImageProbe`
- 编解码接口：搜 `ImageCodec`
- storage 边界：搜 `image 不读写 MinIO`

`docs/design-docs/module/memory.md`：

- 三类记忆：搜 `SKU 处理历史`、`分析结论记忆`
- rubrics 接缝：搜 `module/rubrics`
- 记忆强化：搜 `Rubrics / 用户反馈`
- 写入流程：搜 `InsightIngestService`

`docs/design-docs/module/task.md`：

- process_result 事实源：搜 `process_result`
- TaskCompleted 事件：搜 `TaskCompletedEvent`
- 失败隔离：搜 `失败隔离`

## Plan of Work

第一阶段建立模块壳和数据库事实源。编辑父 `pom.xml`，在 `<modules>` 和 dependencyManagement 中加入 `pixflow-module-rubrics`；新增模块 `pom.xml`，依赖 `pixflow-common`、`pixflow-eval`、`pixflow-hooks`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image`、`pixflow-module-task`、`pixflow-module-memory`、MyBatis-Plus、Jackson、Spring Boot autoconfigure/web/validation/scheduling、Micrometer。新增 `RubricsAutoConfiguration`，用 `@MapperScan("com.pixflow.module.rubrics.persistence")` 注册 mapper，用 `@EnableConfigurationProperties(RubricsProperties.class)` 绑定 `pixflow.rubrics`，并在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册。新增 `db/migration/V1__create_rubrics_tables.sql`，创建五张 rubrics 表。

第二阶段补 memory 的最小写回接缝。当前 `SkuHistoryService` 只有读取能力，而 rubrics 设计要求写回 `sku_history.rubrics_score`。在 `pixflow-module-memory` 中新增一个小 record，例如 `SkuHistoryRubricsScoreCommand(String skuId, String taskId, BigDecimal rubricsScore, String paramsJson, Map<String,Object> evidence)`，并给 `SkuHistoryService` 增加 `void appendRubricsScore(SkuHistoryRubricsScoreCommand command)`。`MybatisSkuHistoryService` 将它实现为 append-only insert；`NoopSkuHistoryService` 空实现。这样 rubrics 不直接引用 `SkuHistoryMapper`，也不破坏 memory 的召回边界。低分模式抽取则复用现有 `MemoryIngestService.ingestAsync(MemoryIngestRequest)`。

第三阶段实现模板与评分模型。新增 `template` 包，定义 `RubricTemplate`、`RubricDomain`、`RubricDimension`、`Anchor`、`VerifierSpec`、`TemplateLoader`、`TemplateRegistry`。内置模板放在 `pixflow-module-rubrics/src/main/resources/rubrics/templates/default.yaml`。加载顺序是 classpath 模板先入 registry，用户目录 `${PIXFLOW_HOME}/rubrics` 或配置项 `pixflow.rubrics.template-scan.user-home-dir` 后覆盖同 id+version 的模板。模板加载失败应 fail-fast，因为错误模板会导致评分不可解释。

第四阶段实现 verifier 与聚合器。`judge` 包实现 `LlmJudge`、`JudgePromptBuilder`、`VerdictParser`、`FewShotSampler` 和 `ImageAttachmentBuilder`。`rule` 包实现 `RuleVerifier` SPI、`ResolutionRuleVerifier`、`FormatRuleVerifier`、`FileSizeRuleVerifier`、`AlphaResidueRuleVerifier`、`BackgroundColorRuleVerifier`。`score` 包实现 `DimensionScore`、`DomainScore`、`RubricScore`、`ScoreAggregator`。聚合器必须用固定公式：PASS/HIGH=100、PASS/MEDIUM=80、PASS/LOW=60、FAIL/HIGH=0、FAIL/MEDIUM=20、FAIL/LOW=40；domain 分数是维度分数按 dimension weight 加权平均，overall 是 domain 分数按 domain weight 加权平均。LLM 返回的任何数字字段都应被忽略或视为 schema 噪声。

第五阶段实现运行编排和持久化。`run` 包实现 `EvaluationRunner`、`EvaluationRunContext`、`EvaluationItem`、`ItemEvaluator` 和 run repository。手动触发时按请求中的 `resultIds` 创建 `rubrics_run` 和 `rubrics_run_item`；事件触发时 listener 从 `TaskLifecyclePayload.taskId` 查成功的 `process_result`，创建一个默认模板 run；定时批量时查最近 24 小时成功结果。runner 逐 item 执行，单 dimension 失败可跳过该维度并记录原因，单 item 失败标 `ISOLATED`，整 run 根据成功/失败比例落 `SUCCEEDED`、`PARTIAL` 或 `FAILED`。重启时扫描 `RUNNING` run，把未 `SUCCEEDED` 的 item 重新入队，保证断点续跑。

第六阶段实现基线、回归和告警。`baseline` 包实现 `BaselineService`、`RegressionComparator`、`RegressionReport` 和 `RegressionAlertService`。基线以 run 为单位，按 `template_id` 只允许一个 active baseline。对比时只比较相同 template id 的两次 run；默认维度退化阈值是 -5，overall 退化阈值是 -10；退化维度数大于等于 2 或 overall delta 小于阈值时写 `rubrics_alert`。这一步不做 A/B 实验，不自动选择最佳基线。

第七阶段实现写回和 API。`feedback` 包实现 `ScoreFeedbackWriter` 和 `MemoryFeedbackTrigger`。每条 item 完成后写 `rubrics_score`，再调用 memory 的 `appendRubricsScore`；run 完成后统计同 SKU 连续低分/高分模式，构造 `MemoryIngestRequest` 投递到 `MemoryIngestService.ingestAsync`，categories 使用 `NEUTRAL` 或设计允许的等价类别。`api` 包实现 `RubricsAdminController`、`RubricsReportController` 和 DTO，覆盖 `docs/design-docs/api.md` 的所有 Rubrics API。controller 可以放在模块内，因为 app 启动类位于 `com.pixflow` 根包，能扫描 `com.pixflow.module.rubrics`。

第八阶段补验证与边界守护。单元测试覆盖模板加载、parser、few-shot 确定性、规则边界、聚合公式、回归阈值、run 状态迁移。集成测试用 H2 或 Testcontainers MySQL 加 fake AI / fake storage 跑一次 2-5 条结果的完整 run。ArchUnit 守护 rubrics 不被 `agent`、`harness/loop`、`harness/tools`、`harness/session`、`harness/context`、`module/dag`、`module/vision`、`module/imagegen` 引用，并且 rubrics 自身不依赖这些包。

## Concrete Steps

在仓库根目录 `D:\study\PixFlow` 执行。

1. 新增 Maven 模块。

   编辑 `pom.xml`，在 `<modules>` 加入：

       <module>pixflow-module-rubrics</module>

   在 dependencyManagement 加入 `pixflow-module-rubrics`。新建 `pixflow-module-rubrics/pom.xml`，依赖本计划 “Plan of Work” 第一阶段列出的模块与库。

   预期结果：

       mvn -pl pixflow-module-rubrics -am test
       [INFO] Building PixFlow Module Rubrics

2. 新增基础包结构和 AutoConfiguration。

   在 `pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics` 下建立：

       api/
       baseline/
       config/
       error/
       feedback/
       judge/
       persistence/
       rule/
       run/
       score/
       template/

   新增 `RubricsAutoConfiguration`、`RubricsProperties`、`RubricsService` 和 AutoConfiguration imports 文件。

3. 新增数据库迁移。

   创建 `pixflow-module-rubrics/src/main/resources/db/migration/V1__create_rubrics_tables.sql`，至少包含：

       rubrics_run(id, template_id, template_version, trigger_type, baseline_run_id, status, stats_json, started_at, finished_at, created_at)
       rubrics_run_item(run_id, result_id, status, error_msg, updated_at)
       rubrics_score(id, result_id, task_id, run_id, template_id, template_version, overall_score, image_score, copy_score, decision_score, dimension_scores_json, explanation_json, alert_flag, created_at)
       rubrics_baseline(id, name, run_id, template_id, template_version, active, created_at)
       rubrics_alert(id, run_id, baseline_run_id, template_id, severity, message, dimension_key, delta_value, acknowledged, created_at)

   `rubrics_score.result_id` 应有唯一索引；`rubrics_run_item` 应有 `(run_id, result_id)` 唯一索引。

4. 扩展 memory 写回接口。

   在 `pixflow-module-memory` 新增 command record，并扩展 `SkuHistoryService`。修改 `MybatisSkuHistoryService` append-only insert；修改 `NoopSkuHistoryService` 空实现。运行：

       mvn -pl pixflow-module-memory -am test

5. 实现模板加载与默认模板。

   添加 `rubrics/templates/default.yaml`，至少覆盖 `IMAGE_QUALITY`、`COPY_QUALITY`、`DECISION_QUALITY` 三个 domain。先保证规则维度和 LLM 维度都出现，方便测试 runner 双路径。

6. 实现 judge、rule 和 score。

   `VerdictParser` 必须能解析正常 JSON、markdown 代码块包裹 JSON、大小写有偏差的 enum；缺字段或 enum 非法应抛 rubrics 域异常。`ScoreAggregatorTest` 应先写，因为它锁定核心方法论。

7. 实现 runner、hook listener 和 scheduler。

   `RubricsTaskCompletedHook` 实现 `HookCallback`，`supportedEvents()` 返回 `TASK_COMPLETED`。`handle` 方法只校验配置和 task id，然后提交到 rubrics executor 并返回 `HookResult.empty()` 或等价非阻断结果。不得在 hook 线程内调用模型。

8. 实现 API。

   API 路径必须和 `docs/design-docs/api.md` 对齐：

       GET  /api/rubrics/templates
       GET  /api/rubrics/templates/{id}/versions
       POST /api/rubrics/runs
       GET  /api/rubrics/runs
       GET  /api/rubrics/runs/{id}
       GET  /api/rubrics/runs/{id}/regression?baselineRunId=...
       GET  /api/rubrics/scores/by-result/{resultId}
       GET  /api/rubrics/scores/by-sku/{skuId}
       GET  /api/rubrics/baselines
       POST /api/rubrics/baselines
       GET  /api/rubrics/alerts

9. 验证模块和 app 接线。

   运行：

       mvn -pl pixflow-module-rubrics -am test
       mvn -pl pixflow-app -am -DskipTests package

   若有 Docker 可用，再运行包含 MySQL/MinIO 的集成测试 profile；若 Docker 不可用，集成测试应自动跳过并在输出中说明跳过原因。

## Validation and Acceptance

验收标准是可观察行为，而不只是编译通过。

- 手动运行：向 `POST /api/rubrics/runs` 发送 `templateId`、`templateVersion` 和两个成功 `process_result.id` 后，接口返回 `runId`；查询 `GET /api/rubrics/runs/{id}` 能看到总数、成功数、失败/隔离数和状态推进。
- 评分落库：run 完成后，`rubrics_score` 对每个成功 item 有一行，包含 `overall_score`、三个 domain score、`dimension_scores_json` 和 `explanation_json`。
- 程序计分：构造固定 verdict 的测试中，LLM 输出不含任何数值，`ScoreAggregator` 仍按权重算出确定值；如果 LLM 响应包含 `"score": 73`，该字段不影响结果。
- 规则验证：给 `ResolutionRuleVerifier` 输入 799x800 且模板要求 minWidth=800，应返回 FAIL/HIGH，理由包含实际宽度和阈值；给 800x800 应返回 PASS/HIGH。
- 图片读取：图片维度评分从 `process_result.output_minio_key` 读取对象字节，缺对象时该 item 标 `ISOLATED`，不影响其他 item。
- trace 缺失：决策质量依赖 trace 的维度在 trace 缺失时跳过或低置信失败，但图片/文案评分仍可完成。
- 事件触发：派发 `TASK_COMPLETED` payload 且配置 `pixflow.rubrics.event-trigger.enabled=true` 时，系统异步创建 run；关闭配置时不创建 run。
- 基线回归：指定一个历史 run 为基线后，当前 run 与基线对比返回 per-dimension delta；overall 下降超过阈值时 `rubrics_alert` 有记录。
- memory 写回：评分完成后，相关 SKU 的 `sku_history` 新增或更新可召回记录，`rubrics_score` 字段有值；run 完成后满足连续低分条件时，`MemoryIngestService.ingestAsync` 被调用一次。
- API 安全：Rubrics API 只返回评分摘要、证据引用和必要解释，不暴露原始 `agent_trace` 全量 JSON。

最低测试命令：

    cd D:\study\PixFlow
    mvn -pl pixflow-module-rubrics -am test
    mvn -pl pixflow-module-memory -am test
    mvn -pl pixflow-app -am -DskipTests package

预期输出是 Maven build success。新增测试名至少包括：

    ScoreAggregatorTest
    VerdictParserTest
    TemplateLoaderTest
    RuleVerifierTest
    EvaluationRunnerTest
    RegressionComparatorTest
    RubricsArchitectureTest

## Idempotence and Recovery

本计划的实现应尽量可重复执行。

- `rubrics_run_item` 以 `(run_id, result_id)` 唯一约束作为断点，重复启动同一 run 时跳过 `SUCCEEDED` item。
- `rubrics_score.result_id` 唯一，重复评分同一 `process_result` 时默认 upsert 同一评分行，保留最新 `run_id` 与模板版本；如果后续要保留多版本历史，必须先改设计文档。
- 模板加载不修改模板文件，历史评分保存 `template_id` 和 `template_version`，模板升级不重算历史。
- hook listener 只异步入队，失败时记录 rubrics error 和指标，不影响 task 终态。
- memory 写回采用 append-only 或幂等 update，不删除历史 `sku_history`。
- 若集成测试需要 Docker 而当前机器没有 Docker，测试应按现有项目策略跳过，不应失败整个单元测试套件。
- 如果迁移执行一半失败，删除新建的 rubrics 表后可重跑迁移；不要修改或删除 `process_result`、`sku_history`、`analysis_insight`、`agent_trace` 的既有数据。

## Artifacts and Notes

本计划预计新增或修改的关键文件：

    pom.xml
    pixflow-module-rubrics/pom.xml
    pixflow-module-rubrics/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    pixflow-module-rubrics/src/main/resources/db/migration/V1__create_rubrics_tables.sql
    pixflow-module-rubrics/src/main/resources/rubrics/templates/default.yaml
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/config/RubricsAutoConfiguration.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/config/RubricsProperties.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/RubricsService.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/run/EvaluationRunner.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/judge/LlmJudge.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/rule/RuleVerifier.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/score/ScoreAggregator.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/baseline/RegressionComparator.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/feedback/ScoreFeedbackWriter.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/api/RubricsAdminController.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/api/RubricsReportController.java
    pixflow-module-memory/src/main/java/com/pixflow/module/memory/skuhistory/SkuHistoryService.java
    pixflow-module-memory/src/main/java/com/pixflow/module/memory/skuhistory/MybatisSkuHistoryService.java
    pixflow-module-memory/src/main/java/com/pixflow/module/memory/skuhistory/NoopSkuHistoryService.java

实现时不要修改 `harness/eval` 的写入语义，不要让 `harness/hooks` 依赖 rubrics，不要给 `harness/tools` 增加 rubrics 工具，不要让 `agent` 直接依赖 rubrics。

## Interfaces and Dependencies

`RubricsService` 对外至少提供：

    UUID runEvaluation(RunEvaluationCommand command);
    RubricScoreDetail latestScoreByResult(Long resultId);
    RubricsRunDetail getRun(UUID runId);
    RegressionReport regress(UUID currentRunId, UUID baselineRunId);

`RunEvaluationCommand` 至少包含：

    String templateId;
    String templateVersion;
    List<Long> resultIds;
    UUID baselineRunId;
    RubricsTrigger trigger;

`JudgeVerdict` 必须是不可变 record：

    Verdict verdict;          // PASS 或 FAIL
    Confidence confidence;    // HIGH、MEDIUM、LOW
    String rationale;         // 1-2 句理由
    List<EvidenceRef> evidence;

`RuleVerifier` 必须是同步、纯 Java 接口：

    String ruleClass();
    RuleCheckResult verify(RuleCheckInput input);

`ScoreAggregator` 必须不依赖 Spring，不调用数据库，不调用模型，便于纯单元测试。它只接收 template 和 dimension results，返回程序聚合出的 domain/overall score。

`RubricsTaskCompletedHook` 必须实现：

    HookCallback.supportedEvents() -> Set.of(HookEvent.TASK_COMPLETED)
    HookCallback.handle(...) -> 非阻断 HookResult

Memory 最小新增接口：

    void appendRubricsScore(SkuHistoryRubricsScoreCommand command);

如果实现时发现 `sku_history` 需要“更新已有 task+sku 行”而不是 append-only insert，必须在 `Decision Log` 记录原因，并保持幂等。

## Revision Notes

2026-07-01 / Codex: 新建 `rubrics-module-implementation-plan.md`。本计划按 `PLANS.md` 格式把 `docs/design-docs/module/rubrics.md` 的 Wave 6 设计落为可执行步骤，明确实现机制是本地 YAML 模板、LLM PASS/FAIL judge、确定性规则验证器、程序聚合器、run/item 断点续跑、基线回归、memory 三层写回和 REST 查询；同时记录参考文档检索关键词，便于后续执行者快速定位设计文本。
