# 将 infra/vector 重构为只读 Qdrant 检索边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。本文中的 Vector 指正式模块 `pixflow-infra-vector`；用户所称“Vectory”按该模块理解。执行者只依赖当前工作树和本文，就应能把最新目标设计落实为只读、可降级、不可被在线业务写入的 Qdrant 检索能力。每个停止点都必须更新本文；改变公开接口、配置前缀、启动校验、错误分类、实施顺序或验收命令时，还必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，PixFlow 在线运行时只能查询管理员预先准备的 Qdrant ACTIVE analysis-insight 投影，不能建集合、写入、删除、按过滤清理或触发重建。Memory 用 `infra/ai` 生成查询向量，通过唯一公开接口 `VectorSearch` 做稠密向量检索，再与 MySQL FULLTEXT 结果融合；Qdrant 缺失、集合维度错误或网络失败时，Conversation 和 Agent 仍可运行，Memory 明确降级到 FULLTEXT 或空的 analysis-insights section。

这是一项开发期一次性重构，不是兼容性迁移。实施结束后，生产源码中不存在 `VectorStore`、`QdrantVectorStore`、`VectorPoint`、`ensureCollection`、`upsert`、`delete`、`deleteByFilter`、`autoCreateCollection` 或同义包装；不存在 deprecated 接口、旧 Bean 别名、配置别名、双实现开关或只为旧调用方保留的适配层。可以用三类可观察证据验证结果：源码和 Spring Context 只暴露读接口；真实 Qdrant 集成测试只能通过生产 API 查询由测试管理夹具预置的数据；Qdrant 不可用或集合不匹配时应用上下文仍启动且 Memory 召回记录清晰的降级原因。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和当前活动执行计划。
- [x] (2026-07-17) 对比最新提交 `0faa171` 与父提交 `b620027`，确认 `docs/design-docs/infra/vector.md` 已从在线读写存储重写为只读检索边界。
- [x] (2026-07-17) 阅读最新 `docs/design-docs/design.md`、`docs/design-docs/infra/vector.md`、`docs/design-docs/module/memory.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/base/common.md`、历史 Vector 实施计划和 Context Map。
- [x] (2026-07-17) 审计 `pixflow-infra-vector` 的生产源码、自动配置、配置属性、测试、Memory 直接消费方和当前工作树修改。
- [x] (2026-07-17) 创建本中文重构 ExecPlan，固定一次性切换策略、实现机制、实施顺序、验证方式和快速检索关键词。
- [x] (2026-07-17) Milestone 0：保留 Vector 既有 Lint 等价改动，新增三方法反射守护和 Spring Context 原生客户端负向守护。
- [x] (2026-07-17) Milestone 1：一次性切换为 `VectorSearch`、`QdrantVectorSearch`、`VectorPointView`，生产写方法和旧类型全部删除。
- [x] (2026-07-17) Milestone 2：配置切换为 `pixflow.vector.qdrant.*`，原生 client 由适配器独占，Memory readiness 吸收启动探测失败。
- [x] (2026-07-17) Milestone 3：Memory public API 收窄为 `prepareContext`，删除 ingest、reinforcement、lifecycle、rebuild 与旧 Agent hook。
- [x] (2026-07-17) Milestone 4：真实 Qdrant 管理夹具与生产只读 API 分离；Vector 和 Memory 各 10 项测试零失败、零跳过。
- [x] (2026-07-17) Milestone 5：生产源码负向搜索零命中，Vector/Memory 严格 verify 成功，App 30 模块测试源码编译成功；全 App 测试被两个既有 Context 失败阻断。
- [x] (2026-07-17) 审查收尾：补齐只读 transport health、`operation/result` 指标契约、retry/脱敏测试、完整 Qdrant 读合同与 MySQL+Qdrant 无副作用组合测试。
- [x] (2026-07-17) 最终 App 编译复跑在无关的并行 Conversation 修改处失败：`pixflow-conversation` 缺少 `com.pixflow.harness.loop.*` 编译依赖；Vector/Memory 任务 reactor 未受影响且已独立全绿。

## Surprises & Discoveries

- Observation: 最新提交对 Vector 的修改是职责和权限模型反转，不是接口改名。旧设计允许集合创建、upsert、delete 和在线重建；新设计只允许验证既有集合、search 和 get。
  Evidence: `git diff b620027 0faa171 -- docs/design-docs/infra/vector.md` 删除 `VectorStore` 写方法和多存储补偿，新增 `VectorSearch` 与 operational maintenance boundary。

- Observation: 当前生产实现完整保留了旧写面，而且写能力不仅从 `VectorStore` 泄漏，还通过 Spring 容器中的原生 `QdrantClient` Bean 泄漏。
  Evidence: `VectorStore.java` 暴露 `ensureCollection/upsert/delete/deleteByFilter`；`VectorAutoConfiguration.qdrantClient(...)` 注册拥有 Qdrant 管理和写 API 的客户端 Bean。

- Observation: 当前 Memory 不只是调用旧接口写向量，还装配在线抽取、reinforcement、生命周期调度和索引重建。只改 infra/vector 会导致调用方无法编译，也会把被删除的产品机制留成死代码或 no-op 兼容层。
  Evidence: `MemoryAutoConfiguration` 装配 `InsightIngestService`、`DefaultInsightLifecycleService`、`ScheduledInsightMaintenance` 和 `DefaultInsightIndexRebuildService`；`InsightVectorRepo` 同时暴露 ensure、upsert、search 和 delete。

- Observation: 当前 `QdrantVectorStoreIntegrationTest` 通过生产 API 自己建集合和写入测试数据。删除写 API 后仍需要真实数据夹具，但夹具不能反向决定生产接口。
  Evidence: 测试调用 `store.ensureCollection(...)`、`store.upsert(...)` 和 `store.delete(...)` 后再断言 search/get。

- Observation: 最新 `infra/ai` 文档的总原则已经写成“Qdrant 只读检索”，但后部仍有“Qdrant 读写检索”和“存取走 infra/vector”的旧措辞。
  Evidence: `docs/design-docs/infra/ai.md` 的设计原则第 8 条使用只读口径，而“嵌入与重排边界”和模块契约仍出现读写/存取。

- Observation: Lint 活动计划已经清空 infra-vector 的 Checkstyle suppression，且当前 Vector 文件包含用户已有的等价格式修改。
  Evidence: `lint-baseline-remediation-plan.md` 记录 Vector 11 项测试通过、2 项 Qdrant 测试因 Docker 不可用跳过；当前 `git status --short` 显示 Vector 生产文件已修改。

- Observation: Qdrant payload 的 JSON 数组可以合法包含 `null`，Java 侧递归冻结不能使用拒绝 `null` 的 `List.copyOf`。
  Evidence: `VectorPointViewTest.preservesNullValuesInNestedCollections` 覆盖 list/array 内嵌 `null`，实现改用 `Collections.unmodifiableList`。

## Decision Log

- Decision: 采用一次性切换，不保留任何旧生产接口、类名、Bean 名、配置路径或行为开关。
  Rationale: 仓库处于开发期，兼容层会让在线写权限继续可达，并形成两个可注入契约。用户也明确要求重构而非兼容迁移。
  Date/Author: 2026-07-17 / Codex

- Decision: 唯一公开能力命名为 `VectorSearch`，Qdrant 实现命名为 `QdrantVectorSearch`；`get` 返回只读 `VectorPointView`，不复用带写入含义的 `VectorPoint`。
  Rationale: 名称本身表达 capability boundary。调用方无法从接口或 DTO 推断、寻找或重新接回写路径。
  Date/Author: 2026-07-17 / Codex

- Decision: 原生 `QdrantClient` 不再成为 Spring Bean，由 `QdrantVectorSearch` 内部独占并随适配器关闭。
  Rationale: 原生客户端包含集合管理和 point 写 API；把它放入运行时容器会绕过 `VectorSearch` 的只读权限边界。
  Date/Author: 2026-07-17 / Codex

- Decision: Actuator 健康状态通过 `VectorHealthIndicator` 调用适配器 package-private `healthCheck`，失败只报告稳定的 failure kind，并记录 `operation=health,result=degraded`。
  Rationale: transport health 仍需可观测，但不能把原生客户端或写能力重新暴露给 Spring，也不能把 gRPC metadata、凭证或 payload 带入健康详情。
  Date/Author: 2026-07-17 / Codex

- Decision: `verifyCollection` 只读取集合元数据并比较存在性、维度和距离，绝不创建、迁移或修复集合。
  Rationale: Qdrant 是管理员准备的派生索引。配置或数据错误必须可见且确定性失败，在线应用没有治理数据集的权限。
  Date/Author: 2026-07-17 / Codex

- Decision: 集合名、预期维度和距离由 Memory 读侧配置提供；维度缺失视为向量召回未配置，不猜测模型维度，也不调用 embedding 试探。
  Rationale: embedding 型号可配置，猜测或用一次付费调用探测维度都不稳定。显式配置才能证明在线查询向量与管理员索引使用同一合同。
  Date/Author: 2026-07-17 / Codex

- Decision: 启动校验失败不阻断整个 Spring Boot 应用，而是把 Vector recall gate 标记为 unavailable；Memory 跳过 embedding/vector 分支并记录降级原因。
  Rationale: 最新设计要求 Vector fail closed，同时非 Vector 功能继续启动。fail closed 的含义是禁止对未验证集合发查询，不是关闭整个应用。
  Date/Author: 2026-07-17 / Codex

- Decision: 真实 Qdrant 测试用独立的 test-scope 管理夹具建集合和预置 point，生产类不保留任何写方法供测试调用。
  Rationale: 测试准备数据与产品运行权限是两个边界。测试可以使用官方客户端管理 API，但该代码不能进入 `src/main` 或 Spring 生产 Context。
  Date/Author: 2026-07-17 / Codex

- Decision: 本计划删除 Vector 直接依赖的 Memory 在线写回代码；不实现管理员导入 CLI、重建服务或运维 Web API。
  Rationale: 新设计把数据维护放在在线应用之外。把旧重建服务换个包名保留仍会违反边界；真正的运维导入需要独立凭证、审计和后续明确设计。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

重构已完成。Vector 删除 `VectorStore`、`QdrantVectorStore`、`VectorPoint`、原生 `QdrantClient` Bean 与全部 create/upsert/delete 路径，只保留 `VectorSearch.verifyCollection/search/get`；返回 payload 递归不可变，`get` 兼容 Qdrant 1.18 的 dense vector 输出。Memory 删除在线抽取、写回、强化、生命周期、定时维护和重建类型，公开 `MemoryService` 只剩 `prepareContext`；readiness 在维度缺失、集合不匹配或依赖失败时跳过 embedding/vector，并保留 FULLTEXT 或空结果。

验证结果为 Vector 18 项、Memory 14 项全部通过；两组真实 Testcontainers 均实际执行且零跳过。Vector test-scope 管理客户端覆盖集合缺失、维度/距离不匹配（含 Manhattan 不得冒充 EUCLID）、topK、threshold、score 排序、filter、get hit/miss、防御性复制与读前后 point/payload/vector 快照一致；retry、权限/NOT_FOUND 单次调用、健康降级指标和异常链凭证/向量/payload 脱敏由受控 client 单元测试覆盖。重建后的 MySQL+Qdrant 组合测试由 test fixture 预置 ACTIVE point，证明 READY 时 FULLTEXT 与 vector 同时参与召回，且一次 `prepareContext` 前后 MySQL access count 和 Qdrant count/payload/vector 都不变化。严格 Vector/Memory reactor 为零 Checkstyle、零 SpotBugs。较早一次 App 30 模块生产与测试源码编译通过；完整 App 测试运行到 `pixflow-context` 时被既有 `ContextBudgetServiceTest` 与 `ContextProjectorTest` 两项失败阻断。审查收尾后的 App 编译复跑又被无关的并行 Conversation 修改阻断（缺少 `com.pixflow.harness.loop.*` 编译依赖），Vector/Memory 之后模块被跳过；这些结果均未记为全仓通过。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-infra-vector` 是基础设施适配器：它把外部 Qdrant gRPC 协议翻译成供应商无关的 Java 记录和接口。基础设施适配器只表达技术能力，不拥有 analysis insight、SKU、用户偏好、召回融合或 Prompt 组装等业务语义。`pixflow-module-memory` 是唯一生产消费方；它用 `pixflow-infra-ai` 的 `EmbeddingClient` 生成查询向量，调用 Vector 检索，再将结果与 MySQL FULLTEXT 排名做 RRF 融合。RRF（Reciprocal Rank Fusion，倒数排名融合）只使用各路候选的名次合并结果，不属于 Vector。

Qdrant collection 是存放相同维度向量的集合；point 是集合中的一条记录，包含 point id、向量和 payload 元数据。ACTIVE analysis-insight projection 是管理员根据 MySQL 中已有 ACTIVE facts 预先生成的只读 Qdrant 投影。MySQL 仍是事实源，Qdrant 只是可重建的读优化副本。在线读取不得改变 access count、reinforcement、lifecycle、MySQL 行或 Qdrant point。

当前核心文件是：

- `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/VectorStore.java`：旧读写一体接口，目标是删除。
- `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/QdrantVectorStore.java`：旧 Qdrant 适配器，目标是由只读实现替换并删除旧类。
- `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/config/VectorAutoConfiguration.java`：当前暴露原生客户端和两个 store Bean，目标是只装配一个只读 capability。
- `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/VectorProperties.java`：当前使用扁平 `pixflow.vector.*` 和 `autoCreateCollection`，目标是新的 qdrant 连接配置且无自动创建。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/InsightVectorRepo.java` 与 `DefaultInsightVectorRepo.java`：旧业务仓储同时读写，目标是 read-side port。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/config/MemoryAutoConfiguration.java`：旧在线写回、生命周期和重建 Bean 的主要删除入口。
- `pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/HybridInsightRecallService.java`：向量失败后降级和 RRF 前候选编排的现有落点。
- `pixflow-app/src/main/resources/application-dev.yml`：当前扁平 Vector 配置示例，切换时必须与新属性原子更新。

开始任何实施工作前，执行者必须重新阅读当时 `docs/design-docs/exec-plans/` 下所有活动计划、`docs/design-docs/infra/vector.md`、`docs/design-docs/module/memory.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/base/common.md` 和 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md`。本计划是后端总计划 Milestone 5 的细化切片；总计划若已修改相同 Memory 文件，必须先合并所有权和 Progress，不能并行覆盖。

## Scope and Non-Goals

范围包括 Vector 公共契约、Qdrant 实现、过滤翻译、连接配置、自动配置、异常和指标、健康状态、真实 Qdrant 集成测试，以及为完成一次性切换所必需的 Memory 直接调用方、自动装配、旧在线写回删除和降级测试。必要时同步修正 `docs/design-docs/infra/ai.md` 的旧“读写/存取”措辞，使其不再与最新 Vector 和 Memory 设计冲突。

范围不包括管理员数据导入工具、Qdrant migration、索引重建 CLI、运维 Web endpoint、Qdrant payload schema 重新设计、RRF 算法重写、MySQL FULLTEXT 调优、embedding provider 更换、多租户 collection、sparse/BM25 向量、服务端融合、图记忆或无关代码风格整理。已有生产数据的准备和迁移属于后续独立运维计划；本计划只明确在线应用需要什么只读数据合同。

## Target Mechanism

目标公开契约位于 `com.pixflow.infra.vector`：

    public interface VectorSearch {
        void verifyCollection(String collection, int dimension, Distance distance);

        List<ScoredPoint> search(
                String collection,
                float[] query,
                int topK,
                float threshold,
                VectorFilter filter);

        Optional<VectorPointView> get(String collection, String id);
    }

`VectorPointView` 是只读快照，形态为 `record VectorPointView(String id, float[] vector, Map<String,Object> payload)`。构造和 accessor 都必须 defensive copy 向量数组；payload 中的 map、list 和数组也必须递归复制成不可变值，不能只冻结顶层 map。`ScoredPoint` 继续只返回 id、score 和递归不可变 payload。Qdrant、gRPC、protobuf 和 Spring AI 类型不得出现在这些签名中。

`QdrantVectorSearch` 实现该接口并内部持有 `QdrantClient`。自动配置只注册 `VectorSearch`（实际实例可同时用于内部 health probe），不注册 `QdrantClient`、写侧 facade 或 administration bean。适配器实现 `AutoCloseable` 或由 Bean destroy method 关闭内部 client。为了可测试，可以提供 package-private 构造器注入 client，但不能把该构造器或 client 暴露给其他模块。

`verifyCollection` 调用 Qdrant 的 exists/info 读 API。集合不存在、预期维度非正数、实际维度不匹配、距离不匹配是 deterministic failure，不进入 retry；它不读取 `auto-create`，也没有 create fallback。`search` 校验 collection、query、topK、threshold 和 filter，把中立 `VectorFilter` 翻译为 Qdrant Filter，要求结果按 score 降序且低于 threshold 的结果不返回。`get` 只用于诊断或对已经选中的 point 做确定性 hydration，不用于列表扫描。

瞬时 `UNAVAILABLE`、`DEADLINE_EXCEEDED`、`RESOURCE_EXHAUSTED` 和明确可安全重试的 gRPC 状态走有界 retry；集合不存在、参数非法、维度/距离错误和权限拒绝不重试。每次调用受 timeout 限制，最终抛 `VectorException`，由 common 在跨模块出口归一化为 `DEPENDENCY` 或相应确定性错误。日志、异常 details 和指标不能包含 query vector、返回 vector、完整 payload 文本、API key 或 gRPC metadata。

Memory 内部把旧 `InsightVectorRepo` 替换成只读端口，例如：

    interface InsightVectorSearch {
        void verifyCollection(int dimension);
        List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter);
    }

名称可以在实施时按现有包语汇微调，但不得含 upsert/delete/rebuild/lifecycle 方法。Memory 配置显式提供 collection 和 expected dimension；distance 固定为该 read-side projection 合同要求的 COSINE。启动期 readiness 组件调用 `verifyCollection` 并捕获失败：成功标记 READY；维度未配置标记 NOT_CONFIGURED；集合不存在、配置不匹配或依赖失败标记 UNAVAILABLE。失败不得逃出并终止非 Vector Spring Context。`HybridInsightRecallService` 在非 READY 时不调用 embedding，也不尝试 search，直接运行 FULLTEXT 分支并把 `vector_not_configured`、`vector_collection_invalid` 或 `vector_unavailable` 写入 recall trace/degraded reasons。

## Plan of Work

### Milestone 0：冻结基线、冲突和负向守护

先记录 `git status --short`、`git diff -- pixflow-infra-vector pixflow-module-memory pixflow-app/src/main/resources/application-dev.yml` 和当前活动计划 Progress。当前 Vector 文件已有 Lint 计划的等价格式修改，执行者必须保留这些修改；不得 restore、重排或把它们混写成业务逻辑变化。若后端总计划 Milestone 5 已开始修改 Memory，先在两个计划的 Progress 中记录文件所有权，再把本计划作为其 Vector 子阶段执行。

建立契约清单和负向搜索基线。清单要覆盖 `VectorStore`、`QdrantVectorStore`、`VectorPoint`、原生 `QdrantClient` Bean、`ensureCollection`、`upsert`、`delete`、`deleteByFilter`、`autoCreateCollection`、Memory ingest/reinforce/lifecycle/rebuild 以及相关测试和配置。新增架构测试或反射契约测试，要求 Vector 公共 API 只有 `verifyCollection/search/get`，任何 `src/main` 类型都不能向其他模块暴露 `io.qdrant.*`，Spring Context 中不存在 `QdrantClient` 或 `VectorStore` Bean。不要先提交一个故意失败的测试；契约守护与原子切换在同一可构建提交中落地。

同时修正 `docs/design-docs/infra/ai.md` 中残留的“读写检索”“存取走 infra/vector”措辞为“只读检索”，只做与已接受设计一致的最小文档修正。不得借此重写 AI 模块设计。

### Milestone 1：原子替换只读接口和 Qdrant 实现

在一个可编译的原子提交中删除 `VectorStore.java`、`QdrantVectorStore.java` 和 `VectorPoint.java`，新增 `VectorSearch.java`、`QdrantVectorSearch.java` 和 `VectorPointView.java`。保留仍符合目标的 `Distance`、`ScoredPoint`、`VectorFilter` 和 `VectorFilterTranslator`，但重新审计不可变性、输入限制和 Qdrant 类型泄漏。不要创建 deprecated class、旧包转发器或 `LegacyVectorStore`。

从旧实现迁移 read-only 部分：collection info 校验、search、get、payload 反序列化、filter 翻译、有界 retry、timeout、metrics 和异常上下文。删除 collection create、point 序列化、UUID 写入校验、upsert、delete 和 deleteByFilter 的全部生产代码及只为它们服务的 helper/import。point id 在读取时按 Qdrant 实际类型稳定投影为字符串；不要继续强制“必须是 UUID”的写侧规则，因为在线模块不再创建 point。

`get` 是否需要返回 vector 由最新设计已经确定为 `VectorPointView`；实现必须请求 point vector 与 payload。`search` 只需 payload，不要为列表召回传回完整向量，避免带宽和日志风险。所有 deterministic validation 在发出 gRPC 请求前完成，retry predicate 只接受瞬时依赖错误。

### Milestone 2：关闭 Spring 写能力并实现可观察的 fail-closed

把 `VectorProperties` 改为新配置结构，至少表达 `pixflow.vector.qdrant.host`、`grpc-port`、`api-key` 和 `timeout`；TLS 和 retry 若继续保留，必须位于同一 qdrant 配置树并有明确校验。删除扁平旧属性的绑定、`port` 旧名和 `autoCreateCollection`，不提供 aliases。在线应用使用只能读取 collection 元数据和 points、执行 search 的凭证；管理员导入/重建使用的写凭证不得进入应用配置、Spring Environment、日志或测试资源。同步修改 `application-dev.yml` 和配置绑定测试。

重写 `VectorAutoConfiguration`：条件使用新 qdrant host 路径；内部创建 client 并只返回一个 `VectorSearch` capability；容器中不得出现原生 client Bean、第二个 concrete store Bean 或接口别名。健康检查通过只读 transport probe 和 Memory collection readiness 报告状态，不能调用 create/upsert，也不能因为 DOWN 抛出 bean-creation exception。若健康探针保留 `QdrantVectorSearch.healthCheck()`，该方法应是实现内部或 package-private 运维探针，不进入 `VectorSearch` 公共接口。

指标标签固定为低基数 `operation=verify|search|get|health` 和 `result=ok|error|degraded`，不得把 collection、point id、异常文案或 payload 作为 tag。删除 write operation 指标的生产分支，更新 metrics tests 证明不存在 upsert/delete 标签。错误 details 可以包含 operation、已脱敏 collection 和 mismatch 的 expected/actual dimension/distance，但不能包含向量、payload 或凭证。

### Milestone 3：删除 Memory 写回并切换唯一消费方

这一步与 Milestone 1 的公开接口切换必须处于同一个能通过 reactor 编译的实施批次；章节分开只是为了说明责任，不允许在提交间留下“Vector 已删除但 Memory 仍调用旧接口”的状态。

把 `InsightVectorRepo` 一次性替换或重命名为 read-only port，只保留 verify/search。`DefaultInsightVectorRepo` 改为依赖 `VectorSearch`，继续负责把 `InsightFilter` 的 category、related SKU 和 minimum confidence 翻译成中立 `VectorFilter`，以及把 `ScoredPoint` payload 映射成 `MemoryItem`。它不得构造 `VectorPointView`、upsert payload 或 point id，不得删除 point。

从 `MemoryService` 删除 `ingestAsync` 和 `reinforce`，相应简化 `DefaultMemoryService` 构造器与 `MemoryAutoConfiguration`。删除 production 中的 `InsightIngestService`、`MemoryIngestService`、no-op ingest、`InsightLifecycleService`、默认/no-op lifecycle、`ScheduledInsightMaintenance`、`InsightIndexRebuildService`、默认/no-op rebuild、`MemoryReinforcementService`、no-op reinforcement，以及只服务这些机制的 request/event/extractor 类型和测试。删除，而不是改成永远 no-op；no-op 仍会保留错误的公开能力。

检查 Agent hooks、Task、Conversation 和 Rubrics。当前 `AssistantMemoryIngestionHook` 虽然尚未调用写 API，但其命名和注释承诺下一迭代接入 `ingestAsync`，应随总计划 Milestone 5 删除或改成与 memory 无关的真实 hook；不得保留“以后恢复写回”的注释。源码负向搜索必须证明没有任何在线事件能触发 memory/vector write。

在 Memory 配置中加入显式 expected vector dimension。维度缺失不猜测、不调用 embedding；readiness 标记 NOT_CONFIGURED。配置存在时，启动校验 collection、dimension 和 COSINE。`HybridInsightRecallService` 只有在 readiness READY 时才生成 query embedding 并调用 vector search；任何 VectorException 都只让向量路失败，FULLTEXT 路继续。读取成功也不得更新 access count、last recalled、reinforcement 或 lifecycle 字段。

### Milestone 4：重建测试，不让测试反向污染生产接口

删除 `VectorPointTest` 和旧读写 round-trip 测试，新增 `VectorPointViewTest` 验证 vector/payload 不可从外部修改。更新 `VectorFilterTranslatorTest` 覆盖 must、should、mustNot、match、matchAny、range、嵌套组合和非法过滤条件；它只测试读侧过滤翻译。

把真实 Qdrant 集成测试重写为 read-side contract。测试类在 `src/test` 中直接创建独立 admin `QdrantClient`，负责建唯一 collection、写入固定 points 和 teardown；生产 `VectorSearch` 只执行 verify、search 和 get。测试至少覆盖：集合存在且维度/距离匹配；集合缺失；维度不匹配；距离不匹配；topK；threshold；score 降序；payload filter；get 命中/缺失；search 不返回 vector；get 返回 defensive-copy vector。测试结束后关闭两类 client，重复运行不得依赖上次容器数据。

增加 retry 边界测试，用 fake/受控 client 证明瞬时状态最多尝试配置次数，deterministic validation、NOT_FOUND、dimension mismatch 和 permission failure只调用一次。增加日志/异常测试，构造含伪 API key、长 payload 和向量的失败场景，证明输出不包含这些内容。

更新 Memory 单元和集成测试。MySQL+Qdrant 测试仍可由 test fixture 预置 ACTIVE point，但不能经 Memory 或 Vector 生产写 API。测试要证明 READY 时向量与 FULLTEXT 参与 RRF；Vector 不可用时只返回 FULLTEXT；FULLTEXT 不可用时可使用已验证的 vector；两者都不可用时 analysis-insights 为空但 `prepareContext` 返回；未配置维度或 collection mismatch 时跳过 embedding；所有路径对 MySQL 和 Qdrant 都无副作用。

### Milestone 5：组合验证和总计划交接

先运行源码负向搜索和 Vector 单元测试，再运行有 Docker 的真实 Qdrant 测试，然后运行 Memory、Agent、App 直接消费者，最后串行运行相关 Maven reactor。不要并行启动多个 Maven reactor，因为它们共享 `target` 与静态分析输出。

更新 `backend-architecture-alignment-refactor-plan.md` Milestone 5 的 Progress，记录本计划完成的 Vector/Memory 子范围、删除类型、测试数量和遗留工作。若总计划的 canonical Asset Reference 改造尚未进入 Memory，本计划不能伪造该部分已完成；只记录 Vector read-only seam 与在线写回删除。Lint suppression 由活动 Lint 计划管理，本计划不得重新增加 Vector suppression。

## Tiny Commit Sequence

建议以可构建边界提交，而不是按单文件提交。V0 记录文档措辞收敛和测试/文件所有权；V1 是不可拆分的 production cutover，原子删除旧 Vector 契约、切换 Memory 直接消费方并删除在线写回 Bean，使 reactor 在提交末尾恢复编译；V2 完成新配置、client ownership、readiness 和健康状态；V3 重建 Vector 单元与真实 Qdrant 读侧测试；V4 完成 Memory 降级/无副作用测试、源码负向搜索和总计划交接。V1 不能拆成“先加新接口、旧接口继续存在”的兼容提交，也不能留下 deprecated bridge。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点运行：

    git status --short
    git diff -- pixflow-infra-vector pixflow-module-memory pixflow-app/src/main/resources/application-dev.yml

对比最新设计变化并重读目标：

    git diff b620027 0faa171 -- docs/design-docs/infra/vector.md docs/design-docs/module/memory.md
    rg -n "Runtime API|Data maintenance boundary|Reliability|Configuration|Verification" docs/design-docs/infra/vector.md
    rg -n "Removed mechanisms|Storage boundary|Errors and degradation|Verification" docs/design-docs/module/memory.md

实施前保存旧能力命中数，实施后以下生产源码搜索必须无结果：

    rg -n "VectorStore|QdrantVectorStore|VectorPoint\b|ensureCollection|upsert\(|deleteByFilter|vector(Store|Repo)\.delete|autoCreateCollection" pixflow-infra-vector/src/main pixflow-module-memory/src/main pixflow-agent/src/main pixflow-app/src/main
    rg -n "InsightIngestService|MemoryIngestService|InsightLifecycleService|ScheduledInsightMaintenance|InsightIndexRebuildService|MemoryReinforcementService|ingestAsync|reinforce\(" pixflow-module-memory/src/main pixflow-agent/src/main
    rg -n "QdrantClient" pixflow-module-memory/src/main pixflow-agent/src/main pixflow-app/src/main

目标只读能力应有且只有预期命中：

    rg -n "VectorSearch|QdrantVectorSearch|VectorPointView|verifyCollection|search\(|get\(" pixflow-infra-vector/src/main pixflow-module-memory/src/main

运行 Vector 严格编译和测试：

    mvn -pl pixflow-infra-vector -am -DskipTests verify
    mvn -pl pixflow-infra-vector -am test

Docker 可用时，Surefire 报告应显示真实 Qdrant integration tests 实际执行且零 skipped。Docker 不可用时，记录具体跳过的类和数量，不能把 skipped 写成 passed，也不能删除 `disabledWithoutDocker` 或用 mock 替代真实合同。

运行直接消费方和 App 验证：

    mvn -pl pixflow-module-memory -am test
    mvn -pl pixflow-agent -am test
    mvn -pl pixflow-app -am test
    mvn -pl pixflow-app -am -DskipTests verify

最终检查依赖和改动范围：

    mvn -pl pixflow-infra-vector dependency:tree
    git diff --check
    git diff --stat

期望 `pixflow-infra-vector` 对 PixFlow 内部模块只依赖 `pixflow-common`；它可以依赖 Qdrant client、Spring autoconfigure/actuator、Micrometer、Resilience4j 和测试库，但不能依赖 AI、Memory、Agent、Task、Conversation、Hooks、Rubrics 或 File。

## Validation and Acceptance

接口验收要求编译后的 `VectorSearch` 只有 `verifyCollection/search/get` 三种能力；不存在名字不同但语义等价的 create/upsert/delete/rebuild 方法。`VectorPointView` 和 `ScoredPoint` 不泄漏可变内部状态，Qdrant client/protobuf 类型不跨公共边界。Spring Context 只能按 `VectorSearch` 注入生产能力，按类型查询 `QdrantClient`、`VectorStore` 或任何 vector admin/write facade 得到零 Bean。

集合验收要求预置匹配 collection 后 `verifyCollection` 成功且没有数据变化；集合缺失、维度不符和距离不符返回明确 deterministic error，调用次数为一次，Qdrant 中不会出现新 collection 或被修改的配置。配置中不存在自动创建开关。

检索验收要求 test fixture 预置三个 points 后，production `search` 按 topK、threshold 和 filter 返回确定的 score 降序结果；`get` 能读取指定 point，缺失返回 empty；调用前后 collection point count、payload 和向量不变。日志、metrics tags 和异常输出不包含 query vector、point vector、完整 payload 或 API key。

降级验收要求至少覆盖四个场景。Qdrant 正常且集合已验证时，Memory 同时使用 vector 和 FULLTEXT；Qdrant 不可达时，只使用 FULLTEXT；dimension 未配置或 collection mismatch 时，应用仍启动、跳过 embedding/vector 并记录固定降级 reason；Vector 和 FULLTEXT 都失败时，Conversation 可获得没有 analysis-insights 的 Memory Context，而不是 500 或启动失败。

权限验收要求 Agent、Conversation、Task、Hooks、Rubrics 和 Memory 的生产源码都不存在 Vector 写调用，Memory 公共 API 只有 `prepareContext`。Spring Environment 只含在线只读凭证，不含管理员写凭证。触发一次用户消息、Proposal 决策、任务完成、Hook 和 Rubrics 流程后，Qdrant point count 与 MySQL memory facts 均不变化；这可以由组合测试中的 before/after 快照证明。

静态质量验收要求 touched modules 不新增 Checkstyle suppression、SpotBugs exclusion、`@SuppressWarnings` 宽泛规则或测试跳过。`mvn -pl pixflow-app -am -DskipTests verify` 成功，Vector、Memory、Agent 和 App tests 零失败、零错误；真实 Qdrant 是否执行按 Docker 环境如实记录。

## Idempotence and Recovery

搜索、编译、测试、collection verify、search 和 get 都可重复运行。真实 Qdrant 测试为每次运行生成唯一 collection，并在 finally/afterAll 中用 test admin client 清理；测试清理失败时，下次运行仍因唯一名称不受影响。生产接口永远不承担测试清理。

V1 是一次性删除边界。若实施中途停止，恢复单位是 `VectorSearch + QdrantVectorSearch + VectorAutoConfiguration + Memory read-side consumer + Memory write-path deletion + compilation tests`，不能提交或交接半迁移状态。不得通过恢复旧 `VectorStore`、注册旧 Bean、增加配置 alias 或加入 no-op writer 解决编译/启动问题；应继续迁移或删除真实调用方。

启动 verification 失败时不修改 Qdrant，也不反复进行无界重试。修正管理员数据集或配置后可安全重启应用重新验证。若未来需要在线恢复而不重启，必须另行设计有界 re-probe；本计划不通过后台写入或隐式建集合“自愈”。

保留当前工作树的用户修改。禁止 `git reset --hard`、整仓 restore 或删除未跟踪文件。Vector 文件已有 Lint 等价格式变化时，以最小补丁演进并在最终 diff 中区分语义删除与格式历史；遇到重叠无法可靠合并时，先在 Progress 记录冲突并与活动计划协调。

## Quick Reference Search Keywords

为了快速找到设计依据和实现参考，优先使用以下文件内关键词，而不是泛搜“vector”得到大量无关结果。

- 在 `docs/design-docs/infra/vector.md` 搜索 `Read-only vector retrieval`、`Runtime API`、`verifyCollection`、`Data maintenance boundary`、`fails the vector component closed`、`architecture tests expose no runtime write method`，定位只读权限、启动校验和验证要求。
- 在 `docs/design-docs/module/memory.md` 搜索 `Read-only Memory Recall`、`Storage boundary`、`Removed mechanisms`、`vector failure degrades`、`Recall never mutates facts`，定位唯一消费方、写回删除和降级语义。
- 在 `docs/design-docs/design.md` 搜索 `当前只读召回已有记忆`、`管理员预先准备的只读 ACTIVE 派生索引`、`Qdrant`、`MySQL 为事实源`、`混合检索 + RRF`，定位系统级数据权威和召回链。
- 在 `docs/design-docs/infra/ai.md` 搜索 `向量库无感`、`嵌入与重排边界`、`EmbeddingClient`、`不使用 Spring AI`，定位 query embedding 与 Vector 检索的职责分离；实施时同时清理该文档残留的“读写/存取”旧词。
- 在 `docs/design-docs/base/common.md` 搜索 `infra 异常收口策略`、`DEPENDENCY`、`ErrorNormalizer`、`Sanitizer`，定位 VectorException 的归一化和敏感信息处理。
- 在 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 搜索 `Milestone 5`、`Memory、Vector、Hooks 与 Agent`、`只读召回`、`删除全部在线写回路径`，定位本计划在总重构中的实施顺序。
- 在当前代码搜 `VectorAutoConfiguration`、`QdrantVectorStore`、`InsightVectorRepo`、`HybridInsightRecallService`、`MemoryAutoConfiguration`，快速找到旧能力暴露、唯一消费方、降级位置和删除入口。
- 在现有测试搜 `QdrantVectorStoreIntegrationTest`、`MemoryMySqlQdrantIntegrationTest`、`disabledWithoutDocker`，定位真实 Qdrant fixture 和 MySQL+Qdrant 组合验证，但只复用测试基础设施思路，不复用生产写 API。

## Artifacts and Notes

最新提交的关键差异可以概括为：

    before: VectorStore = ensure/create + upsert + search + get + delete + rebuild support
    after:  VectorSearch = verify existing collection + search + get

    before: online Memory/Hook/Task/Rubrics may mutate memory/vector
    after:  administrator prepares dataset offline; online runtime has read authority only

目标运行链是：

    user prompt
      -> Memory prepares query text
      -> infra/ai EmbeddingClient creates query vector
      -> verified VectorSearch searches prebuilt ACTIVE projection
      -> Memory fuses vector rank with MySQL FULLTEXT rank
      -> token-budgeted analysis_insights section

失败链是：

    startup verify missing/mismatch/unavailable
      -> vector readiness != READY
      -> skip query embedding and VectorSearch
      -> use MySQL FULLTEXT or empty section
      -> record stable degraded reason
      -> Conversation continues

## Interfaces and Dependencies

重构完成后，`pixflow-infra-vector` 必须提供 `VectorSearch`、`VectorPointView`、`ScoredPoint`、`VectorFilter`、`Distance` 和 `VectorException`，以及实现内部的 `QdrantVectorSearch`、filter translator、properties、metrics 和 health adapter。公共 API 中只有查询和验证能力。是否将具体实现类设为 public 取决于 Spring/test 需要；即使 public，也不能暴露原生 client 构造器或写方法。

`pixflow-module-memory` 只依赖 `VectorSearch`、`EmbeddingClient` 和自己的 MySQL read mappers。它拥有 collection、expected dimension、COSINE、topK、threshold 与业务 filter；Vector 不得导入 Memory 类型。Memory 的公开 `MemoryService` 最终只有：

    public interface MemoryService {
        MemoryContext prepareContext(MemoryContextRequest request);
    }

依赖方向固定为：

    pixflow-common <- pixflow-infra-vector <- pixflow-module-memory <- pixflow-agent
                         ^                          ^
                         |                          |
                    Qdrant client             infra/ai EmbeddingClient

`pixflow-infra-vector` 不依赖 `pixflow-infra-ai`；Memory 把已经生成的 query vector 传给它。它不依赖 Cache，因为只读检索不需要锁、补偿队列或运行时状态。它不依赖 Agent、Conversation、Task、Hooks、Rubrics、File 或任何业务实体。

## Revision Notes

2026-07-17 / Codex: 根据最终双轴代码审查补齐 health/metrics、retry/异常链脱敏和真实数据合同；修复 payload 内嵌 null、非 COSINE threshold 校验与未知距离误映射；重建只读 MySQL+Qdrant 组合测试，并把最终计数更新为 Vector 18、Memory 14。

2026-07-17 / Codex: 完成只读切换。新增唯一 `VectorSearch` 能力、递归不可变 view、适配器内部 client ownership、qdrant 子配置树和 Memory fail-closed readiness；删除所有在线 Memory/Vector 写路径及旧 hook。真实 Qdrant、降级、架构负向守护和严格 lint 均通过；全 App 测试的两个既有 Context 失败已如实记录。

2026-07-17 / Codex: 创建本计划。原因是最新提交 `0faa171` 已把 `infra/vector` 从在线读写存储重新定义为只读检索，而当前代码仍暴露完整 Qdrant 写面和 Memory 在线写回。按用户补充要求，本计划采用一次性重构，明确禁止兼容接口、旧 Bean、配置别名、双实现和 no-op writer，并补充 client ownership、测试夹具隔离、启动 fail-closed、Memory 降级机制与快速检索关键词。
