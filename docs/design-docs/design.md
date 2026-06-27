# PixFlow 电商运营 Agent — 总体设计文档

> 本文是 PixFlow 从「自然语言批量图片处理工具」演进为「电商运营 Agent」阶段的总体架构与技术选型设计。
> 需求来源：`requirement.md`；本阶段为**完整重写**，不以既有 MVP 实现为约束，仅复用其中经过验证的事实层结论（如像素工具清单、SKU 绑定规则）。

---

## 目录

- [一、文档定位与范围](#一文档定位与范围)
- [二、设计原则](#二设计原则)
- [三、总体架构](#三总体架构)
- [四、技术栈选型](#四技术栈选型)
- [五、Harness 横切层](#五harness-横切层)
- [六、Agent 决策层](#六agent-决策层)
- [七、RAG 记忆层](#七rag-记忆层)
- [八、电商数据接入层](#八电商数据接入层)
- [九、DAG 确定性执行引擎](#九dag-确定性执行引擎)
- [十、子 Agent 设计](#十子-agent-设计)
- [十一、Rubrics 评估（离线阶段）](#十一rubrics-评估离线阶段)
- [十二、业务模块划分](#十二业务模块划分)
- [十三、数据模型](#十三数据模型)
- [十四、异步执行时序](#十四异步执行时序)
- [十五、技术风险](#十五技术风险)
- [十六、暂不考虑](#十六暂不考虑)

---

## 一、文档定位与范围

PixFlow 面向电商运营人员，以对话窗口为唯一入口，由 Agent 消费用户指令与外部电商数据，在 Human-in-the-loop（HITL）约束下给出数据支撑的处理建议，确认后执行批量图片处理。系统以 **RAG 为记忆底座、DAG 引擎为确定性执行核心、子 Agent 为视觉/生成辅助、Rubrics 为离线评估闭环、Harness 为横切安全约束层**。

本文覆盖：总体架构、技术选型、Maven 多模块组织、Harness 六件套、Agent 决策循环、三类子 Agent、两条产图路径、RAG 记忆、电商数据接入、异步执行、数据模型、技术风险。

不覆盖（见 [十六](#十六暂不考虑)）：视频处理、权限/多账号、计费限流、外部电商平台真实 API 对接、参数自动猜测、Rubrics 评估的具体评分细则（待数据集确定后单独设计）。

---

## 二、设计原则

1. **两层循环分离**。上层是 **Agent 决策循环**（非确定性，think-act-observe，驱动召回、分析、建议、确认、重跑决策）；下层是 **DAG 确定性执行引擎**（可预测、可测试的批处理底座）。DAG 执行是 Agent 决策循环里的一个动作，本身不是 Agent，绝不内嵌自主迭代。

2. **确定性底座不被污染**。Agent 永远不直接调用像素工具（`remove_bg`/`resize`…），它只「编译出 DAG 并请求确认执行」。两套工具严格分离（见 [5.2](#52-tool-registry)）。

3. **安全边界是硬约束，不是 Prompt 约束**。「Agent 无法自主拍板执行、必须等用户确认」「超阈值二次确认」等，由**权限层硬 deny** 实现，Agent 无法自签发确认令牌。Hook 只能观察/改写/软阻断，其 allow 不能覆盖权限层 deny。

4. **Harness 是横切 services 层，不是领域模块**。六件套贯穿执行各阶段，靠注册/注入接入，不按业务领域切分。

5. **测试/评估与主循环解耦**。Rubrics 评估是独立离线阶段，消费 Evaluation Interface 的 trace + 本地数据集，与主循环无关。主循环中的子 Agent 是辅助能力（视觉理解、生图），不是测试工具。

6. **完整项目标准**。对象存储、异步、断点恢复、可观测、韧性都按生产标准设计，不做 MVP 式简化。

7. **共享契约独立成模块**。跨模块只共享接口、record、enum 与纯 DTO，统一放入独立的 `contracts` Maven 模块；`common` 继续只承载错误处理、分页、脱敏等横切能力。

---

## 三、总体架构

```
┌────────────────────────────────────────────────────────────────┐
│                      Vue 3 前端（对话 / 文件 / 结果 / 评分展示）        │
└───────────────┬───────────────────────────┬────────────────────┘
        SSE(LLM流式) │                WebSocket/STOMP(进度推送) │  REST
┌───────────────▼───────────────────────────▼────────────────────┐
│                        Spring Boot 主后端                          │
│                        共享契约层 contracts                         │
│                                                                  │
│  ┌────────────────────── Agent 决策层 ──────────────────────┐    │
│  │  Execution Loop (think-act-observe, max iterations)       │    │
│  │  动态 Prompt 组装 + section 缓存                            │    │
│  │  Tool Registry(Agent级动作): recall_memory /              │    │
│  │     query_commerce_data / compile_dag / submit_dag(需令牌)│    │
│  │     / run_vision_subagent / run_imagegen_subagent         │    │
│  └───────┬─────────────┬──────────────┬───────────┬─────────┘    │
│          │             │              │           │              │
│  ┌───────▼──────┐ ┌────▼─────┐  ┌─────▼────┐ ┌────▼─────────┐    │
│  │ RAG 记忆层    │ │电商数据层 │  │ 子Agent   │ │ DAG 编译/校验 │    │
│  │ Qdrant+MySQL │ │本地集+API │  │视觉/生图   │ │              │    │
│  └──────────────┘ └──────────┘  └─────┬────┘ └────┬─────────┘    │
│                                       │           │ submit(确认后) │
│  ┌──────────────── Harness 横切层 ─────┼───────────┼───────────┐  │
│  │ Context Manager · Lifecycle Hooks · State Store · Eval IF  │  │
│  └────────────────────────────────────┼───────────┼──────────┘  │
└───────────────────────────────────────┼───────────┼─────────────┘
                                  调用第三方│    RabbitMQ│(任务分发)
                          ┌──────────────▼──┐ ┌───────▼──────────┐
                          │ 抠图API/VLLM/生图  │ │ DAG 确定性执行引擎  │
                          │ /文本LLM(Spring AI)│ │ 分支展开+并发+失败隔离│
                          └──────────────────┘ └───────┬──────────┘
                                                       │
              ┌────────────┬──────────────┬───────────┼────────────┐
        ┌─────▼────┐ ┌─────▼─────┐ ┌──────▼─────┐ ┌───▼────┐ ┌─────▼────┐
        │ MySQL 8  │ │  Redis    │ │  Qdrant    │ │ MinIO  │ │ 离线Rubrics│
        │ 关系数据  │ │锁/断点/进度│ │ 向量检索   │ │对象存储 │ │ 评估(独立) │
        └──────────┘ └───────────┘ └────────────┘ └────────┘ └──────────┘
```

两条产图路径：
- **确定性路径**：Agent 编译 DAG → 确认 → DAG 引擎执行像素工具（抠图/换底/缩放/压缩/水印/格式转换/分组聚合）。
- **生成式路径**：Agent 综合数据+记忆+用户要求撰写生图提示词 → 生图子 Agent 以源图+提示词重绘新图。

---

## 四、技术栈选型

| 维度 | 选型 | 理由 |
|---|---|---|
| 后端框架 | Spring Boot 3 | 主体服务 |
| 工程组织 | Maven multi-module | `common`、`contracts`、`harness`、`module`、`infra`、`agent` 分层编译 |
| LLM 抽象 | **Spring AI + Spring AI Alibaba** | Spring 原生;一套抽象覆盖文本/多模态(Qwen-VL)/生图(通义万相)/嵌入;内置 Qdrant VectorStore;手写 Agent 循环只用其调用层 |
| 任务队列 | **RabbitMQ** | 作业队列语义最匹配:逐消息 ack、DLQ、重试、prefetch 公平分发;Spring AMQP 成熟 |
| 并发/缓存 | **Redis + Redisson** | 分布式锁(看门狗续期)、支路断点缓存、进度计数、第三方 API 限流(信号量) |
| 向量库 | **Qdrant** | 分析结论记忆的语义召回 |
| 关系库 | **MySQL 8 + MyBatis-Plus** | 任务/结果/记忆结构化数据/电商数据;向量交给 Qdrant 故无需换 PG |
| 对象存储 | **MinIO** | S3 兼容,可无缝切阿里云 OSS;原图/结果图/生图/大 tool-result 外置 |
| 去背景 | **第三方 API** | 抽象客户端封装(remove.bg / 阿里云智能抠图),HTTP 调用,无本地模型 |
| 图片处理 | **TwelveMonkeys ImageIO + Thumbnailator** | 纯 Java 补齐格式读取 + 高质量缩放;无原生依赖 |
| WebP 写出 | **scrimage(libwebp 绑定)** | 弥补 Java WebP 写出短板 |
| 第三方韧性 | **Resilience4j** | 重试/熔断/限流/舱壁,落地 Tool Registry 失败策略 |
| 实时推送 | **SSE + WebSocket(STOMP)** | SSE 做 LLM token 流式;WebSocket 做任务进度/完成通知;轮询兜底 |
| 可观测/Eval IF | **MySQL trace 表 + Micrometer/Actuator** | trace 表供 Rubrics 当数据查询+回放;Micrometer 补运维指标 |
| Token 计数 | **jtokkit** | Context Manager 预算裁剪 |
| 定时任务 | **Spring @Scheduled (+ ShedLock 多节点)** | 启动断点恢复扫描、延迟清理 |
| 文档解析 | **Apache POI + commons-csv** | 文案文档 + 电商数据导入 |
| 容器编排 | **Docker Compose** | 统一拉起 MySQL/Redis/RabbitMQ/Qdrant/MinIO |
| 前端 | Vue 3 + Vite + Element Plus | 对话/文件/结果/Rubrics 评分展示 |

> 模型具体型号（文本 LLM、Qwen-VL、生图模型、embedding 模型）由配置承载，不在本文锁定——抽象层定了即可随时替换。

---

## 五、Harness 横切层

Harness 是贯穿运行全生命周期的横切 `services` 层，由六个扩展点组成。它们靠注册/注入接入 Agent 决策循环与 DAG 引擎，不按业务领域切分。设计参照成熟 Agent runtime（tool runtime / hooks / context / compaction / subagents / prompts）的边界划分。

### 5.1 Execution Loop（执行主循环）

驱动 Agent 的 think-act-observe 主循环，是手写的显式循环（**不依赖 Spring AI 的自动 function-calling**，以便把 Tool Registry 执行管线、Hooks、Context Manager、权限拦截插入每一步）。

- 设置while true循环，有工具调用时继续，没有工具调用时输出最后的文本并终止循环。
- 每轮记录 ContextSnapshot（system prompt + 消息 + 可见工具 schema），异常可回溯。
- 单轮流程：组装 Prompt → 调 LLM（带可见工具 schema）→ 解析工具调用 → 经 Tool Registry 执行管线执行 → 观察结果回填 → 判断是否继续或自然结束（无工具调用即 TurnStopped）。
- 循环内每个动作节点须经 Lifecycle Hooks + 权限层校验后方可执行。

### 5.2 Tool Registry（工具注册表）

**关键边界：系统有两套完全独立的「工具」，绝不合并。**

| | Agent 级动作（本 Registry 管理） | DAG 级像素工具（DAG 引擎内部） |
|---|---|---|
| 例 | `recall_memory`、`query_commerce_data`、`compile_dag`、`submit_dag`、`run_vision_subagent`、`run_imagegen_subagent` | `remove_bg`、`set_background`、`resize`、`compress`、`watermark`、`convert_format`、`generate_copy`、`compose_group` |
| 调用者 | Agent 决策循环 | DAG 执行引擎 |
| 校验 | 执行管线（schema→分类→权限→hook→handler→结果预算） | DAG 校验器（结构/白名单/参数 schema/无环） |
| 性质 | 非确定性决策动作 | 确定性处理单元 |

Agent **永不直接调像素工具**，只产出 DAG 并 `submit_dag`。

Tool Registry 执行管线（每次工具调用串行 preflight）：
```
registry.get(name) → schema 校验 → 工具级 validate → 调用分类(classify)
→ 权限评估(deny-first) → PreToolUse hook(可改写输入,改写后重新校验)
→ handler 执行(Resilience4j 包裹) → 结果预算(超限外置 MinIO+返回引用)
→ PostToolUse/ToolError hook → 记录 trace
```
- **结果预算**：工具结果超阈值（默认 50KB）写 MinIO，模型只见引用+预览，防上下文膨胀。
- **失败策略**：handler 异常转结构化 tool error 回填模型，不让主循环崩溃；按 Tool Registry 注册的策略（重试/跳过/终止）处理。

### 5.3 Context Manager（上下文管理）

维护每轮对话的上下文质量与体积，超窗口时按优先级裁剪。

- 消息 append-only 存储（MySQL `message` 表 + 大结果外置 MinIO）。
- 投影滑窗：保留 tool call/result 配对，丢弃孤立 tool_result。
- Token 估算用 jtokkit；超阈值触发裁剪。
- **裁剪优先级（保留顺序）**：用户最新指令 > 当前任务状态 > Lifecycle Hooks 强规则 > 关键记忆片段 > 历史对话。
- 旧 tool result 可降级为占位符（microcompact）。

### 5.4 State Store（状态存储）

持久化 Agent 运行状态与任务执行状态，支撑断点恢复。

| 存储 | 内容 |
|---|---|
| MySQL | 任务进度、已完成节点/支路（`process_result` 天然 checkpoint）、当前 DAG 结构、会话 transcript |
| Redis | 任务运行态、支路中间产物**引用**（节点完成标记 + MinIO key，非原始字节）、进度计数器、取消标志位、分布式锁、第三方信号量 |
| MinIO | 中间产物文件本体（字节落此）、外置大结果 |

> 中间产物存储边界：Redis 只放轻量引用（"某节点已完成，产物在 MinIO key X"），**原始图片/大字节一律落 MinIO**，避免 Redis 内存膨胀与序列化压力；真正的持久断点是 MySQL `process_result`，Redis 缓存只是同一次运行内避免重算的优化，可丢失可重建。

断点恢复策略见 [9.4](#94-断点恢复与失败隔离)。State Store 同时为任务调度提供状态查询接口（轮询/WebSocket 推送的数据源）。

### 5.5 Lifecycle Hooks（生命周期钩子）

在关键时机插入扩展点。**Hook 可阻断/改写/补充 metadata，但其 allow 不能覆盖权限层 deny——安全边界在权限层。**

核心事件：`UserPromptSubmit`、`PreToolUse`、`PostToolUse`、`ToolError`、`AssistantMessageCompleted`、`TurnStopped`、`TaskCreated`、`TaskCompleted`、`PreCompact`/`PostCompact`。

requirement 的强规则拦截落点：

| 拦截点 | 实现层 | 机制 |
|---|---|---|
| 生成建议后必须等用户确认，禁止自动执行 | **权限层硬 deny** | `submit_dag` 要求携带用户确认令牌,Agent 无法自签发 |
| 大批量重处理二次确认（超阈值张数） | **权限层硬 deny** | 超阈值的 submit 要求二次确认令牌 |
| 生图/重跑前用户确认 | **权限层硬 deny** | 同上 |
| DAG 参数异常检测 | PreToolUse hook + DAG 校验器 | 拦截非法参数 |
| Rubrics 评分低于阈值推送预警 | PostTaskExecution（离线阶段触发通知） | 观察 + 通知 |

### 5.6 Evaluation Interface（评估接口）

统一可观测接口，暴露每轮循环的输入输出、工具调用记录、记忆召回内容、上下文裁剪日志，写入 MySQL trace 表（JSON 列，可 SQL 查询、可回放）。

- 为 Rubrics 离线评估提供完整数据来源。
- 支持任务回放与问题追溯。
- Micrometer/Actuator 补充运维指标（QPS、延迟、错误率）。

---

## 六、Agent 决策层

### 6.1 主循环行为

用户每次发消息 = 一个 Agent 决策回合（请求内同步执行，秒级 LLM 调用）。典型一轮：

```
UserPromptSubmit
  → 召回用户偏好(置于 Prompt 静态前缀) + 召回相关记忆/分析结论
  → query_commerce_data(关联本批 SKU 电商数据)
  → [可选] run_vision_subagent(查看具体图片辅助判断)
  → 生成带数据支撑的处理建议(确定性 DAG 方案 和/或 生成式重绘方案)
  → compile_dag(产出 DAG 预览) / 撰写生图提示词
  → 返回建议 + needConfirm=true(SSE 流式)
  ── 用户确认/修改 ──
  → submit_dag(携确认令牌) → 异步任务 / run_imagegen_subagent(携确认令牌)
```

建议必须附数据支撑说明（如「该 SKU 近 30 天点击率低于类目均值 40%，历史数据显示白底处理后点击率平均提升 18%，建议执行去背景白底方案」）。

### 6.2 动态 Prompt 组装 + section 缓存

Prompt 缓存 = 动态组装 + section 级缓存（**不依赖服务商 context caching**）。

- 静态前缀（identity、行为规则、工具列表、用户偏好画像）各自带 fingerprint 缓存，按 `(section_key, fingerprint)` 复用渲染结果。
- 动态部分（用户当次指令、当前 SKU 数据、召回记忆片段）每轮重组。
- 用户偏好画像更新时，只失效对应 section 的缓存。
- 可见工具视图单一来源：Prompt 中工具说明与 LLM 可见 tool schema 来自同一可见集合。

### 6.3 HITL 确认

所有触发真实副作用的动作（执行 DAG、生图、重跑）走权限层硬拦截，需用户确认令牌（见 [5.5](#55-lifecycle-hooks生命周期钩子)）。Agent 无法自主拍板。

分组聚合的张数预期不符时同样触发 HITL：用户指定了组内张数（如「三张拼接」）但实际成员数不符，`submit_dag` 前即拦截，向用户确认「按实际张数处理」还是「漏传图片」，由用户裁决（见 [9.5](#95-分组聚合三视图--合成图)）。

---

## 七、RAG 记忆层

三类记忆性质不同，分开存储、统一检索接口，按需召回。**只有「分析结论记忆」真正需要向量库。**

| 记忆类型 | 存储 | 召回方式 | 内容 |
|---|---|---|---|
| **用户偏好画像** | MySQL | 每次对话开始全量召回,置于 Prompt 静态前缀 | 偏好底色、常用水印位置、文案风格、历史确认/拒绝行为 |
| **SKU 处理历史** | MySQL | 按 SKU_ID 精确召回 | 处理时间、参数、前后电商数据对比、Rubrics 评分变化 |
| **分析结论记忆** | **Qdrant**(向量) | 语义相似度召回 | 类目级洞察(如「夏季连衣裙白底图转化率高于场景图 30%」),标注来源与置信度 |

- 统一检索接口对上层屏蔽底层差异（`recall_memory` 工具按类型路由到 MySQL 或 Qdrant）。
- 嵌入由 Spring AI EmbeddingModel 生成后写入 Qdrant（embedding 模型由配置定）。
- Rubrics 评估结果写回各记忆，与对应 SKU 处理历史 / 分析结论绑定。

---

## 八、电商数据接入层

系统不拥有电商平台数据，通过标准接口消费外部数据。

- 当前阶段：导入本地数据集（CSV/Excel，POI + commons-csv 解析）。
- 预留外部店铺数据 API 接入接口（适配器模式，后续对接真实平台）。
- 数据字段：`SKU_ID`、曝光量、点击率、加购率、购买率、时间周期。
- `query_commerce_data` 工具按 SKU 关联数据，供 Agent 生成数据支撑说明。

---

## 九、DAG 确定性执行引擎

### 9.1 编译、校验、分支展开

- **编译**：Agent 的 `compile_dag` 动作调 LLM 将自然语言+上下文解析为 DAG JSON（nodes/edges），节点 `tool` 仅限白名单。缺必填参数则逐项列出向用户追问（LLM 判断，不自动猜测）。
- **校验**：`submit_dag` 时服务端独立严格校验（不信任前端回传）：结构 → 节点数(1–50) → 工具白名单 → 参数 schema → 边引用 → 无环。任一失败不创建任务。
- **分支展开**：DAG 支持单节点后分出多条并行路径（如去背景后同出 800×800 主图与 200×200 缩略图）。展开为 source→sink 支路，每条分配唯一 branchId，独立执行、结果分别持久化。
- **文案分支解耦**：`generate_copy` 建模为独立分支，不串接像素流水线末端，以 SKU 的 `asset_copy`（product_name/keywords/description）为上下文。

### 9.2 异步任务分发（RabbitMQ）

```
submit_dag(确认令牌通过)
  → 创建 process_task(status=待执行) + 序列化已校验 DAG 入库
  → 发布任务消息到 RabbitMQ(任务级粒度,一条消息=一个任务)
  → 立即返回 taskId(前台可继续操作)
  → worker 消费 → 任务内 fan-out [图片×支路] 到本地线程池
  → 进度写 Redis 计数器 → WebSocket 推送 "已处理 320/800"
  → 完成 → 推送通知 + 提供预览/下载入口
```
- 任务级消息保持 MQ 消息量低、断点恢复简单。
- DLQ + Resilience4j 重试处理消费失败。

### 9.3 并发保障（Redis + Redisson）

- Redisson 分布式锁防同一任务/工作单元被重复消费。
- 任务内并发线程数 ≤ 配置上限（默认 8）。
- 第三方 API（抠图/生图/VLLM）并发用 Redisson 信号量 + Resilience4j 限流。

### 9.4 断点恢复与失败隔离

- **支路幂等**：恢复时整条支路重跑（非节点级续传），实现简单（支路本就是独立单元）。
- **断点缓存**：支路中间产物的**轻量引用**（节点完成标记 + MinIO key）缓存进 Redis（原始字节落 MinIO，见 [13.3](#133-redis键约定)）；**支路成功后立即删除该支路全部缓存引用**；失败可重试。持久断点以 MySQL `process_result` 为准，Redis 缓存仅为同次运行内避免重算的优化。
- **恢复触发**：启动时 `@Scheduled` 扫描 `status=执行中` 的任务重新入队；worker 靠已落库的成功 `process_result` 跳过已完成单元 + Redis 缓存避免重算。
- **中断**：Redis 取消标志位，在工作单元之间检查，优雅停并持久化断点。
- **失败隔离**：单「图片×支路」工作单元失败 → 该 `process_result` 标记失败(status=2)、记 error_msg(≤1000字)、不保留半成品 output_path；同图其余支路、批次其余图片继续。
- **任务终态**：至少一条成功 → 完成(2)；全失败 → 失败(3)。

### 9.5 分组聚合（三视图 → 合成图）

部分商品的多张图片（如正/侧/背三视图）需作为整体加工、合成为单张图。引入与支路正交的「组（group）」维度：普通处理仍是 `[图片 × 支路]`，分组聚合是 `[组 × 支路]`。**不含聚合节点的 DAG 行为与原先完全一致。**

- **分组来源（文件名驱动，落 `module/file`）**：约定文件名（去扩展名）按 `_` 分段——
  - 三段 `组id_商品id_图id` → 分组图：`group_key=组id`、`sku_id=商品id`、`view_id=图id`；同 `(package_id, group_key)` 的图片构成一组。
  - 两段 `组id_图id` → 普通单图：`group_key` 置空，首段作 `sku_id`。
  - 其余/无下划线 → 回退现有 `SkuExtractor`，`group_key` 置空。
  分组完全由文件名编号决定，不做视觉识别。

- **聚合工具**：DAG 像素工具白名单新增 `compose_group`（输入基数 N≠1，区别于其余逐图工具）。参数：`layout`（horizontal/vertical/grid 枚举，必填）、可选 `order`（按 view_id 排序）、可选 `expected_count`（正整数，仅供 HITL 张数校验）、可选 `gap`/`background`。一组 N 张 → 输出 1 张。

- **组支路**：`BranchExpander` 识别含 `compose_group` 的支路为「组支路」。聚合节点之前的逐图节点（remove_bg/resize…）对组内每张成员各自施加；聚合节点 fan-in 合成；聚合节点之后的节点作用于合成后的单图。`DagValidator` 增校验：组支路内 `compose_group` 唯一、其前驱均为逐图工具。

- **张数预期与 HITL（确认时校验）**：
  - 用户说「整组全部拼接」→ `compose_group` 不带 `expected_count`，以组内实际成员为准，无张数校验。
  - 用户说「这组三张拼接」→ Agent 编译时置 `expected_count=3`；`submit_dag` 前按组比对实际成员数，不一致则**不执行、走 HITL 二次确认**（提示「该组实际仅 2 张：确认按 2 张处理，还是漏传图片？」），由用户裁决，Agent 不自行拍板。

- **断点缓存（边界①）**：组内各成员预处理中间产物**引用**（同样只存引用，字节落 MinIO）缓存键加组维度 `group:cache:{taskId}:{groupKey}:{branchId}:{imageId}`，**推迟到整组聚合成功后统一删除**；非组支路仍走 `branch:cache:*` 即删旧逻辑。

- **失败隔离与缺图（边界②，运行期）**：组支路工作单元内任一预期成员读取/解码/处理失败 → **整条组支路 `status=2`**，`error_msg` 标注缺失视图，不留半成品 `output_path`；其他组、其他普通支路、批次其余图片继续。

- **恢复**：组支路整体幂等重算（与支路幂等一致）；组结果 `process_result` 落库即视为该组完成，恢复时整组跳过。

> 「各自处理但保持一致」（N→N，统一画布/底色等）：参数为静态固定值时，逐图支路用同一份 DAG 参数处理即天然一致，沿用原设计无需改动；仅当共享参数需由组内联合推导（如取组内最大尺寸再回填各图）时，才需额外的组参数推导步骤，本期不做。

---

## 十、子 Agent 设计

主循环中的子 Agent 是 Agent 的**tool能力**（非测试）。child runtime 独立装配、工具裁剪、结果只回最终 ToolExecutionResult。三类：

### 10.1 视觉理解子 Agent（VLLM）

- 主 Agent 按需调用：分析商品数据时查看其图片构图/卖点呈现、理解具体图片内容、判断图片是否符合描述。
- 输入：图片（MinIO 引用）+ 问题；输出：结构化视觉描述/评估。
- 走 Spring AI 多模态（Qwen-VL 类）。
- 抽样/单图按需，量小则同步跑在 Agent turn 内（量大再降级后台子任务）。

### 10.2 生图子 Agent（生成式重绘路径）

- 主 Agent 综合数据分析 + 召回记忆 + 用户要求，撰写生图提示词。
- 把源图 + 提示词发给生图 LLM（通义万相类），重新生成新图。
- 与确定性 DAG 并列的第二条产图路径；产出同样落 MinIO + `process_result`。
- 触发执行需用户确认令牌（HITL 硬拦截）。

### 10.3 通用/规划子 Agent（可选）

- 复杂任务的子规划、只读探索等，按需扩展，保持 subagent 接入边界统一（一个 `run_subagent` 动作 + 类型参数），不在主循环加特例分支。

---

## 十一、Rubrics 评估（离线阶段）

**完全独立于主循环的离线环节**，消费 Evaluation Interface 的 trace + 本地数据集。

| 评估维度 | 评估者 | 说明 |
|---|---|---|
| 图片质量 | VLLM | 去背景干净度、底色均匀性、水印位置、整体视觉一致性,满分 100,低于阈值预警 |
| 文案质量 | LLM | 卖点突出、品牌调性、关键词覆盖率(结合 keywords 字段)、流畅度 |
| Agent 决策质量 | 综合 | 用户行为(采纳率/修改频率/追问轮次)+ 后续电商数据变化 |

- 评估结果写回 RAG 记忆层，与 SKU 处理历史 / 分析结论绑定。
- 评分通过 Evaluation Interface 暴露，支持任务回放与问题追溯。
- **本阶段**：用本地数据集驱动；决策质量评估中依赖滞后电商反馈的部分**本期不做**，具体评分细则待数据集确定后单独设计。

---

## 十二、业务模块划分

```
spring-boot 主工程
├── common/                     错误处理 / 分页 / 通用
├── contracts/                  共享契约（确认令牌等纯接口/DTO）
├── harness/                    横切 services 层（不依赖业务领域）
│   ├── loop/                   Execution Loop
│   ├── tools/                  Tool Registry + 执行管线
│   ├── context/                Context Manager（消息存储/投影/裁剪）
│   ├── state/                  State Store（MySQL+Redis+MinIO 状态聚合）
│   ├── hooks/                  Lifecycle Hooks
│   ├── permission/             权限层（硬 deny 安全边界）
│   ├── session/                session会话transcript
│   └── eval/                   Evaluation 评估
├── agent/                      Agent 决策层（主循环编排、Prompt 组装、子Agent runner）
├── module/
│   ├── file/                   素材管理（上传/解压/SKU 绑定/结果管理/评分展示）
│   ├── conversation/           对话与消息（SSE 流式、附件关联）
│   ├── dag/                    DAG 编译/校验/分支展开/执行引擎
│   ├── task/                   任务调度（RabbitMQ 消费、进度、断点恢复、下载）
│   ├── memory/                 RAG 记忆（偏好/SKU历史/分析结论；Qdrant+MySQL）
│   ├── commerce/               电商数据接入（本地导入 + 预留 API）
│   ├── vision/                 视觉理解子 Agent
│   ├── imagegen/               生图子 Agent
│   └── rubrics/                Rubrics 离线评估
├── infra/
│   ├── ai/                     Spring AI 封装（文本/多模态/生图/嵌入）
│   ├── image/                  图片编解码 + 像素工具执行器
│   ├── storage/                MinIO 对象存储抽象
│   ├── mq/                     RabbitMQ 封装
│   ├── cache/                  Redis/Redisson 封装
│   ├── vector/                 Qdrant 封装
│   └── thirdparty/             抠图 API 等第三方客户端 + Resilience4j
```

> `contracts` 是**零依赖叶子**（仅依赖 JDK，不依赖 `common` 或任何模块），只放跨模块契约，不包含 Spring Bean、Redis/DB 实现或业务流程。`permission` 与 `infra/cache` 通过它共享确认令牌相关类型（SPI 倒置），避免基础设施反向依赖业务模块。详见 `module/contracts.md`。

```mermaid
graph TD
    common[common]
    contracts[contracts]
    permission[permission]
    cache[infra/cache]

    contracts --> permission
    contracts --> cache
```

---

## 十三、数据模型

### 13.1 MySQL（关系数据）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `asset_package` | id, name, minio_zip_key, doc_key, image_count, status, created_at | 素材包 |
| `asset_image` | id, package_id, sku_id, group_key, view_id, minio_key, original_path, created_at | 单图,软关联;`group_key` 非空表示分组成员 |
| `asset_copy` | id, package_id, sku_id, product_name, keywords, description | 文案条目 |
| `conversation` | id, title, created_at, updated_at | 对话 |
| `message` | id, conversation_id, role, content, attached_package_id, task_id, created_at | 消息(transcript) |
| `process_task` | id, conversation_id, package_id, dag_json, status, total_count, done_count, created_at, finished_at | 任务 |
| `process_result` | id, task_id, image_id, sku_id, group_key, branch_id, source_path(确定性/生成式), output_minio_key, generated_copy, status, error_msg | 结果(支路级,天然 checkpoint);组支路结果 `group_key` 非空、`image_id` 置空 |
| `process_result_member` | id, result_id, image_id, view_id | 组支路结果的成员明细(N 张源图),普通支路无此记录 |
| `user_preference` | id, key, value, updated_at | 用户偏好画像 |
| `sku_history` | id, sku_id, task_id, params_json, metrics_before, metrics_after, rubrics_score, created_at | SKU 处理历史 |
| `commerce_data` | id, sku_id, impressions, ctr, add_cart_rate, purchase_rate, period, created_at | 电商数据 |
| `rubrics_score` | id, result_id, image_score, copy_score, decision_score, alert, created_at | 评分 |
| `agent_trace` | id, conversation_id, turn_no, input_json, tool_calls_json, recall_json, prune_log_json, created_at | Evaluation IF |

### 13.2 Qdrant（向量）

- collection `analysis_insight`：向量 = 结论文本 embedding；payload = {结论文本, 类目, 来源, 置信度, 关联SKU, created_at}。

### 13.3 Redis（键约定）

| 键 | 用途 |
|---|---|
| `lock:task:{taskId}` / `lock:unit:{...}` | Redisson 分布式锁 |
| `progress:task:{taskId}` | 进度计数(原子 incr) |
| `cancel:task:{taskId}` | 取消标志位 |
| `branch:cache:{taskId}:{imageId}:{branchId}` | 支路中间产物**引用**(节点完成标记+MinIO key,非字节;成功即删) |
| `group:cache:{taskId}:{groupKey}:{branchId}:{imageId}` | 组支路成员中间产物**引用**(非字节;整组聚合成功后统一删) |
| `sem:thirdparty:{api}` | 第三方 API 并发信号量 |

> `submit_dag` 与 `run_imagegen_subagent` 的确认令牌类型、载荷模型与存储契约放在独立的 `contracts` Maven 模块；`permission` 负责签发与校验，`infra/cache` 负责存取实现，`design.md` 仅在这里定义 Redis 运行时键，不承载令牌契约。

### 13.4 MinIO（对象）

```
packages/{packageId}/source.zip
packages/{packageId}/images/{relPath}
packages/{packageId}/doc/{fileName}
results/{taskId}/{skuId}_{imageId}_{branchId}.{ext}
tool-results/{id}.txt              # 外置的大 tool result
```

---

## 十四、异步执行时序

```
用户确认执行
   │
   ├─ submit_dag(确认令牌) ── 权限层硬校验通过
   │
   ├─ DagValidator 服务端独立校验 ── 失败则不创建任务
   │
   ├─ 创建 process_task(待执行) + DAG 入库
   │
   ├─ 发布任务消息 → RabbitMQ ──────────► 立即返回 taskId(前台不阻塞)
   │
   ▼ (worker 异步)
 消费任务消息
   ├─ Redisson 锁 lock:task:{id}
   ├─ 加载图片 + 分支展开
   ├─ 线程池并发 [图片×支路]:
   │     ├─ 跳过已成功(process_result 检查)
   │     ├─ 确定性支路: readMinIO→像素工具链→encode→writeMinIO
   │     │              中间态缓存 Redis,成功删缓存
   │     ├─ 文案支路: LLM 生成→写 generated_copy
   │     ├─ 失败 → FailureIsolator 隔离,继续其余
   │     ├─ Redis 进度 incr → WebSocket 推送
   │     └─ 工作单元间检查 cancel 标志
   ├─ 写任务终态(2/3) + finished_at + done_count
   └─ 释放锁 → 推送完成通知(预览/下载入口)

崩溃/重启恢复:
   @Scheduled 扫 status=执行中 → 重新入队 → worker 靠 process_result + Redis 跳过已完成
```

---

## 十五、技术风险

| 风险 | 等级 | 说明与缓解 |
|---|---|---|
| Agent 循环非确定性削弱可测试性 | 高 | 安全规则一律权限层硬拦截而非 Prompt;Evaluation IF 全程 trace 支持回放;确定性底座单独属性测试 |
| 断点恢复正确性 | 高 | 支路幂等重算(非节点续传)+ process_result 作 checkpoint + Redis 缓存成功即删;恢复时跳过已成功单元 |
| Context Manager 裁剪正确性 | 中 | 按 requirement 优先级裁剪;裁剪日志进 Evaluation IF 便于调试;保 tool call/result 配对 |
| 第三方 API（抠图/生图/VLLM）抖动与成本 | 中 | Resilience4j 重试/熔断 + Redisson 信号量限流;失败隔离不拖垮整批 |
| 多模态/生图延迟 | 中 | 视觉子 Agent 抽样量小则同步,量大降级后台;生图为按需触发 |
| WebP 写出兼容 | 中 | scrimage(libwebp) 专门补;无写出器格式按"无法编码"记入跳过清单 |
| Spring AI Alibaba 对所需模型/能力覆盖度 | 中 | 抽象层隔离,必要时为个别能力直连 SDK;先做能力 spike 验证 |
| 多存储一致性（MySQL/Redis/MinIO/Qdrant） | 中 | 以 MySQL 为事实源;Redis/MinIO 可重建;Qdrant 写入失败可异步补偿 |

---

## 十六、暂不考虑

视频处理、用户权限与多账号管理、计费与限流（业务层面）、外部电商平台 API 的真实对接（本期用本地数据集替代）、参数不明确时的自动猜测、Rubrics 决策质量中依赖滞后电商反馈的部分、Rubrics 具体评分细则（待数据集确定后单独设计）。
