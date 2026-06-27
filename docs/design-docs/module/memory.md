# module/memory —— RAG 记忆层（mem0 v3 简化版，Wave 2）

> 本文是 PixFlow 完整重写阶段 `module/memory` 模块的设计文档，对应 `design.md` 第七章「RAG 记忆层」、§13.1/§13.2 数据模型、§15 多存储一致性，以及 `module-dependency-dag-plan.md` 的 **Wave 2**。
> 范围：三类记忆（用户偏好画像 / SKU 处理历史 / 分析结论记忆）的存储路由、统一 `recall_memory` 召回，以及分析结论记忆的 **mem0 v3 风格 ADD-only 写入管线** 与 **稠密向量 + 关键词 FULLTEXT 混合召回 + RRF 融合**。
> 本文不涉及 MVP 既有实现，从新架构需求重新推导，参考 mem0 v3 算法（取精华、避难点）。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、三类记忆与存储路由](#二三类记忆与存储路由)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、数据模型](#四数据模型)
- [五、写入：ADD-only 抽取管线（mem0 v3）](#五写入add-only-抽取管线mem0-v3)
- [六、召回：混合检索 + RRF 融合](#六召回混合检索--rrf-融合)
- [七、recall_memory 工具路由](#七recall_memory-工具路由)
- [八、异步巩固（Hook 触发）](#八异步巩固hook-触发)
- [九、一致性与重建](#九一致性与重建)
- [十、配置](#十配置)
- [十一、错误与降级](#十一错误与降级)
- [十二、对其他模块的契约](#十二对其他模块的契约)
- [十三、测试策略](#十三测试策略)
- [十四、对 design.md 的细化](#十四对-designmd-的细化)
- [十五、暂不考虑](#十五暂不考虑)

---

## 一、文档定位与设计原则

`module/memory` 处于依赖 DAG 的 **Wave 2**，依赖 `infra/vector`（向量存取）+ `infra/ai`（嵌入与抽取 LLM）+ `common`（错误/脱敏）+ MySQL（MyBatis-Plus）。被 `agent` 决策层（`recall_memory` 动作）与 `module/rubrics`（评分写回）消费。

模块专属设计原则：

1. **mem0 的精华在「抽取 + 混合召回」，不在「存 embedding」**。朴素的「对话原文 → embedding → 检索」会把噪声堆进向量库；本模块只存 LLM 抽取出的**原子结论**，并用混合检索保证召回质量。
2. **采用 mem0 v3 的 ADD-only**：单次 LLM 抽取、只新增、不做 UPDATE/DELETE 决策。记忆随时间累积，靠检索排序让最相关/最新结论浮现。比 v2 的两阶段 diff 更简单、更快。
3. **混合召回走路线 A**：稠密向量召回（Qdrant，`infra/vector`）∥ 关键词召回（MySQL FULLTEXT）→ **RRF 排名融合**（应用层）→ threshold + topN。**不用 BM25 稀疏向量、不引入 Elasticsearch、不做实体/图记忆**（见 `vector.md §八`）。
4. **三类记忆差异化，不强行套 mem0**。只有「分析结论记忆」走向量 + mem0 管线；用户偏好、SKU 历史是结构化数据，走 MySQL 简单读写。
5. **巩固异步、不阻塞当轮**。抽取/写入挂生命周期 Hook 后台执行，用户当轮 SSE 不等待记忆落库。
6. **MySQL 为事实源，Qdrant 可重建**（`design.md §15`）。分析结论同时落 MySQL 镜像（兼做关键词检索源）与 Qdrant 向量，point id = MySQL 行 id。
7. **中文关键词先做轻量工程化**。记忆大概率是中文，但本模块存的是 LLM 抽取后的短原子结论，不是任意长文搜索；本期用 MySQL `ngram` FULLTEXT + 查询归一化 + 结构化过滤解决词面召回，不为分词单独增加搜索基础设施。

---

## 二、三类记忆与存储路由

| 记忆类型 | 存储 | 召回方式 | 套 mem0 管线 |
|---|---|---|---|
| **用户偏好画像** | MySQL `user_preference` | 每次对话开始全量召回，置于 Prompt 静态前缀 | 否（简单 upsert；偏好演化用覆盖，必要时文本层冲突覆盖） |
| **SKU 处理历史** | MySQL `sku_history` | 按 `sku_id` 精确召回 | 否（append-only 事实，无去重/巩固需求） |
| **分析结论记忆** | **Qdrant `analysis_insight` + MySQL 镜像** | **混合检索（向量 + FULLTEXT）+ RRF** | **是**（ADD-only 抽取 + 混合召回） |

只有第三类需要向量库与 mem0 风格管线，前两类沿用 `design.md` 原设计。

---

## 三、模块结构与依赖位置

源码包：`com.pixflow.module.memory`

```
module/memory/
├── MemoryService.java            # 对外门面：recall(...) + ingestAsync(...)
├── recall/
│   ├── RecallType.java           # enum PREFERENCE / SKU_HISTORY / INSIGHT
│   ├── RecallQuery.java          # record(type, query?, skuId?, filters?, topN?)
│   ├── MemoryItem.java           # 统一召回结果 DTO
│   └── RrfFuser.java             # RRF 排名融合（k 可配）
├── insight/
│   ├── InsightExtractor.java     # 单次 LLM 抽取原子结论（infra/ai）
│   ├── InsightIngestService.java # ADD-only 管线：近邻去重上下文→抽取→MD5去重→embed→双写
│   ├── InsightRecallService.java # 向量召回 ∥ FULLTEXT 召回 → RrfFuser
│   ├── InsightKeywordSearch.java # 关键词召回接口：默认 MySQL FULLTEXT，预留替换实现
│   ├── InsightDocMapper.java     # MyBatis-Plus：analysis_insight 镜像表 + FULLTEXT 查询
│   └── InsightVectorRepo.java    # 封装 infra/vector 调用（集合名/payload 约定）
├── preference/
│   ├── PreferenceService.java
│   └── UserPreferenceMapper.java
├── skuhistory/
│   ├── SkuHistoryService.java
│   └── SkuHistoryMapper.java
└── config/
    └── MemoryProperties.java     # @ConfigurationProperties(pixflow.memory)
```

依赖方向：`memory → infra/vector + infra/ai + common`（+ MySQL/MyBatis-Plus）。**不依赖 harness/hooks**——巩固的 Hook 触发由 `agent`/`harness/loop` 层订阅事件后调用 `MemoryService.ingestAsync`，本模块只暴露可异步执行的入口（见 [§八](#八异步巩固hook-触发)）。

---

## 四、数据模型

### 4.1 MySQL

沿用 `design.md §13.1` 的 `user_preference`、`sku_history`，并**新增分析结论镜像表**（`design.md §13.2` 原本只有 Qdrant 集合，混合检索需要 MySQL 侧关键词索引与事实源，见 [§十四](#十四对-designmd-的细化)）：

| 表 | 关键字段 | 说明 |
|---|---|---|
| `user_preference` | id, `key`, value, updated_at | 偏好画像（沿用） |
| `sku_history` | id, sku_id, task_id, params_json, metrics_before, metrics_after, rubrics_score, created_at | SKU 处理历史（沿用） |
| `analysis_insight` | id, text, category, source, confidence, related_sku, content_hash, created_at | **新增**：分析结论镜像；`id` 即 Qdrant point id；`content_hash` 为 MD5 精确去重；`text` 上建 **FULLTEXT 索引**供关键词召回 |

> `analysis_insight.text` 建 InnoDB `FULLTEXT` 索引，关键词召回用 natural-language 模式（`MATCH ... AGAINST`）取带相关度排名的 topN。中文语料默认按 MySQL `ngram` parser 建索引；`ngram_token_size`、停用词与字符集属于数据库部署配置，不放进 `pixflow.memory` 运行时配置。

### 4.2 Qdrant

- collection `analysis_insight`：向量 = 结论文本 embedding（`infra/ai` 生成，维度随模型）；payload = `{text, category, source, confidence, related_sku, created_at}`，与 MySQL 镜像字段对齐；point id = `analysis_insight.id`。

---

## 五、写入：ADD-only 抽取管线（mem0 v3）

对应 mem0 v3「单次 ADD-only 抽取」（[来源](https://docs.mem0.ai/migration/oss-v2-to-v3)，内容已改写以符合引用规范）。由 `InsightIngestService` 实现，**后台异步执行**：

```
本轮分析上下文（用户指令 + 数据支撑 + Agent 结论）
  → ① 取 top-N 近邻既有结论（向量检索）当「去重上下文」
  → ② 单次 LLM 调用：抽取本轮全部不重复的原子结论（带上①作为已知，避免重抽）
  → ③ MD5 内容去重：对每条结论算 content_hash，命中 analysis_insight 已有 hash 则丢弃
  → ④ 批量 embed（infra/ai）
  → ⑤ 双写：先 MySQL analysis_insight（事实源）→ 再 infra/vector.upsert（point id = 行 id）
```

要点：

- **只新增**：不与既有记忆做 UPDATE/DELETE diff。旧结论过时由召回阶段的 recency tie-break 处理（见 [§六](#六召回混合检索--rrf-融合)），不在写入期巩固。
- **两层去重**：① 近邻上下文让 LLM 语义层不重抽；③ MD5 防完全相同文本入库。二者廉价、无需额外 LLM 决策。
- **批量**：embed 与 vector upsert 走批量接口，减少往返（对齐 v3 batch embed + batch insert）。
- **provenance**：`source`/`confidence` 由抽取 LLM 标注并落库，供召回过滤（如 `confidence ≥ 阈值`）与 Rubrics 回写。

---

## 六、召回：混合检索 + RRF 融合

仅作用于分析结论记忆，由 `InsightRecallService` 实现，**同步在当轮执行**（`recall_memory` 工具调用）：

```
查询文本 q + 过滤条件（类目/SKU/置信度）
  → 并行两路召回：
      A. 向量召回：embed(q) → infra/vector.search(topN_each, threshold, filter) → ranked listA
      B. 关键词召回：MySQL FULLTEXT MATCH(text) AGAINST(q) + 等值过滤 → ranked listB
  → RRF 融合：score(d)=Σ 1/(k + rank_i(d))，按 id 合并 A、B 的排名
  → recency tie-break（同分时 created_at 新者优先）
  → 取融合后 topN，套最终 threshold
```

设计要点：

- **真·混合召回**：A、B 各自独立召回（区别于 mem0 v3 把 BM25 仅当 boost、不扩召回）。RRF 基于**排名**融合，天然回避「cosine 分数与 FULLTEXT 相关度不可比」的归一化问题。
- **关键词路由保持可替换**：`InsightRecallService` 依赖 `InsightKeywordSearch` 接口，不直接把 `MATCH ... AGAINST` 散落在业务逻辑里；默认实现由 `InsightDocMapper` 走 MySQL FULLTEXT。未来若确有必要接 Elasticsearch，只替换关键词召回实现与运维索引同步，不改变 `MemoryService`、`RrfFuser` 与 `recall_memory` 契约。
- **中文召回先靠原子记忆质量**：抽取阶段要求结论文本包含明确类目、场景、指标与结论，例如「夏季连衣裙白底图点击率高于场景图」，避免只存「效果更好」这类上下文缺失文本。对中文查询先做轻量归一化（同义词、简称、类目词、SKU 词扩展），再交给 FULLTEXT 与向量召回。
- **不以 BM25 作为本期硬依赖**：关键词侧的职责是把词面强相关记忆捞进候选集，最终质量由「向量召回 + FULLTEXT 候选 + RRF 融合 + 结构化过滤 + recency tie-break」共同保证。若线上评估显示 topN 质量不足，优先增加 query 扩展、字段过滤或可选 rerank，而不是直接引入新的搜索系统。
- **RRF 参数可配**：`k`（默认 60）、每路 `topN_each`、最终 `topN`、`threshold` 走 `pixflow.memory.*`。
- **过滤一致**：类目/SKU 等值过滤在两路都下推（向量侧用 `VectorFilter`，MySQL 侧用 WHERE），保证两路候选域一致。
- **recency tie-break**：ADD-only 会累积同类结论，融合后用 `created_at` 让较新结论在并列时占先，弥补不做 UPDATE 带来的「旧值残留」。
- `RrfFuser` 是与存储无关的纯算法工具，单测覆盖。

### 6.1 何时才升级 Elasticsearch

Elasticsearch 不属于本期设计。只有同时出现明确证据时，才重新评估新增 `infra/search` 或关键词索引服务：

- MySQL FULLTEXT 在实际中文记忆集上出现稳定漏召回，且 query 扩展、抽取文本规范化、结构化过滤、可选 rerank 后仍不能满足质量目标。
- `analysis_insight` 规模增长到 MySQL FULLTEXT 的写入/查询延迟不可接受，或需要独立水平扩展搜索索引。
- 产品能力需要复杂中文 analyzer、同义词热更新、短语匹配、字段权重、高亮、模糊搜索、多字段检索等通用搜索能力，而不只是 RAG 记忆候选召回。

若未来升级，Elasticsearch 只能作为 **MySQL 事实源的派生关键词索引**：写入仍先落 MySQL，搜索索引可重建；RRF 融合仍在 `module/memory` 应用层完成；`recall_memory` 对外 DTO 不变。

---

## 七、recall_memory 工具路由

`design.md §7` 的统一检索接口落为 `MemoryService.recall(RecallQuery)`，按 `RecallType` 路由：

| type | 行为 |
|---|---|
| `PREFERENCE` | 全量读 `user_preference`，整理为偏好画像，供 Prompt 静态前缀（由 agent 层缓存） |
| `SKU_HISTORY` | 按 `skuId` 精确查 `sku_history`，返回处理历史与前后指标 |
| `INSIGHT` | 走 [§六](#六召回混合检索--rrf-融合) 的混合检索 + RRF，返回类目级洞察 |

返回统一 `MemoryItem` DTO（含来源 type、文本、元数据、融合分），对 agent 层屏蔽底层差异。`agent` 模块据此注册 `recall_memory` 动作（按 type 参数路由）。

---

## 八、异步巩固（Hook 触发）

- 抽取/写入管线（[§五](#五写入add-only-抽取管线mem0-v3)）**不在用户当轮同步执行**，避免拖慢 SSE。
- 触发点：`agent`/`harness/loop` 层在 `AssistantMessageCompleted`（或 `TurnStopped`）事件上订阅 Hook，调用 `MemoryService.ingestAsync(turnContext)`。
- 本模块的 `ingestAsync` 用 Spring `@Async`（独立线程池 `pixflow.memory.ingest.pool`）执行，失败仅记 warn + 指标，不影响主对话（记忆缺一条可下次补，事实源在 MySQL）。
- **依赖边界**：Hook 的事件订阅与触发逻辑属 `harness/hooks` + `agent` 层；`module/memory` 只提供可异步调用的 `ingestAsync` 入口，不反向依赖 hooks，保持 Wave 2 依赖最小。

---

## 九、一致性与重建

呼应 `design.md §15`：

- **写顺序**：先 MySQL `analysis_insight`（事实源）成功，再 `infra/vector.upsert`；向量写失败按 `DEPENDENCY/RETRY` 异步补偿重试。
- **point id = MySQL 行 id**：补偿与重建可定位。
- **重建**：提供运维入口按 MySQL 全量 re-embed + `upsert`（同 id 覆盖，幂等），用于 Qdrant 集合损坏/迁移。
- **召回降级**：向量库不可用时，`InsightRecallService` 退化为「仅 FULLTEXT 召回」（单路，不做 RRF），记 warn；MySQL 不可用则召回为空并上报（事实源不可用是严重故障）。

---

## 十、配置

`@ConfigurationProperties(prefix = "pixflow.memory")`：

```yaml
pixflow:
  memory:
    insight:
      collection: analysis_insight     # Qdrant 集合名
      dedup-neighbors: 10              # ADD-only 抽取前取近邻条数（去重上下文）
      recall:
        topn-each: 20                  # 每路召回条数（向量 / FULLTEXT）
        topn: 10                       # RRF 融合后最终条数
        rrf-k: 60                      # RRF 常数 k
        threshold: 0.1                 # 向量召回阈值 [0,1]
    ingest:
      pool:
        core-size: 2
        max-size: 4
        queue-capacity: 100
```

> 嵌入模型维度不在此配，由 `infra/ai` 的 EmbeddingModel 决定，启动期传给 `infra/vector.ensureCollection`。

---

## 十一、错误与降级

| 场景 | 行为 |
|---|---|
| 抽取 LLM 失败 | `ingestAsync` 记 warn + 指标，本轮不落库（可下次补），不影响对话 |
| 向量 upsert 失败 | MySQL 已落（事实源在），按 `DEPENDENCY/RETRY` 异步补偿；不阻塞 |
| 向量 search 失败 | 召回降级为仅 FULLTEXT 单路，记 warn |
| MySQL 不可用 | 召回为空 + 上报（严重故障，事实源不可用） |
| RRF 输入某路为空 | 退化为另一路排名（不报错） |

`infra/vector`/`infra/ai` 的异常跨边界已由各自归一化为 `DEPENDENCY`；本模块在召回/巩固层按上表做策略性降级，文案经 `Sanitizer`。

---

## 十二、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `infra/vector` | `ensureCollection(analysis_insight, dim, COSINE)`；ADD-only `upsert`（id=MySQL 行 id）；向量召回 `search` 出 ranked list；过滤用 `VectorFilter` |
| `infra/ai` | 文本 embedding（写入与查询）、抽取结论的 LLM 调用；维度由其模型决定 |
| MySQL | `analysis_insight` 是事实源与默认关键词索引源；中文 FULLTEXT 使用 `ngram` parser 的数据库部署配置 |
| `agent` | 注册 `recall_memory` 动作 → `MemoryService.recall`；在 turn 完成事件上触发 `ingestAsync` |
| `module/rubrics` | 评分结果写回 `sku_history.rubrics_score` 与（必要时）`analysis_insight` 置信度 |
| `common` | 异常归一化与 `Sanitizer` |

**反向约束**：本模块不依赖 `harness`、`module/task`、`module/dag`。

---

## 十三、测试策略

- **RrfFuser 单测**：两路排名融合正确、k 影响、某路为空的退化、recency tie-break。
- **ADD-only 管线测试**：近邻去重上下文注入、MD5 去重命中丢弃、双写顺序（MySQL 成功才 upsert）、向量写失败的补偿标记。
- **混合召回集成测试（Testcontainers：MySQL + Qdrant）**：同一查询下向量召回与 FULLTEXT 召回各自结果、RRF 融合后 topN、过滤一致性、向量库宕机降级为单路。
- **中文关键词召回测试**：准备中文原子结论样本（类目/场景/指标/结论完整），验证 `ngram` FULLTEXT 能召回「白底图」「连衣裙」「转化率」等词面查询；验证同义词/简称 query 扩展后能进入候选集。
- **关键词接口替换测试**：`InsightRecallService` 用 fake `InsightKeywordSearch` 验证 RRF 不依赖 MySQL 具体实现，保证未来替换 Elasticsearch 时不改对外契约。
- **路由测试**：`recall` 按 type 正确路由（PREFERENCE 全量 / SKU_HISTORY 精确 / INSIGHT 混合）。
- **异步测试**：`ingestAsync` 走独立线程池、异常不冒泡到调用方。

---

## 十四、对 design.md 的细化

本模块对 `design.md` 做如下**细化（非冲突）**，需同步回 design 记录：

1. **新增 MySQL `analysis_insight` 镜像表**：`design.md §13.1` 表清单未含分析结论的 MySQL 表，§13.2 仅有 Qdrant 集合。混合检索路线 A 需要 MySQL 侧 FULLTEXT 关键词召回 + 事实源，故新增此表（字段见 [§四](#四数据模型)），与 Qdrant 集合按 id 对齐。
2. **召回方式细化**：§7「分析结论记忆」的「语义相似度召回」细化为「**稠密向量 + FULLTEXT 关键词混合召回 + RRF 融合**」。
3. **写入方式明确**：分析结论写入采用 **mem0 v3 ADD-only**（单次抽取、MD5 去重、不做 UPDATE/DELETE），召回期以 recency tie-break 处理累积。
4. **中文关键词策略明确**：默认中文记忆用 MySQL `ngram` FULLTEXT；不为中文分词引入 Elasticsearch。关键词召回通过接口隔离，未来确有质量或规模证据时再升级为派生搜索索引。

> 这些是对既有设计的补充落地，不改变 §7 的记忆三分法与 §15 的「MySQL 事实源 / Qdrant 可重建」原则。

---

## 十五、暂不考虑

- **UPDATE/DELETE 巩固、记忆衰减/老化**：本期 ADD-only + recency tie-break；不做主动改写与过期清理。
- **实体抽取 / 图记忆**：明确不做（与 `vector.md §八` 一致）。
- **BM25 稀疏向量混合**：关键词侧用 MySQL FULLTEXT；不引入 Java 侧稀疏编码。
- **Elasticsearch / OpenSearch**：本期不引入。中文关键词先用 MySQL `ngram` FULLTEXT + query 归一化；若未来升级，搜索索引只能作为 MySQL 派生索引，可重建，不成为事实源。
- **偏好画像的向量化/语义召回**：偏好维持 MySQL 全量召回，不进向量库。
- **复杂跨语言 FULLTEXT/分词优化**：本期按数据集语言配置 MySQL ngram/分词，不做多语言统一 analyzer、同义词热更新、短语高亮等通用搜索能力。
- **决策质量评估依赖的滞后电商反馈写回**：随 `design.md §16` 本期不做。
