# 完成 Milestone 5：收窄 Memory、Vector、Hooks 与 Agent 为只读召回和显式视觉事实消费

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，是 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 中 Milestone 5 的专项实施计划。执行者只依赖当前工作树和本文，就应能完成该里程碑。每个停止点都必须更新本文；改变公开契约、模块依赖、数据库基线、降级语义、阶段顺序或验收命令时，必须同步更新四个 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，每个主 Agent 回合都会在 Prompt 组装前，以用户文本和有序 canonical Asset References 为只读召回信号，获得已有用户偏好、相关 SKU 历史和 ACTIVE analysis insight。Agent 不再传递或推断平行的 `packageId`、`skuIds`、`imageIds`、附件文件名或对象存储位置；Memory 需要素材范围时，使用 File 模块的公开 Asset Reference API 解析 canonical key。

召回必须是对在线业务事实完全无副作用的操作。Qdrant 不可用时退化为 MySQL FULLTEXT，MySQL 某一路不可用时保留其他成功 section，MySQL 整体不可用时返回空或部分 `MemoryContext`，Conversation 仍能继续。所有排序、去重和 token 截断在相同事实快照与相同请求下稳定；读取不会增加访问次数、更新时间、强化时间、生命周期状态，也不会写 Qdrant。

Product Visual Facts 与业务 Memory 保持两条独立输入。Memory 不索引、不缓存、不复制视觉事实；当用户的问题涉及商品外观、可见属性或重绘提示时，主 Agent 通过现有 `get_product_visual_facts(referenceKey)` 工具显式读取当前事实。最终的模型输入可以同时包含只读 `Memory Context` 和本回合工具返回的 Product Visual Facts，但任何一方都不能写入另一方。

开发者可以通过模块测试、真实 MySQL/Qdrant 故障测试和 App 组合根测试观察结果：canonical IMAGE/SKU/PACKAGE reference 能导出正确且去重的 SKU recall filter；非法或已删除 reference 只让该信号降级；Qdrant、FULLTEXT、偏好表或 SKU history 表分别故障时对话不被阻断；trace 中能看到经过脱敏和限界的 recall 计划、候选统计、选择结果及降级原因；工具注册表只有显式视觉事实读取工具，没有 memory recall/write 工具；Session Memory 仍只在同一 Conversation 内用于上下文压缩。

## Progress

- [x] (2026-07-20) 阅读 `AGENTS.md`、`PLANS.md`、全部活动 ExecPlan、`docs/design-docs/index.md`、父架构计划的 Milestone 5、总体设计第七章、Memory/Vector/Agent/Hooks/Context/Eval/File/Vision 设计、Context Map 与 domain-doc 指引。
- [x] (2026-07-20) 审计当前 `pixflow-module-memory`、`pixflow-infra-vector`、`pixflow-agent`、`pixflow-hooks`、`pixflow-loop`、`pixflow-eval`、`pixflow-module-file`、`pixflow-module-vision` 与 `pixflow-app` 的公开 API、自动配置、POM、测试和生产搜索结果。
- [x] (2026-07-20) 创建本专项 ExecPlan；本次只写计划，不修改生产代码、不运行 Maven 测试、不把父计划 Milestone 5 标记为完成。
- [ ] 阶段 0：复核工作树与数据库策略，冻结 owner-defined contracts 和可复现基线。
- [x] (2026-07-20) 阶段 1：删除 `MemoryAttachment` 与 package/task/SKU 平行身份；`MemoryContextRequest`/Agent signal 改为有序去重 `MemoryReference`，Memory 通过 File public resolver/expander 在 `INSPECT` 用途中逐条 best-effort 解析 SKU filter。
- [x] (2026-07-20) 阶段 2（核心实现）：偏好、SKU history 与 insight 读取分别隔离失败；总预算按 `user_preferences -> sku_history -> analysis_insights` 顺序以完整 item 截断，并将预算、选择数量和降级状态写入 recall trace。真实 MySQL/Qdrant 故障矩阵仍待阶段 5 运行。
- [ ] 阶段 3：接通 Agent recall trace、Prompt 注入和 Product Visual Facts 显式消费的组合验证。
- [ ] 阶段 4：固化 Hooks、Session Memory、Rubrics 与 Vector 的无写边界，删除旧合同和数据库残留。
- [ ] 阶段 5：完成真实依赖故障矩阵、App 生产装配、全仓静态门禁与端到端验收。
- [ ] 完成后更新父计划 Milestone 5、`Outcomes & Retrospective` 和 `Revision Notes`，并记录精确测试证据。

## Surprises & Discoveries

- Observation: Milestone 5 的底层只读能力已经存在，不应重写 Vector 或混合召回主体。
  Evidence: `VectorSearch` 当前仅有 `verifyCollection`、`search`、`get` 三个方法；`VectorReadOnlyArchitectureTest` 已守护 Spring 不暴露原生 `QdrantClient`；`HybridInsightRecallService` 已实现 vector/FULLTEXT 双路召回和 RRF。

- Observation: Memory 的当前公开请求仍是旧身份模型，与 Milestone 1 已完成的 canonical message references 冲突。
  Evidence: `MemoryContextRequest` 和 `MemoryRecallSignal` 仍含 `MemoryAttachment attachments`、`packageId`、`taskId`、`skuIds`；`AgentOrchestrator.recall` 虽把 `reference_keys` 放入 metadata，却仍把并行 package/SKU 字段作为正式参数，Memory 也没有依赖 File public resolver。

- Observation: 当前降级只覆盖 insight 的 vector/FULLTEXT 子路径，没有覆盖偏好、SKU history 或 Memory 门面整体失败。
  Evidence: `HybridInsightRecallService` 捕获两路检索异常；`MemoryContextBuilder` 直接调用 `PreferenceService.recallPreferences` 和 `SkuHistoryService.recallBySkuIds`，异常会向上冒泡；`AgentOrchestrator.driveTurn` 在 loop 启动前直接召回，异常会终止本回合。

- Observation: 配置名虽然包含 token budget，当前实现并没有对三个 section 执行一个统一预算。
  Evidence: `MemoryContextRequest.tokenBudget` 只用于在预算较小时把 insight topN 减半；`MemoryContextBuilder` 渲染全部偏好与 SKU history，没有按 section 优先级逐项截断，也没有证明返回 section 的 token 总和不超过请求预算。

- Observation: Eval 已有 `TurnTrace.recordRecall(TraceRecall)`，但生产召回结果没有进入它。
  Evidence: `AgentOrchestrator` 仅记录日志并把 `MemoryContext` 用于 Prompt；`TraceFanout` 目前只转投 prune、hook、retry 和 tool trace；生产搜索只在 Eval API/recorder 中命中 `recordRecall`。

- Observation: Product Visual Facts 的 Agent 工具已经由 Vision owner 实现，无需在 Agent 或 Memory 新建平行 adapter。
  Evidence: `ProductVisualFactsTool` 注册唯一 `get_product_visual_facts` descriptor，schema 只接受 `referenceKey`；`VisualFactsLookupResult` 不包含 writer/provider 元数据；Vision 设计和实现均禁止写 Memory/Qdrant。

- Observation: Hooks 中保留 `ASSISTANT_MESSAGE_COMPLETED`、`TASK_COMPLETED` 事件本身不是写回路径；真正需要禁止的是订阅这些事件并写业务 Memory 的 callback。
  Evidence: `pixflow-hooks` 是无业务依赖的薄事件总线；当前 Agent callback 只有 `TURN_STOPPED -> SessionMemoryService` 和 `PRE_COMPACT -> summary instructions`。Session Memory 是 conversation-scoped 压缩事实，不是跨会话业务 Memory。

- Observation: 当前工作树包含大量用户修改，其中包括本计划会触达的 Agent、Memory、App 和配置文件。
  Evidence: 2026-07-20 的 `git status --short` 显示 `pixflow-agent` subagent 文件、`pixflow-module-memory` 多个实体/排序类、`pixflow-app` 组合根及 `config/checkstyle/suppressions.xml` 已修改。执行者必须逐文件阅读并在现有改动上做小补丁，不能 restore 或覆盖。

## Decision Log

- Decision: 采用“保留已验证的只读底座，替换旧请求合同并补齐闭环”的迁移策略，不重写 `VectorSearch`、Qdrant adapter 或 RRF 算法。
  Rationale: 父计划已把 Vector/Memory 只读子范围记为完成；当前剩余风险集中在 canonical identity、故障隔离、预算、trace 和最终装配。重写底层会扩大已验证能力的风险。
  Date/Author: 2026-07-20 / Codex

- Decision: `MemoryContextRequest` 的身份输入只保留 ordered `references`，每项为 `referenceKey + displayPathSnapshot`；删除 `MemoryAttachment`、`packageId`、`taskId` 和 `skuIds`。
  Rationale: message references 已经是用户输入中的唯一素材身份。display path 仅用于显示和 trace，不可作为 SKU filter；真实 package/SKU/image 身份必须由 File owner 解析。
  Date/Author: 2026-07-20 / Codex

- Decision: Memory 直接依赖 `pixflow-module-file` 的公开 `AssetReferenceResolver` 与 `AssetReferenceExpander`，不复制 File DTO，不建立 App-owned 同义端口。
  Rationale: 当前设计明确把 canonical resolution 归 File public API；Memory 到 File 是既定依赖方向。使用 owner public API 比在 App 中重建身份投影更深、更少歧义。
  Date/Author: 2026-07-20 / Codex

- Decision: reference 解析采用逐 key best-effort；SKU/IMAGE 用 resolver 取得 `skuId`，PACKAGE 用 expander 取得当前图片的 SKU 集并去重。用途固定为 `AssetUse.INSPECT`。
  Rationale: recall signal 不是权限或 Proposal 校验边界。一个无效或已删除 key 不应使其他 key 或整个对话失败；`INSPECT` 表达只读事实解析且不允许越过 File 生命周期检查。
  Date/Author: 2026-07-20 / Codex

- Decision: Memory public 门面仍只有 `MemoryService.prepareContext`；内部 source interfaces 可以保留，但不得成为其他 Maven 模块的编译依赖。
  Rationale: 偏好、SKU history、insight mapper 和融合算法都是深模块内部实现。Agent 只需要请求与返回 records。
  Date/Author: 2026-07-20 / Codex

- Decision: 降级在 Memory owner 内逐 section 隔离，Agent 只做最后一道“Memory 门面异常变为空上下文”的保险。
  Rationale: Memory 最了解数据源与 trace 原因，应尽量返回成功的部分；Agent 必须保证任何未预见依赖异常也不会阻断 Conversation，但不能把所有失败吞成无诊断的空值。
  Date/Author: 2026-07-20 / Codex

- Decision: token budget 使用稳定的 section 优先级 `user_preferences -> sku_history -> analysis_insights`，每个 section 内保持 owner 排序并按完整 item 截断，不截断半条事实。
  Rationale: 小而稳定的用户约束优先，明确引用的 SKU 历史次之，语义召回候选最后；完整 item 避免向模型注入语义残片。预算为零时返回三个可识别但内容为空的 section 和完整 trace。
  Date/Author: 2026-07-20 / Codex

- Decision: Product Visual Facts 不成为 Memory section，也不在 Prompt 预取；它继续作为主 Agent 的显式只读工具结果进入后续 think-act-observe 迭代。
  Rationale: 视觉事实是当前素材事实，生命周期和更新语义与跨会话业务 Memory 不同。显式工具调用同时保留按需性、可观测性和 writer metadata 隔离。
  Date/Author: 2026-07-20 / Codex

- Decision: `HookEvent` 中的通用生命周期事件保留；删除和守护的是任何业务 Memory writer callback、端口或依赖。`TurnStoppedSessionMemoryHook` 保留。
  Rationale: 事件还服务 trace、回合收尾和离线 Rubrics admission。删除枚举会破坏无关能力；Session Memory 写入只更新同会话压缩视图，不构成业务记忆写回。
  Date/Author: 2026-07-20 / Codex

- Decision: `analysis_insight` 的既有 lifecycle/ranking 字段可只读保留，当前 baseline 中的 `sku_history.rubrics_score` 必须删除；不得增加兼容 reader 或双写。
  Rationale: 设计允许管理员预装事实携带 ACTIVE/SUPPRESSED/EXPIRED、confidence、importance、decay 和历史 reinforced 时间供只读排序，但 Rubrics scalar score 已被新 Rubrics 模型删除且形成跨上下文污染。
  Date/Author: 2026-07-20 / Codex

## Outcomes & Retrospective

本专项已完成 canonical Memory 请求切换、File owner 解析、源级降级、总 token budget 和 Agent 门面兜底。定向 compile、Memory planner 单测及 Agent 单测均通过；真实 MySQL/Qdrant 集成测试在本次 120 秒命令上限内未完成，Loop/Eval recall trace、App 装配和完整故障矩阵仍以 `Progress` 未完成项为准。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。父计划的 Milestone 1 已把 Conversation 消息素材切成 ordered canonical references；Milestone 4 已把通用 Vision 子 Agent 替换为持久 Product Visual Facts 和唯一 lookup tool。Milestone 5 必须消费这些既有边界，不能恢复附件身份、通用图片问答或视觉事实写入 Memory。

“业务 Memory”指 `pixflow-module-memory` 读取的三类跨会话既有事实：`user_preference`、`sku_history`、ACTIVE `analysis_insight`。“Session Memory”指 `pixflow-agent` 为单个 Conversation 保存的压缩摘要，它经 `TurnStoppedSessionMemoryHook` 更新，只帮助同一会话重入和上下文压缩，不是用户偏好、SKU 历史或分析结论，也不能被提升到业务 Memory。

`pixflow-infra-vector` 是 Qdrant 的 provider-neutral 只读适配器。它只验证预先存在的 collection、搜索和按 id 读取，不创建 collection，不写 point。测试夹具可以用独立 Qdrant admin client 建 collection/seed points，但该 client 不得成为生产 Spring bean。

`pixflow-module-file` 拥有 canonical Asset Reference 解析。公开 `AssetReferenceResolver.resolve(referenceKey, AssetUse)` 返回不含对象位置的 `ResolvedAssetReference`；`AssetReferenceExpander.expand(referenceKeys, AssetUse)` 把 PACKAGE/SKU 展开为当前图片视图。Memory 只利用这些结果生成 SKU/category 等 read filters，不持有 File mapper/entity/object key。

`pixflow-agent` 在每轮模型调用前同步调用 `MemoryService.prepareContext`，把 `user_preferences` 放入 instruction memory，把 `sku_history` 与 `analysis_insights` 放入 long-term memory。Agent 不拥有 RRF、mapper 或 Qdrant。`pixflow-loop` 创建 `TurnTrace` 并负责把 recall 只读视图转投 `pixflow-eval`；Agent 不直接持久化 trace。

`pixflow-module-vision` 已提供 `ProductVisualFactsLookup` 和 `ProductVisualFactsTool`。该工具只接受一个 canonical `referenceKey`，返回当前 observation-only facts 与安全状态，不返回 writer/provider metadata。主 Agent 看到它并在需要外观事实时调用；Memory、Vector 和 Hooks 都不依赖 Vision。

## Scope and Non-Goals

范围包括 Memory request/public contract、File resolver 接入、召回计划/过滤/融合/预算/降级、Agent 适配与 Prompt section、Loop/Eval recall trace、Hooks 和工具表面的负向守护、Memory fresh schema、App 自动装配与真实依赖测试。实现中允许调整这些范围内的 POM、自动配置、单元/集成/ArchUnit 测试和必要配置。

本计划不实现业务 Memory 导入后台、Qdrant reindex CLI、管理员偏好编辑 UI、在线 insight extraction、Rubrics Promotion、任务完成写回、对话行为学习、视觉事实索引、通用 vision child、前端页面或 Session Memory 全面重构。管理员离线准备 MySQL/Qdrant 数据的机制属于独立运维流程；本计划只证明在线 runtime 没有调用它的能力。

本计划不修复范围外 Checkstyle 基线，不新增 suppression。若范围内文件已有 suppression，应在该文件达到零 violation 后精确删除；若静态门禁被范围外用户改动阻断，记录模块、规则和文件，使用不扩大代码修改的最小 reactor 继续验证，最终全仓门禁留到冲突解除后执行。

## Interfaces and Dependencies

最终 `MemoryService` 保持：

    public interface MemoryService {
        MemoryContext prepareContext(MemoryContextRequest request);
    }

最终请求等价于：

    public record MemoryContextRequest(
        String conversationId,
        int turnNo,
        String traceId,
        String userPrompt,
        List<MemoryReference> references,
        List<String> categoryHints,
        Map<String, Object> metadata,
        int tokenBudget
    ) {}

    public record MemoryReference(
        String referenceKey,
        String displayPathSnapshot
    ) {}

`MemoryReference` 名称可以复用一个真正中立的 existing record，但不能让 Memory 依赖 Context/Conversation message entity，也不能把 `displayPathSnapshot` 用作身份推断。`tokenBudget` 必须非负并在构造时规范化；references 保序、过滤空值并去重时保留第一次出现位置。

Memory 内部新增语义等价的 reference-filter component，例如：

    interface RecallReferenceResolver {
        ResolvedRecallReferences resolve(List<MemoryReference> references);
    }

    record ResolvedRecallReferences(
        List<String> skuIds,
        List<String> categoryHints,
        List<ReferenceResolutionTrace> trace
    ) {}

实现通过 File public `AssetReferenceResolver`/`AssetReferenceExpander` 工作。不得返回 package mapper、image entity、storage key 或文件路径。无法解析的 key 以 bounded safe reason 进入 trace；原始异常和绝对路径不进入 Prompt 或持久 trace。

`MemoryContext` 固定含三个 section 和一个 `recallTrace`。每个 section trace 至少说明 requested/selected item count、estimated tokens、omission reason 和 dependency status。总 trace 至少说明 reference resolution、source outcomes、RRF policy/version、token budget、used tokens、degraded reasons；不保存 embedding、完整未选候选正文、数据库异常栈、密钥或对象位置。

Vector runtime API 保持三方法，不增加 writer：

    verifyCollection(collection, dimension, distance)
    search(collection, query, topK, threshold, filter)
    get(collection, id)

依赖方向最终为：

    common + infra/ai + infra/vector + module/file -> module/memory
    context + session + hooks + eval + loop + module/memory -> agent
    tools + module/file + infra/ai + module/vision + agent -> app composition

禁止方向为：

    memory -X-> agent / hooks / task / conversation / rubrics / vision
    vector -X-> memory / file / any business module
    hooks -X-> memory / vector / vision / agent
    rubrics / task / conversation / agent hooks -X-> memory writers

Agent 到 Eval 的 recall 可观测应沿现有 owner direction 实现。Agent 把 bounded recall snapshot 放进 `RuntimeState.metadata` 或一个 Loop 可读的窄 SPI；`AgentLoop` 创建 `TurnTrace` 后，由 `TraceFanout` 转成一条或多条 `TraceRecall`。不得让 Agent 直接注入 mapper/recorder，也不得让 Memory 依赖 Eval。

## Plan of Work

### 阶段 0：冻结基线、数据库策略和测试证据

先运行 `git status --short`，保存本计划范围内用户修改清单。逐个读取 Memory、Vector、Agent、Hooks、Loop、Eval、File、Vision 和 App 的 POM、自动配置和已有测试；不要依据父计划的历史描述猜测当前代码。特别复核正在进行的 App composition 工作是否同时修改 `pixflow-app/pom.xml`、`application.yml`、Flyway locations 或 Context test，避免平行接线。

对 Memory 数据库确定唯一策略。若当前数据库仍是可重建开发库，把 Memory 目标 schema 合并进 App-owned fresh baseline，并删除 `sku_history.rubrics_score`；若 migration 已在共享环境执行，保留已执行 migration checksum，新增 forward migration 删除该列。两种路径都不添加 nullable legacy DTO、旧 score reader 或兼容视图。执行任何 drop/重建前确认数据库归属并备份有价值数据。

建立基线搜索与静态门禁证据。预期旧身份搜索当前会命中 `MemoryAttachment`、`packageId` 和 `skuIds`，recall trace 搜索只命中 Eval API；把实际命中写入本计划 `Surprises & Discoveries`，不要提前把阶段标记完成。

阶段完成时，接口方案、数据库方案、受保护用户文件和精确验证 reactor 已写入 Decision Log；未修改生产行为也能通过现有最小模块静态门禁，或者已记录由范围外脏改动导致的具体阻塞。

### 阶段 1：canonical references 成为唯一 recall 身份

先改 `pixflow-module-memory` 的 public request records 和对应测试，删除 `MemoryAttachment` 以及 package/task/SKU 平行字段。再改 `pixflow-agent` 的 `MemoryRecallSignal`、`MemoryRecallPlanner`、`AgentOrchestrator.recall/prepareTurn/driveTurn`，从 Conversation 已校验的 ordered `MessageReference` 只投影 `referenceKey + displayPathSnapshot`。不得把 references 塞进 metadata 后继续传旧身份字段。

在 `pixflow-module-memory/pom.xml` 增加 File public API 依赖。实现内部 reference resolver：逐个 canonical key 解析；SKU/IMAGE 直接收集非空 SKU；PACKAGE 单 key 展开后收集图片 SKU；保持首次出现顺序并去重。解析失败、tombstone、空包和无法映射 SKU 分别进入 safe trace，不阻断其他 key。对单个 PACKAGE 的展开设置配置化且有明确默认值的上限，超限时稳定截断并记录，不进行无界 SQL/内存扩张。

删除从 user prompt 正则、附件文件名或 display path 推断 SKU 身份的逻辑。Prompt 文本仍可提供 category/intent/metric 非权威信号，但形如 `SKU123` 的自由文本不能绕过 canonical reference 成为精确 SKU history filter；若产品未来需要文本 SKU 搜索，应走 Commerce owner 的显式查询，不在 Memory 猜测。

补齐单元测试：PACKAGE、SKU、original IMAGE、generated IMAGE、重复 key、多 key 顺序、无 SKU image、空 package、非法 key、tombstone 和 resolver 异常。证明 File display path/object key 不进入 filters；证明 `MemoryContextRequest` 已无法构造旧身份。

阶段完成时运行 Memory/File/Agent 静态门禁和定向测试；负向搜索对生产源码不再命中 `MemoryAttachment` 或 Memory request 的 `packageId/taskId/skuIds`。

### 阶段 2：逐数据源降级、稳定融合与总预算

把 `MemoryContextBuilder` 改为逐 section fault isolation。偏好查询失败时只清空 `user_preferences` 并记录 `preference_unavailable`；SKU history 查询失败时只清空 `sku_history`；insight 保留已有 vector/FULLTEXT 双路降级。异常日志保留脱敏分类和 traceId，不把 SQL、凭证或完整异常文本放进 `MemoryContext`。

明确 insight 四种组合：vector 和 FULLTEXT 都成功则 RRF；vector 失败则 keyword-only；FULLTEXT 失败则 vector-only；两者都失败则空 insight section。Embedding 失败属于 vector 路失败。collection 缺失、维度或 distance 不匹配是确定性 unavailable，不重试建表；transport failure 由 Vector 有界重试后交给 Memory 降级。

实现一个单一 budgeter，在所有 source 返回并按稳定规则排序后按 `tokenBudget` 选 item。优先级固定为用户偏好、引用 SKU history、analysis insight；section 内不改变 owner 排序；跨 section 和同分候选使用稳定业务 id 作为最终 tie-breaker。预算估算器必须与 Prompt section 使用同一规则或共享窄接口，避免 Memory 声称未超预算而 Prompt 实际超出。任何 section 的 `renderedText`、items 和 tokenEstimate 必须来自同一 selected list。

清理只读排序中的歧义。允许读取现有 confidence、importance、decay、createdAt、lastReinforcedAt 作为预装事实，但排序不能在一次请求中多次读取 wall clock；每次 `prepareContext` 捕获一个 `asOf` 并写入 trace。同一 request、同一数据库快照、同一 `asOf` 必须得到相同顺序。任何 access count、last recalled、updatedAt 或 lifecycle transition 都不得发生。

新增 write-spy/transaction 测试。对 mapper 使用只读 fake 或 SQL proxy，断言所有成功和降级路径只发 SELECT；对 Qdrant 测试 client 断言 runtime 只调用 search/get/collection read，不调用 upsert/delete/create。测试 token budget 为 0、刚好容纳一项、跨 section 用尽、超长单项、Unicode 文本和重复候选。

阶段完成时，Memory 在任意单源故障下都返回 bounded `MemoryContext`；所有 section token 总和不超过请求预算；重复调用不改变 MySQL 行或 Qdrant point。

### 阶段 3：Agent Prompt、recall trace 与视觉事实并行消费

更新 `MemoryRecallPlanner` 使其只做 request adaptation，并在 Memory 未预见异常时返回带 `agent_memory_recall_failed` safe reason 的空 context，同时记录 metrics。不要在这里实现 source-specific fallback。Agent Properties 保留一个总 recall max token 配置，具体 source topN/RRF/阈值仍归 `pixflow.memory.*`。

更新 `PreferenceSection` 与 `LongTermMemorySection` 测试，证明只消费选后 `renderedText`，空 section 不渲染，recall trace/分值/degradation detail 不进入模型文本。Prompt fingerprint 使用稳定、已限界的 section 内容和必要版本，不直接对包含 `asOf` 或诊断时间的完整 trace 求 hash，否则相同内容会每轮破坏 cache。

接通 recall trace。推荐在 Agent 完成召回后，把一个不可变、bounded、sanitized 的 recall snapshot 写入 `RuntimeState.metadata`；`AgentLoop.runLoop` 创建 `TurnTrace` 后只转投一次，`TraceFanout` 负责把 section/source 统计适配为 `TraceRecall`。每个模型 iteration 不重复写同一 recall。测试 COMMITTED、ABORTED、CANCELLED 回合都能保留已发生的 recall trace，Eval 写失败继续遵循 best-effort，不阻断对话。

在 App 组合根测试中同时装配 Memory、Vision tool、Tool Registry、Agent 和 mock model。第一轮模型输入含 Memory Context；当 mock model 调用 `get_product_visual_facts` 后，第二轮能看到 tool result；Memory trace 和 Vision tool trace 分别存在。断言 Memory 数据库/Qdrant 不出现视觉事实，Vision lookup 不触发 Memory 调用，tool response 不含 `lastWriter`、provider、prompt、attempt 或 operational metadata。

增加 Main Agent 工具表面测试：`get_product_visual_facts` 可见且 schema 只有 `referenceKey`；`recall_memory`、`write_memory`、`remember`、通用 `vision`/`agent(type=vision)` 不存在。保留 `submit_image_plan`/`submit_imagegen_plan` 等既有工具，不把本阶段扩大成工具系统重构。

阶段完成时，一条 mock 回合可同时证明自动只读 Memory 注入和显式视觉事实读取，两者各自可观测且没有互写。

### 阶段 4：Hooks、Session Memory、Rubrics、schema 与架构守护

全仓审计所有 `HookCallback`。允许 `TurnStoppedSessionMemoryHook` 更新 conversation-scoped `session_memory`，允许 `PreCompactSummaryHook` 注入摘要指令；禁止任何 callback 在 `ASSISTANT_MESSAGE_COMPLETED`、`TURN_STOPPED` 或 `TASK_COMPLETED` 上调用业务 Memory mapper/service、Vector writer 或伪装后的 import/reindex service。通用 HookEvent 保留。

新增或扩展 ArchUnit/反射守护：Memory 门面只有 `prepareContext`；Agent 不能依赖 Memory `preference/skuhistory/insight/recall/config` 内部包；Hooks、Conversation、Task、Rubrics 不能依赖 Memory/Vector writer；Vector API 精确为三方法且生产 Spring context 不暴露 `QdrantClient`；Memory 不依赖 Vision；Vision 不依赖 Memory；Agent-visible descriptors 不含 memory tool 和旧 vision tool。

删除已废弃的 ingest/reinforce/lifecycle writer/noop writer/rebuild compensation 类型、配置、测试和 migration 残留。搜索必须覆盖同义动词和基础设施调用，而不只覆盖旧类名。运维导入工具若在未来另建，必须位于独立运行入口和独立凭证，不作为本计划的 Spring bean；当前没有明确运维需求时不要顺手创建。

对数据库目标 baseline 删除 `sku_history.rubrics_score`。保留 read-only lifecycle fields 时，在 schema 注释和测试中证明在线代码没有 UPDATE。不得因为字段名 `confidence` 或 `last_reinforced_at` 存在就误删管理员预装事实的排序输入；真正禁止的是在线 mutation 与 Rubrics score/Promotion 关联。

阶段完成时负向搜索清零，合法 Session Memory 测试仍通过，Rubrics 自动化仍只写 Rubrics 自己的 run/alert facts。

### 阶段 5：生产装配、真实依赖故障矩阵与最终验收

在 `pixflow-app` 中验证生产 graph 对 Memory 必需依赖 fail fast、对可降级依赖 fail soft。File resolver 和 MySQL read repositories 是构造期明确依赖，不能通过 `NoopPreferenceService`/`NoopSkuHistoryService` 静默掩盖生产漏装；Qdrant/Embedding 可以不可用并进入明确 degraded readiness。测试 slice 可以显式提供 fake，而生产 profile 不使用 `@ConditionalOnBean` 让整个 recall 路径悄悄消失。

使用 Testcontainers 或现有 Docker compose 运行 MySQL 与 Qdrant，数据由测试 admin fixture 显式 seed。故障矩阵至少覆盖：全成功、Qdrant 停止、错误维度、错误 distance、embedding provider 失败、FULLTEXT SQL 失败、偏好查询失败、SKU history 查询失败、MySQL 全断、非法/tombstoned reference、Eval sink 失败。每个场景都断言 Conversation 是否继续、哪些 section 保留、degraded reasons、trace 边界和零写副作用。

最后运行全仓 static gate、Maven tests 和精确负向搜索。若父 Milestone 7 的 App/Flyway 改动仍在进行，先合并其最新 owner baseline，再运行最终 App context；不能用旧本地 Maven artifact 或 `-rf :pixflow-app` 结果代替 clean reactor 证据。

完成全部验收后，把父计划 Milestone 5 标为完成并写入日期与证据；不要顺带勾选 Milestone 6/7。更新本计划 Outcomes，列出测试数量、容器零 skip、任何保留字段和后续运维数据准备缺口。

## Concrete Steps

所有命令在 `D:\study\PixFlow` 执行。开始先记录状态和旧路径：

    git status --short
    rg -n "MemoryAttachment|packageId|taskId|skuIds|MemoryIngestService|InsightIngestService|ingestAsync|reinforce\(|upsert|deleteByFilter|AssistantMemoryIngestionHook|recall_memory|write_memory|remember" pixflow-module-memory pixflow-infra-vector pixflow-agent pixflow-hooks pixflow-loop pixflow-app
    rg -n "recordRecall|TraceRecall|recall_json|recallTrace" pixflow-agent pixflow-loop pixflow-eval
    rg -n "get_product_visual_facts|VisionSubagentTool|agent\(type=vision\)|ProductVisualFacts" pixflow-module-vision pixflow-agent pixflow-app

每个 Java 切片先运行 lint/static/compile gate，成功后才运行测试。canonical reference 切片：

    mvn -pl pixflow-module-file,pixflow-module-memory,pixflow-agent -am -DskipTests verify
    mvn -pl pixflow-module-file,pixflow-module-memory,pixflow-agent -am test

Vector/降级/预算切片：

    docker info
    docker compose up -d mysql qdrant
    docker compose ps
    mvn -pl pixflow-infra-ai,pixflow-infra-vector,pixflow-module-file,pixflow-module-memory -am -DskipTests verify
    mvn -pl pixflow-infra-vector,pixflow-module-memory -am test

Agent/Loop/Eval/Vision 组合切片：

    mvn -pl pixflow-hooks,pixflow-eval,pixflow-loop,pixflow-module-memory,pixflow-module-vision,pixflow-agent,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-hooks,pixflow-eval,pixflow-loop,pixflow-module-memory,pixflow-module-vision,pixflow-agent,pixflow-app -am test

若 Docker 不可用，记录实际错误并完成纯单元/架构测试，但不得把真实依赖阶段标记完成，也不得接受 skipped Testcontainers 作为成功。不得删除现有 container、volume 或用户数据以修复测试。

最终验收按以下顺序：

    mvn -DskipTests verify
    mvn verify
    rg -n "MemoryIngestService|InsightIngestService|ingestAsync|reinforce\(|deleteByFilter|AssistantMemoryIngestionHook|recall_memory|write_memory|remember|rubrics_score" --glob "*.java" --glob "*.sql" --glob "*.xml" --glob "*.yml" --glob "*.yaml" --glob "!**/target/**" --glob "!docs/**" .
    rg -n "MemoryAttachment|source_image_ids|sourceImageIds" pixflow-module-memory pixflow-agent pixflow-hooks pixflow-app
    rg -n "upsert|delete|createCollection|QdrantClient" pixflow-infra-vector/src/main pixflow-module-memory/src/main
    rg -n "module\.memory|infra\.vector" pixflow-hooks/src/main pixflow-conversation/src/main pixflow-module-task/src/main pixflow-module-rubrics/src/main
    rg -n "recall_memory|write_memory|agent\(type=vision\)|VisionSubagentTool" pixflow-agent/src/main pixflow-app/src/main pixflow-module-vision/src/main
    rg -n "pixflow-module-memory|pixflow-infra-vector|pixflow-agent|pixflow-hooks" config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml
    git diff --check
    git status --short

负向搜索应对生产源码和当前 schema 无输出。`QdrantClient` 可以出现在 `pixflow-infra-vector` adapter 私有实现和测试 admin fixture，但不能穿过 `VectorSearch`、Spring bean surface 或 Memory；因此第三条搜索的命中必须逐条审阅，不能机械追求零输出。Memory 自己的 confidence/importance/decay/lastReinforced read fields 是允许的既有事实；任何 setter/update SQL、访问计数增长或 Rubrics score 才是失败。

## Validation and Acceptance

Canonical identity 验收要求 `MemoryContextRequest` 只含 references，不含 attachment/package/task/SKU 平行身份；PACKAGE/SKU/IMAGE 都经 File owner API 得到 filter；generated IMAGE 与 original IMAGE 行为一致；重复 reference 不重复召回；无效 key 只降级该 signal；display path 和 prompt 中的 SKU-like 文本不能伪造精确 filter。

只读验收要求所有 recall 成功、partial failure 和 total failure 路径对 MySQL/Qdrant 都没有写；不存在 access-count、last-recalled、reinforce、expire、suppress、upsert、delete、rebuild 或 compensation side effect。重复同一请求前后数据快照字节级相同。

检索验收要求 vector + FULLTEXT 使用确定性 RRF；Qdrant 失败回落 FULLTEXT，FULLTEXT 失败保留 vector，两者失败为空 insight；偏好或 SKU history 单独失败不删除其他 section；MySQL 全断返回空/部分 context 且 Agent 回合继续。collection 缺失、dimension/distance mismatch 不自动创建或迁移 collection。

预算验收要求三个 section 的总 tokenEstimate 不超过 request budget；优先级固定，item 不被半截断；budget=0、空数据、超长首项和 Unicode 均有明确行为；相同 request/fact snapshot/asOf 返回相同 items、顺序、renderedText 和 fingerprints。

Agent 验收要求首个 model input 自动含允许的 Memory sections，模型不可调用 recall/write memory tool；涉及外观时可显式调用唯一 `get_product_visual_facts(referenceKey)`，后续 input 看到 observation-only tool result；Memory 与 Product Visual Facts 同时可用但不互相存储或触发。

Trace 验收要求每回合至多记录一组 recall facts，包含 source outcome、候选/选中数量、filters 摘要、budget 与 degradation，不包含 embedding、完整未选正文、writer metadata、对象位置、SQL、密钥或绝对路径。Eval sink 失败不阻断 Conversation。

Hooks/架构验收要求保留合法 Session Memory 与 compaction hook；ASSISTANT/TASK/TURN 事件没有业务 Memory writer callback；Memory、Vector、Vision、Rubrics 之间的禁止依赖由 ArchUnit 守护；Vector 生产 API 仍精确三方法；Agent 只依赖 Memory public context contract。

最终完成标准是所有定向静态门禁、模块测试、真实 MySQL/Qdrant 故障矩阵、App production context 和全仓 `mvn verify` 成功且容器测试零 skip；负向搜索满足预期；父计划 Milestone 5 更新为完成。

## Idempotence and Recovery

所有搜索、lint、compile、test 和只读查询命令可重复执行。Testcontainers seed 使用测试独有 collection/database 名和固定 business ids；重复运行先按测试 fixture 自己的 namespace 清理，不能删除开发者已有 Qdrant collection、Docker volume 或 MySQL schema。

迁移按先 request types/tests、再 File adapter、再 source degradation/budget、再 Agent/trace、最后删除旧类型的顺序进行。每个阶段保持 reactor 可编译；若中途停止，在 Progress 拆出已完成与剩余项。不要用长期双字段 DTO、deprecated constructor 或 metadata fallback 让新旧身份共存。

Memory recall 是可重算读操作，不需要 checkpoint。依赖恢复后下一回合自然重新召回；同一回合不在后台补写 Prompt。Qdrant collection 配置错误由运维修复后重启或 readiness reprobe，不由应用自动建集合。Eval trace 丢失按 best-effort 可观测处理，不重放 recall 造成业务写入。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore、清理用户工作树或删除无关文件。当前工作树有大量在途修改；发生同文件冲突时先阅读 diff，在既有内容上做最小补丁。无法安全合并时，把文件、冲突语义和可继续的独立阶段写入 Progress，而不是覆盖。

## Artifacts and Notes

目标在线链路：

    Conversation ordered MessageReference
      -> Agent MemoryRecallPlanner
      -> MemoryService.prepareContext
      -> File public canonical resolve/expand
      -> preference SELECT
      -> exact SKU history SELECT
      -> query embedding + VectorSearch and FULLTEXT SELECT
      -> deterministic RRF + stable ranking
      -> cross-section token budget
      -> MemoryContext + bounded RecallTrace
      -> Agent Prompt sections
      -> Loop TraceFanout -> Eval recall_json

独立视觉事实链：

    Main Agent sees get_product_visual_facts(referenceKey)
      -> Vision ProductVisualFactsLookup
      -> observation-only current facts
      -> tool result in next model iteration

禁止链路：

    Conversation / Agent / Hooks / Task / Rubrics -X-> business Memory write
    Memory -X-> Product Visual Facts / Vision
    Vision -X-> Memory / Qdrant
    Vector runtime -X-> upsert / delete / create / rebuild
    Session Memory -X-> user_preference / sku_history / analysis_insight

关键故障预期：

    bad reference -> omit that signal + trace reason
    Qdrant down -> FULLTEXT-only insight
    FULLTEXT down -> vector-only insight
    both insight sources down -> empty insight section
    preference/history source down -> preserve other sections
    MySQL fully down -> partial or empty MemoryContext
    unexpected Memory facade failure -> Agent empty context fallback
    Eval sink down -> conversation continues

## Revision Notes

2026-07-20 / Codex: 创建 Milestone 5 专项中文 ExecPlan。基于当前源码确认 Vector/Memory 只读主体和 Product Visual Facts tool 已存在，把剩余实施固定为 canonical reference 请求切换、File resolver、逐源降级、总 token budget、recall trace、Hooks/Session Memory 边界、schema 清理、App 组合和真实故障矩阵；记录当前脏工作树并禁止覆盖用户修改。本次只撰写计划，未改生产代码、未运行测试、未更新父计划完成状态。

2026-07-20 / Codex: 实施阶段 1 和阶段 2 核心代码：删除旧 Memory material identity，新增 canonical `MemoryReference` 与 File public resolver/expander recall filter；各 Memory source 独立降级并落实跨 section token budget；Agent 在未预期 Memory 门面错误时提供空的 degraded context。删除 `sku_history.rubrics_score` fresh schema 列。定向 compile 和单元测试通过；真实依赖故障矩阵、trace 投影和 App 组合尚未完成。
