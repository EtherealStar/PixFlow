# module/memory —— 自动记忆层（衰减召回，Wave 2）

> 本文是 PixFlow 完整重写阶段 `module/memory` 模块的设计文档，对应 `design.md` 第七章「RAG 记忆层」、§13.1/§13.2 数据模型、§15 多存储一致性，以及 `module-dependency-dag-plan.md` 的 **Wave 2**。
> 范围：三类记忆（用户偏好画像 / SKU 处理历史 / 分析结论记忆）的存储路由、**系统自动召回与 Prompt 注入**、分析结论记忆的抽取巩固、衰减遗忘、稠密向量 + 关键词 FULLTEXT 混合召回 + RRF 融合。
> 本文不涉及 MVP 既有实现，从生产级 Agent 记忆需求重新推导。`recall_memory` 不再作为 Agent 工具存在，也不保留其工具路由设计。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、三类记忆与存储路由](#二三类记忆与存储路由)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、数据模型](#四数据模型)
- [五、写入：异步抽取与巩固](#五写入异步抽取与巩固)
- [六、召回：系统自动规划 + 混合检索 + 衰减排序](#六召回系统自动规划--混合检索--衰减排序)
- [七、自动注入 Prompt 的契约](#七自动注入-prompt-的契约)
- [八、生命周期衰减与遗忘](#八生命周期衰减与遗忘)
- [九、Hook 触发边界](#九hook-触发边界)
- [十、一致性与重建](#十一致性与重建)
- [十一、配置](#十一配置)
- [十二、错误与降级](#十二错误与降级)
- [十三、对其他模块的契约](#十三对其他模块的契约)
- [十四、测试策略](#十四测试策略)
- [十五、对 design.md 的细化](#十五对-designmd-的细化)
- [十六、暂不考虑](#十六暂不考虑)

---

## 一、文档定位与设计原则

`module/memory` 处于依赖 DAG 的 **Wave 2**，依赖 `infra/vector`（向量存取）+ `infra/ai`（嵌入与抽取 LLM）+ `common`（错误/脱敏）+ MySQL（MyBatis-Plus）。它被 `agent` 的 Prompt 组装层、记忆 Hook 接线层与 `module/rubrics` 消费。

模块专属设计原则：

1. **记忆召回是系统责任，不是模型工具调用**。PixFlow 不实现 `recall_memory` Agent 工具。Agent 不应该决定“是否召回记忆、召回哪类记忆”；系统在 Prompt 组装前根据用户输入、附件、SKU、类目、任务阶段与会话上下文自动规划召回。
2. **自动召回必须可解释、可追踪**。每轮注入 Prompt 的记忆都要带召回来源、召回理由、分数、过滤条件与衰减信息，并写入 eval trace，便于回放与排查。
3. **mem0 的可取部分是「抽取原子记忆 + 混合召回」**。不存对话原文 embedding，避免把噪声堆入向量库；只存 LLM 抽取出的原子结论，并通过向量 + FULLTEXT 混合检索保证候选质量。
4. **生产级记忆必须衰减和遗忘**。分析结论记忆不是无限 ADD-only 堆积。旧记忆会随时间、低使用率、低置信度、冲突与长期未强化而降权，必要时进入 `SUPPRESSED` / `EXPIRED` 状态，并从 Qdrant active 索引删除。
5. **三类记忆差异化，不强行套同一算法**。用户偏好画像和 SKU 处理历史是结构化数据，走 MySQL 读写；只有分析结论记忆走抽取、向量化、混合召回、衰减生命周期。
6. **巩固异步，不阻塞当轮**。记忆抽取/写入由生命周期 Hook 触发，但 Hook 只负责触发，不承载业务实现。用户当轮 SSE 不等待记忆落库。
7. **MySQL 为事实源，Qdrant 是 active 派生索引**。分析结论先落 MySQL，Qdrant 仅保存 `ACTIVE` 记忆的向量索引，可按 MySQL 重建。point id = MySQL 行 id。
8. **中文关键词先做轻量工程化**。本期用 MySQL `ngram` FULLTEXT + query 归一化 + 结构化过滤解决词面召回，不引入 Elasticsearch / OpenSearch。

---

## 二、三类记忆与存储路由

| 记忆类型 | 存储 | 自动召回方式 | 生命周期 |
|---|---|---|---|
| **用户偏好画像** | MySQL `user_preference` | 每轮 Prompt 组装前全量读取，形成稳定用户偏好 section | 覆盖式 upsert；新偏好覆盖旧偏好 |
| **SKU 处理历史** | MySQL `sku_history` | 系统从附件、素材包、文件名、电商数据上下文提取 SKU 后精确召回 | append-only 事实；不做向量衰减 |
| **分析结论记忆** | MySQL `analysis_insight` + Qdrant active 索引 | 系统构造检索 query 与 filters，走向量 + FULLTEXT + RRF，再叠加衰减排序 | `ACTIVE` / `SUPPRESSED` / `EXPIRED`；支持衰减、强化、遗忘 |

只有第三类需要向量库和混合检索。前两类直接作为 Prompt 的基础上下文或结构化事实补充，不交给模型自主检索。

---

## 三、模块结构与依赖位置

源码包：`com.pixflow.module.memory`

```
module/memory/
├── MemoryService.java               # 对外门面：prepareContext(...) + ingestAsync(...) + reinforce(...)
├── context/
│   ├── MemoryContextRequest.java     # Prompt 组装前的自动召回请求
│   ├── MemoryContext.java            # 已排序、可注入 Prompt 的记忆上下文
│   ├── MemorySection.java            # preference / sku_history / insight 分区
│   └── MemoryContextBuilder.java     # 调用 planner + 各召回服务，生成注入内容
├── recall/
│   ├── RecallPlanner.java            # 系统确定性规划召回类型、query、filters、topN
│   ├── RecallPlan.java               # preference? skuIds? insightQuery? filters?
│   ├── RecallSignalExtractor.java    # 从 prompt/附件/任务上下文提取 SKU、类目、意图
│   ├── MemoryItem.java               # 统一召回结果 DTO
│   ├── MemoryRanker.java             # RRF + 衰减 + 重要性 + recency 排序
│   └── RrfFuser.java                 # 存储无关的 RRF 排名融合
├── insight/
│   ├── InsightExtractor.java         # 单次 LLM 抽取原子结论（infra/ai）
│   ├── InsightIngestService.java     # 抽取→去重→冲突检测→embed→MySQL→Qdrant
│   ├── InsightRecallService.java     # 向量召回 ∥ FULLTEXT 召回 → RRF → 衰减排序
│   ├── InsightLifecycleService.java  # 衰减、强化、抑制、过期、Qdrant active 同步
│   ├── InsightKeywordSearch.java     # 关键词召回接口：默认 MySQL FULLTEXT
│   ├── InsightDocMapper.java         # MyBatis-Plus：analysis_insight + FULLTEXT 查询
│   └── InsightVectorRepo.java        # 封装 infra/vector 调用（active 集合）
├── preference/
│   ├── PreferenceService.java
│   └── UserPreferenceMapper.java
├── skuhistory/
│   ├── SkuHistoryService.java
│   └── SkuHistoryMapper.java
└── config/
    └── MemoryProperties.java         # @ConfigurationProperties(pixflow.memory)
```

依赖方向：`memory → infra/vector + infra/ai + common`（+ MySQL/MyBatis-Plus）。**不依赖 `harness/hooks`、`harness/loop`、`agent`**。

Hook 注册、事件订阅、Prompt 注入接线归 `agent` / harness 接线层；memory 模块只提供可调用能力：

- `MemoryService.prepareContext(MemoryContextRequest request)`：同步自动召回，供 Prompt 组装前调用。
- `MemoryService.ingestAsync(MemoryIngestRequest request)`：异步巩固入口，供 Hook 接线层触发。
- `MemoryService.reinforce(MemoryReinforcementEvent event)`：召回使用、Rubrics 结果或用户反馈后的强化入口。

---

## 四、数据模型

### 4.1 MySQL

沿用 `design.md §13.1` 的 `user_preference`、`sku_history`，并新增分析结论镜像表。`analysis_insight` 是事实源，Qdrant 是 active 派生索引。

| 表 | 关键字段 | 说明 |
|---|---|---|
| `user_preference` | id, `key`, value, updated_at | 偏好画像；按 key 覆盖式 upsert |
| `sku_history` | id, sku_id, task_id, params_json, metrics_before, metrics_after, rubrics_score, created_at | SKU 处理历史；append-only 事实 |
| `analysis_insight` | id, text, category, source, confidence, related_sku, content_hash, importance, status, access_count, last_recalled_at, last_reinforced_at, decay_score, created_at, updated_at, expires_at | 分析结论事实源；`text` 建 FULLTEXT；active 行同步到 Qdrant |

`analysis_insight.status`：

| 状态 | 含义 | Qdrant |
|---|---|---|
| `ACTIVE` | 可召回记忆 | 存在 point |
| `SUPPRESSED` | 被新结论冲突覆盖、质量不足或人工/系统抑制 | 删除 point，MySQL 保留审计 |
| `EXPIRED` | 衰减到过期阈值，默认不再召回 | 删除 point，MySQL 保留审计 |

字段说明：

- `importance`：抽取或强化得到的重要性，默认来自 LLM 标注与规则修正。
- `access_count`：被注入 Prompt 的次数，不统计仅入候选但未注入的结果。
- `last_recalled_at`：最近一次注入 Prompt 的时间。
- `last_reinforced_at`：最近一次被 Rubrics、用户采纳或新证据强化的时间。
- `decay_score`：生命周期服务计算出的衰减分，越低越不应召回。
- `expires_at`：可选硬过期时间，用于临时活动、短周期结论。
- `content_hash`：规范化文本 MD5，做精确去重。

> `analysis_insight.text` 建 InnoDB `FULLTEXT` 索引，关键词召回用 natural-language 模式（`MATCH ... AGAINST`）取带相关度排名的 topN。中文语料默认按 MySQL `ngram` parser 建索引；`ngram_token_size`、停用词与字符集属于数据库部署配置，不放进 `pixflow.memory` 运行时配置。

### 4.2 Qdrant

- collection `analysis_insight`：只保存 `status=ACTIVE` 的分析结论。
- 向量 = 结论文本 embedding（`infra/ai` 生成，维度随模型）。
- payload = `{text, category, source, confidence, related_sku, importance, decay_score, created_at, last_reinforced_at}`。
- point id = `analysis_insight.id`。

当 MySQL 行进入 `SUPPRESSED` / `EXPIRED`，`InsightLifecycleService` 必须删除对应 Qdrant point。重建时按 MySQL 中所有 `ACTIVE` 行 re-embed + upsert。

---

## 五、写入：异步抽取与巩固

分析结论记忆由 `InsightIngestService` 后台异步执行：

```
本轮完整上下文（用户指令 + 自动召回记忆 + 数据支撑 + 工具结果 + Agent 最终结论）
  → ① 取 top-N 近邻 ACTIVE 结论作为去重/冲突上下文
  → ② 单次 LLM 调用：抽取本轮新的原子结论，输出 category/source/confidence/importance/related_sku/evidence
  → ③ 规范化与 MD5 精确去重：命中已有 content_hash 的丢弃或强化
  → ④ 冲突检测：与近邻结论冲突时，旧结论可转 SUPPRESSED，新结论 ACTIVE
  → ⑤ 批量 embed
  → ⑥ 先写 MySQL analysis_insight（事实源）→ 再 upsert Qdrant active point
```

要点：

- **不是纯 ADD-only 堆积**：新增仍是主路径，但生产级实现必须处理冲突、强化、抑制与过期。旧结论不能只靠 recency tie-break 永久残留。
- **Hook 只触发，不实现抽取**：Hook 接线层拿到回合结束事件后调用 `MemoryService.ingestAsync`，具体抽取、去重、冲突处理和双写都在 memory 模块。
- **抽取输入必须是完整 turn context**：不能只传 assistant 文本。至少包含用户 prompt、最终回答、自动召回过的记忆、关键工具观察、电商数据证据、相关 SKU/类目、taskId、traceId。
- **原子结论要求自含上下文**：例如「夏季连衣裙白底图点击率高于场景图」是合格记忆；「效果更好」不合格。
- **强化优先于重复写入**：完全重复或语义等价记忆不新增，更新 `last_reinforced_at`、`importance`、`confidence` 或 `access_count`。

---

## 六、召回：系统自动规划 + 混合检索 + 衰减排序

召回由系统在 Prompt 组装前自动执行，不通过 Agent 工具调用。

```
MemoryContextRequest（conversationId + turnNo + userPrompt + attachments + package/task context）
  → RecallSignalExtractor：提取 SKU、类目、意图、时间范围、任务阶段
  → RecallPlanner：确定 preference / sku_history / insight 的召回计划
  → PreferenceService：全量偏好画像
  → SkuHistoryService：按 skuIds 精确召回
  → InsightRecallService：
       A. 向量召回：embed(q) → infra/vector.search(topN_each, threshold, filter) → ranked listA
       B. 关键词召回：MySQL FULLTEXT MATCH(text) AGAINST(q) + WHERE filter → ranked listB
       C. RRF 融合：score(d)=Σ 1/(k + rank_i(d))
       D. 衰减排序：RRF + confidence + importance + decay_score + recency + reinforcement
       E. topN + token budget 裁剪
  → MemoryContext：按 section 产出可注入 Prompt 的记忆上下文
```

### 6.1 RecallPlanner 的职责

`RecallPlanner` 是确定性系统组件，不调用 LLM。它根据上下文规划召回：

| 输入信号 | 召回动作 |
|---|---|
| 每轮对话 | 必召回 `PREFERENCE` |
| 附件 / package / 文件名 / commerce 数据中出现 SKU | 召回对应 `SKU_HISTORY` |
| prompt 或任务上下文出现类目、视觉处理目标、指标词 | 构造 insight query + category/SKU filters |
| 无明确 SKU 但有类目或处理意图 | 召回类目级 `INSIGHT` |
| token 预算紧张 | 降低 insight topN，保留偏好和当前 SKU 历史 |

这避免让模型自己判断“该不该召回记忆”。模型只看到最终被注入的 memory section。

### 6.2 分析结论混合召回

- **真·混合召回**：向量和 FULLTEXT 各自独立扩召回，再应用层 RRF 融合。
- **过滤一致**：类目/SKU/置信度/status/过期时间等条件在两路都下推。向量侧用 `VectorFilter`，MySQL 侧用 WHERE。
- **只召回 active 记忆**：MySQL 查询默认 `status='ACTIVE'` 且未过期；Qdrant collection 也只存 active point。
- **中文召回靠抽取质量 + query 归一化**：召回前做类目词、简称、同义词、SKU 词扩展。
- **衰减参与最终排序，不替代相关性**：相关性差的内容不能靠高 importance 进入 Prompt；同等相关时优先高 confidence、高 importance、近期强化、低衰减的记忆。

### 6.3 最终排序建议

最终分数不要求暴露给模型，但要写入 trace。建议形态：

```
final_score =
  rrf_weight * rrf_score
  + confidence_weight * confidence
  + importance_weight * importance
  + decay_weight * decay_score
  + reinforcement_weight * reinforcement_boost
  + recency_weight * recency_boost
```

`MemoryRanker` 负责实现该公式，并对同分结果用 `last_reinforced_at`、`created_at` 做稳定 tie-break。

### 6.4 何时才升级 Elasticsearch

Elasticsearch 不属于本期设计。只有同时出现明确证据时，才重新评估新增 `infra/search`：

- MySQL FULLTEXT 在真实中文记忆集上稳定漏召回，且 query 扩展、抽取文本规范化、结构化过滤后仍不能满足质量目标。
- `analysis_insight` 规模增长到 MySQL FULLTEXT 的写入/查询延迟不可接受。
- 产品能力需要复杂中文 analyzer、同义词热更新、短语匹配、字段权重、高亮、模糊搜索、多字段检索等通用搜索能力。

若未来升级，搜索索引只能作为 **MySQL 事实源的派生关键词索引**，不改变自动召回与 Prompt 注入契约。

---

## 七、自动注入 Prompt 的契约

`MemoryService.prepareContext(...)` 返回 `MemoryContext`，由 agent Prompt 组装层转成系统 memory section。推荐 section：

| section | 来源 | 注入规则 |
|---|---|---|
| `user_preferences` | `user_preference` | 每轮注入，数量小，可全量 |
| `sku_history` | `sku_history` | 仅注入当前相关 SKU；按时间和 rubrics_score 摘要 |
| `analysis_insights` | `analysis_insight` | 注入 topN，带类目/SKU/置信度/来源摘要 |

Prompt 注入要求：

- 不把原始分数暴露成模型可操纵的指令，只提供简洁来源说明。
- 不注入 `SUPPRESSED` / `EXPIRED` 记忆。
- 若记忆之间存在冲突，只注入 active 且分数更高的新结论；冲突说明可进 trace，不必喂给模型。
- 注入结果必须写入 `agent_trace.recall_json`，包含召回计划、query、filters、候选数量、最终注入项、衰减/过滤原因。

---

## 八、生命周期衰减与遗忘

`InsightLifecycleService` 负责分析结论记忆的强化、衰减和遗忘。

### 8.1 衰减因素

| 因素 | 影响 |
|---|---|
| 时间距离 | 越久未强化，`decay_score` 越低 |
| 访问情况 | 长期未被注入 Prompt 的记忆降权 |
| 置信度 | 低置信度记忆更快衰减 |
| importance | 高重要性记忆衰减更慢 |
| Rubrics / 用户反馈 | 被采纳或被评分证实的记忆强化 |
| 冲突 | 新证据冲突时，旧记忆可转 `SUPPRESSED` |
| expires_at | 到期直接不召回，并进入过期处理 |

### 8.2 生命周期任务

后台定时执行：

1. 重新计算 `decay_score`。
2. 对低于 `suppress-threshold` 的记忆转 `SUPPRESSED`。
3. 对低于 `expire-threshold` 或超过 `expires_at` 的记忆转 `EXPIRED`。
4. 删除非 `ACTIVE` 记忆的 Qdrant point。
5. 对 `ACTIVE` 但 payload 过期的 point 做 upsert 同步。

MySQL 行默认不物理删除，保留审计与重建依据。若未来需要合规清理，再单独设计硬删除策略。

### 8.3 强化入口

以下事件可以强化记忆：

- 被自动召回并注入 Prompt。
- Agent 后续回答采纳了该记忆。
- 用户明确确认某结论正确。
- Rubrics 评分或任务结果支持该结论。
- 新一轮抽取发现等价结论。

强化行为更新 `last_reinforced_at`、`importance`、`confidence`、`access_count`，并提升或重算 `decay_score`。

---

## 九、Hook 触发边界

Hook 是触发机制，不是 memory 业务实现位置。

推荐接线：

- `USER_PROMPT_SUBMIT`：可用于预热或记录召回信号，但主路径是在 Prompt 组装前直接调用 `MemoryService.prepareContext(...)`。
- `TURN_STOPPED`：推荐作为记忆异步抽取的主触发点，表示本轮 Agent 已自然结束。
- `ASSISTANT_MESSAGE_COMPLETED`：只适合观察 assistant 消息完成，不建议作为主抽取触发点，因为中间轮可能还有后续 tool call。
- `TASK_COMPLETED` / Rubrics 完成事件：触发 SKU 历史写入或记忆强化。

依赖边界：

- `harness/hooks` core 不写任何 memory 逻辑。
- `module/memory` 不依赖 hooks。
- `agent` 或接线层注册 `MemoryConsolidationHook`，订阅 `TURN_STOPPED`，组装 `MemoryIngestRequest` 后调用 `MemoryService.ingestAsync(...)` 并立即返回 `HookResult.noop()`。

---

## 十、一致性与重建

呼应 `design.md §15`：

- **写顺序**：先 MySQL `analysis_insight`（事实源）成功，再 `infra/vector.upsert`。向量写失败按 `DEPENDENCY/RETRY` 异步补偿。
- **point id = MySQL 行 id**：补偿、删除与重建可定位。
- **active 索引同步**：`ACTIVE` 行 upsert Qdrant；`SUPPRESSED` / `EXPIRED` 行 delete Qdrant。
- **重建**：运维入口按 MySQL 中 `ACTIVE` 行全量 re-embed + upsert；并删除 Qdrant 中不存在于 active id 集合的孤儿 point。
- **召回降级**：Qdrant 不可用时，分析结论召回退化为仅 FULLTEXT；MySQL 不可用时无法召回事实源，返回空 memory context 并上报严重故障。

---

## 十一、配置

`@ConfigurationProperties(prefix = "pixflow.memory")`：

```yaml
pixflow:
  memory:
    prompt:
      max-items: 18
      max-tokens: 1800
      preference-max-items: 50
      sku-history-max-items-per-sku: 5
      insight-topn: 10
    insight:
      collection: analysis_insight
      dedup-neighbors: 10
      recall:
        topn-each: 20
        topn: 10
        rrf-k: 60
        vector-threshold: 0.1
        min-final-score: 0.1
      rank:
        rrf-weight: 0.55
        confidence-weight: 0.15
        importance-weight: 0.15
        decay-weight: 0.10
        recency-weight: 0.05
      lifecycle:
        decay-half-life-days: 30
        suppress-threshold: 0.25
        expire-threshold: 0.10
        maintenance-cron: "0 0 * * * *"
    ingest:
      pool:
        core-size: 2
        max-size: 4
        queue-capacity: 100
```

嵌入模型维度不在此配置，由 `infra/ai` 的 `EmbeddingClient` 决定，启动期传给 `infra/vector.ensureCollection`。

---

## 十二、错误与降级

| 场景 | 行为 |
|---|---|
| 自动召回规划失败 | 返回空或部分 memory context，记 warn + trace，不阻断主对话 |
| 向量 search 失败 | 分析结论退化为仅 FULLTEXT 单路召回 |
| FULLTEXT 查询失败 | 若向量可用，则退化为仅向量召回；MySQL 整体不可用则召回为空 |
| 抽取 LLM 失败 | `ingestAsync` 记 warn + 指标，本轮不落库，不影响对话 |
| 向量 upsert/delete 失败 | MySQL 已落事实状态，按 `DEPENDENCY/RETRY` 补偿 |
| 生命周期维护失败 | 不影响在线召回；下次 maintenance 重试 |
| RRF 输入某路为空 | 退化为另一路排名 |
| token 预算不足 | 保留偏好与当前 SKU 历史，压缩或减少 insight |

错误文案经 `Sanitizer`，召回和注入决策写入 eval trace。

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `infra/vector` | `ensureCollection(analysis_insight, dim, COSINE)`；active 记忆 `upsert`；非 active 记忆 `delete`；向量召回 `search`；过滤用 `VectorFilter` |
| `infra/ai` | embedding；抽取原子结论的 LLM 调用；可选 rerank 不作为本期硬依赖 |
| MySQL | `analysis_insight` 是事实源与默认关键词索引源；中文 FULLTEXT 使用 `ngram` parser 部署配置 |
| `agent` | Prompt 组装前调用 `MemoryService.prepareContext(...)`，把返回的 `MemoryContext` 注入系统 Prompt；不注册 `recall_memory` 工具 |
| `harness/hooks` | 只派发生命周期事件；Hook core 不包含 memory 实现 |
| `agent` / 接线层 | 注册 memory 相关 Hook：`TURN_STOPPED` 触发 `ingestAsync`；Rubrics/Task 事件触发强化 |
| `harness/eval` | 记录 `recall_json`：召回计划、query、filters、候选、注入项、衰减和降级信息 |
| `module/rubrics` | 评分结果写回 `sku_history.rubrics_score`，并通过 reinforcement 事件强化或抑制分析结论 |
| `common` | 异常归一化与 `Sanitizer` |

反向约束：本模块不依赖 `harness`、`agent`、`module/task`、`module/dag`。

---

## 十四、测试策略

- **RecallPlanner 单测**：每轮必召回偏好；从 prompt/附件/package/task context 提取 SKU；类目/意图触发 insight query；token 预算下降级策略正确。
- **Prompt 注入测试**：`MemoryContext` 分区、排序、token 裁剪、冲突结果过滤、trace 字段完整。
- **RrfFuser 单测**：两路排名融合、k 影响、某路为空退化、稳定 tie-break。
- **MemoryRanker 单测**：RRF、confidence、importance、decay、recency、reinforcement 权重计算正确。
- **ADD + 巩固管线测试**：近邻去重上下文注入、MD5 去重命中强化、冲突旧记忆转 `SUPPRESSED`、双写顺序、向量写失败补偿标记。
- **生命周期测试**：衰减分计算、长期未访问降权、过期转 `EXPIRED`、低分转 `SUPPRESSED`、Qdrant delete 调用。
- **混合召回集成测试（Testcontainers：MySQL + Qdrant）**：向量召回、FULLTEXT 召回、RRF 融合、过滤一致性、active-only 检索、向量库宕机降级。
- **中文关键词召回测试**：中文原子结论样本验证 `ngram` FULLTEXT 与 query 扩展。
- **Hook 接线测试（在 agent/接线层）**：`TURN_STOPPED` hook 只触发 `ingestAsync` 并立即返回，不在 hook 内执行抽取。
- **无工具召回测试**：Agent 可见工具集合中不存在 `recall_memory`，记忆仍被自动注入 Prompt。

---

## 十五、对 design.md 的细化

本模块对 `design.md` 做如下细化，需同步回总体设计：

1. **删除 `recall_memory` Agent 工具设计**：记忆召回不作为模型可调用工具存在。召回改为 Prompt 组装前的系统自动规划与注入。
2. **新增 MySQL `analysis_insight` 镜像表并扩展生命周期字段**：`analysis_insight` 是事实源，Qdrant 是 active 派生索引。
3. **召回方式细化**：分析结论从单纯语义召回细化为「系统自动规划 + 稠密向量 + FULLTEXT + RRF + 衰减排序」。
4. **写入方式修正**：不采用无限 ADD-only 堆积。抽取以新增为主，但必须支持去重强化、冲突抑制、过期和遗忘。
5. **记忆生命周期明确**：分析结论有 `ACTIVE` / `SUPPRESSED` / `EXPIRED` 状态，Qdrant 只保存 active 记忆。
6. **Hook 职责明确**：Hook 只触发异步巩固，不承载 memory 业务逻辑；`TURN_STOPPED` 是推荐抽取触发点。

---

## 十六、暂不考虑

- **Elasticsearch / OpenSearch**：本期不引入；中文关键词先用 MySQL `ngram` FULLTEXT + query 归一化。
- **实体抽取 / 图记忆**：明确不做。
- **BM25 稀疏向量混合**：关键词侧用 MySQL FULLTEXT，不引入 Java 侧稀疏编码。
- **偏好画像向量化**：偏好维持 MySQL 全量召回，不进向量库。
- **物理删除历史记忆**：本期通过 `SUPPRESSED` / `EXPIRED` 逻辑遗忘，MySQL 保留审计；合规硬删除另行设计。
- **复杂多语言搜索 analyzer**：本期按中文电商场景优化，不做多语言统一 analyzer、同义词热更新、短语高亮等通用搜索能力。
- **外部电商平台长期反馈闭环**：随 `design.md §16` 本期不做；Rubrics 和本地任务结果可作为强化信号。
