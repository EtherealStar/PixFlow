# 将 Rubrics 彻底重构为证据约束的离线评估深模块

本 ExecPlan 是一个 living document。实施过程中必须持续更新 `Progress`、`Surprises & Discoveries`、`Decision Log` 和 `Outcomes & Retrospective`。本文遵循仓库根目录的 `PLANS.md`；执行者只依赖当前工作树和本文，也应能完成重构、验证结果并在中断后恢复工作。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有一个真正离线、证据约束、可恢复和可回放的 Rubrics 评估模块。它分别评估 `IMAGE_RESULT`、`COPY_RESULT` 和 `TASK_DECISION`，每个原子 Criterion 只产生 `PASS`、`FAIL`、`INCONCLUSIVE` 或 `NOT_APPLICABLE`，系统用 Hard Rule 形成 Quality Gate，用 Principle 的非加权通过率和 coverage 提供透明统计。缺失证据、解析错误、provider 故障或 judge 无多数意见都不会伪装成质量失败。

管理员或离线作业可以用一个版本化模板和一组显式 Subject，或一个不可变 Evaluation Dataset，创建并恢复 Evaluation Run。结果能够还原 Subject snapshot、模板、evaluator、Evidence Pack、每次 rollout 和最终 Criterion Verdict；正式回归只在相同 Dataset 版本和相同 Subject snapshot 上做成对比较。Rubrics 不进入 Agent 主循环，不影响 Task 终态，不提供浏览器 Evaluation Center，也不把 verdict、alert 或人工判断写入 Memory。

本次是开发期一次性替换，不是兼容迁移。旧标量评分、旧 `rubrics_score`、旧 baseline/alert 语义、`rubrics_promotion`、旧 HTTP Controller、前端 Rubrics 占位页面以及只验证旧内部类形状的测试全部删除。数据库使用新的 Rubrics fresh-schema 基线重建；不保留 legacy reader、双写、wrapper、nullable fallback、旧 route 或旧表只读兼容。执行者不得为了让旧测试继续通过而保留旧生产代码。

## Progress

- [x] (2026-07-19 13:31+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和 `docs/design-docs/exec-plans/` 下全部当前执行计划，确认本计划属于父计划 Milestone 6，并记录与活动 lint 计划的文件所有权关系。
- [x] (2026-07-19 13:31+08:00) 阅读 Rubrics 权威设计、Context Map、模块 `CONTEXT.md`、模块 ADR、completed 历史计划和当前生产代码、测试、migration、前端残留及 Checkstyle suppression。
- [x] (2026-07-19 13:31+08:00) 创建本彻底重构 ExecPlan，固定无兼容替换、深模块 interface、三类 Subject、Evidence Pack、可恢复运行、dataset/calibration/regression、离线自动化及 lint-first 验证机制。
- [x] (2026-07-19 15:02+08:00) Milestone 0：冻结 `RubricsEvaluationService.start/resume/get`、类型化 request/selection/run identity 和 bounded view；删除后端 `RubricsController` 与旧 public command/view，修正 ADR 的 Memory Promotion 冲突。旧 coordinator 仅由私有 adapter 隔离，待可恢复引擎切片整体删除；Rubrics 静态门禁和 23 项测试通过。
- [ ] Milestone 1：重写模板、Criterion、Evidence 与 judge 纯领域核心。
- [ ] Milestone 2：用 fresh-schema 重建持久事实和可恢复 Evaluation Run。
- [x] (2026-07-19 15:02+08:00) Milestone 1/2 后端基础切片：严格 YAML 已拒绝 unknown/duplicate key；Evidence Pack identity 已绑定 Subject snapshot 与 canonical metadata并排除 `capturedAt`，失败分类不再压平；旧 V1/V2、scalar score、Promotion、旧 baseline/alert entity 已由 fresh schema 替换。真实 MySQL 8.4 schema 与 claim epoch/lease takeover 两项容器测试均零 skip；完整可恢复 run engine 尚未接管旧 coordinator，因此 Milestone 1/2 保持未完成。
- [x] (2026-07-19 15:46+08:00) Milestone 2/3/4 后端纵向切片：`DefaultRubricsEvaluationService` 已直接实现 `start/resume/get`，删除临时 adapter、legacy command/view 与旧 coordinator；run/item admission、epoch/lease claim、evaluation/criterion/rollout 与 terminal checkpoint 的 fenced transaction 已接通。公共 interface 的真实 MySQL 测试覆盖显式 Image run、bounded view、不可变 Dataset、snapshot identity 和同 Dataset formal regression；模块 25 项测试零失败、零错误、零跳过。heartbeat、crash-window rollback、Gold Label/validation/report、Copy/Decision 与组合根仍未完成，所以对应 Milestone 保持未勾选。
- [x] (2026-07-19 16:14+08:00) 完成当前切片双轴审查与修复：non-replayable Dataset item 不再从 run/分母消失；fenced commit 追加 runId/templateHash/evaluatorVersion 校验并在同一 MySQL multi-table update 固化 evaluator；瞬时 runtime/persistence 错误进入 `FAILED_RETRYABLE`；formal baseline 限定同 Dataset 的成功 FORMAL_REGRESSION run 并逐 item 配对 snapshot。删除已移除或已格式化 Rubrics 文件的旧 suppression，Rubrics suppression 从本轮开始时 118 降到 65；干净静态门禁和 25 项完整测试再次全绿。
- [x] (2026-07-19 16:32+08:00) 收口 non-replayable Dataset 投影与验证：admission 保留 item 并记录安全 replay error code，item 直接成为 `PARTIAL`，run 即使没有可 claim item 也从 item facts 重算为 `PARTIAL`，report 单列 `nonReplayableCount` 且 failure count 保持为零。真实 MySQL 定向测试 5 项、Rubrics 模块完整测试 26 项全部零失败、零错误、零跳过；修改后的干净静态门禁为零 Checkstyle violation、零 SpotBugs finding。
- [ ] Milestone 3：完成 `IMAGE_RESULT` 纵向切片及真实对象证据验证。
- [ ] Milestone 4：完成 Evaluation Dataset、Gold Label、校准、成对回归、baseline 与 Rubrics-owned alert。
- [ ] Milestone 5：完成 `COPY_RESULT` 与 `TASK_DECISION`，接入 Task public outcome 和 Eval read interface。
- [ ] Milestone 6：完成 lifecycle-gated 离线自动化、组合根、观测与故障恢复。
- [ ] Milestone 7：删除全部旧代码、前端残留和 Rubrics suppression，完成 lint-first 全仓验收。

## Surprises & Discoveries

- Observation: 新深接口落地时，旧 `EvaluationRunCoordinator` 依赖精确行号 Checkstyle suppression；直接改其方法签名会让 155 条历史格式问题重新暴露，并把 Milestone 0 的接口冻结与运行引擎重写混为一个切片。
  Evidence: 首次 Rubrics reactor 在 `EvaluationRunCoordinator.java` 报 155 条 Checkstyle violation；改为由 `RubricsEvaluationServiceAdapter` 隔离旧引擎后，Rubrics `-DskipTests verify` 为零 violation、零 SpotBugs finding。

- Observation: 用户本次明确要求“不做前端”，与 Milestone 7 原定删除 Web Rubrics surface 的范围不一致。
  Evidence: 2026-07-19 实施请求明确写明“不要做前端”。因此本次执行不修改 `pixflow-web`；后端完成判定与全计划完成判定将分别记录，前端清理留给后续明确授权的工作。

- Observation: provider-neutral `ChatResult` 只返回文本、工具调用、stop reason 与 token usage，没有实际响应 model revision；Rubrics 当前只能锁定 `ModelRouter` 解析出的 provider/model 配置。
  Evidence: `RepeatedLlmCriterionVerifier` 可记录 resolved provider/model、prompt hash、parser version 和 rollout policy，但 `ChatResult` 没有 response model identity。当前切片不伪造 revision；若需要满足完整 provenance，必须在 infra/ai 另行增加 provider-neutral 返回字段。

- Observation: 首轮双轴审查发现临时 adapter 丢弃 purpose/baseline、公共 view 泄漏 implementation status、终态 item 可被 claim，以及 Evidence 失败被压平和 generic hash 依赖 Image resolver。
  Evidence: 修正后 `RubricsEvaluationServiceAdapterTest` 证明 `PRODUCTION_SAMPLE` purpose 保留；claim 容器测试证明 `SUCCEEDED` 即使 lease 过期也不可重领；`ImageEvidencePackBuilderTest` 区分 identity mismatch 与 transient storage failure；公共 `EvaluationRunView` 只引用 API/model 类型。

- Observation: 当前 Rubrics 不是一个深模块。`EvaluationRunCoordinator` 同时知道模板注册、Subject 解析、Evidence 构建、rule、LLM rollout、汇总、MyBatis entity、JSON 反序列化和 HTTP view，调用者和测试无法通过一个稳定 interface 验证完整行为。
  Evidence: `pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/run/EvaluationRunCoordinator.java` 直接依赖 13 个实现对象和 5 个 mapper，并在单个类中实现 start、resume、evaluate 和 read projection。

- Observation: 当前实现只有 `IMAGE_RESULT` 的半条路径，dataset 只建表不执行，`COPY_RESULT` 和 `TASK_DECISION` 被硬编码拒绝，且没有 coordinator、transaction、MySQL resume、dataset 或 regression 测试。
  Evidence: `EvaluationRunCoordinator.evaluate` 对非 `IMAGE_RESULT` 抛出 `subject adapter is not available`；`start` 对 `datasetId != null` 抛出异常；Rubrics 当前只有 7 个测试文件。

- Observation: 当前 Task public outcome 只有 `SuccessfulResultSnapshot` 图像结果，不存在稳定 Copy Artifact 或 confirmed decision revision；直接实现另外两类 Subject 会被迫读取临时 Proposal、Conversation 或 persistence implementation。
  Evidence: `pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskOutcomeQuery.java` 只暴露 `successfulResult`/`successfulResults`，其 record 只描述 published image output。本轮因此没有伪造 `COPY_RESULT` 或 `TASK_DECISION`，Milestone 5 保持未完成。

- Observation: 单模块 Maven 的增量编译曾报告 `Nothing to compile`，导致刚写入的红灯测试直到 Surefire 运行期才暴露缺失方法；干净构建会正确重编译全部源码。
  Evidence: 首次 `mvn -pl pixflow-module-rubrics -Dtest=RunItemClaimRepositoryIntegrationTest test` 在 Surefire 中报告 unresolved compilation problem；随后 `clean -DskipTests verify` 明确编译 81 个 main source 和 14 个 test source。

- Observation: MySQL multi-table `UPDATE` 在同时改变 `rubrics_run_item` 与 `rubrics_run` 时会返回 2 个 affected rows，不能继续用 `updated == 1` 判断 fence 是否命中。
  Evidence: 首次追加 evaluator identity 的定向测试中 item 和 run 均已更新，但 repository 返回 false；改为 `updated > 0` 后两个真实 MySQL 测试类 4 项全绿，旧 epoch 的第二次提交仍返回 false。

- Observation: 当前 public interface 无法引导第一个 formal baseline。`FORMAL_REGRESSION` admission 必须引用一个成功的 formal run，但 public request 没有创建首个已授权 formal baseline 的路径。
  Evidence: 当前真实 MySQL 测试只能先完成 calibration run，再用测试 SQL 把其 purpose 固化为 `FORMAL_REGRESSION` 后作为 baseline；该手段只构造测试前置事实，不代表生产 authority。Milestone 4 必须补齐 bootstrap/authority 设计后才能标记完成。

- Observation: 当前 V2 migration 仍创建已被最新设计明确删除的 `rubrics_promotion`，而模块 ADR 的 Consequences 最后一句仍声称人工可提升到 Memory。权威模块设计、总体设计和父计划则要求 Rubrics 与 Memory 零依赖、没有 Promotion。
  Evidence: `V2__migrate_rubrics_to_criterion_verdicts.sql` 创建 `rubrics_promotion`；`docs/design-docs/module/rubrics.md` §11、`docs/design-docs/design.md` §11.6 和父计划 Decision Log 明确否定任何写回。

- Observation: 当前 `ImageEvidencePackBuilder` 把任意读取、身份或解码异常吞成一个空 Evidence Pack，丢失可区分的 non-replayable 与 transient failure 语义；judge 只用预解析的 model identity，无法证明实际调用与锁定 evaluator 完全一致。
  Evidence: builder 捕获 `Exception` 后只保存异常类名；rollout 没有独立 error detail、request identity 或实际响应 model revision。

- Observation: 目标设计说浏览器没有 Rubrics 页面，但前端仍注册 `/rubrics`、左侧导航、Pinia store 和占位 API；模块同时直接暴露未定义安全策略的 `/api/rubrics` Controller。
  Evidence: `pixflow-web/src/router/index.ts`、`pixflow-web/src/components/layout/LeftPanel.vue`、`pixflow-web/src/stores/rubrics.ts`、`pixflow-web/src/api/rubrics.ts` 和 `RubricsController.java` 仍存在。

- Observation: Rubrics 仍依赖 118 条精确 Checkstyle suppression。若只在最后格式化，会让重写期间的真实静态问题继续被隐藏，并与活动 lint 计划发生文件所有权冲突。
  Evidence: `rg -n "pixflow-module-rubrics" config/checkstyle/suppressions.xml` 当前返回 118 行；SpotBugs filter 当前没有 Rubrics 命中。

## Decision Log

- Decision: Milestone 0 使用私有 `RubricsEvaluationServiceAdapter` 暂时隔离旧 coordinator，只把三方法深接口暴露给调用者；adapter 和旧引擎必须在 Milestone 2/可恢复运行引擎落地时一起删除，不能成为长期兼容层。
  Rationale: 这使接口契约、调用方迁移和 Controller 删除可以独立验证，同时避免为了维持精确 suppression 在同一切片重写持久化与 judge 行为。
  Date/Author: 2026-07-19 / Codex

- Decision: 本次执行严格排除 `pixflow-web` 修改，即使原计划 Milestone 7 包含前端 route/nav/store/api 删除。
  Rationale: 用户对本次授权范围给出了比 ExecPlan 更具体的新限制；后端不能借“完整执行计划”扩大到被明确排除的前端工作。
  Date/Author: 2026-07-19 / Codex

- Decision: 按用户后续要求，后续验证限定为 `pixflow-module-rubrics` 模块静态门禁、定向测试与必要的真实 MySQL/MinIO 测试，不运行全仓或跨模块 reactor。
  Rationale: 本轮工作树同时承载其他活动计划的大量未提交改动；模块级证据足以验证当前 Rubrics-owned 切片，同时避免把范围外基线误算为本计划结果。
  Date/Author: 2026-07-19 / Codex

- Decision: 使用开发期一次性替换，删除旧 V1/V2 Rubrics migration 并建立只含新事实的 fresh-schema 基线；旧 Rubrics 数据和 Flyway 历史不迁移。
  Rationale: 用户明确要求重构性修改且不为兼容保留旧代码。保留 `rubrics_score`、Promotion、旧 nullable 列或 legacy reader 会继续维持两套领域真相。实施前只确认目标是可重建的 PixFlow 开发数据库；有价值数据由操作者另行导出，但不会驱动兼容设计。
  Date/Author: 2026-07-19 / Codex

- Decision: Rubrics 对外只暴露一个深模块 interface `RubricsEvaluationService`，包含 `start`、`resume` 和 `get` 三个用例。模板查找、Subject dispatch、Evidence Pack、rollout、持久化、dataset、校准、回归、告警和 read projection 都是 implementation，不向调用者暴露 mapper、entity 或内部策略。
  Rationale: 这为调用者提供高 leverage，为维护者提供 locality；测试也可通过同一个 interface 覆盖真实行为。现有 controller 的模板 CRUD、evaluation history 和 mapper-shaped view 会被删除，不作为新 interface 的兼容目标。
  Date/Author: 2026-07-19 / Codex

- Decision: `EvaluationRunRequest` 用类型化 `RunSelection` 表达 `ExplicitSubjects` 或 `DatasetSelection`，用 `RunPurpose` 表达 `CALIBRATION`、`FORMAL_REGRESSION`、`MANUAL_INSPECTION` 或 `PRODUCTION_SAMPLE`；不使用一组可互相矛盾的 nullable 字段。
  Rationale: 当前 `RunEvaluationCommand` 同时携带 dataset 与 subjectIds 并靠运行时 if 拒绝，interface 把无效状态暴露给所有调用者。sealed selection 在构造时即可保证互斥和完整。
  Date/Author: 2026-07-19 / Codex

- Decision: 模板文件的 lifecycle 声明不是自动化许可的唯一事实。有效 lifecycle 由不可变模板 release 与该模板/evaluator 在 holdout Dataset 上的最新达标 Validation Report 共同决定。
  Rationale: 仅把 YAML 改成 `PRODUCTION` 不能绕过 gold-set 校准。EXPERIMENTAL 只能手动或校准；VALIDATED/PRODUCTION 仍须显式 automation binding。
  Date/Author: 2026-07-19 / Codex

- Decision: 每个 run item 使用 MySQL claim epoch 和 lease/heartbeat 做持久所有权；provider 调用在事务外，最终 evaluation、criterion results、rollouts 和 item terminal checkpoint 在一个 fenced transaction 中提交。
  Rationale: Redis lock 不是离线评估事实源。进程在 provider 调用后、提交前崩溃可能消费第二次模型额度，但不能留下半套事实或让旧 worker 覆盖恢复 worker。提交成功后唯一键和 terminal checkpoint 使 resume 直接跳过。
  Date/Author: 2026-07-19 / Codex

- Decision: Evidence Pack 的 identity 只由 Subject snapshot identity 和按稳定顺序排列的 entry id/type/sourceRef/contentHash/metadataHash 组成；`capturedAt` 记录审计时间但不进入 hash。
  Rationale: replay identity 必须对相同证据稳定，不能因为重跑时间不同而变化。底层字节或 canonical metadata 变化则必须改变 entry hash 和 pack hash。
  Date/Author: 2026-07-19 / Codex

- Decision: `COPY_RESULT` 和 `TASK_DECISION` 只消费 Task 属主公开的 immutable outcome/decision snapshot，以及 Eval 的 `TraceQuery`/`TraceReplay`；Rubrics 不 import Task mapper/entity，也不依赖 Conversation、File、Vision、Memory、Agent、Loop、Tools 或 Hooks。
  Rationale: Subject 的业务身份归产出它的上下文，Rubrics 只做离线判断。Task Decision 的 trace 是 best-effort evidence，缺失或过期产生 `INCONCLUSIVE(MISSING_EVIDENCE)` 或 non-replayable dataset item，不能反向改变 Task。
  Date/Author: 2026-07-19 / Codex

- Decision: 删除旧内部类单元测试，并在新深模块 interface 上重建行为测试；仅为纯算法保留少量 value-level tests。
  Rationale: 根据 replace-don't-layer 原则，继续维护针对旧 coordinator、mapper 或 builder 形状的测试会锁死 implementation。新的 interface 测试应在内部重排时保持不变。
  Date/Author: 2026-07-19 / Codex

- Decision: 所有测试前必须先通过相同切片的 lint/static gate。后端顺序固定为 `mvn ... -DskipTests verify` 后 `mvn ... test`；前端顺序固定为 lint、typecheck、test、build。
  Rationale: 用户明确要求测试前先做 linting，活动 lint 计划也要求 Maven reactor 串行运行。静态门禁失败时运行测试只会混淆首个失败原因。
  Date/Author: 2026-07-19 / Codex

- Decision: 删除临时 `RubricsEvaluationServiceAdapter`、`LegacyRunCommand`、`LegacyRunView` 和 `EvaluationRunCoordinator`，由 `DefaultRubricsEvaluationService` 直接承担唯一 external interface；mapper、claim、模板、Evidence 和 judge 全部留在 implementation。
  Rationale: 旧结构只是把宽 coordinator 包在新 interface 外，未通过 deletion test。直接接管后调用者与集成测试只学习 `start/resume/get`，而恢复、持久化和评估复杂度集中在模块内部。
  Date/Author: 2026-07-19 / Codex

- Decision: Dataset admission 在创建 run item 时固化 manifest 的 `subjectSnapshotHash`，最终 evaluation 使用同一个 claim fence 校验实际 snapshot；formal regression baseline 必须是相同 Dataset ID/version 的成功 run。
  Rationale: 只比较 subject ID 会让底层产物变化后仍伪装成成对回归。把预期 snapshot 放入 durable item，使迟到、错配和不可回放都无法提交成功事实。
  Date/Author: 2026-07-19 / Codex

- Decision: fenced evaluation 使用 MySQL multi-table update 同时校验 item/run identity，并只在 run 的 evaluator 尚未固定或与当前 evaluator 相同时提交；不可恢复的 Subject/schema 输入进入 `FAILED`，其余 runtime/persistence 异常进入 `FAILED_RETRYABLE`。
  Rationale: item-only fence 无法阻止错误 template/evaluator 的内部调用写入不一致事实；把所有 RuntimeException 都终结为 FAILED 又会破坏恢复语义。新的分类保留事实一致性和 transient retry。
  Date/Author: 2026-07-19 / Codex

## Outcomes & Retrospective

Milestone 0 已完成。调用方现在只看见 `RubricsEvaluationService` 的 `start(EvaluationRunRequest)`、`resume(EvaluationRunId)` 和 `get(EvaluationRunId)`；显式 Subject 与 Dataset 选择由 sealed 类型互斥表达，正式回归在构造请求时要求 Dataset 和 baseline identity。后端 end-user Controller 已删除，TaskCompleted listener 已迁移到类型化请求。旧 coordinator 尚未重写，它被私有 adapter 隔离并明确列入后续删除边界，不能把这一阶段误记为可恢复运行引擎完成。

Milestone 1/2 已形成可验证基础但尚未完成。模板和 Evidence identity 的关键不变量已经由行为测试固定；fresh schema 已在真实 MySQL 8.4 创建成功；`RunItemClaimRepository` 证明 lease 过期接管会推进 epoch，旧 epoch 无法 heartbeat 或 finish。该 repository 尚未接入旧 coordinator，Dataset/Gold Label 也只有 schema 而没有深接口行为，因此不能把数据库存在误记为完整离线评估能力。

双轴代码审查的首轮 hard findings 已在本切片内修复。run provenance 不再依赖 schema 默认值；`PARTIAL`、`FAILED`、`SUCCEEDED` 均作为终态不被 claim，只有 `PENDING`、`FAILED_RETRYABLE` 或 lease stale 的 `RUNNING` 可恢复；Evidence failure 现在具有稳定 kind/code，generic hashing 留在 evidence package。公共 view 已预留 bounded item projection、truncation 和 purpose report，但旧 coordinator 目前只能返回空且 `complete=false` 的 report，这仍属于后续 run engine 缺口。

本轮最终模块级证据为 `mvn -pl pixflow-module-rubrics -DskipTests verify` 成功，Checkstyle 与 SpotBugs 均为零；`mvn -pl pixflow-module-rubrics test` 执行 23 项测试，零失败、零错误、零跳过，其中 fresh schema 和 claim fencing 使用真实 MySQL 8.4 Testcontainers。按用户要求未运行全仓或跨模块 reactor，也未修改前端。

后续 Run Engine/Dataset 切片把上述 23 项扩展为 25 项并保持零失败、零错误、零跳过。`RubricsEvaluationService` 现在直接创建 durable run/item、同步 claim 并通过 fenced transaction 写入 evaluation/criterion/rollout/checkpoint；`get` 返回最多 100 个 item 的 bounded projection。真实 MySQL 测试证明显式 Image run、Dataset run 和同 Dataset formal regression 可以通过同一个 external interface 完成。尚未完成 heartbeat 与 crash-window rollback 故障矩阵、Gold Label/validation/regression report/alert、Copy/Decision Subject、强类型自动化与 App 组合根，因此不能把专项计划或父计划 Milestone 6 标记完成。

双轴审查的主要规格问题已经修复并复验：non-replayable item 保留为带安全原因的 `PARTIAL` terminal item，并在 report 中单列而不混入 failure；commit 同时 fence run/template/evaluator/snapshot；transient error 保留恢复资格；formal baseline 逐项验证 snapshot。Standards 审查指出的 stale Outcomes 文本与本轮触达文件旧 suppression 也已修正。最终证据为修改后的干净 `-DskipTests verify` 零 Checkstyle/SpotBugs、两个真实 MySQL 测试类 5 项全绿，以及完整 26 项测试零失败、零错误、零跳过；剩余 65 条 suppression 属于尚未重写/格式化的旧 Rubrics 文件，Milestone 7 保持未完成。

Milestone 4 仍有一个明确的 authority 缺口：当前 external interface 要求 formal regression 在 admission 时已有成功的 formal baseline，却没有建立第一个 formal baseline 的公开工作流。集成测试通过 SQL 构造该前置事实只用于验证同 Dataset、成功状态和 snapshot pairing 约束，不能视为生产 bootstrap。后续必须先决定由哪一个 Rubrics-owned 校准/发布动作授予首个 baseline，再实现对应持久事实与接口行为。

当前已经修改 Rubrics 生产行为并完成 Image/Dataset/Run Engine 的后端纵向切片，但完整离线评估机制仍未收口。剩余工作必须继续以真实 MySQL/MinIO、fake OpenAI-compatible judge、Task/Eval contract tests 和负向架构扫描证明 heartbeat/crash recovery、Gold Label/validation、Copy/Decision 与组合根不会污染在线上下文；在所有验收完成前不得把父计划 Milestone 6 标记为完成。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。Rubrics 生产模块位于 `pixflow-module-rubrics`，由 `pixflow-app/pom.xml` 引入。`pixflow-module-task` 拥有任务终态、成功输出和确认后的执行决定；`pixflow-eval` 拥有 best-effort Agent trace 的只读查询与回放；`pixflow-infra-ai` 提供 provider-neutral judge 调用和逻辑 model role；`pixflow-infra-storage` 提供对象字节；`pixflow-infra-image` 提供受 Pixel Budget 约束的 probe/decode。Rubrics 只能消费这些公开 interface，不得穿透其 persistence implementation。

执行任何 Milestone 前，必须先阅读以下共同参考，并重新检查其 Revision Notes：

- `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`；
- `docs/design-docs/exec-plans/` 下当时全部活动计划，尤其 `backend-architecture-alignment-refactor-plan.md` 和 `lint-baseline-remediation-plan.md`；
- `docs/design-docs/design.md` 的 §5.6、§7、§11、§13 和 §15；
- `docs/design-docs/module/rubrics.md`；
- `CONTEXT-MAP.md`、`pixflow-module-rubrics/CONTEXT.md`、`pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md`；
- `docs/agents/domain.md`。

Rubrics 的核心领域词必须采用模块 `CONTEXT.md`：Evaluation Subject 是被判断的单一产物或决定；Rubric Template 是人工批准、版本化且只绑定一个 Subject Type 的 criterion 集合；Criterion 是一个可独立判断的命题；Hard Rule 是失败即阻止 Quality Gate 通过的显式要求；Principle 是二元质量期望，不产生部分分；Evidence Pack 是系统生成的不可变证据集合；Criterion Verdict 是四态结果；Evaluation Dataset 是可回放的版本化 Subject manifest；Gold Label 是人工裁定标签；Evaluation Alert 是 Rubrics 自有信号。

当前代码有几条必须被替换的浅路径。`RubricsController` 直接暴露模板和运行内部形状；`EvaluationRunCoordinator` 同时承担所有职责；`ImageEvidencePackBuilder` 把所有失败压成空 pack；`EvaluationPersistence` 逐行拼 entity 并在 implementation 外暴露多个 mapper；`RubricsProperties` 用宽泛 nullable 字符串表达自动化；V1/V2 migration 同时保存标量旧世界、四态新世界和 Promotion；前端仍假装存在 Evaluation Center。这些文件不是新设计的兼容基础，只是删除清单。

## Target Mechanism and Module Shape

外部 seam 位于 `pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/api/`。最终 interface 等价于：

    public interface RubricsEvaluationService {
        EvaluationRunView start(EvaluationRunRequest request);
        EvaluationRunView resume(EvaluationRunId runId);
        EvaluationRunView get(EvaluationRunId runId);
    }

`EvaluationRunRequest` 必须包含完整的 `TemplateRef(templateId, semanticVersion)`、`RunPurpose`、`RunTrigger` 和一个 sealed `RunSelection`。`ExplicitSubjects` 保存同一 Subject Type 的去重稳定 ID；`DatasetSelection` 保存 dataset ID/version。正式回归还要携带一个已冻结的 baseline run identity，且 service 在内部验证两侧 dataset/version/snapshot pairing。调用者不传 template hash、evaluator version、Evidence Pack hash、provider/model 或 lifecycle 权限；这些值必须由 Rubrics 自己解析、锁定和记录。

`EvaluationRunView` 返回 run identity、固定 template/evaluator/dataset identity、status、四态计数、item progress 和 purpose-specific report。详细 criterion 与 rollout 通过 view 内的有界 item detail 投影返回，不暴露 entity、mapper、对象 key、完整 prompt 或 raw provider response。列表必须有明确上限和 truncation 标记，避免一次 `get` 无界加载大型 dataset。

implementation 内部按能力形成私有模块：Template Catalog 负责严格 YAML 解析、canonical hash、semantic version 和 lifecycle；Subject Catalog 选择三类 snapshot adapter；Evidence Factory 先判 applicability 再构造 criterion 可见的 pack view；Criterion Evaluator 分派 deterministic rule 或 repeated judge；Run Engine 负责 claim、heartbeat、resume 和 fenced commit；Dataset Catalog 负责 immutable manifest 与 Gold Label；Calibration Engine 和 Regression Engine 产出版本化报告；Alert Engine 只根据 production Hard Rule 或 formal paired regression 产生 Rubrics-owned alert。它们可以有 package-private internal seams 和 fake adapters，但不得扩大外部 interface。

LLM judge 每次只看到一个 Criterion、pass/fail anchors、类型化 Subject view 和该 Criterion 允许的 Evidence IDs。文本使用 `RUBRICS_JUDGE_TEXT`，图片使用 `RUBRICS_JUDGE_VISION`；绝不回退 `PRIMARY_CHAT`。默认三次独立 rollout，高风险模板可用五次。每次 rollout 都记录实际 provider/model/model revision（可得时）、prompt hash、parser schema version、latency、token usage、verdict、reason、rationale、evidence IDs 和归一化错误。只有全部配置 rollout 中严格多数为 PASS 或 FAIL 才产生对应最终 verdict；其余为 `INCONCLUSIVE(JUDGE_DISAGREEMENT)`。未知 evidence ID、禁止的 evidence type、空 rationale、解析失败或 provider failure 都是独立的 INCONCLUSIVE reason。

Quality Gate、pass rate 和 coverage 由一个纯汇总模块统一计算。任一 applicable production Hard Rule FAIL 得到 FAILED；没有 FAIL 但有 applicable Hard Rule INCONCLUSIVE 得到 UNKNOWN；所有 applicable Hard Rule PASS 才得到 PASSED，NOT_APPLICABLE 不进入 gate。Principle `passRate = PASS / (PASS + FAIL)`；所有 applicable criterion 的 `coverage = (PASS + FAIL) / (PASS + FAIL + INCONCLUSIVE)`。分母为零时对应值是 null，不是零，也不输出 overall score。

## Plan of Work

### Milestone 0：冻结新 interface、修正文档冲突并建立删除清单

先保护工作树，记录 `git status --short`、活动计划 Progress、Rubrics production/test/migration/frontend 文件清单和 118 条 suppression。把 Rubrics 文件所有权写入活动 lint 计划：本计划负责所有被重写或删除 Rubrics 文件的 suppression，lint 计划不得并行机械修改相同文件。不得覆盖当前 Vision、File、Task 或 App 的用户改动；Rubrics 需要新增 Task/Eval public contract 时只触及明确列出的新 interface 或最小 adapter。

更新 `docs/design-docs/module/rubrics.md`、`docs/design-docs/design.md` §11/§13、`pixflow-module-rubrics/CONTEXT.md` 和模块 ADR，使它们明确采用开发期 fresh-schema、无 v1 历史保留、无 Promotion、无浏览器 API。修复 ADR Consequences 中“人工提升到 Memory”的过期句子。completed ExecPlan 保持历史原文，不重写。

先在 `api/` 写新 `RubricsEvaluationService`、`EvaluationRunRequest`、`RunSelection`、`EvaluationRunId` 和有界 view，再删除 `RubricsController`、旧 `RunEvaluationCommand` 和旧 mapper-shaped views。删除模块的 Spring Web/Validation 依赖；手动执行由内部 service、集成 fixture 或未来单独受保护的 operational adapter 调用，本计划不保留 `/api/rubrics`。

本 Milestone 的静态验收是 Rubrics main source 可只依赖新 interface 编译，旧 Controller 和前端 route 已列入后续删除守护，不要求运行行为测试。若为表达 interface 新增 contract test，必须先执行严格 verify，再执行该 test。

开始前除共同文档外，必须阅读 `docs/design-docs/base/contracts.md`、`docs/design-docs/harness/eval.md` 的 §1、§7、§11-§13、`docs/design-docs/module/task.md` 的 §4、§10、§12.5、§17-§18，以及 `pixflow-module-task/CONTEXT.md`。

### Milestone 1：重写模板、Criterion、Evidence 与 judge 纯领域核心

删除当前 `template/`、`summary/`、`verifier/`、`judge/` 和 `evidence/` 中以旧类形状为中心的 implementation，在新 internal packages 中从目标不变量重写，不在旧类外再包一层。旧 unit tests 同时删除；新增从新 interface 或稳定纯值行为验证语义的 tests。

Template Catalog 使用配置明确的 classpath 目录和 `$PIXFLOW_HOME/rubrics/templates/`，YAML parser 必须拒绝重复 key、未知字段、空 anchors、重复 criterion key、非法 semantic version、非正奇数 rollout、role/subject mismatch 和相同 `(id, version)` 的 hash 冲突。canonical hash 基于解析后的规范树：object key 排序，criterion/evidence 数组保持声明顺序，UTF-8 编码，SHA-256。criterion 内容变化必须升级 version。确定性可验证的 Hard Rule 必须注册 rule verifier；Principle 可以使用 LLM。原子性语义最终由人工 review 和 Gold Label 校准保证，静态 lint 只拒绝确定可识别的空值或明显复合结构，不调用模型自证模板合法。

Evidence Factory 为每个 Subject 先取得 immutable snapshot，再创建稳定 entry IDs。Entry 至少保存 evidence type、owner-defined source reference、content hash、canonical metadata hash、capturedAt 和 replay locator。criterion 只能获得其声明 evidence types 的 view。对象读取、身份不匹配、Pixel Budget 拒绝、解码损坏、trace 过期和真实 missing evidence 使用不同 reason；不能再以 `catch Exception -> empty pack` 混为一类。

Criterion Evaluator 对 rule exception 返回 `INCONCLUSIVE(EVALUATOR_FAILURE)`，对不适用返回 NOT_APPLICABLE，对缺 required evidence 返回 INCONCLUSIVE。LLM parser 使用 closed verdict enum，拒绝 judge 发出的 NOT_APPLICABLE、未知 evidence ID 和无 citation rationale。原始 provider 文本不进入生产表；只保存解析事实、bounded diagnostic 和错误码。Self-judged 根据 Subject 的 producer provider/model identity 与 rollout 的实际 judge identity 判断，不从 template role 名猜测。

先为纯核心编写行为测试，然后在运行这些测试前删除对应文件的全部 suppression 并通过严格 verify。验收覆盖四态、gate/null denominator、模板 hash 冲突、非法 YAML、Evidence view、invalid evidence、三次/五次多数决、无多数、provider/parser failure 和 selfJudged。

开始前还要阅读 `docs/design-docs/infra/ai.md` 的 §4、§5.1-§5.2、§7、§9、§13-§15，`docs/design-docs/infra/image.md` 的 §4.3、§8、§9、§12-§13，以及 `docs/design-docs/infra/storage.md` 的 §5.2、§9、§11-§13。

### Milestone 2：用 fresh-schema 重建持久事实和可恢复 Evaluation Run

删除 `V1__create_rubrics_tables.sql` 和 `V2__migrate_rubrics_to_criterion_verdicts.sql`，创建新的 Rubrics baseline migration 和同步的 fresh-schema fixture。实现前必须确认测试和本地配置使用可重建的 PixFlow 开发数据库；不要对任何未确认的共享数据库执行 drop。已存在 Flyway 历史的开发实例通过重建 Rubrics schema 或整个 disposable test database 恢复，不编写兼容 migration。

新 schema 只包含 `rubrics_run`、`rubrics_run_item`、`rubrics_evaluation`、`rubrics_criterion_result`、`rubrics_judge_rollout`、`rubrics_dataset`、`rubrics_dataset_item`、`rubrics_gold_label`、`rubrics_validation_report`、`rubrics_baseline` 和 `rubrics_alert`。没有 `rubrics_score`、`rubrics_promotion`、overall/image/copy/decision score、legacy resultId 双身份或 memory destination。外键、unique key、enum check 或等价约束必须表达 run/template/dataset/evaluation/criterion/rollout 事实关系；所有 JSON 字段有 owner 和 schema version。

Run Engine 先持久化 run 和 items，再用 `status + claim_epoch + lease_owner + heartbeat_at` CAS claim item。模型和对象 I/O 全在事务外执行。commit transaction 重新校验 runId、itemId、claimEpoch、Subject snapshot hash、template hash、evaluator version 和 item 非终态，然后一次写入 evaluation、criterion results、rollouts 与 terminal item。旧 claimant 返回时更新零行并丢弃结果。run summary 从 item terminal facts 重算，不靠进程内计数器累加。

`resume` 只处理 PENDING、FAILED_RETRYABLE 或 lease stale 的 RUNNING item；SUCCEEDED/PARTIAL terminal item 永不覆盖。一次 evaluation 中存在 INCONCLUSIVE 时 item 为 PARTIAL；Subject 不存在、模板不匹配或不可恢复的 schema 错误为 FAILED；provider 局部故障已经是 criterion INCONCLUSIVE，不把整个 item 变成 FAILED。run 由 item 状态派生 SUCCEEDED/PARTIAL/FAILED，不能把低质量 FAIL verdict 当运行失败。

新增真实 MySQL 8.4 integration suite，覆盖 fresh schema、唯一键、两个 worker 并发 claim、旧 epoch commit、provider 调用后 crash、transaction 中途失败、resume 跳过 terminal、run summary 重算和 JSON round trip。不得以 H2 替代 MySQL 的 CAS 与 JSON 行为。

### Milestone 3：完成 IMAGE_RESULT 纵向切片

在 Task public 包中保留或收敛一个 Rubrics 无关的 immutable successful-output query。它必须返回稳定 process result identity、Task identity、published Generated Image reference、producer provider/model provenance、完成时间和生成时固化的必要 metadata；不得暴露 Task mapper/entity、临时 object key 或可变 JavaBean。Rubrics 的 Image Subject adapter 只依赖该 public interface。

Image Evidence adapter 通过 Task published-asset read interface 取得稳定 ObjectLocation，再用 ObjectStorage 读取字节、先执行 ImageCodec probe/Pixel Budget admission，构造 `OUTPUT_IMAGE` 与 `IMAGE_METADATA`。Task snapshot hash、对象 content hash 和 metadata hash分别记录，不能用 referenceKey、ETag、文件名或完成时间替代内容身份。图片被删除或字节与 snapshot 不一致时，manual production evaluation 得到明确 INCONCLUSIVE；固定 Dataset replay 则把 item 标为 non-replayable，并从 formal paired denominator 中显式报告，不能偷偷换用新字节。

把 `image-result-quality.yaml` 改为新严格 schema，先实现 resolution/format Hard Rules，再实现 background cleanliness/product visibility Principles。judge 视觉输入使用一次 run 内固定的 evidence bytes 或受时限 URL，但 rollout provenance 绑定同一个 content hash；URL 过期重建不能改变 pack identity。

通过 `RubricsEvaluationService` 的 integration test 创建一个显式 Image run，断言最终 view、MySQL facts 和三次 judge request。用 fake OpenAI-compatible HTTP server 覆盖两个 PASS 多数、两个 FAIL 多数、三方分歧、非法 evidence、timeout 和 selfJudged；用真实 MinIO/MySQL/ImageCodec 覆盖正常图片、损坏图片、超 Pixel Budget、对象缺失与内容替换。测试不得直接调用内部 builder 或 mapper 证明成功。

开始前要重读 `docs/design-docs/module/task.md` 的 publication、cleanup、public contract 和测试章节，以及当前完成的 Generated Image publication ExecPlan；completed 计划只用于确认已实现 publication 事实，不恢复旧 object-key contract。

### Milestone 4：实现 Dataset、Gold Label、校准、回归、baseline 和 alert

Dataset Catalog 从 `$PIXFLOW_HOME/rubrics/datasets/` 或 classpath test fixture 加载不可变 manifest。manifest 固定 dataset ID/version、Subject Type、每个 Subject ID/snapshot hash、可选 Evidence Pack hash和 gold-label-set version。相同 identity 内容不同启动失败；注册后数据库中的 manifest hash 不可覆盖。构建 dataset 时解析并固化 snapshot/evidence identity；不能把任意历史 production run 直接改名成 dataset。

Gold Label import 要求两个独立 annotator 对每个 applicable criterion 标 PASS/FAIL/NOT_APPLICABLE，并用第三个 adjudication fact解决分歧。Gold Label 不允许 INCONCLUSIVE 伪装成人类判断，也不作为训练 reward。Validation Engine 计算 human-human agreement、judge-human macro-F1、repeated agreement、INCONCLUSIVE、parser、invalid-evidence 和 missing-evidence rate，并按 criterion/category/easy-hard slice报告。holdout partition 在模板编辑期间不可参与调参。

只有 Validation Report 满足模块设计门槛，template release 才能获得 effective VALIDATED/PRODUCTION automation eligibility。未达门槛的 criterion 和模板保持 EXPERIMENTAL；不能通过配置覆盖。校准 report 绑定 dataset/template/evaluator/evidence schema identity，避免换 judge 后沿用旧资格。

Formal Regression Engine 要求候选 run 与 baseline run 使用相同 dataset ID/version，并逐 item 校验 Subject snapshot hash。报告 per-criterion PASS rate、PASS->FAIL/FAIL->PASS、Hard Rule gate transitions、coverage、INCONCLUSIVE、agreement、parser/invalid evidence、样本数和不足样本标记。non-replayable item单独列出，不进入伪造的 paired comparison。baseline 只能指向成功完成的 formal dataset run；alert 只保存在 Rubrics，包含 criterion-level evidence和确认状态，不触发 Memory。

真实 MySQL tests 覆盖 manifest immutability、重复 import、双标注与裁定、门槛未达、同 dataset paired comparison、dataset/version mismatch、snapshot mismatch、non-replayable、baseline authority 和 alert 幂等。测试前先跑涉及 Rubrics/Task/Eval 的严格 verify。

### Milestone 5：完成 COPY_RESULT 与 TASK_DECISION

开始前先审计 Task 当前真正持久化的文本产物和确认后执行决定。不得从旧 Vision copy enrichment、临时 Proposal、浏览器状态或完整 conversation dump 构造兼容 Subject。若 Task public outcome 尚不能返回稳定 Copy Artifact 或 confirmed decision revision，本 Milestone 在 Task 属主内新增最小 immutable read model 和持久 identity；名称使用 Task/Copy 领域语言，不包含 Criterion、score 或 Rubrics DTO。

Copy Subject snapshot 只包含稳定 copy identity、精确文本及 hash、产品事实引用、目标 audience/voice 和 producer provider/model provenance。Evidence Pack 提供 `COPY_TEXT` 与 criterion 明确要求的事实；不默认加入图片审美或无关 task history。使用 `RUBRICS_JUDGE_TEXT`，模板在 Image criteria 达到门槛后再发布。

Task Decision snapshot 绑定 process task identity和不可变 confirmed decision revision，包含 requirements、validated proposal payload、Canonical DAG snapshot、相关 commerce facts identity，以及定位 Eval trace 的 conversation/turn/trace references。Rubrics 通过 `TraceQuery`/`TraceReplay` 只选择与当前 criterion 有关的 span；trace 是 best-effort，超过保留期或缺失时返回 INCONCLUSIVE/non-replayable。不得让 Rubrics 依赖 Conversation implementation、DAG mapper或 raw tool payload。

为三种 Subject 建立共享 contract test suite：相同 source facts 得到稳定 snapshot hash；一种 Subject 的 evidence 不泄漏到另一种；缺 evidence 是四态语义而不是运行失败；template subject type mismatch在创建 run 前拒绝。Copy 与 Decision tests 通过同一个 `RubricsEvaluationService` interface，不复制一套 coordinator。

开始前必须完整阅读 `docs/design-docs/harness/eval.md` 的 read/replay、retention、security 和 contract 章节，重读 `docs/design-docs/module/task.md` 的 public query与 payload/terminal facts，并按 `docs/design-docs/index.md` 阅读当前 Conversation、DAG 和 Commerce 设计中与 immutable proposal、DAG snapshot、commerce fact identity直接相关的章节。Rubrics 不因此新增对这些 implementation 的 Maven dependency。

### Milestone 6：完成 lifecycle-gated 自动化、组合根、观测和恢复

重写 `RubricsProperties` 为强类型配置：template/dataset directories、runner concurrency、queue capacity、claim lease、heartbeat、bounded rationale/evidence metadata、显式 event binding和 scheduled dataset binding。删除 nullable string组合和隐式默认模板。生产所需 Task query、Trace query、Storage、Image、AI roles、ModelRouter和repositories缺失时，Rubrics auto-configuration必须 fail fast；测试 fake 显式注入，不能用 `@ConditionalOnBean` 静默少装能力。

TaskCompleted listener 只在 event binding enabled、模板 effective lifecycle允许、Subject Type适用和确定性 sample命中时创建持久 run/items，然后提交 run ID 给 Rubrics 自有有界 executor；publisher线程不读对象、不执行 rule、不调用模型。队列满时 run 保持 PENDING并由 recovery scan拾取，不丢掉已经持久化的 admission fact。Rubrics 异常不向 Task event publisher传播，也不改变 Task status。

Scheduled run 必须绑定明确 template version与Dataset version，不扫描“最近24小时”并选择 latest。watchdog按 MySQL lease/heartbeat恢复，不能依赖进程内 future或Redis lock。重复 Task event用稳定 admission key去重；同一逻辑 event不得创建第二个 run，显式新 manual run则允许。

加入 Micrometer metrics：run/item latency与状态、criterion verdict counts、coverage/inconclusive/agreement、judge latency/token/error、parser/invalid evidence、automation skipped reason、queue depth和recovery。日志携带 run/subject/template/evaluator/criterion/trace IDs，不记录图片字节、presigned URL、完整 prompt、raw provider response、secret或 unrestricted trace。

在 `pixflow-app` 只做必需的 adapter 装配和配置，不增加 end-user Controller。App context test证明必需 bean缺失失败、完整 production graph启动、Rubrics与Memory之间没有bean seam。事件、scheduler、queue full、stale recovery和重复admission都通过真实MySQL加受控executor/clock测试。

### Milestone 7：删除旧代码与前端残留，完成 lint-first 验收

删除旧 `RubricsController`、旧 DTO、旧 coordinator/persistence实现、旧 migration、旧 entity/mapper、旧 properties形状和所有不再被新深模块使用的测试。删除前端 `src/api/rubrics.ts`、`src/stores/rubrics.ts`、`/rubrics` route、左侧 Rubrics导航和相关 placeholder注释/测试。前端不是改成新 API；目标是完全不存在 Evaluation Center surface。

删除 `config/checkstyle/suppressions.xml` 中所有 Rubrics条目，不新增 suppression、`@SuppressWarnings`、`eslint-disable`或 SpotBugs exclude。对已删除文件同时删除精确 suppression；对保留/新写文件修复所有严格问题。更新活动 lint计划 Progress，明确118条Rubrics suppression由本计划清零，避免重复计数。

增加 ArchUnit/module dependency tests：Rubrics main source不得依赖 Memory、Vision、File、Agent、Loop、Tools、Hooks、Conversation implementation、Task persistence或Eval persistence；Task/Eval不反向依赖Rubrics；AI不理解Criterion、Evidence Pack或majority；Rubrics不公开Controller。增加全仓搜索守护，production source和fresh schema不得命中 Promotion、scalar scores、PRIMARY_CHAT judge或前端Rubrics route。

最终先完成所有后端静态分析和前端lint/typecheck，再运行任何完整测试。真实MySQL/MinIO suite不得在Docker可用但配置错误时skip。完成后更新本计划living sections、父计划Milestone 6、Rubrics设计Revision Notes和必要的Context/ADR；completed历史计划保持原样。

## Small Commit Sequence

以下顺序是实现边界，不要求当前计划编写者代替用户提交；即使工作树最后统一提交，也必须逐段验证，不能把schema、judge、恢复、dataset和三类Subject一次混改。

1. `docs(rubrics): freeze no-compatibility deep-module contract`：修正文档/ADR冲突，写新 interface 和删除清单，不改变评估行为。
2. `refactor(rubrics): replace template and criterion core`：重写严格模板、四态、gate、Evidence identity纯核心并替换旧unit tests。
3. `refactor(rubrics): replace judge protocol and provenance`：重写text/vision rollout、parser、evidence验证、majority和selfJudged。
4. `refactor(rubrics): replace legacy schema with evaluation facts`：删除V1/V2，落fresh schema、repositories和真实MySQL schema tests。
5. `refactor(rubrics): add fenced resumable run engine`：只落claim/heartbeat/resume/transaction，不接自动化。
6. `feat(rubrics): complete image result evaluation slice`：接Task published output、Storage/Image和fake judge，完成Image interface tests。
7. `feat(rubrics): add immutable datasets and gold labels`：只落manifest、labels、calibration和effective lifecycle。
8. `feat(rubrics): add paired regression baseline and alerts`：只落formal comparison与Rubrics-owned alerts。
9. `feat(task): expose immutable copy and decision outcomes`：仅在Task public owner seam增加Rubrics无关的snapshot合同和adapter。
10. `feat(rubrics): evaluate copy results and task decisions`：接入text evidence与Eval trace，完成三类Subject共享tests。
11. `feat(rubrics): add durable offline automation and recovery`：落event/scheduled bindings、bounded runner、watchdog和metrics。
12. `refactor(app): assemble rubrics fail-fast graph`：只做App adapter/config/context tests，不加浏览器API。
13. `refactor(web): remove rubrics product surface`：删除route/nav/store/api与旧tests。
14. `test(rubrics): enforce architecture and failure matrix`：清零Rubrics suppression，完成真实依赖矩阵、负向扫描和文档收尾。

每个后端提交先运行该切片 `-DskipTests verify`，成功后才运行tests。每个前端提交先lint/typecheck，成功后才运行Vitest/build。任何门禁失败都在该提交边界修复，不能把红色reactor带入下一个提交。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 串行执行。开始或恢复先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs/exec-plans config/checkstyle/suppressions.xml
    rg -n "pixflow-module-rubrics" config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml
    rg -n "RubricsController|rubrics_promotion|rubrics_score|overall_score|image_score|copy_score|decision_score|PRIMARY_CHAT|/rubrics|useRubricsStore" pixflow-module-rubrics pixflow-web/src

若活动 lint计划当前正在修改Rubrics同一文件，先在两个计划的Progress记录所有权并串行完成一个切片。不得并行运行Maven reactor，因为共享Checkstyle/SpotBugs输出会竞争。

Milestone 0-2 的后端固定顺序：

    mvn -pl pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-module-task,pixflow-module-rubrics -am -DskipTests verify
    mvn -pl pixflow-module-rubrics -am test

第一条是lint/static/compile gate；失败时停止，不运行第二条，不新增suppression。`-DskipTests verify`不能记作测试成功。

Image Result切片先确认Docker并启动现有服务，不删除volume或用户容器：

    docker info
    docker compose up -d mysql minio
    docker compose ps
    mvn -pl pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-module-task,pixflow-module-rubrics -am -DskipTests verify
    mvn -pl pixflow-module-rubrics -am test

Dataset、Copy和Task Decision切片使用：

    mvn -pl pixflow-eval,pixflow-module-task,pixflow-module-rubrics -am -DskipTests verify
    mvn -pl pixflow-eval,pixflow-module-task,pixflow-module-rubrics -am test

组合根切片使用：

    mvn -pl pixflow-eval,pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-module-task,pixflow-module-rubrics,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-eval,pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-module-task,pixflow-module-rubrics,pixflow-app -am test

前端删除切片严格按以下顺序：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

lint或typecheck失败时不运行Vitest。不得运行整仓`lint:fix`；若对显式文件使用自动修复，必须立即审阅diff。

最终验收先lint/static，再tests/build：

    mvn -DskipTests verify
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    mvn verify
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    rg -n "pixflow-module-rubrics" config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml
    rg -n "RubricsController|PromotionService|ReviewedInsightService|rubrics_promotion|rubrics_score|overallScore|imageScore|copyScore|decisionScore|DimensionScore|DomainScore|RubricScore|ScoreAggregator|MemoryFeedback|ScoreFeedback|ModelRole.PRIMARY_CHAT" pixflow-module-rubrics pixflow-app pixflow-module-memory
    rg -n "(/rubrics|useRubricsStore|api/rubrics|Rubric评估)" pixflow-web/src
    rg -n "com\.pixflow\.(module\.(memory|vision|file)|agent|harness\.(loop|tools|hooks)|module\.task\.infra|harness\.eval\.infra)" pixflow-module-rubrics/src/main
    git diff --check
    git status --short

前三个负向搜索对生产源码和fresh schema预期无输出。Memory自己的confidence召回字段不是Rubrics scalar score，若宽泛搜索命中必须按owner判断，不得为了空输出修改范围外Memory。completed历史文档允许保留旧词；生产路径、当前migration、配置和前端不得命中。

## Validation and Acceptance

Template验收要求相同`(id, version)`同内容可去重、不同内容启动失败；semantic version正确排序；unknown YAML、duplicate key、缺anchor/evidence/applicability、非法role、偶数或零rollout、无deterministic verifier的可确定Hard Rule都被拒绝。模板文件声称PRODUCTION但没有匹配Validation Report时，自动run仍被拒绝。

四态验收要求NOT_APPLICABLE在judge前决定；missing evidence、parser error、invalid evidence、provider failure和judge disagreement都是有原因的INCONCLUSIVE。任一Hard Rule FAIL使Quality Gate FAILED；无FAIL但存在applicable INCONCLUSIVE使UNKNOWN；所有applicable Hard Rule PASS使PASSED。passRate与coverage分母为零时是null，任何响应或表都没有0-100质量分。

Evidence验收要求每个pack由系统生成稳定ID和hash，criterion只能看声明类型；相同snapshot和字节重建得到相同identity，字节或canonical metadata变化得到新identity。invented ID、跨criterion evidence、对象身份错配、Pixel Budget拒绝、trace过期和对象缺失都有独立行为。日志、view和表不存图片字节、presigned URL、完整prompt或raw provider output。

Judge验收要求每个LLM criterion产生配置数量的独立provider call，只有严格多数PASS/FAIL形成最终verdict；实际provider/model/revision、prompt/parser/rollout policy组成evaluator version。`RUBRICS_JUDGE_TEXT`和`RUBRICS_JUDGE_VISION`配置缺失时fail fast，绝不使用PRIMARY_CHAT。生产者与judge实际identity相同则selfJudged=true，但不改变verdict。

恢复验收要求两个worker只能有一个claim epoch提交；旧epoch、过期lease和旧snapshot均无法提交。evaluation、criterion、rollout和terminal item要么全写要么全不写。commit前crash可resume并可能重新调用provider；commit后resume跳过。FAIL verdict是质量事实，不是运行失败；provider局部故障是criterion INCONCLUSIVE和item PARTIAL。

Dataset验收要求manifest/version不可变、Gold Label双人标注并裁定、holdout隔离、validation门槛按criterion计算。Formal regression只比较相同dataset/version和配对snapshot；non-replayable单列，不能进入假配对。任意production run不能成为formal baseline；alert只由Rubrics拥有，不调用Memory。

三类Subject验收要求Image不夹带copy或decision evidence，Copy不默认夹带图片审美，Task Decision只读取criterion相关trace。Rubrics对Task只看public immutable facts，对Eval只看read interface。trace丢失不阻断online task，也不把decision criterion判FAIL。

自动化验收要求TaskCompleted publisher线程只持久admission和排队，不读对象或调模型；队列满后run仍可恢复；重复event不重复run；EXPERIMENTAL或未校准模板不自动运行；scheduled binding必须固定template和dataset version。Rubrics异常不改变Task终态。

产品与架构验收要求浏览器不存在Rubrics route/nav/store/api，后端没有end-user Rubrics Controller。Rubrics与Memory/Vision/File/Agent/Loop/Tools/Hooks零编译依赖，不存在Promotion或memory write seam。118条Rubrics Checkstyle suppression全部清零，SpotBugs不新增exclude；每次测试前对应lint/static gate均有成功证据。

## Idempotence and Recovery

所有search、lint、compile、test和GET/read命令可重复运行。Template和Dataset注册按identity/hash幂等；相同Task event按admission key幂等；run item按unique key与claim epoch幂等；terminal evaluation不覆盖。重复resume只处理可恢复item。

fresh-schema策略是有意破坏旧Rubrics开发数据的。实施者必须先确认目标数据库可重建且只属于PixFlow开发/测试，再删除旧schema或重建database；不得对名称来源不明的数据库执行drop。若发现共享或有价值数据，先停止破坏性步骤并导出数据，但不要因此加入旧代码兼容层；恢复后仍导入新schema支持的显式事实或放弃旧数据。

不得使用`git reset --hard`、`git checkout --`、整仓restore或删除用户无关文件。遇到Task/App/File/Vision当前改动时，只在明确public seam上做最小补丁；不能安全合并就把具体文件和依赖顺序写入Progress并停在静态门禁通过的边界。

provider调用后、commit前crash的恢复允许重新消费调用额度，因为Rubrics当前没有持久provider attempt预算设计；这必须在run diagnostics中可见。若未来成本上限成为产品要求，应单独设计persistent attempt budget，不能借用Vision generation语义或在本计划中静默加入。

## Artifacts and Notes

目标执行链：

    RubricsEvaluationService.start(request)
      -> resolve immutable template release and effective lifecycle
      -> resolve ExplicitSubjects or immutable Dataset manifest
      -> persist run + item admission facts
      -> bounded runner claims item with MySQL claim epoch
      -> Subject adapter resolves immutable owner snapshot
      -> Evidence Factory captures and hashes permitted evidence
      -> applicability / required-evidence decision
      -> deterministic rule OR 3/5 independent judge rollouts
      -> majority + four-state summary
      -> fenced transaction writes evaluation/criteria/rollouts/item terminal
      -> run summary, optional calibration/regression report and Rubrics alert

目标依赖链：

    common
      -> infra/ai, infra/storage, infra/image, harness/eval, module/task
      -> module/rubrics
      -> app composition

禁止的反向或横向链：

    Rubrics -X-> Memory / Vision / File / Agent / Loop / Tools / Hooks
    Task / Eval -X-> Rubrics
    browser -X-> Rubrics Evaluation Center
    Rubrics verdict / alert -X-> Agent memory

关键crash windows及恢复事实：run/item admission覆盖队列提交失败；claim epoch覆盖并发worker；heartbeat/lease覆盖进程崩溃；transaction覆盖半套evaluation事实；terminal unique key覆盖重复resume；Subject/template/evaluator/evidence hashes覆盖迟到或错误输入提交。进程内executor只负责吞吐，不是真相源。

## Interfaces and Dependencies

`pixflow-module-rubrics` 最终只直接依赖 `pixflow-common`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-image`、`pixflow-eval`、`pixflow-module-task` 的public outcome/event interface、MyBatis/Jackson/Spring transaction/Micrometer。若删除Controller后Spring Web只被DTO注解间接需要，也一并删除Web依赖。

Task public seam提供Rubrics无关的immutable values，等价于：

    public interface TaskOutcomeQuery {
        Optional<PublishedImageOutput> imageOutput(long resultId);
        Optional<PublishedCopyOutput> copyOutput(String artifactId);
        Optional<ConfirmedTaskDecision> confirmedDecision(long taskId, String revision);
    }

这些record包含稳定业务identity、content/snapshot identity、producer provenance和定位必要evidence的最小references。具体方法名以Task `CONTEXT.md`的最终语言为准，但不得暴露mapper/entity/object key或Rubrics类型。若Task已有等价public interface，扩展或收敛它，不平行新增同义query。

Eval seam继续使用`TraceQuery`和`TraceReplay`。Rubrics按conversation/turn/trace identity读取相关span；Eval不新增Rubrics术语或回写接口。

infra/ai只负责role routing、admission/retry、provider call和归一化错误。Criterion、Evidence Pack、rollout count、majority、parser evidence validation、evaluator version组合和生命周期属于Rubrics implementation，不下沉infra/ai。

ObjectStorage只提供纯I/O，ImageCodec只提供probe/decode/encode和Pixel Budget机制。Rubrics通过owner public snapshot取得ObjectLocation；不拼storage key，不直接使用MinioClient、ImageIO或vendor SDK。

## Revision Notes

2026-07-19 / Codex: 完成 non-replayable Dataset item 的公共投影与 run 汇总收敛：item 以 `PARTIAL` 保留、安全错误码进入 bounded view、report 单列 `nonReplayableCount` 且不增加 failure。新增真实 MySQL 回归测试，定向 5 项与模块完整 26 项均零失败/错误/跳过，修改后干净静态门禁零 Checkstyle/SpotBugs；同时记录首个 formal baseline 尚无 public bootstrap/authority，Milestone 4 保持未完成。本轮未修改前端。

2026-07-19 / Codex: 删除临时 adapter/legacy coordinator 并让深模块 interface 直接接管可恢复 run；接入 epoch/lease fenced evaluation transaction、bounded view、不可变 Dataset 读取、snapshot pairing 和同 Dataset formal baseline 验证。新增公共 interface 与 claim repository 的真实 MySQL 8.4 测试，模块 25 项测试零 skip；记录 Task 尚无 Copy/Decision immutable outcome seam，未伪造另外两类 Subject，也未修改前端。

2026-07-19 / Codex: 根据双轴审查修复 non-replayable 分母丢失、run/template/evaluator fence 缺口、transient failure 被错误终结和 formal baseline 配对不足；同步修正 stale Outcomes 并清理旧 coordinator、已格式化 persistence 与本轮触达文件 suppression，Rubrics suppression 降至 65。最终干净静态门禁与 25 项完整测试再次通过。

2026-07-19 / Codex: 创建本专项ExecPlan。依据Rubrics权威设计、Context/ADR、父架构对齐计划、活动lint计划、completed历史计划和当前源码审计，采用无兼容fresh-schema与replace-don't-layer深模块重构；固定三类typed Subject、系统Evidence Pack、独立judge多数决、MySQL fenced resume、immutable Dataset/Gold Label、校准门、同Dataset paired regression、Rubrics-owned alert和无Memory/前端边界。按用户要求把每个切片的验证顺序写死为lint/static成功后才运行测试，并把118条Rubrics Checkstyle suppression清零纳入完成条件。
