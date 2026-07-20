# 彻底完成后端架构对齐 Milestone 4：可恢复 Product Visual Facts

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，是 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 中 Milestone 4 的专项实施计划。执行者应当只依赖当前工作树和本文就能完成工作；每次停止都要更新本文。若改变事实模型、事件可靠性、模型调用预算、模块依赖、REST 合同、前端交互、验证顺序或兼容策略，必须同时更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，上传并完成解压的 Asset Package 会在后台为每个 SKU 生成一份可恢复、可直接编辑的当前 Product Visual Facts。管理员打开 Materials 的 Original Image 详情，可以看到同 SKU 的完整商品视觉分析，直接替换整份事实，或单击一次重新分析；页面只在分析活动期间每两秒轮询，未保存草稿不会因切图或离开页面而丢失。图片内容集合变化会清空过期 SKU facts 并自动分析，改名、排序、预览 URL、模型、prompt、预处理配置或事实 schema 版本变化都不会自动重跑。

主 Agent 不再拥有通用视觉子 Agent或任意“图片 + 问题”入口。它只能调用 `get_product_visual_facts(referenceKey)` 读取当前 SKU 或目标图事实；营销文案和 YAML redraw prompt 仍由主 Agent 基于这些事实推理。Vision 不再生成或写入 `asset_copy`，不把视觉事实写入 Memory/Qdrant，也不保留事实修订、完成运行、完整 prompt 或 raw provider output 历史。

开发者可以观察两条闭环。第一条是上传后等待后台工作，打开 `/materials/packages/{packageId}/images/{imageId}`，看到 Product Visual Analysis 从 `PENDING/RUNNING` 变为可编辑事实；第二条是在 Agent 回合中用 canonical SKU/IMAGE `referenceKey` 调用唯一 lookup tool，得到 `AVAILABLE`、`ANALYSIS_PENDING` 或 `UNAVAILABLE`。故障测试必须证明 MQ 重投、Redis 锁丢失、stale heartbeat、HTTP transport retry、结构修复、管理员并发编辑和进程崩溃都不会产生第二份 current row、突破三次真实 provider attempt，或让旧 generation/epoch 覆盖新事实。

## Progress

- [x] (2026-07-19 00:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、设计索引、全部活动 ExecPlan、领域 Context Map、Milestone 4 权威设计和 Lint 规则。
- [x] (2026-07-19 00:00+08:00) 审计 Vision、AI、File、App、Agent 和 Web 当前实现、数据库资源、自动配置、架构测试及 Checkstyle suppression。
- [ ] Milestone 0：冻结公开合同、旧路径清单、数据库测试基线和每个小提交的 lint-before-test 门禁。
- [x] (2026-07-19 17:59+08:00) Milestone 1：`VisionRequest` 与底层 `ChatRequest` 已携带工具 schema、tool choice 和 request-scoped `AttemptBudget`；每次真实 provider HTTP attempt 在全局并发与 quota 准入之后、出站 HTTP 前执行 reserve。`mvn -pl pixflow-infra-ai -am -DskipTests verify` 与 `-am test`（35 项 AI、23 项 Common）均通过。
- [x] (2026-07-19 21:10+08:00) Milestone 2：已建立唯一 current SKU/image facts、current work item 与 package aggregate；管理员替换使用 fact version CAS，重分析使用 generation/requestId，AI 写入按 expected version CAS。version 0 采用 `insert ignore`，version > 0 显式按 expectedVersion 更新；CAS 失败不会完成 work item。
- [x] (2026-07-19 02:03+08:00) Vision-only Milestone 2 切片：新增 closed/bounded Product Visual Facts API、规范化/JSON codec、管理员 read/replace/reanalyze service、current-state MyBatis store、fresh schema 与 V1 migration；CAS、active conflict、requestId 幂等和零图失败语义已有单测。完整 Milestone 2 仍等待真实 MySQL migration/并发集成验收。
- [x] (2026-07-19 21:10+08:00) Milestone 3：`asset_image` 已增加 `content_hash`/`updated_at`（fresh schema 与 V7 migration 一致），Original/Generated Image 写入 SHA-256；File-owned outbox 在 package READY/PARTIAL、Original Image 删除和 package 删除的 preparation 事务内原子记录事件，App bridge 通过 RocketMQ 发布 Vision trigger。归档数据库写失败会删除已上传对象，旧兼容构造器已删除。
- [x] (2026-07-19 21:10+08:00) Milestone 4：已完成 package/SKU coordinator、确定性抽样、单 IMAGE focused analysis、强制事实工具解析、持久 attempt budget、generation/epoch/version fenced commit、周期 heartbeat、stale recovery、工作消息发布和失败聚合重算。三类 RocketMQ destination/binding/consumer、retry/DLQ handler 与定时 dispatcher/recovery 入口已装配。
- [x] (2026-07-19 02:03+08:00) Vision-only Milestone 4 前置切片：实现内容 hash fingerprint、稳定 rank 两图抽样、package/SKU coordinator、已知空 SKU 收敛、先持久化 PENDING 后发布的 work seam，以及内容变化清事实/推进 generation、内容不变 no-op。Provider forced-tool、attempt reservation、lock/epoch fenced commit、heartbeat/recovery 仍未实现。
- [x] (2026-07-19 21:10+08:00) Milestone 5：Vision 已贡献唯一 `get_product_visual_facts` descriptor/handler；lookup 支持 SKU 与 IMAGE reference，IMAGE 可尝试有界 focused execution。Agent 已删除 `VisionSubagentTool`、`SubagentType.VISION`、`SubagentRequest.vision` 和 vision 参数解析，并在主 Agent 行为规则中要求外观文案、视觉比较和 redraw prompt 先查询事实，UNAVAILABLE 时禁止猜测。
- [x] (2026-07-19 16:45+08:00) Tools-only Milestone 5 前置切片：`DefaultToolRegistry` 对启动期和动态注册的所有输入 schema 统一强制非空 `type=object` 与 `additionalProperties=false`，并以 `get_product_visual_facts` 命名的公开 descriptor seam 完成 red-green 单测；双轴审查发现并修复空 schema 绕过。实际 Vision handler、Agent schema 切换和跨模块装配未实施，Milestone 5 保持未完成。
- [x] (2026-07-19 02:25+08:00) Vision-only legacy removal：按用户明确要求删除 `VisionService`、`DefaultVisionService`、旧 `analyze/*`、全部 Copy Enrichment/`asset_copy` mapper、旧 storage image resolver/preprocessor、旧 metrics/properties/error codes及对应测试；自动配置改为 `VisionAutoConfiguration`，Vision Checkstyle suppression 清零。Agent 侧 `agent(type=vision)` 与唯一 lookup handler 仍属后续跨模块切片。
- [x] (2026-07-19 21:10+08:00) Milestone 6（非前端）：App 已装配 Vision GET/PUT/reanalyze controller、File-to-Vision adapter 和安全事实 lookup；Original Image detail read model 已包含 previous/next image IDs。前端页面和交互仍明确留在 Milestone 7。
- [ ] Milestone 7：实现前端图片详情、完整表单、轮询、dirty guard、版本冲突与响应式布局。
- [ ] Milestone 8：真实依赖故障矩阵、全仓静态门禁和端到端验收按用户范围决定推迟到后续里程碑。旧路径清零已于 2026-07-19 完成并经三条验收搜索验证（生产源码 `VisionService|DefaultVisionService|ProductCopyExtractor|CopyEnrichment|VisionSubagentTool|agent(type=vision)` 零命中、Checkstyle/SpotBugs 中 `pixflow-module-vision` 清零、无 `facts_revision|snapshot_history|completed_run|raw_provider_output` 表）；父计划 Milestone 4 已据此回写勾选。

- [x] (2026-07-19 21:10+08:00) 已完成的局部验证：30 模块生产源码 reactor compile 成功；File 61 项、Vision 29 项、Agent 39 项、App Vision REST 2 项测试均零失败/错误/跳过。File 测试包含真实 MySQL 与 MinIO Testcontainers。完整 App reactor、Vision 真实 MySQL migration/CAS/fencing 集成测试、全套 verify 和最终双轴 code review 仍属于 Milestone 8。

## Surprises & Discoveries

- Observation: 当前 `pixflow-module-vision` 不是 Product Visual Facts 子系统，而是同步 `VisionService.analyze(VisionAnalysisRequest)` 加上传期 Copy Enrichment。
  Evidence: 生产源码仍包含 `DefaultVisionService`、`VisionAssessment`、`ProductCopyExtractor`、`CopyEnrichmentConsumer`、`AssetCopyWriteMapper`，且 `VisionServiceAutoConfiguration` 用 `@ConditionalOnBean` 让富化能力静默消失。

- Observation: 当前 `infra/ai` 设计文档已经描述 request-scoped `AttemptBudget`，源码合同尚未实现。
  Evidence: `VisionRequest` 目前只有 `role/messages/options`；`DefaultVisionModelClient` 把 tool schemas 和 tool choice 传为 `null`；`ModelRetryRunner` 可以在一次 `call` 内多次发起 HTTP，但没有向 Vision 持久工作项报告每个 attempt。

- Observation: File 当前没有每张 Asset Image 的内容 SHA-256，也没有 package-ready 或 SKU visual-input-changed 的可靠生产事件。
  Evidence: `asset_image` 和 `AssetImageDescriptor` 没有 `contentHash`；`ZipExtractor` 在终态只写 package status 和 progress；生产搜索只命中 extraction MQ publisher，没有 `PACKAGE_VISUAL_ANALYSIS_REQUESTED` 或 `SKU_VISUAL_INPUT_CHANGED`。

- Observation: Agent 仍保留已被目标设计删除的视觉 child runtime。
  Evidence: `VisionSubagentTool`、`SubagentType.VISION`、`SubagentRequest.vision(...)`、`parseVision(...)` 和对应测试仍存在。

- Observation: Web 只有 `/files` 和预览 dialog，没有权威设计要求的 Materials Original Image detail route、Vision API client、完整事实表单或活动期轮询。
  Evidence: `pixflow-web/src/router/index.ts` 只注册 `/files`；`src/api`、`src/stores` 和 `src/pages` 中没有 Product Visual Facts 类型或页面。

- Observation: 活动 Lint 计划尚未清空 Vision、infra-ai 和 App 的全部历史 suppression。
  Evidence: `config/checkstyle/suppressions.xml` 仍有 14 个 Vision 条目，并有 `DefaultChatModelClient`、`ModelRetryRunner` 等本计划将修改文件的条目；File 和 Agent 已清零，Web suppression 已为空。

- Observation: 当前工作树含 Milestone 3 收尾和 File/Task/App 的用户修改。
  Evidence: `git status --short` 显示父计划、File 计划、Lint 计划及 Generated Image publication 相关文件有未提交改动。本计划不得格式化、还原或覆盖这些修改。

- Observation: 在不修改其他模块的约束下，Vision 可以独立冻结 current-state 合同和持久化语义，但不能闭合 provider、触发或管理员 HTTP 链。
  Evidence: `infra/ai` 尚无 attempt/tool-choice seam，File 尚无 content hash/outbox，App 尚无 `VisualAssetReader` adapter；因此本切片保留旧 `VisionService`/Copy Enrichment 供未迁移消费者编译，且新管理员 service 仅在 state store 与 asset reader 同时存在时装配。

- Observation: Vision-only 与上游组合测试可在当前 Docker 环境内完成且零跳过。
  Evidence: `mvn -pl pixflow-module-vision -am test` 于 2026-07-19 02:03+08:00 BUILD SUCCESS；Common 23、Storage 19、AI 35、MQ 17、Image 32、Vision 50 项均零失败/错误/跳过，Storage 的真实 MinIO Testcontainers 测试已运行。

- Observation: 旧 Vision public API 在其他模块生产源码中已无引用，兼容保留没有实际消费者。
  Evidence: 排除 Vision 模块后的 `VisionService`、`VisionAssessment`、`CopyEnrichment` 等引用搜索无输出；删除后 `mvn -pl pixflow-module-vision -am -DskipTests verify` 与 `-am test` 均成功。

- Observation: Tools Registry 已具备业务模块贡献 descriptor/handler、可见集合单一来源和 Plan mode 过滤能力，但注册边界没有落实设计文档的 unknown-field closed 不变量。
  Evidence: 修改前 `DefaultToolRegistry` 仅校验 `inputSchema.type=object`；`DefaultToolRegistryTest.rejectsInputSchemaThatAllowsUnknownProperties` 首次运行按预期失败，证明 `additionalProperties=true` 会被接受。

- Observation: Maven 增量编译缓存曾生成带 `Unresolved compilation problems` 的残缺 `AssetImageVisualWriter.class`，源码本身并非不可编译。
  Evidence: 删除 `pixflow-module-file/target/classes` 与对应 `target/maven-status/maven-compiler-plugin/compile` 后强制完整 javac，File 主源码和测试源码均编译成功，随后 61 项测试全绿。

- Observation: 单独运行 `mvn -pl pixflow-app test` 会从本地仓库解析旧 snapshot，导致 `SourceImageInfo`、`AssetImageDescriptor` 构造器和 `AssetReferenceCatalog.listGeneratedByTaskId` 的 `NoSuchMethodError`。
  Evidence: 把 File、Imagegen、Vision 与 App 放入同一 reactor 后可使用当前工作树合同；这些错误不是当前源码断言失败，最终 App 全套测试仍须用完整当前 reactor 重跑。

- Observation: 当前 File lint 仍报告约 52 项 Checkstyle，主要为 `EmptyLineSeparator` 和单行 getter 的 `LeftCurly`。
  Evidence: 用户明确要求 linting 时仅记录 Checkstyle 问题、不修复；因此这些问题不作为本轮文档更新的代码修改项，也不通过新增 suppression 隐藏。

## Decision Log

- Decision: 本次执行不运行全仓 Maven 或前端测试；仅运行每个触达后端切片的 `-pl <touched-modules> -am` 静态门禁及定向测试。
  Rationale: 用户明确要求避免全仓测试；局部验证仍须维持“静态门禁先于测试”的顺序。
  Date/Author: 2026-07-19 / Codex

- Decision: 本轮只修改 `pixflow-tools` 和本 living ExecPlan，不在 Tools 内实现或特判 Product Visual Facts handler。
  Rationale: `harness/tools.md` 明确 Tools 只拥有通用 SPI 与执行管线，具体 `get_product_visual_facts` descriptor/handler 归 Vision 贡献；把业务查询放入 Tools 会造成反向业务依赖。Tools 本轮只收紧所有业务工具共同依赖的 closed input-schema 注册边界。
  Date/Author: 2026-07-19 / Codex

- Decision: 本轮只实施 `pixflow-module-vision` 自有切片和必需的 ExecPlan/Lint 记录，不修改 infra-ai、File、App、Tools、Agent 或 Web；旧跨模块入口暂不删除。
  Rationale: 用户明确限定只做 Vision 模块且不做前端。直接删除旧入口会使尚未迁移的其他模块无法编译；伪造本地 provider retry 或直接读取 File 表又会违反权威模块边界。
  Date/Author: 2026-07-19 / Codex

- Decision: 后续用户指示 supersede 上述兼容决定；Vision 模块内旧同步问答和 Copy Enrichment 立即删除，不保留 alias、旧 Bean、旧配置或旧测试。
  Rationale: 仓库处于开发期，用户明确要求不为兼容保留；全仓引用审计也证明没有其他生产消费者。跨模块尚未实现的新能力继续通过目标端口表达，而不是复用旧路径。
  Date/Author: 2026-07-19 / Codex

- Decision: package coordinator 对“当前资产 SKU”与“Vision 已知 SKU”取并集后重算。
  Rationale: 只按当前图片分组会遗漏最后一张 Original Image 被删除的 SKU，无法把其 fingerprint 变为空集合、清除旧事实并终态为 `NO_IMAGE`。
  Date/Author: 2026-07-19 / Codex

- Decision: Vision 只保存一份可变 current fact 和一份可恢复 current work item；不存在 fact revision、completed run、raw response 或完整 prompt 表。
  Rationale: ADR 0008 把管理员替换视为纠正当前事实，无历史产品功能；保留旧内容会违背用户删除/纠正意图并造成无界增长。
  Date/Author: 2026-07-19 / Codex

- Decision: Vision 定义 `VisualAssetReader` 和触发/查询公开合同，`pixflow-app` 用 File public API 与 Storage 实现 adapter；Vision 不 import File entity/mapper，File 也不 import Vision。
  Rationale: File 拥有图片身份与字节位置，Vision 拥有事实和工作状态；App 是唯一允许同时依赖两侧的组合根。
  Date/Author: 2026-07-19 / Codex

- Decision: File 使用可恢复 outbox 记录 package-ready 和 SKU input-changed，App bridge 把 outbox event 发布到 Vision-owned RocketMQ destinations；成功确认后才删除 outbox row。
  Rationale: “DB 终态已提交、进程在发布前崩溃”不能靠内存 Spring event 修复。File-owned outbox 不含 Vision 类型，重复发布由 Vision current-row 状态机吸收。
  Date/Author: 2026-07-19 / Codex

- Decision: `asset_image.content_hash` 迁移先允许已有行为空，但所有新图片写入必须带 SHA-256；提供流式、可恢复的 backfill，Milestone 4 验收要求所有 processable 图片的 hash 均非空。
  Rationale: SQL migration 无法从 MinIO 安全计算已有对象 SHA-256，直接加 `NOT NULL` 会让旧库无法升级。生产代码对缺 hash fail closed；物理 `NOT NULL` 留给确认 backfill 完成后的 schema-cleanup migration。
  Date/Author: 2026-07-19 / Codex

- Decision: Vision 的 persistent attempt reservation 发生在 AI 并发许可和本地 quota 准入成功之后、provider HTTP 发出之前；transport retry 与结构修复复用同一个 budget owner。
  Rationale: 本地 quota 拒绝没有发出 provider HTTP，不应消耗业务三次预算；把 callback 放在真正 outbound 边界可避免 `structureRounds * transportRetries` 的乘法调用。
  Date/Author: 2026-07-19 / Codex

- Decision: SKU 抽样用 `SHA-256(seed + imageId)` 形成稳定 rank，按 rank、imageId 排序后取前两张；seed 由 `packageId + skuId + visualInputFingerprint` 生成。
  Rationale: 这种确定性 shuffle 不依赖集合遍历顺序或 JVM 随机实现，MQ 重投、恢复和手动重分析会选择完全相同且不重复的图片。
  Date/Author: 2026-07-19 / Codex

- Decision: PACKAGE lookup 返回按 `skuId` 排序、最多 20 个 SKU 的 bounded current-facts scopes，并显式标记 `truncated`；普通 PACKAGE/SKU lookup不创建 analysis generation。
  Rationale: 这满足 PACKAGE key 可接受但不能猜测单一 SKU 的设计，同时控制 tool result 大小。只有 IMAGE lookup 缺少 image-scoped facts 时可以使用同一 work-item 机制做 focused analysis。
  Date/Author: 2026-07-19 / Codex

- Decision: IMAGE lookup 尝试在当前工具调用内取得同一 Vision lock并完成 focused analysis；已有 owner 时立即返回 `ANALYSIS_PENDING`，不并发发第二次 provider call。
  Rationale: redraw reasoning通常需要当前图片构图事实；同步补偿可以在成功时一次返回 AVAILABLE，而锁竞争时仍保持有界延迟与单 owner。
  Date/Author: 2026-07-19 / Codex

- Decision: 每个 Java 小提交先执行触达 reactor 的 `-DskipTests verify`，成功后才运行测试；每个 Web 小提交先执行 `lint` 和 `typecheck`，成功后才运行 Vitest。
  Rationale: 用户明确要求测试前 linting，活动 Lint 计划也要求修改文件与 suppression 原子处理。静态门禁失败时不得用测试成功掩盖问题。
  Date/Author: 2026-07-19 / Codex

- Decision: 用户最新指示覆盖本计划原先“发现 Checkstyle 即修复”的要求；后续 linting 对 Checkstyle 只记录、不修复，也不新增 suppression。编译、SpotBugs 和测试失败仍需正常处理。
  Rationale: Checkstyle 属于已知代码风格债务，用户要求本轮聚焦非前端功能闭环；保留原始报告能使最终验收透明，同时避免把风格清理混入功能实现。
  Date/Author: 2026-07-19 / Codex

## Outcomes & Retrospective

2026-07-19 的 Vision-only 切片已经把目标状态模型从文档推进为可编译代码：公开事实合同严格拒绝 unknown/nested fields，执行 trim、稳定去重、列表/字符串/64 KiB 限制；数据库只有一份 current SKU facts、一份 current image facts、一份 current work item 和一份 package aggregate，没有 revision/completed-run/raw-output 表。管理员替换使用 expectedVersion，重分析使用 expectedGeneration + requestId，内容变化与显式重分析均复用当前行。

该切片还完成了内容 fingerprint、确定性两图抽样、package/SKU coordinator 和输入失效存储原语。触达的 `VisionServiceAutoConfiguration` Checkstyle suppression 已删除；Vision 严格 Checkstyle/SpotBugs 为零，Vision 50 项测试和 7 模块 `-am test` 全绿、零跳过。

Milestone 2 与 Milestone 4 不能据此标记完成：真实 MySQL migration/CAS 并发测试、infra-ai persistent attempt hook、forced tool output、Vision lock/run-epoch/fact-version fenced commit、heartbeat/recovery、RocketMQ destination、File/App adapters 均仍待后续跨模块切片。旧同步 Vision/Copy Enrichment 已在用户后续指示下彻底删除；仍未实现 Agent tool、管理员 REST 或任何前端。

旧路径清理后，Vision 生产源码从 61 个类收敛到 33 个类，POM 删除未使用的 AI、Storage、MQ、Validation 与 Micrometer 依赖；MyBatis 注解和扫描配置只存在于 `persistence`。最终四模块静态 reactor 成功，Vision 23 项测试、Common 23 项和 Image 32 项均零失败/错误/跳过。负向架构测试保留旧名称字符串作为禁止清单，这是旧路径搜索中唯一允许的测试命中。

Tools-only 前置切片把 closed input schema 从各业务 descriptor 的自律提升为 Registry 注册不变量，启动期 bean 收集与动态 Skill 注册共用同一校验；空 schema、非 object schema、缺少或放开 `additionalProperties=false` 均被拒绝，避免未来视觉事实工具接收未声明的 `question`、`imageIds` 或对象存储字段。该切片没有越界实现 Vision handler、Agent prompt/schema 或 App 装配，因此唯一 lookup tool 尚不可用，Milestone 5 仍未完成。

该切片最终按静态门禁先行的顺序通过 `mvn -pl pixflow-tools -DskipTests verify`，Checkstyle 与 SpotBugs 均为零问题；随后 `mvn -pl pixflow-tools test` 运行 7 项测试，零失败、错误或跳过。双轴复审最终均无剩余发现。

截至 2026-07-19 21:10+08:00，计划中的非前端实现已形成完整生产链路。File 持久化图片内容 hash，并通过同事务 outbox 可靠表达 package-ready 与 SKU-input-changed；App 负责跨 bounded context 的事件 bridge、资产读取和管理员 REST；Vision 以 current facts/current work 为唯一事实源，完成 SKU/IMAGE 两种工作项、forced-tool provider 调用、持久 attempt accounting、heartbeat/recovery、fenced commit、聚合状态和 RocketMQ 消费；Agent 只保留 `get_product_visual_facts`，不再保留通用视觉 child runtime 或 Copy Enrichment。

验证证据包括：`mvn -pl pixflow-module-vision,pixflow-app -am -DskipTests compile` 覆盖 30 个模块并成功；File 61 项测试（含真实 MySQL、MinIO Testcontainers）、Vision 29 项、Agent 39 项和 App Vision REST 2 项均全绿且零 skip。新增覆盖集中在图片/outbox 原子写入、MyBatis CAS、heartbeat、MQ consumer、focused IMAGE analysis 和管理员 REST。

本计划尚不能整体宣告完成。Milestone 7 前端按用户范围明确未实施；Milestone 8 仍需以当前 File/Imagegen/Vision/App reactor 完成 App 全套测试，补 Vision 真实 MySQL migration/CAS/fencing 集成测试，确认 backfill 的持续调度策略和自动配置条件装配，执行旧路径零命中搜索、完整 verify，并按 `$implement` 要求完成最终双轴 code review。Checkstyle 报告仅记录，不在本轮修复。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。Java 21 Maven reactor 的 Vision 模块是 `pixflow-module-vision`，模型调用底座是 `pixflow-infra-ai`，图片/锁/MQ/Storage 底座分别是 `pixflow-infra-image`、`pixflow-infra-cache`、`pixflow-infra-mq`、`pixflow-infra-storage`。Asset Library 在 `pixflow-module-file`；Tool Registry 在 `pixflow-tools`；Agent 组合在 `pixflow-agent`；跨模块 adapters 和管理员 Web controller 放在 `pixflow-app`；Vue 3 前端在 `pixflow-web`。

Product Visual Facts 是图片中可直接观察到的结构化事实。SKU Visual Facts 是 `(packageId, skuId)` 的唯一当前文档，最多基于该 SKU 两张 Original Image。Image Visual Facts 是 `(packageId, skuId, imageId)` 的唯一当前文档，用于具体图片的构图、背景、文字和视角。Visual Analysis Work Item 是同一 scope 的唯一当前可恢复工作行，不是用户 Activity，也不是 Task。Visual Input Fingerprint 是当前相关图片内容 hash 的稳定摘要；它不含显示名、顺序、URL、模型、prompt 或 schema 版本。

`analysis_generation` 区分同一 scope 的自动失效或手动重分析代次；`run_epoch` 区分同一 generation 的崩溃接管；fact `version` 区分管理员/模型对当前文档的并发替换。模型提交必须同时满足当前线程仍持 lock、generation/epoch 仍匹配、work item 仍为 RUNNING、fact version 仍等于本 generation 开始值。三者用途不同，不能合并成一个版本号。

### 实施前必须阅读的参考文档

每次开始或恢复本计划，先读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和 `docs/design-docs/exec-plans/` 根下所有活动计划。特别重读父计划 `backend-architecture-alignment-refactor-plan.md`、与 File 重叠的 `pixflow-file-development-plan.md`、与静态基线重叠的 `lint-baseline-remediation-plan.md`，以及 `docs/development/linting.md`；它们决定当前阶段、文件所有权和 lint-before-test 门禁。

领域决策必须读 `docs/adr/0008-keep-only-current-product-visual-facts.md`、作为历史反例的 `docs/adr/0004-use-durable-product-visual-facts.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md`、`CONTEXT-MAP.md`、`docs/agents/domain.md`、`pixflow-module-vision/CONTEXT.md`、`pixflow-module-file/CONTEXT.md` 和 `pixflow-web/CONTEXT.md`。ADR 0008 优先于 ADR 0004；不得恢复不可变 snapshot/revision 模型。

Vision 主体实现前完整阅读 `docs/design-docs/module/vision.md`。跨模块工作分别阅读 `docs/design-docs/module/file.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/infra/image.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/mq.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/agent/agent.md` 和 `docs/design-docs/module/imagegen.md`。这些文档分别固定内容 hash/asset 生命周期、provider retry、probe-before-decode、流式对象读取、至少一次消息、watchdog lock、Tool Descriptor、Agent 行为和 redraw confirmation 边界。

管理员 API 和 Web 工作前完整阅读 `docs/design-docs/frontend/files.md` 与 `docs/design-docs/frontend/api.md`。后者是 REST 权威来源；不要恢复已删除的根级 `api.md`，也不要把当前 `/files` 兼容路由当成目标合同。

实施者还要直接阅读当前源码，而不是只看设计：`VisionServiceAutoConfiguration`、`DefaultVisionService`、`VisionProperties`、`ModelRetryRunner`、`DefaultChatModelClient`、`VisionRequest`、`ZipExtractor`、`AssetImageDescriptor`、`AssetImageQuery`、`FileAutoConfiguration`、`VisionSubagentTool`、`SubagentType`、`pixflow-web/src/router/index.ts`、`FilesPage.vue` 和现有 API/store 测试。类名在实施中变化时，把发现写入本文，不能悄悄照搬过期路径。

## Scope and Non-Goals

本计划包含 Vision current-facts/current-work 持久化、File 内容 hash 和可靠触发 seam、infra/ai attempt accounting、Vision 内部 MQ/lock/recovery、唯一 lookup tool、旧视觉/文案富化路径删除、管理员 API、Materials 图片详情和前端交互。为了闭合 Milestone 4，可以修改 File、AI、Agent、App、Tools 和 Web 的窄接缝及测试。

本计划不完成 File 活动计划中的 RAR/7z 全量提取、Reference Tombstone、资产删除总重构或 Outputs；不重写 Task/Generated Image publication；不实现 Memory 写入、Rubrics、Promotion、通用视觉问答、视觉 child Agent、营销文案持久化、事实历史、模型自动升级重跑、用户取消 Vision 分析或新的 Evaluation Center。YAML redraw prompt 的安全 typed projection若现有 Imagegen 仍缺失，只补 `get_product_visual_facts` 到既有 confirmed Imagegen Proposal 的必要接缝，不借机重写 Imagegen 执行链。

## Plan of Work

### Milestone 0：冻结合同、旧路径和质量门禁

先记录工作树并区分用户修改。用搜索建立旧生产路径清单、Vision suppression 清单、当前 REST/Agent schema 清单和数据库资源清单。为公开事实结构、lookup 三态、管理员 CAS、attempt reservation、outbox 重投和 legacy absence 写契约测试名称与 fixture 约定。不要先写大而全实现；先确认每个小提交的编译边界以及当前 File 计划是否正在修改同一个 `AssetImage`、schema 或 auto-configuration 文件。

把实施拆成可独立工作的窄提交：infra-ai attempt seam；Vision domain/persistence；File hash/outbox；App bridge；Vision worker/recovery；lookup tool 与 Agent 删除；管理员 API；Web 详情；最终清理。每个提交都必须保持 reactor 可编译，源码和该文件的精确 Checkstyle suppression 在同一提交删除。不得新增 suppression、`@ConditionalOnBean` 静默降级、兼容 alias 或 feature flag。

### Milestone 1：把真实 provider attempt 接到持久预算

在 `pixflow-infra-ai` 新增 request-scoped `AttemptBudget`，其唯一动作是在真实 HTTP 发送前调用 owner 的 reservation。保持普通 Chat、Rubrics 和其他 Vision 调用可显式使用 `AttemptBudget.unbounded()`；商品视觉分析必须传持久 owner，不能走默认无限预算。

`VisionRequest` 增加 `toolSchemas`、`toolChoice` 和 `attemptBudget`。`DefaultVisionModelClient` 保持复用 `ChatModelClient`，不引入第二个 provider client。为 `ChatModelClient` 增加带 budget 的 overload，普通 overload 委托到 unbounded；`DefaultChatModelClient` 在每个 retry attempt 内依次执行并发许可、本地 quota、`AttemptBudget.reserve()`、provider HTTP。`ModelRetryRunner` 的透明 retry、退避和错误归一化保持唯一 retry owner。

商品事实调用只传一个名为 `submit_product_visual_facts` 的 `ToolSchema` 和 `ToolChoice.REQUIRED`。因为 provider 可见集合只有一个 tool，REQUIRED 就等价于只能选择它。单测必须证明两次 transport attempt 调两次 reservation，quota 拒绝不调 reservation/HTTP，reservation 拒绝不发 HTTP，普通 chat 仍可用，Rubrics role 不被强制套商品事实 schema。

修改 `DefaultChatModelClient` 和 `ModelRetryRunner` 时，从 `config/checkstyle/suppressions.xml` 删除这两个文件的全部条目并修复该文件所有严格问题；不得只改 suppression 行号。

### Milestone 2：建立 current facts 和 current work 持久模型

在 `pixflow-module-vision` 建立公开 `api`、内部 `domain`、`persistence`、`application`、`execution`、`tool` 和 `config` 边界。公开事实结构至少包含 `ProductVisualFacts`、`CommonVisualFacts`、`VisualAttribute`、`VisualFactsLookupResult`、`VisualFactsView`、`ReplaceVisualFactsCommand` 和 `ReanalyzeVisualFactsCommand`。Persistence entity/mapper 不得出现在公开 API 或 App controller DTO 中。

`ProductVisualFacts` 后端规范化规则固定为：trim 所有 scalar；列表按首次出现顺序去除 trim 后的空项和重复项；category/background 各不超过 200 字符；每个普通 list 最多 32 项、每项不超过 200 字符；attributes 最多 32 项，name 最多 64、value 最多 256；limitations/conflicts 各最多 16 项、每项最多 500；规范化 JSON UTF-8 不超过 64 KiB。未知字段、嵌套 attribute、额外 persistence/identity 字段一律拒绝。全空但形状完整的文档合法且与 `facts=null` 不同。

新增当前表。`vision_analysis_job` 对 `package_id` 唯一，保存当前 package aggregate。`vision_analysis_item` 使用非空 `target_image_id`，SKU scope 固定为 0，IMAGE scope 使用正 imageId，并对 `(package_id, sku_id, scope, target_image_id)` 唯一；这样不依赖 MySQL 对 nullable unique 的宽松语义。它保存 fingerprint、status、analysis_generation、run_epoch、heartbeat、provider/structure counters、last_request_id、generation 开始时的 fact version、当前失败代码和覆盖式 operational metadata。`asset_visual_analysis` 对 `(package_id, sku_id)` 唯一，`asset_image_visual_analysis` 对 `(package_id, sku_id, image_id)` 唯一；两者只保留当前 fingerprint、nullable facts JSON、单调 version 和覆盖式 bounded metadata。SKU row另含 `last_writer` 供管理员页面显示。

所有状态变化原地 update/upsert。首次无事实时 API 投影 version 为 0；管理员保存全空 document 后 facts 非空、version 至少为 1。输入 fingerprint 变化清空 facts 并推进 version，使旧模型 result 无法提交。数据库与 application 都守护唯一性，不建立 revision/run/raw-output 表。

实现 `VisualFactsAdministrationService`。GET 分离 facts availability 和 analysis status。PUT 首先验证 work item 不 active，再用 expectedVersion 条件替换完整 SKU facts，version + 1，writer=`ADMINISTRATOR_EDITED`；冲突返回 `VISUAL_FACTS_VERSION_CONFLICT`。POST reanalyze 先按 `last_request_id` 吸收同一 click 重试，再检查 expectedGeneration 和 active 状态；合法新 requestId 让同一 work row generation + 1、epoch/counters 归零、保留旧 facts、记录起始 fact version并排队。没有图片时同样推进 generation，但直接终态 `FAILED/NO_IMAGE`，provider count 保持 0。

### Milestone 3：File 内容 hash、可靠 outbox 与 App bridge

在 `asset_image` 增加 nullable `content_hash CHAR(64)` 和必要的 `updated_at`。所有新 Original Image admission 和 Generated Image publication 在流式写入或已有 bytes 流经过时计算标准 SHA-256并持久化；rename、预签名 URL 和展示排序不改 hash。把 `contentHash`、size 和必要媒体信息加入 File public `AssetImageDescriptor`，但不把 mapper/entity 暴露出去。

为已有 processable image 实现 bounded `AssetImageContentHashBackfill`：按 imageId 游标分页，从 `ObjectStorage.getStream` 用 `DigestInputStream` 计算，条件更新空 hash；对象缺失或读取失败记录可恢复诊断且不伪造 hash。重复运行只扫描剩余空值。Vision adapter 对空 hash fail closed，package visual outbox 在相关 Original hash 全部可用前保留待重试。

File 定义中立的 `AssetVisualInputEvent` 和 `AssetVisualInputEventSink`。在同一事务中，READY/PARTIAL terminal transition写 `PACKAGE_READY` outbox；Original Image add/delete/replace/content-hash change写 `SKU_INPUT_CHANGED` outbox。rename/order/preview 不写。outbox 以 eventId 为消息 identity，payload 只含 kind、packageId、可选 skuId 和发生时间，不 import Vision 类型。Dispatcher 有界 claim PENDING row，调用 sink；成功才删除，失败保留 attempt/nextAttemptAt。崩溃在 Vision trigger 成功后、File 删除 outbox 前只造成安全重放。

`pixflow-app` 新增 bridge，实现 File sink并调用 Vision public `VisionTriggerPublisher`。Publisher 把 package 和 SKU event转换为 `pixflow-vision` topic 的 `PACKAGE_VISUAL_ANALYSIS_REQUESTED`、`SKU_VISUAL_INPUT_CHANGED` tag；Vision 自己再发布 `VISION_ANALYZE_ITEM`。生产 App 缺 bridge、MessagePublisher 或 VisualAssetReader adapter 时启动失败；测试通过显式 `@TestConfiguration` fake，不用新的 `@ConditionalOnBean` 隐藏能力。

App 还实现 Vision-owned `VisualAssetReader`：用 `AssetReferenceCodec`/File public query验证 PACKAGE/SKU/IMAGE ownership和 READY 状态，只把 Original Image 用于 SKU集合；focused IMAGE 可读取当前 processable Original 或 Generated Image。Adapter把 File `ObjectLocation` 包成 infra/image `ReopenableImageSource`，Vision public contract 不泄漏 storage key。

### Milestone 4：Coordinator、worker、fencing 和恢复

Package consumer读取当前 Original Images，按 `(packageId, skuId)` 分组。SKU fingerprint 是排序后全部 64 位 content hash以换行连接的 UTF-8 SHA-256；空集合是空字节 SHA-256。相同 fingerprint不改 facts、不推进 generation、不发布 provider work。首次输入或不同 fingerprint原地创建/重置 work item；不同 fingerprint先清空对应 SKU facts并推进 fact version，再开始新 generation。零图直接 `FAILED/NO_IMAGE`，不调用 provider。Package job counts从当前 items重算，不累计历史 run。

抽样对每张候选计算 `rank = SHA-256(seed + ":" + imageId)`，其中 seed 是 `SHA-256(packageId + "\0" + skuId + "\0" + fingerprint)`；按 rank、imageId排序取前 `min(2, count)`。一张图只提交一次并添加 single-view limitation。预处理先检查 source bytes，再用 reopenable source probe；在 decode 前通过 infra/image 全局 Pixel Budget，按 `min(1, 1280/maxEdge, sqrt(1_600_000/sourcePixels))` 计算缩放，自动 EXIF、白底 flatten、一次 JPEG encode、禁止 upscale。任一采样图失败可用剩余图并写 limitation；全部失败不调用 provider。

Item consumer使用 `lock:vision:{packageId}:{skuId}:{scope}:{target}` watchdog lock。拿到 lock 后以事务 claim当前 generation，run_epoch + 1，置 RUNNING并启动条件 heartbeat。每次真实 provider attempt 的 `AttemptBudget.reserve()` 执行：

    update vision_analysis_item
       set provider_attempt_count = provider_attempt_count + 1
     where id = :id
       and status = 'RUNNING'
       and analysis_generation = :generation
       and run_epoch = :epoch
       and provider_attempt_count < 3

更新 0 行时区分 stale owner 与预算耗尽，并以不可 retry 的领域错误阻止 HTTP。结构 round 初始为 1，只有错误/missing/wrong/multiple `submit_product_visual_facts` call、非法 JSON 或事实 schema失败时进入第 2 round；两轮共用相同 persistent attempt counter，绝不外套第二个 retry runner。

Commit facts 的一个 MySQL 事务先 `LockGuard.assertHeld()`，再条件校验 item仍 RUNNING、generation/epoch相同、input fingerprint未变、当前 fact version等于 generation起始 version。随后原地替换 facts、version + 1、writer=`AI_GENERATED`，并把 item置 SUCCESS。任何条件失败都不写 facts；旧 owner的 HTTP 即使完成也只能成为 fenced diagnostic。完整 prompt、raw response、旧 facts不落库，operational metadata只覆盖当前 generation 的 selected IDs、尺寸、encoded bytes、usage、provider/model和 contract versions，并限制在 16 KiB。

Recovery scanner只扫描 `RUNNING` 且 heartbeat早于 `stale-after` 的行，条件置 EXPIRED并重发 item message；它不查 Redis lock、不 `forceUnlock`。PENDING publish gap scanner重发超龄 PENDING item。旧 owner仍持 lock时新 consumer拿不到锁并 ack/no-op，后续扫描重试；旧 owner释放后下一 consumer claim并推进 epoch。显式 reanalysis和 focused IMAGE analysis复用同一 owner、预算、heartbeat和 commit代码，不复制状态机。

### Milestone 5：唯一 lookup tool 与旧路径删除

Vision 贡献 `get_product_visual_facts` 的 `ToolDescriptor`/handler，输入 schema只有 required `referenceKey` 且 `additionalProperties=false`。工具分类是 read-only、concurrency-safe、Plan mode可见。它先用 canonical codec和 VisualAssetReader验证 key/ownership/readiness，再返回不含 writer、provider、model、prompt、attempt、epoch或 storage信息的结果。

SKU lookup有 facts即 AVAILABLE；无 facts且 item为 PENDING/RUNNING/可恢复 EXPIRED时 ANALYSIS_PENDING；无 facts且 FAILED时 UNAVAILABLE；NO_IMAGE给明确 safe reason且不启动 generation。IMAGE lookup返回可用 SKU context和 Image Visual Facts；缺 image facts时创建/复用 image-scoped current work并尝试 inline focused analysis，成功后 AVAILABLE，已有 owner或仍在恢复则 ANALYSIS_PENDING。PACKAGE lookup按 skuId返回最多 20 个 current scopes和 truncated标志，不启动新分析。

同一切换提交删除 `VisionService`、`DefaultVisionService`、旧 `analyze/*` assessment/question合同、`ProductCopyExtractor`、`ProductCopyDraft`、`CopyFillPolicy`、`CopyEnrichment*`、`AssetCopyWriteMapper`和其测试/bean/MQ tag。Vision不能再写 `asset_copy`。删除 `pixflow-agent` 的 `VisionSubagentTool`、`SubagentType.VISION`、`SubagentRequest.vision`、vision argument parser和测试，收窄 `agent` tool schema为 `type=explore`。更新 ChildToolFilter、prompt和架构测试，证明没有 general vision question、raw image IDs或 `agent(type=vision)`。

Vision 可以依赖 `pixflow-tools` 的公开 handler SPI，但不能依赖 loop、agent、conversation、task、rubrics或 File内部包；不能直接 import Redisson、MinioClient或 RocketMQ vendor types。更新旧 ArchUnit 规则：MyBatis只允许在 `persistence..`，scheduled annotation只允许恢复配置入口，业务执行依赖抽象 `LockTemplate`/MQ接口。

删除或改写旧 Vision 文件时同步删除 `config/checkstyle/suppressions.xml` 中该模块全部剩余条目，最终 `rg -n "pixflow-module-vision" config/checkstyle/suppressions.xml` 无输出。

### Milestone 6：管理员 API 和 Materials detail read model

在 `pixflow-app` 暴露权威三端点：

    GET  /api/vision/packages/{packageId}/skus/{skuId}/facts
    PUT  /api/vision/packages/{packageId}/skus/{skuId}/facts
    POST /api/vision/packages/{packageId}/skus/{skuId}/reanalyze

Controller只做 URL decode、DTO validation和 service调用；Configured Administrator资格仍由 Auth/Security统一保证，客户端不能提交 writer。GET返回 packageId、skuId、`PENDING|RUNNING|SUCCEEDED|FAILED`、analysisGeneration、nullable facts、version、nullable writer和updatedAt。PUT body是 expectedVersion + 完整 facts；409分别使用 `VISUAL_FACTS_VERSION_CONFLICT` 或 `VISUAL_ANALYSIS_ACTIVE`。POST body是 expectedGeneration + 长度受限 requestId；同 requestId重试返回同一 generation，旧 expectedGeneration返回 `VISUAL_ANALYSIS_GENERATION_CONFLICT`，没有 cancel endpoint。

补齐 File Original Image detail read model，使 route可按 packageId/imageId读取 current display、skuId、canonical IMAGE reference、尺寸/size、preview URL，并取得同 package的默认顺序 previous/next IDs。它只能返回 Original Image；Generated Image仍属于 Outputs。Vision controller不解析 display path，不接受 object key或独立 imageIds。

MockMvc合同覆盖空事实、全空事实、facts + RUNNING、初次失败、手动失败保留 facts、PUT成功、version conflict、active conflict、reanalyze idempotency、generation conflict、URL encoded skuId和非管理员拒绝。错误使用项目 ApiResponse/HttpErrorRenderer，不泄漏 stack、prompt、provider或事实写入来源给 Agent tool。

### Milestone 7：Web 图片详情、表单和生命周期

新增 `src/types/vision.ts`、`src/api/vision.ts` 和专用 Pinia store/composable。所有 API边界用 unknown + zod或现有集中 decoder解析，不能用 `as` 假定响应。表单完整投影 categoryAppearance、background、八类逐行 list、repeatable attributes、limitations和conflicts；保存前 trim、去空、按首次出现去重，与后端规范化一致。全空 document可保存。

注册 `/materials/packages/:packageId/images/:imageId` route并实现 Original Image detail page。当前 `/files` gallery可在 Milestone 7总前端重构前继续作为兼容来源，但 image card必须导航到新 detail route。进入时把来源 query、filter、sort、page、visible order和scroll保存在 page-memory store；前后按钮按来源顺序且不环绕。直接 URL回退到 package默认 image order。返回时恢复 gallery状态。

桌面用 flexible image region + 约 420px `商品视觉分析` 栏；tablet右栏约 40%；phone单列、图片在上、sticky Save/Cancel。标题下显示“这是同商品的综合视觉分析，基于该商品中最多 2 张素材图片生成。”同 SKU切图复用同一 facts；换 SKU才加载新文档。

轮询只在 detail mounted且 backend status为 PENDING/RUNNING时以两秒间隔运行。unmount、离开 route或终态立即清 timer，不取消 backend。无 facts + active显示 spinner；已有 facts + active显示只读完整表单；初次失败显示可编辑空表单；手动失败保留旧 facts；网络失败显示“暂时无法获取分析状态”和 Retry，不把状态改 FAILED或清 facts。

dirty form启用 Save/Cancel并禁用 Reanalyze。换 SKU、前后切图到另一 SKU、关闭 detail或 SPA导航触发 `Save and leave / Discard and leave / Continue editing`；browser refresh/close使用 `beforeunload`。409 version conflict保留本地 draft并提示加载新版本，不做 last-write-wins。Reanalyze每次 deliberate click生成一个 requestId，同一网络重试复用它；成功响应后才能为下一 click生成新 ID。无二次确认、无取消。

前端测试覆盖 API合同、normalization、poll开始/停止、网络失败、同 SKU复用、跨 SKU加载、dirty guard三选择、beforeunload、保存/取消、version conflict draft保留、reanalyze requestId、直接 URL fallback和 router meta。用 1440px、1024px、390px手工检查布局，不新增 ESLint suppression或 `eslint-disable`。

### Milestone 8：组合故障矩阵与完成判定

新增真实 MySQL 8.4 + Redis 7.4 + MinIO + RocketMQ 集成 suite和本地 fake OpenAI-compatible HTTP server。必须覆盖 File outbox publish前崩溃、publish后删除前崩溃、package/SKU消息重复、item消息重复、heartbeat stale、lock owner丢失、HTTP超时重试、结构修复、第三次预算耗尽、管理员并发 version变化、manual reanalysis新 generation、input fingerprint变化、零图和单图。容器 suite不得因 Docker可用却配置错误而 skip。

端到端场景从一个含两个 SKU 的 READY/PARTIAL package开始，证明每 SKU只有一行 current facts/work；相同内容重放不调用 provider；rename不重跑；替换图片清空旧 facts并自动重跑；管理员替换不产生历史；失败 reanalysis保留旧 facts；Agent lookup只返回当前事实；Materials detail符合轮询和草稿合同。最后删除所有旧路径和精确 suppression，更新 Vision/File/Web Context或设计 Revision Notes中实际发生的必要偏差，再在父计划 Progress勾选 Milestone 4并链接本文结果。

## Small Commit Sequence

按以下小提交顺序实施；每一个提交都要在自己的 lint/static gate 和定向测试成功后结束，不能提交红色 reactor。若工作树由用户统一提交，也仍按这些边界逐段实现和验收，避免一次大改同时改变调用预算、事实状态机、事件可靠性和 UI。

1. `refactor(ai): expose request-scoped provider attempt budget`：只修改 infra/ai合同、默认实现、精确 suppression和单测，普通调用行为不变。
2. `feat(vision): add current visual facts domain and persistence`：加入事实类型、schema、mapper/repository、规范化与fresh-schema测试，旧 Vision服务仍可编译。
3. `feat(vision): add administrator CAS and reanalysis state transitions`：只加入GET service、完整替换和generation/requestId状态机及单元/MySQL测试。
4. `feat(file): persist asset content hashes and resumable backfill`：新写路径、public descriptor和backfill一起落地，不接Vision。
5. `feat(file): add recoverable visual input event outbox`：终态/内容变化事务、dispatcher和重放测试一起落地，使用fake sink。
6. `feat(app): bridge file visual events to vision triggers`：加入App adapter、fail-fast装配和边界测试，不运行provider。
7. `feat(vision): add coordinator deterministic sampling and fenced worker`：加入package/SKU/item消息、预处理、预算owner、强制tool解析和commit测试。
8. `feat(vision): add heartbeat recovery and focused image analysis`：加入stale/PENDING恢复、inline focused path和故障测试，不改变Agent schema。
9. `feat(vision): publish product visual facts lookup tool`：注册唯一lookup descriptor和三态输出，保持旧vision child暂时存在以便独立验证新工具。
10. `refactor(agent): remove general vision and copy enrichment paths`：一次性删除Vision旧服务/富化与Agent vision child，移除全部Vision suppression并跑跨模块架构测试。
11. `feat(app): expose visual facts administration API`：加入controller/DTO、File image detail投影和MockMvc合同。
12. `feat(web): add visual facts client store and detail workflow`：先落API/types/store/轮询/dirty guard，再以第二个Web小提交加入route/page/responsive layout；两个提交都严格lint/typecheck后才test。
13. `test(vision): complete real dependency recovery matrix`：只补容器故障矩阵、legacy scans、文档结果和父计划状态；生产行为不再变化。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 串行执行。Maven reactor不能并行，因为共享 Checkstyle/SpotBugs输出会竞争。开始或恢复先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs/exec-plans config/checkstyle/suppressions.xml
    rg -n "VisionService|ProductCopyExtractor|CopyEnrichment|VisionSubagentTool|SubagentType.VISION|agent\\(type=vision\\)" --glob "*.java" .
    rg -n "pixflow-module-vision|DefaultChatModelClient|ModelRetryRunner" config/checkstyle/suppressions.xml

任何一次测试前必须先通过该切片的 lint/static gate。后端固定顺序是：

    mvn -pl <touched-modules> -am -DskipTests verify
    mvn -pl <touched-modules> -am test

第一条失败时停止，不运行第二条；修复 Checkstyle、SpotBugs、compile或testCompile问题，不增加 suppression。若 `-am test` 被范围外容器或既有模块阻断，记录首个失败，再运行目标模块定向测试；不得把 `-DskipTests verify` 记成测试成功。

Milestone 1 使用：

    mvn -pl pixflow-infra-ai -am -DskipTests verify
    mvn -pl pixflow-infra-ai -am test

Milestone 2 和 4 的纯 Vision切片使用：

    mvn -pl pixflow-module-vision -am -DskipTests verify
    mvn -pl pixflow-module-vision -am test

Milestone 3 bridge切片使用：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-vision,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-vision,pixflow-app -am test

Milestone 5 Agent/tool切换使用：

    mvn -pl pixflow-tools,pixflow-module-vision,pixflow-agent,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-tools,pixflow-module-vision,pixflow-agent,pixflow-app -am test
    rg -n "VisionService|ProductCopyExtractor|CopyEnrichment|VisionSubagentTool|SubagentType.VISION|SubagentRequest\\.vision|parseVision" --glob "*.java" .
    rg -n "packageId|imageIds|question|objectKey|bucket|provider|writer" pixflow-module-vision/src/main/java/com/pixflow/module/vision/tool

第一条旧路径搜索在生产与当前测试源码中预期无输出；历史 completed plans和 research docs可以保留。第二条只允许 server-side canonical resolution代码或明确的 negative tests，tool input/output DTO不得泄漏这些字段。

Milestone 6 API切片使用：

    mvn -pl pixflow-module-file,pixflow-module-vision,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-module-file,pixflow-module-vision,pixflow-app -am test

Milestone 7 每个 Web小提交严格按以下顺序：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

`lint` 或 `typecheck` 失败时不运行 Vitest。不得运行仓库级 `lint:fix`；若需要自动修复，只对本切片显式文件清单执行并立即审阅 diff。

真实依赖验收前先确认 Docker并启动现有 compose服务；不要删除 volume或清理用户容器：

    docker info
    docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker minio
    docker compose ps

随后运行 Milestone 4组合 reactor测试。测试类名落地后把占位替换为真实名称并记录零 skip输出：

    mvn -pl pixflow-infra-ai,pixflow-infra-cache,pixflow-infra-mq,pixflow-infra-storage,pixflow-module-file,pixflow-module-vision,pixflow-agent,pixflow-app -am test

最终先运行所有 lint/static analysis，再运行任何完整测试：

    mvn -DskipTests verify
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    mvn verify
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    rg -n "pixflow-module-vision" config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml
    rg -n "VisionService|ProductCopyExtractor|CopyEnrichment|VisionSubagentTool|agent\\(type=vision\\)" --glob "*.java" --glob "*.ts" --glob "*.vue" .
    rg -n "facts_revision|snapshot_history|completed_run|raw_provider_output" pixflow-module-vision
    git diff --check
    git status --short

三个搜索均预期对生产源码无输出；测试名或历史文档命中必须逐项解释，不能为了空搜索改写 completed历史。`mvn verify` 若因 Docker不可用而 skip/失败，Milestone 4保持未完成；记录 `docker info`、首个失败测试和栈帧后等待环境恢复，不删除、禁用或条件跳过验收测试。

## Validation and Acceptance

事实合同验收要求所有 public DTO结构化、bounded、unknown-field closed。全空 document是 AVAILABLE，`facts=null`才是缺失。AI和管理员成功写都只修改同一 row并推进 version；数据库中没有 revision、completed-run或 raw-output表。writer只出现在管理员 GET，不出现在 Agent tool。

输入与触发验收要求 SKU fingerprint只由当前 Original Image content hashes决定。add/remove/replace/hash change触发；rename/order/URL/model/prompt/profile/schema不触发。File DB终态到 MQ之间任一 crash point重启后仍最终产生一次逻辑 generation；重复 outbox/MQ消息不产生第二行或第二 generation。已有对象缺 hash时 backfill可重试，Vision不使用文件名、ETag或对象 key冒充内容 hash。

执行验收要求每 scope只有一个 current work item。并发 consumer、recovery、manual reanalysis和focused lookup共享同一 lock/state machine。旧 generation、旧 epoch、失锁线程、过期 fingerprint或旧 fact version均不能提交。Recovery只看 heartbeat和MySQL状态，不读锁状态、不 force unlock。初次失败无 facts；手动重分析失败保留旧 facts。

成本验收要求每 generation最多三次真实 provider HTTP request、最多两轮结构修复。Transport retry和structure repair共享 persistent counter；MQ重投、进程重启或recovery不重置。只有显式新 reanalysis requestId或内容 fingerprint变化推进 generation并获得新三次预算。quota拒绝、零图和全部预处理失败不发 provider HTTP。

Tool/Agent验收要求provider-visible Agent schema只含 `get_product_visual_facts(referenceKey)`，没有 general question、imageIds、object keys或 vision child type。SKU普通 lookup不创建 generation；IMAGE focused补偿受同一预算和fencing。主 Agent prompt明确外观相关文案、比较和redraw prompt必须先 lookup，不可用时不得编造视觉断言。

API/Web验收要求三个管理员端点与 `frontend/api.md` 一致。页面在facts和analysis status之间不做错误合并；只在detail active且backend active时两秒轮询；离开即停但backend继续。409保留draft；网络错误不清facts；dirty guard和beforeunload工作；同 SKU切图复用facts；desktop/tablet/mobile布局可用。Frontend lint、typecheck、全部 Vitest和build成功。

架构验收要求 File与Vision互不编译依赖，App是跨边界adapter；Vision不依赖File mapper/entity、Agent、Loop、Task、Conversation、Rubrics或vendor client；infra/ai不依赖Vision；tools只看Vision贡献的public handler SPI。生产必需bean缺失时context启动失败，测试fake显式注入。

## Idempotence and Recovery

所有 lint、compile、test、search和GET命令可重复运行。数据库 migration只新增版本，不修改已发布checksum。Schema、mapper、entity和fresh-schema integration fixture必须同步。现有数据backfill按imageId游标和 `content_hash is null` 条件更新，可中断重跑；在报告零空hash前不把Milestone记为完成。

File outbox采用“业务事实 + pending event同事务，confirmed后删除”。重放同eventId安全；Vision根据scope identity、fingerprint、generation和状态吸收重复。若MQ不可用，outbox保留并退避；不得把发布失败写成成功，也不得直接调用Vision绕过outbox。

Provider attempt在HTTP前持久保留，crash后即使不确定请求是否到达provider也视为已消费，这是成本上限优先的选择。Heartbeat/recovery可以重跑同generation但不能返还attempt。结构失败只覆盖当前 operational diagnostics，不保存raw response。

工作树恢复不得使用 `git reset --hard`、`git checkout --`、整仓 restore或删除用户文件。遇到与活动 File/Lint计划重叠的文件，先重读其Progress和diff；在同一语义上做最小合并。无法安全合并时在本文记录具体文件和所需顺序，停在静态门禁通过的边界。

## Artifacts and Notes

目标触发链：

    File terminal/image-content transaction
      -> File-owned pending visual-input outbox
      -> App AssetVisualInputEventSink bridge
      -> Vision trigger publisher / RocketMQ confirmed
      -> delete File outbox row
      -> Vision coordinator upserts current job/items
      -> VISION_ANALYZE_ITEM

目标执行链：

    watchdog lock
      -> MySQL claim generation + new run_epoch
      -> deterministic sample
      -> probe / Pixel Budget / decode once / encode once
      -> AI concurrency + quota
      -> persistent attempt reservation
      -> provider HTTP with one forced facts tool
      -> structure validation/optional second round
      -> lock + generation + epoch + fingerprint + fact-version fenced commit

目标读取链：

    canonical referenceKey
      -> VisualAssetReader ownership/readiness
      -> current SKU/image facts + current work status
      -> AVAILABLE | ANALYSIS_PENDING | UNAVAILABLE
      -> administrator API includes writer metadata
      -> Agent tool excludes writer/operational metadata

核心 crash windows及恢复事实是：File outbox row覆盖触发发布；Vision PENDING item覆盖item消息发布；RUNNING heartbeat覆盖worker崩溃；persistent attempt count覆盖provider调用不确定性；generation/epoch/fact version覆盖迟到提交。Redis lock只降低并发，不是唯一事实源。

## Interfaces and Dependencies

在 `pixflow-infra-ai` 定义等价接口：

    @FunctionalInterface
    public interface AttemptBudget {
        void reserve();

        static AttemptBudget unbounded() {
            return () -> { };
        }
    }

`VisionRequest` 最终至少携带：

    ModelRole role
    List<ChatMessage> messages
    List<ToolSchema> toolSchemas
    ToolChoice toolChoice
    ChatOptions options
    AttemptBudget attemptBudget

在 Vision public API 定义观察事实：

    public record ProductVisualFacts(
        CommonVisualFacts common,
        List<VisualAttribute> attributes,
        List<String> limitations,
        List<String> conflicts) {}

    public record CommonVisualFacts(
        String categoryAppearance,
        List<String> dominantColors,
        List<String> visibleMaterials,
        List<String> shapes,
        List<String> visibleComponents,
        List<String> patterns,
        List<String> visibleText,
        String background,
        List<String> viewTypes) {}

Vision-owned asset seam等价于：

    public interface VisualAssetReader {
        List<VisualAsset> listCurrentOriginals(long packageId);
        VisualAsset requireImage(long packageId, long imageId);
    }

    public record VisualAsset(
        long packageId,
        String skuId,
        long imageId,
        String contentHash,
        long sizeBytes,
        String contentType,
        ReopenableImageSource source) {}

Trigger和查询 seam至少为：

    public interface VisionTriggerPublisher {
        void packageReady(String eventId, long packageId);
        void skuInputChanged(String eventId, long packageId, String skuId);
    }

    public interface ProductVisualFactsLookup {
        VisualFactsLookupResult lookup(String referenceKey);
    }

Tool result最低形状为：

    status: AVAILABLE | ANALYSIS_PENDING | UNAVAILABLE
    requestedReferenceKey
    scopes[]: skuReferenceKey, optional imageReferenceKey,
              optional skuFacts, optional imageFacts
    truncated
    optional safeReason

模块依赖最终满足：`pixflow-infra-ai` 仍只依赖 Common和provider libraries；`pixflow-module-vision` 依赖 Common、Contracts、AI、Image、Cache、MQ、Tools、MyBatis/Jackson/Spring transaction，不依赖 File/App/Agent；`pixflow-module-file` 不依赖 Vision；`pixflow-app` 依赖两侧public API、Storage并实现bridge/reader/controller；`pixflow-agent` 删除Vision child后仍不直接依赖 Vision内部实现；Web只依赖REST JSON合同。

## Revision Notes

2026-07-19 / Codex: 按用户范围决定回写父计划并勾选 Milestone 4。旧路径清零经三条验收搜索验证完成（生产源码旧路径零命中、Checkstyle/SpotBugs `pixflow-module-vision` 清零、无 revision/history/raw-output 表）；前端交互（M7）与真实依赖故障矩阵/全仓验收（M8）按用户指示推迟到后续里程碑，显式覆盖父计划 line 191「生产切换、真实依赖测试、前端交互、旧路径清零全部完成」的条件。本条 supersede 下方 21:10 Outcomes & Retrospective 中「本计划尚不能整体宣告完成」的结论。

2026-07-19 / Codex: 回写跨模块非前端实施结果。记录 File SHA-256/outbox/事务写入与归档补偿，Vision SKU/IMAGE current-state worker、CAS/fencing、attempt budget、heartbeat/recovery、RocketMQ、唯一 lookup tool，App bridge/REST/detail projection，以及 Agent 旧视觉 child runtime 的彻底删除；同步记录 30 模块 compile 和 File 61、Vision 29、Agent 39、App REST 2 项测试证据。前端与 Milestone 8 完整验收保持未完成；按用户最新要求，Checkstyle 问题只记录、不修复。

2026-07-19 / Codex: 完成 Milestone 1 的 AI 调用预算接缝。以 `AttemptBudget` 取代任何 Vision 本地重试计数：`DefaultChatModelClient` 在 quota 准入成功后、HTTP 请求创建前预留，故每次 transport retry 都会独立消费持久预算；`VisionRequest` 将强制 facts tool schema、tool choice 与预算完整投影到底层 provider-neutral Chat 请求。同步迁移所有生产/测试构造点，不保留旧构造器兼容路径；AI 静态门禁与测试均通过。

2026-07-19 / Codex: 按用户限定完成 Tools-only 前置切片。以 red-green 单测证明并修复 Registry 未强制 `additionalProperties=false` 及空 schema 绕过的缺口，复用同一注册校验覆盖启动期和动态工具；双轴审查发现的空 schema 问题已闭环，业务 handler 仍归 Vision，未修改 Agent/App/前端或误标 Milestone 5 完成。

2026-07-19 / Codex: 按用户后续明确要求取消兼容保留，彻底删除 Vision 模块的同步通用问答、Copy Enrichment、直接 Storage 图片解析、旧配置/指标/错误码及其测试；收敛自动配置与 POM，清空 Vision suppression，并记录静态门禁、23 项 Vision 测试和负向旧路径守护证据。

2026-07-19 / Codex: 记录用户限定的 Vision-only 实施切片。新增 current facts/current work 合同、schema、管理员 CAS/reanalysis、fingerprint、确定性抽样与 coordinator 的实际进度和验证证据；明确因未修改 AI/File/App/Agent/Web 而保留的跨模块缺口，未误标 Milestone 2/4 完成。

2026-07-19 / Codex: 创建 Milestone 4 专项计划。依据 ADR 0008、Vision/File/AI/Tools/Agent/Web权威设计和当前源码审计，固定 current facts/current work、File可靠outbox、App adapters、persistent provider attempt budget、generation/epoch/version fencing、唯一lookup tool、管理员三端点和Materials详情交互。按用户要求把每个切片的验证顺序写死为lint/static analysis成功后才运行测试，并把触达文件的Checkstyle suppression原子清理纳入实施范围。
