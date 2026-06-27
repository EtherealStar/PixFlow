# infra/vector —— Qdrant 向量存储封装（Wave 1 基础设施）

> 本文是 PixFlow 完整重写阶段 `infra/vector` 模块的设计文档，对应 `design.md` 第七章「RAG 记忆层」、§13.2「Qdrant（向量）」、§15 多存储一致性风险，以及 `module-dependency-dag-plan.md` 的 **Wave 1 基础设施**。
> 范围：基于 Qdrant 的**纯向量 I/O 抽象**——集合生命周期、按 id 幂等 upsert、稠密(dense)相似检索 + 元数据过滤、按 id/过滤删除。
> 本文不涉及 MVP 既有实现，从新架构需求重新推导，按生产级标准设计。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、模块结构与依赖位置](#二模块结构与依赖位置)
- [三、核心抽象](#三核心抽象)
- [四、集合生命周期与向量维度](#四集合生命周期与向量维度)
- [五、检索语义（dense + 过滤 + 阈值）](#五检索语义dense--过滤--阈值)
- [六、幂等 upsert 与 MySQL 一致性 / 重建](#六幂等-upsert-与-mysql-一致性--重建)
- [七、过滤 DSL 设计](#七过滤-dsl-设计)
- [八、边界：为什么不做 sparse/BM25/实体](#八边界为什么不做-sparsebm25实体)
- [九、韧性与错误收口](#九韧性与错误收口)
- [十、配置](#十配置)
- [十一、可观测](#十一可观测)
- [十二、对其他模块的契约](#十二对其他模块的契约)
- [十三、测试策略](#十三测试策略)
- [十四、暂不考虑](#十四暂不考虑)

---

## 一、文档定位与设计原则

`infra/vector` 在依赖 DAG 中处于 **Wave 1**，只依赖 `common`（异常归一化 + 脱敏），被 `module/memory`（Wave 2）唯一消费。它承载向量库的**纯 I/O 原语**，不含任何业务领域知识。

模块专属设计原则（与 `storage.md` / `cache.md` 一脉相承）：

1. **纯向量 I/O，无业务语义**。模块内**不得出现** `memory`、`insight`、`sku`、`preference` 等业务词。它只认 `(集合名, point id, 向量, payload)`。「分析结论记忆」「类目洞察」这类语义全部归 `module/memory`。
2. **只做稠密向量检索**。本期混合检索的关键词侧走 MySQL FULLTEXT（见 `memory.md`），RRF 融合在 `module/memory` 应用层完成。**本模块不引入 sparse/BM25 向量、不做实体抽取、不做服务端融合**（理由见 [§八](#八边界为什么不做-sparsebm25实体)）。
3. **接口与实现分离，可替换**。对上暴露 `VectorStore` 接口，Qdrant 官方 Java client 封装在实现后面；未来换 Milvus/PGVector 只换实现类与配置，`module/memory` 零改动。
4. **直连 Qdrant client，不走 Spring AI VectorStore**。Spring AI `VectorStore` 把 embedding 与存储耦合（`add(Document)` 自动调嵌入模型）；本项目 embedding 归 `infra/ai`、存储归 `infra/vector`，职责必须分离。`module/memory` 的 mem0 管线需要「先用候选向量检索近邻、再用同一向量 upsert」，必须显式掌控向量与 payload，直连更顺手。
5. **id 由调用方给、upsert 按 id 覆盖**。point id 由 `module/memory` 用 MySQL 行 id/UUID 提供，使 Qdrant 与 MySQL 一一对应——这是 `design.md §15`「MySQL 为事实源、Qdrant 可重建」的物理基础。
6. **底层只懂 I/O，错误在边界归一化**。本模块抛自有 `VectorException`，跨出 infra 边界由 `common` 的 `ErrorNormalizer` 翻译为 `DEPENDENCY`（默认 `RETRY`）。检索失败是否降级为「召回为空」由 `module/memory` 决策，本模块只负责抛。
7. **生产级，不简化**。集合幂等初始化、批量 upsert、Resilience4j 韧性、Testcontainers 集成测试齐备。

---

## 二、模块结构与依赖位置

源码包：`com.pixflow.infra.vector`

```
infra/vector/
├── VectorStore.java              # 核心接口：ensureCollection/upsert/search/get/delete（纯 I/O）
├── VectorPoint.java              # record(String id, float[] vector, Map<String,Object> payload)
├── ScoredPoint.java              # record(String id, float score, Map<String,Object> payload)
├── Distance.java                 # enum COSINE / DOT / EUCLID
├── VectorFilter.java             # 中立过滤 DSL（must/should/mustNot + match/matchAny/range）
├── VectorException.java          # 独立领域异常（operation/collection/retryable）
├── QdrantVectorStore.java        # VectorStore 的 Qdrant 实现（封装 QdrantClient + Resilience4j）
├── VectorProperties.java         # @ConfigurationProperties(pixflow.vector)
├── VectorFilterTranslator.java   # VectorFilter → Qdrant Filter 翻译（实现内部，不外泄 Qdrant 类型）
└── config/
    └── VectorAutoConfiguration.java  # 装配 QdrantClient、VectorStore Bean
```

依赖方向：`vector → common`，不反向依赖任何 `harness`/`module`/`agent`。

新增 Maven 依赖（`pom.xml`）：

    <dependency>
        <groupId>io.qdrant</groupId>
        <artifactId>client</artifactId>
        <version>1.12.0</version>
    </dependency>

> Qdrant Java client 走 gRPC，自带 protobuf 传输层；集合管理、upsert、过滤检索均由其原生 API 提供。版本与 `design.md` 选型对齐（v3 算法要求 Qdrant client ≥ 1.12）。

---

## 三、核心抽象

接口完全无业务知识，参数只有集合名、向量、payload map、过滤对象与 topK/threshold。

```java
public interface VectorStore {

    // 幂等建集合：已存在则校验维度/距离一致（不一致快速失败）；不存在则创建
    void ensureCollection(String collection, int dim, Distance distance);

    // 按 id 覆盖写入（幂等）；支持批量，对应 mem0 v3 batch embed + batch insert
    void upsert(String collection, List<VectorPoint> points);

    // 稠密相似检索：返回按 score 降序的 topK，score < threshold 的丢弃
    List<ScoredPoint> search(String collection, float[] query, int topK,
                             float threshold, VectorFilter filter);

    Optional<VectorPoint> get(String collection, String id);

    void delete(String collection, List<String> ids);
    void deleteByFilter(String collection, VectorFilter filter);

    boolean collectionExists(String collection);
}
```

不可变 record：

```java
public record VectorPoint(String id, float[] vector, Map<String,Object> payload) {}
public record ScoredPoint(String id, float score, Map<String,Object> payload) {}
public enum Distance { COSINE, DOT, EUCLID }
```

设计要点：

- **id 为字符串**：`module/memory` 用 MySQL 行 id（或 UUID）当 point id，保证两库一一对应、可按 id 互查与重建。
- **payload 为任意 JSON map**：本模块不规定字段。`design.md §13.2` 的 `{结论文本, 类目, 来源, 置信度, 关联SKU, created_at}` 由 `module/memory` 决定并写入。
- **upsert 幂等覆盖**：同 id 重写即更新，新 id 即新增；ADD-only 管线只用「新 id 新增」，重建场景用「同 id 覆盖」。
- **search 内建 threshold**：低相关结果在库侧（或实现侧统一）过滤，避免把噪声带回应用层。

---

## 四、集合生命周期与向量维度

- **维度不写死在 infra**：向量维度 `dim` 由 embedding 模型决定（`infra/ai` 的 EmbeddingModel，型号配置承载）。`module/memory` 启动期以实际维度调用 `ensureCollection(collection, dim, COSINE)`。
- **距离默认 COSINE**：嵌入向量归一化后语义相似度用余弦，与 `design.md` 召回语义一致。
- **幂等初始化**：`ensureCollection` 先 `collectionExists`，存在则校验维度与距离一致（不一致直接抛 `VectorException`，避免静默写入错维度集合污染检索），不存在则 `createCollection`。
- **集合名为参数**：本期只有 `analysis_insight` 一个集合（`design.md §13.2`），但接口按通用多集合设计，集合名由 `module/memory` 给定，infra 不内置任何集合常量。
- **不自动建 `_entities` 集合**：mem0 v3 会建并行实体集合做图记忆，本项目**明确不做**（见 [§八](#八边界为什么不做-sparsebm25实体)），故无此逻辑。

---

## 五、检索语义（dense + 过滤 + 阈值）

`search(collection, query, topK, threshold, filter)`：

- **稠密召回**：`query` 为候选/查询文本的嵌入向量（由 `module/memory` 经 `infra/ai` 生成），库侧按距离返回 topK。
- **过滤前置**：`filter` 翻译为 Qdrant payload 过滤（类目 / 关联 SKU / 置信度区间等），在向量检索时一并下推，避免「取回再过滤」。
- **阈值**：`threshold ∈ [0,1]`，低于阈值的结果剔除（对齐 mem0 v3 默认 0.1 的语义，具体值由 `module/memory` 配置传入）。
- **返回 ranked list**：`ScoredPoint` 按 score 降序。`module/memory` 拿这条列表的**排名**参与 RRF（与 MySQL FULLTEXT 召回的排名融合），而非直接用 score。
- 本模块只产出「向量召回这一路」的有序结果；关键词召回与 RRF 不在此。

---

## 六、幂等 upsert 与 MySQL 一致性 / 重建

呼应 `design.md §15`「以 MySQL 为事实源；Qdrant 写入失败可异步补偿」：

- **写顺序由上层定**：`module/memory` 先写 MySQL 镜像（事实源），再 `upsert` Qdrant；Qdrant 写失败按 `DEPENDENCY/RETRY` 补偿重试，不影响事实源。
- **point id = MySQL 行 id**：保证补偿/重建可定位。
- **重建路径**：`module/memory` 可按 MySQL 全量 `upsert`（同 id 覆盖，天然幂等），重放不产生重复点。
- **批量**：`upsert(List)` 支持 mem0 v3 的「batch embed + batch insert」，减少往返。

本模块不感知「事实源」语义，只保证 upsert 幂等、可批量、可按 id 覆盖——给上层一致性策略提供机制底座。

---

## 七、过滤 DSL 设计

为支撑 `module/memory` 的 scoped 检索（按类目/SKU/置信度过滤），又不把 Qdrant 的 `Filter` 类型泄漏给上层，定义中立的最小过滤 DSL：

```java
public final class VectorFilter {
    // 组合
    static VectorFilter must(Condition... c);     // AND
    static VectorFilter should(Condition... c);    // OR
    static VectorFilter mustNot(Condition... c);   // NOT
    VectorFilter and(VectorFilter other);          // 可嵌套组合

    sealed interface Condition {}
    static Condition match(String field, Object value);          // 精确等值
    static Condition matchAny(String field, List<Object> values);// IN
    static Condition range(String field, Double gte, Double lte);// 数值区间（如置信度 ≥ 0.6）
    static VectorFilter none();                                   // 无过滤
}
```

- `VectorFilterTranslator` 在实现内部把 DSL 翻译为 Qdrant `Filter`（`must`/`should`/`must_not` + `FieldCondition` 的 `match`/`range`）。
- DSL 只覆盖本期所需的等值、IN、数值区间；不暴露全文 `MatchText`（关键词检索走 MySQL，见 [§八](#八边界为什么不做-sparsebm25实体)）。
- 字段名是 payload key 字符串，业务含义由 `module/memory` 掌握，infra 不校验语义。

---

## 八、边界：为什么不做 sparse/BM25/实体

已确认走「**Qdrant dense 召回 + MySQL FULLTEXT 关键词召回 + 应用层 RRF**」（路线 A），因此本模块刻意**不做**以下 mem0 v3 内置能力：

| mem0 v3 能力 | 本项目处置 | 理由 |
|---|---|---|
| BM25 稀疏向量（同集合 dense+sparse） | **不做**，关键词检索改用 MySQL FULLTEXT | Java 无 `fastembed` 等价物，自研 BM25 稀疏编码需维护词表/IDF，属「困难部分」，明确避开 |
| 服务端 Query API 融合（Fusion.RRF） | **不做**，RRF 在 `module/memory` 应用层做 | 既然关键词侧在 MySQL，融合天然落应用层；RRF 基于排名，免分数归一化 |
| 实体抽取 + `{collection}_entities` 图记忆 | **不做** | 内置图记忆是 v3 难点，与项目「砍掉图记忆」决策一致 |

结果：`infra/vector` 维持**单一稠密向量集合 + 过滤检索**的纯净形态，不被混合检索的复杂度污染。混合与融合的复杂度全部内聚在 `module/memory`。

---

## 九、韧性与错误收口

### 9.1 韧性（Resilience4j）

- `QdrantVectorStore` 对幂等操作（ensureCollection/upsert/search/get/delete 均幂等）包裹 Resilience4j **重试 + 超时**，吸收 gRPC 瞬时抖动。
- 与 `common.md` 把 `DEPENDENCY` 默认 `recovery` 定为 `RETRY` 一致——底层有限重试，仍失败则抛 `VectorException` 交上层。
- 重试仅针对网络/超时；确定性失败（集合不存在、维度不匹配）直接抛，不浪费重试。

### 9.2 错误收口

```java
public class VectorException extends RuntimeException {
    private final String operation;    // ENSURE / UPSERT / SEARCH / GET / DELETE
    private final String collection;
    private final boolean retryable;
    // + message + cause
}
```

- 内部 catch Qdrant client 的 gRPC `StatusRuntimeException`/IO 异常，统一包成 `VectorException`，区分 `retryable`。
- 跨出 infra 边界由 `common` 的 `ErrorNormalizer` 翻译为 `PixFlowException(category=DEPENDENCY)`（接线机制属 `common` 职责，见 `common.md §10`）。
- **召回降级是 `module/memory` 的策略**：向量检索失败时是否退化为「仅 MySQL 关键词召回」或「召回为空」，由上层决定；infra 只保证抛出携带上下文的异常。
- 落日志前，message 与 payload 中可能含 id（非敏感）统一过 `common` 的 `Sanitizer` 保持一致。

---

## 十、配置

`@ConfigurationProperties(prefix = "pixflow.vector")`：

```yaml
pixflow:
  vector:
    host: localhost
    port: 6334                 # Qdrant gRPC 端口（HTTP 为 6333）
    use-tls: false
    api-key: ${QDRANT_API_KEY:}# 托管实例鉴权；脱敏不入日志
    timeout: 5s                # gRPC 调用超时
    auto-create-collection: true   # 开发自动建集合；生产置 false 由运维预建
```

- 向量维度/集合名**不在此配置**——由 `module/memory` 在 `ensureCollection` 时按 embedding 模型维度传入，避免 infra 写死业务集合。
- `auto-create-collection=false` 时启动仅校验集合存在（缺失快速失败），不静默创建。
- `api-key` 敏感，从环境变量注入，异常上下文经 `Sanitizer` 遮蔽。

---

## 十一、可观测（Micrometer）

最小指标集：

- `pixflow.vector.op{op=upsert|search|delete, result=ok|error}` + 耗时计时器。
- `pixflow.vector.search.returned`：每次检索返回条数（观测 topK/threshold 命中情况）。
- Qdrant 连接健康并入 Spring Boot Actuator `health`（不可达时 `DOWN`）。

---

## 十二、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `module/memory` | 唯一消费方。启动期 `ensureCollection(analysis_insight, dim, COSINE)`；ADD-only 写入用 `upsert`（point id = MySQL 行 id）；向量召回用 `search` 出 ranked list，交由 memory 侧与 MySQL FULLTEXT 召回做 RRF；scoped 过滤用 `VectorFilter` |
| `infra/ai` | 不直接依赖；`module/memory` 用 `infra/ai` 生成向量后传入本模块，本模块不调嵌入模型 |
| `common` | 本模块抛 `VectorException`，跨边界由 `ErrorNormalizer` 归一化为 `DEPENDENCY`；文案经 `Sanitizer` |
| 调用方统一 | 只依赖 `VectorStore` 接口与 `VectorFilter`，不直接接触 `QdrantClient` 与 Qdrant 类型 |

**反向约束**：本模块对以上任何模块零依赖、零业务词。

---

## 十三、测试策略

- **集成测试（Testcontainers + 真实 Qdrant 容器）**：`ensureCollection` 幂等（重复调用、维度不一致快速失败）、`upsert` 按 id 覆盖、`search` 的 topK/threshold/过滤下推、`get`/`delete`/`deleteByFilter`、按 MySQL 全量重建（同 id 覆盖不产生重复点）。这是验证 Qdrant 行为的唯一可靠方式。
- **VectorFilter 翻译单测**：must/should/mustNot 组合、match/matchAny/range 翻成正确的 Qdrant `Filter`。
- **韧性测试**：模拟 gRPC 瞬时失败触发重试；确定性失败（集合不存在）不重试直接抛。
- **错误收口测试**：Qdrant 异常被包成 `VectorException` 且 `retryable` 标注正确、上下文（operation/collection）齐全。

> 若 CI 无 Docker，Testcontainers 用例按环境跳过（本地必跑），并补 `VectorFilter` 翻译与错误映射的纯单测保最低覆盖。

---

## 十四、暂不考虑

- **sparse/BM25 向量、服务端 Fusion.RRF**：本期关键词检索走 MySQL FULLTEXT、RRF 走应用层（见 [§八](#八边界为什么不做-sparsebm25实体)）；接口不堵死，未来若引入 Java 侧稀疏编码可加 `sparseUpsert`/`hybridSearch`。
- **实体抽取 / 图记忆（`_entities` 集合）**：明确不做。
- **多集合分片 / 多租户隔离**：本期单集合 `analysis_insight`，接口已按集合名参数化，后续可平滑扩展。
- **量化/磁盘索引调优（HNSW 参数、scalar quantization）**：用 Qdrant 默认，待出现明确性能/内存瓶颈再调。
- **换 Milvus/PGVector 的真实切换**：本期用 Qdrant Java client，接口层已为替换预留。
