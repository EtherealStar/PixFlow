# agent —— Agent 决策层装配（Wave 5 决策层编排）

> 本文是 PixFlow 完整重写阶段 `agent` 模块的设计文档，对应 `design.md` 第六章「Agent 决策层」、第十章「子 Agent 设计」、`module-dependency-dag-plan.md` 的 **Wave 5 Agent 决策层**。
> 范围：手写 think-act-observe 主循环之上的**业务装配层**——动态 Prompt 组装与 section 缓存、Skill 工具化与渐进式披露、已有记忆的只读召回、Session Memory 上下文压缩、Subagent Runner、Plan 模式控制、Agent Orchestrator 入口、素材引用注入、Proposal 事件接出，以及与 tools / context / session / hooks / eval / permission / state / loop / memory / commerce / dag / imagegen / vision / conversation / file 的接缝契约。当前产品不把对话结果写回用户偏好、SKU 历史或分析结论。
> 思路参考 `docs/references/prompt-architecture.md`、`subagent-architecture.md`、`compaction-architecture.md`、`context-architecture.md`、`error-handling-architecture.md`、`core-runtime-architecture.md`（Python/OneCode / TypeScript 生产参考），但**仅借鉴「薄装配 + section cache + subagent fork + Session Memory 累积 + 工具化 skill 披露」的设计理念，存储模型、并发模型、类型契约、压缩策略分层全部以 Java 17 + Spring Boot 3 重新设计**。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、与参考实现的本质差异](#二与参考实现的本质差异)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、Prompt 动态组装 + section 缓存](#四prompt-动态组装--section-缓存)
- [五、Skill 机制（工具化接入 + 渐进式披露）](#五skill-机制工具化接入--渐进式披露)
- [六、自动记忆召回与注入](#六自动记忆召回与注入)
- [七、Session Memory 累积提取](#七session-memory-累积提取)
- [八、Subagent Runner（agent 工具 / 摘要 / Session Memory 三处复用）](#八subagent-runneragent-工具--摘要--session-memory-三处复用)
- [九、`SummarizationPort` 实现（destructive compaction 备份路径）](#九summarizationport-实现destructive-compaction-备份路径)
- [十、压缩策略分层（cheap pipeline / Session Memory / auto compact）](#十压缩策略分层cheap-pipeline--session-memory--auto-compact)
- [十一、Plan 模式控制器](#十一plan-模式控制器)
- [十二、Agent Orchestrator 入口](#十二agent-orchestrator-入口)
- [十三、与各模块的接缝契约](#十三与各模块的接缝契约)
- [十四、配置项](#十四配置项)
- [十五、可观测与指标](#十五可观测与指标)
- [十六、测试策略](#十六测试策略)
- [十七、暂不考虑](#十七暂不考虑)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`agent` 模块在依赖 DAG 中处于 `loop/tools/context/hooks/eval/session/state/permission + 所有 module/*` → `agent` 的位置（Wave 5）。它是**把可独立测试的部件在运行时接成「一个 Agent 回合」的薄装配层**——不发明新的基础设施，不持有自己的 LLM 客户端，不写 message 表。

`agent` 专属设计原则：

1. **装配而非实现**。`agent` 不持有 `ModelClient`（归 `infra/ai`），不持有 MySQL `message` 表的写权（归 `harness/session`），不实现 `ToolDescriptor` 的核心 7 个领域动作（归 `module/commerce` / `module/dag` / `module/imagegen`），不持久化任务态（归 `module/task`）。它的全部价值是**在回合执行前决定「每轮要喂给 loop 什么」**——`systemPrompt` 字符串与可见 `toolSchemas`。

2. **provider-neutral + loop-neutral**。`agent` 不引用 provider 具体适配器、不持有 `ModelClient` 实例（`loop` 注入）、不调用 `AgentLoop` 的具体子类。它只通过 `loop` 暴露的 SPI（`PromptProvider` / `ToolSchemaProvider` / `continueStream` / `SummarizationPort`）倒置接入。**loop 可独立测试**（不依赖 agent 装配），agent 也可独立测试（mock loop）。

3. **压缩策略以 Session Memory 为主、auto compact 为备**。`design.md §5.3` 的 cheap pipeline（结果预算 + 投影滑窗 + microcompact）每轮必跑，是无成本的基础治理。Session Memory 累积提取按阈值触发，是长会话的**主要渐进压缩手段**。destructive compaction / auto compact 只在「cheap + session memory 都不够时」才作为**应急备份**触发。auto compact 不是"主路径"，是"最后兜底"。

4. **Session Memory 阈值不入 transcript**。`last_summarized_seq` 是事实（消息 seq，单调不可变），**随 message 表持久化**；但「当前累积 token」「当前累积回合数」等阈值状态**不持久化**。退出/重入会话时，阈值**重新计算**——从 `last_summarized_seq` 之后开始算「已累积多少」「是否触发提取」。这保证已覆盖的消息不被重复处理，也避免阈值状态污染事实源。

5. **Skill 是工具，不是 prompt 段落**。Skill **不预先把 body 塞进 system prompt**；每个 skill 动态注册成一个独立 tool descriptor（`skill__<name>` 命名空间化），模型**按需调用**才看到 body。Schema 暴露 `description`（渐进披露第一层），`prompt` 进 `available_tools` section 引导（第二层），handler 调用才返回 body（第三层）。Skill 目录与正文是数据，**业务方写 SKILL.md 明确 skill 能做什么**。

6. **压缩分层正交不互斥**。cheap pipeline / Session Memory 提取 / auto compact 三者在回合内的触发时机不同，**互相补充不替代**。具体关系见 [第十章](#十压缩策略分层cheap-pipeline--session-memory--auto-compact)。

7. **trace 责任上移但不自存**。`agent` 不写 `agent_trace`、不直接调 eval。loop 持有的 `TurnTrace` 句柄由 loop 转投；agent 在每轮把「本轮的 prompt 摘要、skill 加载、记忆召回、session memory 更新」经 `RuntimeState.metadata` 暴露给 loop 投 trace。

8. **零独立基础设施**。`agent` 不引入新的持久化通道（session memory 走 MySQL + Redis，**复用 `infra/storage` 与 `infra/cache` 的现有抽象**）；不引入新的 LLM 客户端（复用 `infra/ai` 的 `ModelClient`）；不引入新的限流/熔断（复用 `infra/thirdparty` 的 Resilience4j 包装）。**它只把所有已有模块的能力接起来**。

9. **素材引用只认 canonical `referenceKey`**。用户消息中的每个 mention 以 `referenceKey + displayPathSnapshot` 注入上下文；Agent 把同一个 key 原样传给资产检查、DAG 与 IMAGEGEN 工具。Agent 不接收对象存储路径，也不使用并行的 `packageId/imageIds/source_image_ids` 工具契约。

10. **Proposal 是已校验、逐项授权的动作**。提交工具在 Proposal 对用户可见前完成 schema、素材、权限和执行前校验；技术错误作为 tool error 回到同一回合供 Agent 修正。一个回合可以发布多个 Proposal，每个独立确认或拒绝；一个 IMAGEGEN Proposal 只对应一张源图和一张结果，单回合最多 20 个。

11. **当前不做业务记忆写回**。`MemoryService.prepareContext(...)` 仅可读取已有记忆；不得从 assistant 消息或任务结果自动提取并写入用户偏好、SKU 历史或分析结论。Session Memory 仅服务同一会话的上下文压缩，不升级为跨会话业务记忆。

---

## 二、与参考实现的本质差异

参考实现 OneCode（Python）与 Claude Code（TypeScript）有两个层级：`core/loop.py`（薄主循环）+ `QueryEngine.ts`（超繁装配器，含 fallback model / context collapse / skill prefetch / task budget / memory prefetch / MCP 等）。PixFlow 的 `harness/loop` 已对齐 `core/loop.py` 的薄编排定位，**`agent` 模块对应的是 `QueryEngine.ts` 的子集**——主动不引入其绝大多数高级特性。

| 维度 | OneCode / Claude Code（参考） | PixFlow（agent 模块） |
|---|---|---|
| 角色定位 | `QueryEngine` 是单例装配器，持有 tools / mcp / agents / memory / system prompt / state | `AgentOrchestrator` per-request 装配，依赖全部通过构造参数注入 |
| Prompt 组装 | `DynamicPromptAssembler` + `PromptSectionCache` 完整复刻 | **同**——全量借鉴 section cache 机制（[第四章](#四prompt-动态组装--section-缓存)） |
| Skill 机制 | 文本提及 + agent 主动 + attachment 投影 | **工具化接入**（每个 skill = 一个 `skill__<name>` 工具），schema 渐进披露（[第五章](#五skill-机制工具化接入--渐进式披露)） |
| Session memory | 单文件 `.onecode/sessions/<id>/session-memory.md` + 提取子 Agent + 写文件 | **MySQL `session_memory` 表 + Redis 缓存**（[第七章](#七session-memory-累积提取)）；**阈值不入 transcript** |
| 记忆召回 | `startRelevantMemoryPrefetch`（回合入口预取）+ 后台 settled 后消费 | **每轮 buildForModel 前同步调用 `MemoryService.prepareContext(...)`** |
| Subagent 接入 | `services/subagents/` 独立包 + `tools/agent/` 包装 | **`SubagentRunner` 单一类** + **三处复用**（`agent` 工具 / `SummarizationPort` / `SessionMemoryExtractionService`） |
| Fork 机制 | `subagent_type is None` 触发 fork | **本期不引入 fork 模式**——child runtime 一律 clean；公开 `agent` 工具只接受 `type=explore`，摘要与 Session Memory 提取使用内部专用类型 |
| Compaction 摘要 | loop 内联调 subagent_runner fork | `SummarizationPort` SPI 倒置给 agent 层，context 调用，**agent 提供实现**（[第九章](#九summarizationport-实现destructive-compaction-备份路径)） |
| Memory 类型 | session memory（markdown）+ long-term memory（目录 + 文件） | **三类分离**：session memory（MySQL，per 会话）+ 偏好画像（MySQL，全量）+ 分析结论（MySQL + Qdrant，类目级） |
| Context collapse / snip / cache edit | query.ts 的全特性 | **不引入**——只用 context 的 cheap pipeline + Session Memory + auto compact 三层 |
| Fallback model / task budget | query.ts 内置 | **不引入**（loop.md §18 明确） |
| Re-entrancy（重入会话） | session resume 从 JSONL 全量读 | **每轮 rehydrate**（context.md §七）+ Session Memory 阈值**重算**（[原则 4](#一文档定位与设计原则)） |

**可借鉴的结构骨架**：`DynamicPromptAssembler` + `PromptSectionCache` 的 section 缓存机制；subagent 的 child runtime 独立 MessageStore + tool 裁剪 + 继承父 systemPrompt；session memory 累积 + 提取子 Agent；fork child 跑 LLM 摘要。

**必须重写的内核**：
- Session memory 文件 → MySQL 表 + Redis 缓存；阈值状态从入库改为运行时计算
- 记忆召回从 prefetch 改为同步准备 `MemoryContext`（无 prefetch settledAt 复杂状态；具体 RRF/混合召回归 module-memory）
- Skill 从 prompt section 暴露改为 tool descriptor 暴露（渐进披露 + 命名空间化）
- Subagent fork 模式本期不做（`subagent_type is None` 不支持），child runtime 全部 clean 装配
- 压缩策略主次关系重排：Session Memory 为主，auto compact 为备

---

## 三、模块结构与依赖位置

源码包：`com.pixflow.agent`（与仓库根包 `com.pixflow` 对齐；物理位置见 `design.md` 第十二章顶层 `agent/`）。

```
agent/
├── AgentOrchestrator.java              # 入口：装配 loop + 所有依赖，驱动 stream()
├── prompt/
│   ├── DynamicPromptAssembler.java     # 渲染 + section 缓存
│   ├── PromptSection.java              # record (key/title/body/fingerprint/cacheable)
│   ├── PromptRuntimeContext.java       # 装载 state + visibleTools + MemoryContext + sessionMemory + activeSkills
│   ├── PromptSectionCache.java         # (key, fingerprint) 缓存
│   ├── PromptSummary.java              # 给 eval / trace 用的摘要视图（不暴露完整 prompt）
│   ├── PromptProvider.java             # SPI（loop 调用）：systemPrompt + toolSchemas
│   └── sections/
│       ├── IdentitySection.java        # 固定
│       ├── BehaviorRulesSection.java   # 固定
│       ├── RiskAndSafetySection.java   # 固定（含 Proposal 逐项授权规则）
│       ├── VerificationSection.java    # 固定
│       ├── PreferenceSection.java      # 用户偏好画像
│       ├── SessionMemorySection.java   # Session Memory 累积要点
│       ├── LongTermMemorySection.java  # SKU 历史 + 分析结论（消费 module-memory renderedText）
│       ├── ActiveSkillsSection.java    # 已加载 skill 列表（仅目录，body 由 skill__<name> 工具按需取）
│       ├── WorkspaceStateSection.java  # 当前素材包 / 会话上下文 / Plan 模式标记
│       └── ToolPromptSection.java      # 工具 prompt 汇总（与 schema 同源）
├── skill/
│   ├── SkillRepository.java            # 读 MySQL skill 表
│   ├── SkillLoader.java                # 启动期从 skills/<name>/SKILL.md 同步入表
│   ├── SkillToolRegistrar.java         # 把 skill 动态注册成 skill__<name> tool descriptor
│   └── SkillFrontmatter.java           # SKILL.md frontmatter 解析（name/description/when_to_use/version）
├── memory/
│   ├── MemoryRecallPlanner.java        # 薄适配：MemoryRecallSignal → MemoryContextRequest → MemoryService.prepareContext
│   └── MemoryRecallSignal.java         # Agent 回合入口传给 module-memory 的召回上下文字段
├── sessionmemory/
│   ├── SessionMemoryService.java       # 实现 context.SessionMemoryPort
│   ├── SessionMemoryRepository.java    # MySQL 读写（事实源）
│   ├── SessionMemoryCache.java         # Redis 缓存（可丢可重建）
│   ├── SessionMemoryExtractor.java     # fork child LLM 提取当回合要点
│   ├── SessionMemoryUpdater.java       # fallback 规则式提取
│   ├── SessionMemoryThreshold.java     # 运行时阈值状态（不持久化，纯计算）
│   └── SessionMemoryContent.java       # Markdown 结构：任务状态 / 已确认决策 / 用户偏好更新 / 待澄清问题
├── subagent/
│   ├── SubagentRunner.java             # 装配 child runtime + 调 continueStream
│   ├── SubagentRequest.java            # 含 type/prompt/referenceKeys/parentToolCallId/mode
│   ├── SubagentResult.java             # 含 finalText/usage/error/transition
│   ├── ChildRuntimeFactory.java        # 构造 child RuntimeState + MessageStore + ToolRegistry + ContextEngine
│   ├── ChildToolFilter.java            # 工具集裁剪规则
│   └── tools/
│       └── ExploreSubagentTool.java    # 实现 agent(type=explore) handler
├── summarization/
│   ├── ForkChildSummarizationPort.java # 实现 context.SummarizationPort（auto compact 备用路径）
│   ├── SummaryPromptBuilder.java       # 构造 SummarizationRequest → subagent prompt
│   └── PreCompactSummaryHook.java      # 注入 compact.summaryInstructions
├── planmode/
│   ├── PlanModeController.java         # 实现 plan / plan_exit 工具 handler；持有 PlanModeState
│   ├── PlanModeState.java              # 会话级标志（runtime.metadata 持有）
│   └── PlanModePrompter.java           # 计划文本呈现（把 plan_exit 结果投影为 assistant 文本块）
├── hooks/
│   ├── TurnStoppedSessionMemoryHook.java   # TURN_STOPPED → 触发同会话 Session Memory 压缩
│   └── PreCompactSummaryHook.java          # PRE_COMPACT → 注入 compact.summaryInstructions
├── config/
│   ├── AgentProperties.java            # 集中所有配置（section cache size / memory recall limits / session memory threshold）
│   └── AgentAutoConfiguration.java     # Spring bean 装配
└── error/
    └── AgentErrorCode.java             # enum implements ErrorCode（agent 自治码）
```

依赖方向：

```
agent ──► harness/{loop, tools, context, hooks, session, eval, state, permission}
agent ──► module/{memory, vision, imagegen, commerce, dag, conversation, file}
agent ──► infra/{ai, storage, vector, cache, image, mq, thirdparty}
agent ──► common
```

**关键倒置**：
- `agent` 实现 `context.SummarizationPort`（destructive compaction 备用路径）
- `agent` 实现 `context.SessionMemoryPort`（累积提取）
- `agent` 实现 `hooks.HookCallback`（多个业务 hook 接线点）
- `agent` 实现 `tools.ToolHandler`（`agent` 工具、`plan` / `plan_exit` 工具）
- `agent` 不直接实现 `common.ErrorRecorder`（由 `harness/eval` 实现），不实现 `context.TranscriptPort`（由 `harness/session` 实现）

**新增依赖边**（需同步回 `module-dependency-dag-plan.md`）：
- `agent → session`：agent 实现 `context.SessionMemoryPort` SPI 时，session 模型可见性需要（agent 不直接写 `message` 表，但 `SessionMemoryExtractor` 读 message 序列时需要与 session 协同）—— **agent 不直连 MySQL message 表**，经 `context.MessageStore.currentMessages()` 读
- `agent → hooks`：agent 提供 `HookCallback` 实现，只为 Session Memory 压缩与 compaction 订阅 `TURN_STOPPED` / `PRE_COMPACT`；不订阅 assistant 完成事件写回业务记忆
- `agent → tools`：agent 提供 `agent` / `plan` / `plan_exit` 三个工具的 handler + 描述符 bean
- `agent → memory`：agent 调用 `MemoryService.prepareContext(MemoryContextRequest)` 消费统一 `MemoryContext`

---

## 四、Prompt 动态组装 + section 缓存

`agent` 不发明新的 prompt 模板机制；**全量借鉴 `docs/references/prompt-architecture.md` 的 `DynamicPromptAssembler` + `PromptSectionCache` 设计**，结合 PixFlow 的实际 section 集合落地。

### 4.1 section 顺序与分类

固定 section 顺序如下（agent 装配期不动态调整；新 section 加在末尾而非中间插入）：

| # | key | 类别 | 来源 | cacheable | fingerprint 输入 |
|---|---|---|---|:---:|---|
| 1 | `identity` | 固定 | 资源文件 | 否 | section_version |
| 2 | `behavior_rules` | 固定 | 资源文件 | 是 | section_version |
| 3 | `engineering_practices` | 固定 | 资源文件 | 是 | section_version |
| 4 | `risk_and_safety` | 固定（含 Proposal 逐项授权规则） | 资源文件 | 是 | section_version |
| 5 | `verification_and_reporting` | 固定 | 资源文件 | 是 | section_version |
| 6 | `instruction_memory` | 动态 | `MemoryContext.user_preferences.renderedText` | 是 | section name + tokenEstimate + renderedText + trace |
| 7 | `session_memory` | 动态 | `SessionMemoryService`（见 [第七章](#七session-memory-累积提取)） | 是 | `lastSummarizedSeq + contentHash` |
| 8 | `long_term_memory` | 动态 | `MemoryContext.sku_history` + `MemoryContext.analysis_insights` | 是 | renderedText + recallTrace |
| 9 | `workspace_state` | 动态 | 当前消息素材引用 / 会话上下文 / Plan 模式 | 是 | `(referenceKeySetHash, turnCount, planMode)` |
| 10 | `available_skills` | 动态 | 全部已注册 skill 的 name + description + when_to_use | 是 | `sortedSkillNames + descriptionHash + whenToUseHash` |
| 11 | `available_tools` | 动态 | `tools.registry.visibleDescriptors(state)`（与 schema 同源） | 是 | `sortedVisibleToolNames + promptHash` |
| 12+ | `tool_prompt:{name}` | 动态 | 各 `ToolDescriptor.prompt` 字段 | 是 | `toolName + promptVersion` |

**空 body 过滤**：当某 section 渲染结果为空字符串（如 `session_memory` 内容为空、`long_term_memory` 无召回结果），**直接跳过**不进入最终 system prompt。这与参考的 `prompts/sections.py` 行为一致。

**section 拼接**：`# {title}\n{body}`，section 间用 `\n\n` 分隔，**无标题前缀**（参考 `PromptSection.render()`）。

### 4.2 PromptSection 与 fingerprint

```java
public record PromptSection(
    String key,                // 唯一标识（如 "long_term_memory"）
    String title,              // Markdown 标题（如 "## 长期记忆"）
    String body,               // 渲染后内容
    String fingerprint,        // 缓存 key 的一部分
    boolean cacheable          // 标记是否参与缓存（固定 section = true 节省构造时间）
) {
    public String render() { return "# " + title + "\n" + body; }
}
```

`fingerprint` 计算规则：

- 固定 section：`fingerprint = sectionVersion`（如 `"v1"`），不读其他状态
- 偏好 section：`fingerprint = sha256(全部 user_preference 行 update_at + value)`
- session memory section：`fingerprint = "{lastSummarizedSeq}.{contentHash}"`，`contentHash = md5(content)`
- 长期记忆 section：`fingerprint = "{recallPlanId}.{totalTokens}"`，`recallPlanId = UUID`（每次召回计划一次）
- workspace state section：`fingerprint = "{sha256(sorted(referenceKeys))}.{turnCount}.{planMode}"`
- available_skills section：`fingerprint = sha256(sorted(skill.name + skill.description + skill.when_to_use + skill.version))`
- available_tools section：`fingerprint = sha256(sorted(visibleTool.name + visibleTool.prompt))`
- tool_prompt:{name}：`fingerprint = "{toolName}.v{promptVersion}"`

### 4.3 PromptSectionCache

```java
public interface PromptSectionCache {
    Optional<String> get(String key, String fingerprint);   // 命中返回 render() 结果
    void put(String key, String fingerprint, String rendered);
    void invalidate(String key);                            // 整 key 失效
    void invalidateAll();                                    // 全部失效（用户偏好更新等）
    Stats stats();                                           // hits/misses
}
```

**实现要点**：
- **进程内 ConcurrentHashMap**，不引 Redis（section cache 是高频读 + 偶发写，进程内最快）
- **大小上限可配**（`pixflow.agent.prompt.section-cache.max-entries`，默认 1000）；LRU 淘汰
- **统计**：`hits / misses` 暴露给 Micrometer（`pixflow.agent.prompt.section.hit` / `miss`）
- **失效**：用户偏好更新（`preferenceDigest` 变）→ 失效 `instruction_memory`；skill 注册表变化 → 失效 `available_skills`；visible tools 变化 → 失效 `available_tools` 与对应 `tool_prompt:{name}`

### 4.4 PromptRuntimeContext

`DynamicPromptAssembler.assemble(state)` 接收的入参：

```java
public record PromptRuntimeContext(
    RuntimeState state,                    // 来自 loop（含 conversationId / turnNo / runtimeScope）
    String conversationId,
    Integer turnNo,
    List<MessageReference> references,     // 当前用户消息的 canonical key + display snapshot
    List<String> recentAssistantMessageIds,// 最近 N 轮 assistant message id（用于回溯）
    String userMessage,                    // 当次用户输入
    PlanModeState planMode,                // 当前是否 Plan 模式
    MemoryContext memoryContext,           // module-memory 统一产出的可注入记忆上下文
    SessionMemoryContent sessionMemory,    // Session Memory 累积要点
    List<ToolDescriptor> visibleTools      // 与 schema 同源
) {}
```

**关键不变量**：`visibleTools` 与 `toolSchemas(state)` 来自**同一 `ToolRegistry.visibleDescriptors()`**。这是 design.md §6.2「可见工具视图单一来源」与 prompt-architecture.md「单一可见工具视图」的硬约束。

### 4.5 工具 schema 投影

`agent` 模块提供 `PromptProvider.systemPrompt(state)` + `toolSchemas(state)` 两个方法（loop 调）：

- `systemPrompt(state)`：调 `DynamicPromptAssembler.assemble(ctx)` 拿字符串
- `toolSchemas(state)`：从 `visibleTools` 投影为 `infra/ai` 工具调用抽象所需的 schema 形态（与 `harness/tools` 的 `ToolSchemaExporter` 协作）

注意：**agent 不写 schema 导出器**——schema 导出归 `harness/tools`（design.md §五.2 与 tools.md §五.5），agent 只调 `ToolRegistry.toolSchemas(ctx)` 拿到 wire format。

### 4.6 不引入 provider context caching

PixFlow 的 prompt 复用走 agent 的 section cache（进程内），**不依赖服务商 context caching**（loop.md §18 + prompt-architecture 末尾）。这避免供应商锁定 + 跨模型不兼容。

---

## 五、Skill 机制（工具化接入 + 渐进式披露）

Skill 是**领域知识包**（不是工具，不是 subagent），让模型按需查阅"美妆水印规范""服装主图构图"等运营知识。**渐进式披露**：skill 不预先把 body 塞进 system prompt，而是作为工具暴露，模型**调用时**才看到 body。

### 5.1 形态：每个 skill = 一个 tool descriptor

skill **不直接进入 `ToolDescriptor` 核心 7 工具**；它由 agent 模块在启动期**动态注册**为独立的 tool descriptor，命名空间化为 `skill__<name>`。

```java
ToolDescriptor(
  name = "skill__beauty_watermark",
  description = "美妆类目水印规范：固定水印位置/透明度/字体/审核SOP",
  inputSchema = {
    "type": "object",
    "properties": {
      "question": {"type": "string"}     // 可选；本期 handler 忽略
    },
    "additionalProperties": false
  },
  outputSchema = {"type": "object", "properties": {"body": {"type": "string"}}},
  prompt = "需要按美妆类目水印规范处理图片时调用。返回完整规范正文。",
  readOnlyHint = true,
  handler = (inv) -> loadSkillBody("beauty_watermark")
)
```

**三层渐进披露**：
1. **Schema 层**（`description`，10-30 字）—— 模型在 tool schema 里看到 skill 存在与大用途
2. **System prompt 层**（`available_skills` section，含 description + when_to_use）—— 模型在 system prompt 里看到 skill 触发场景
3. **Handler 返回层**（调用 `skill__<name>` 后才看到 body）—— 完整规范正文按需注入

模型决策路径：**看 system prompt 的 `available_skills` section 选目标 → 调 `skill__<name>` 拿具体规则**。

### 5.2 命名空间化（避免冲突）

skill name 来自用户上传 / 项目定义，**不能裸名进 registry**——否则 skill 名 `read` / `search` / `agent` 会与 7 个固定工具冲突。

**统一前缀**：`skill__<name>`（双下划线，业界惯例命名空间分隔符）。

**冲突检测**：
- skill name 不能与 7 个固定工具名（`search` / `read` / `agent` / `submit_image_plan` / `submit_imagegen_plan` / `plan` / `plan_exit`）冲突
- skill name 不能以 `skill__` 开头（防止双重命名）
- skill name 必须匹配 `^[a-z][a-z0-9_]{1,40}$`（snake_case 校验）

### 5.3 SKILL.md 格式（业务方写）

skill 在仓库内以 `skills/<name>/SKILL.md` 形式存放，启动时同步入 MySQL `skill` 表。**frontmatter 强制 4 字段**：

```markdown
---
name: beauty_watermark
description: 美妆类目水印规范：固定水印位置/透明度/字体/审核SOP
when_to_use: 涉及美妆类目图片处理时；用户提及「美妆」「彩妆」「水印」等关键词时
version: 2
---

# 美妆类目水印规范

## 位置
- 主图右下角，距边 80px
- 详情页左上角，距边 40px
...
```

**frontmatter 校验**（启动期 `SkillLoader` 同步时）：
- `name`：必填，snake_case，唯一
- `description`：必填，1-200 字
- `when_to_use`：必填，1-500 字
- `version`：必填，整数
- body 大小：≤ 32KB（防止单 skill 占满 context）
- body Markdown 结构合法

**不合法 SKILL.md 拒绝同步**，记 `pixflow.agent.skill.invalid{path}` 指标 + 启动期 WARN 日志。

### 5.4 数据模型

```sql
CREATE TABLE skill (
  id              VARCHAR PK,
  name            VARCHAR(50) UNIQUE NOT NULL,
  description     VARCHAR(200) NOT NULL,
  when_to_use     VARCHAR(500) NOT NULL,
  body            MEDIUMTEXT NOT NULL,
  source          ENUM('BUILTIN','PROJECT','TEAM') NOT NULL,
  version         INT NOT NULL,
  body_hash       VARCHAR(64) NOT NULL,           -- MD5，fingerprint 辅助
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL
);
CREATE INDEX idx_skill_source ON skill(source);
```

**BUILTIN**：仓库 `skills/<name>/SKILL.md`，启动时同步入表
**PROJECT**：同 BUILTIN，未来支持多项目配置
**TEAM**：未来扩展——运营上传到 MinIO，metadata 入表（**本期不做**，避免引入新存储路径）

### 5.5 启动期同步流程

```
启动时 SkillLoader.syncBuiltIn():
  for file in skills/*/SKILL.md:
    1. 解析 frontmatter + body
    2. 校验格式（见 5.3）
    3. SELECT existing by name
       - 不存在 → INSERT
       - 存在且 body_hash 不同 → UPDATE
       - 存在且 body_hash 相同 → skip
  4. 删除启动时未出现的 BUILTIN skill（处理 skill 被移除场景；PROJECT/TEAM 不删）
```

**幂等性**：基于 `body_hash` 决定是否 UPDATE，避免不必要写入。

### 5.6 ToolRegistry 动态注册

启动期 `SkillToolRegistrar.registerAll()`：

```
for skill in skillRepository.findAll():
  ToolDescriptor d = buildSkillDescriptor(skill)
  toolRegistry.registerDynamic(d)   // 标记 source=SKILL，工具集裁剪时区分
```

**运行时变更**：本期不支持 skill 热加载（启动期一次性注册）。skill 增删需重启。`TEAM` 来源的 skill 上线后引入运行时加载。

**`visibleDescriptors` 过滤**：skill 工具默认全部可见；如有需要可加 `disabled_skills` 配置（本期不做）。

### 5.7 handler 实现

```java
public ToolHandlerOutput handle(ToolInvocation inv) {
    String skillName = inv.toolName().substring("skill__".length());
    Skill skill = skillRepository.findByName(skillName)
        .orElseThrow(() -> new ValidationException("Unknown skill: " + skillName));
    return new ToolHandlerOutput(
        skill.body(),                                          // 完整 body 作为模型可见 content
        Map.of(
            "skill_name", skillName,
            "skill_version", skill.version(),
            "body_chars", skill.body().length()
        )
    );
}
```

**关键**：
- handler **不调 LLM**（本期 skill 是"知识查阅"，不引入"用 body + query 调 LLM 答问题"的二次推理）
- handler **不改任何状态**（纯读 + 缓存命中）
- result 视为大结果——若 body > 50KB 走 `ToolResultStorage` 外置（与其它 tool 一致，由 tools 执行管线统一处理）

### 5.8 `available_skills` section

system prompt 中的 `available_skills` section 渲染所有已注册 skill 的目录：

```markdown
## 可用技能

### 美妆水印规范 v2
美妆类目水印规范：固定水印位置/透明度/字体/审核SOP
when_to_use: 涉及美妆类目图片处理时；用户提及「美妆」「彩妆」「水印」等关键词时

### 服装主图构图 v1
服装类目主图构图规范：模特姿势/留白比例/卖点呈现规则
when_to_use: 涉及服装类目图片处理时

（调用 `skill__<name>` 工具获取完整规范正文）
```

**fingerprint**：`sha256(sorted(skill.name + ":" + skill.description + ":" + skill.when_to_use + ":" + skill.version))`

### 5.9 skill 工具在工具集合中的位置

| 工具 | 来源 | 数量 | readOnly | concurrencySafe | Plan 模式可见 |
|---|---|:---:|:---:|:---:|:---:|
| 7 个核心工具 | harness/tools | 7 | 见 tools.md | 见 tools.md | 见 tools.md |
| `skill__<name>` | agent 启动期动态注册 | N（=skill 数） | 是 | 是 | 是 |
| **合计** | | 7 + N | | | |

工具集大小警告：若 N > 30，建议拆分为"skill catalog 段 + 按需子集"（本期不做）。

### 5.10 工具 schema 总数治理

**当前计算**：7（核心）+ N（skill）+ 0（MCP 本期不做）。当 N 大时（如电商运营项目 50+ skill），LLM 在 function calling 上有 30-50 个上限敏感区。

**缓解**：
- skill `description` 严格 ≤ 30 字（精炼）
- skill `prompt`（system prompt 层）只进 `available_tools` section 聚合，不进每个工具的 `tool_prompt:{name}` 段
- 后续可加 `disabled_skills` 配置或"分类 skill 工具组"（本期不做）

### 5.11 skill 的生命周期

- **加载**：启动期一次性
- **运行时使用**：模型按需调 `skill__<name>`（回合内无状态）
- **跨回合**：skill 始终可见（不卸载）
- **删除/修改**：需重启（启动期 `SkillLoader` 自动处理 UPDATE/DELETE）

### 5.12 skill 的可观测与权限

- **可见性**：所有 skill 对所有回合可见（不区分 Plan 模式 / read-only agent / 普通 agent）。理由：skill 是运营知识，无敏感数据
- **trace**：每次 `skill__<name>` 调用经 `recordToolCall` 投递到 `TurnTrace`，metadata 含 `skill_name` + `body_chars`
- **失败**：skill name 不存在 → `VALIDATION` tool error（`recovery=SKIP`）

---

## 六、自动记忆召回与注入

记忆召回**不是 Agent 工具**——这是关键边界。`module/memory` 提供正式门面 `MemoryService.prepareContext(MemoryContextRequest)`，agent 装配层在每轮 `buildForModel` 前**自动同步触发**，拿到 `MemoryContext` 后只负责把结果注入 Prompt。偏好召回、SKU 历史召回、分析结论向量/FULLTEXT 混合召回、RRF 融合、衰减排序、降级和 trace 都归 `module-memory`。

### 6.1 Agent 侧输入

agent 侧只把回合上下文整理成 `MemoryContextRequest`：

```java
new MemoryContextRequest(
    conversationId,
    turnNo,
    traceId,
    userPrompt,
    references,
    categoryHints,
    metadata,
    tokenBudget
)
```

其中 `references` 是已解析的 `referenceKey + displayPathSnapshot`。module-memory 如需 SKU 或包范围，只能通过共享 Asset Reference Resolver 解析这些 key，不能要求 agent 另传并行的 `packageId/skuIds/imageIds` 身份。`metadata` 可带最近 assistant 文本等非身份辅助信号。该调用严格只读；agent 不调用 `PreferenceService` / `SkuHistoryService` / `InsightDocMapper` 写回，也不持有 RRF 实现。

### 6.2 module-memory 输出

`MemoryService.prepareContext(...)` 返回 `MemoryContext`，其中固定 section 名如下：

| section | Prompt 注入位置 | 说明 |
|---|---|---|
| `user_preferences` | `instruction_memory` | 用户偏好画像；由 module-memory 组织为 `renderedText` |
| `sku_history` | `long_term_memory` 的 `SKU 处理历史` 子段 | 当前相关 SKU 的历史处理事实 |
| `analysis_insights` | `long_term_memory` 的 `分析结论` 子段 | module-memory 已融合后的分析结论，不暴露 vector/fulltext 内部边界 |

### 6.3 Prompt 渲染

`PreferenceSection` 直接读取 `ctx.memoryContext().section("user_preferences").renderedText()`。`LongTermMemorySection` 只读取 `sku_history` 与 `analysis_insights`，示例：

```markdown
## 长期记忆

### SKU 处理历史
SKU历史:
- SKU-A: 2026-06-15 白底处理后点击率 +18%

### 分析结论
分析结论:
- 夏季连衣裙白底图转化率高于场景图 30%
```

向量召回与 FULLTEXT 召回的候选、RRF 分数、衰减和降级信息保留在 `MemoryContext.recallTrace()`，不作为 agent prompt section 名暴露给模型。

### 6.4 不暴露为 Agent 工具

**关键设计**：`module/memory` 的召回路径**完全在 agent 装配层自动完成**，模型不能主动调 "search memory"。

理由：
- 避免「模型忘记召回了」
- 避免「模型过度召回爆上下文」
- 召回规划和降级是系统确定性逻辑，模型决策无附加值
- 与 `module/memory.md` 的「系统自动规划 + 混合检索 + 衰减排序」边界一致

### 6.5 召回的可观测

agent 侧记录 `MemoryContext.degraded()`、section 数量和 `recallTrace` 摘要。具体候选数量、召回 query、filters、RRF、衰减和降级指标由 module-memory 负责产出并进入 eval trace。

---

## 七、Session Memory 累积提取

Session Memory 是**单会话级**的累积要点视图——把"这场对话发生了什么"压缩成结构化 Markdown，每回合/每阈值追加，让模型在长会话中**不靠 rehydrate 整个 message 链**也能记得上下文。

### 7.1 与 destructive compaction 的关系

**两者正交，互补，层次不同**：

| 维度 | Session Memory 提取 | Destructive Compaction（auto compact） |
|---|---|---|
| 触发 | 阈值（每回合末 + content 自身 token 累积超阈值） | `token_after ≥ auto_compact_threshold` 或 `CONTEXT_LIMIT` |
| 粒度 | 累积 Markdown，**不丢弃历史**（历史在 MySQL message 表里） | 整段历史的「边界+摘要+tail」，**有损压缩** |
| 保留度 | 高保真（任务状态/已确认决策/用户偏好更新/待澄清） | 严重失真（93K → 几千 token 摘要） |
| 上下文贡献 | 注入 `session_memory` section | 改写 message 链 |
| 频率 | 频繁（每回合） | 罕见（应急） |
| 失败代价 | 可容忍（下一回合靠 history 继续） | 严重（可能丢重要上下文） |
| 角色 | **主要压缩手段** | **应急备份** |

**原则**（来自用户决策）：Session Memory 是**主要压缩手段**；auto compact 只在 cheap + session memory 都不够时作为**应急备份**触发。两者**不是替代关系**——session memory 让 auto compact 触发频率大幅降低，auto compact 触发时 session memory 的要点仍可作为「压缩指令注入」补充。

### 7.2 数据模型：MySQL `session_memory` + Redis 缓存

#### 7.2.1 MySQL `session_memory` 表

```sql
CREATE TABLE session_memory (
  conversation_id      VARCHAR(64) PK NOT NULL,         -- 一个会话一条累积记录
  content              MEDIUMTEXT NOT NULL,            -- Markdown 格式累积要点
  last_summarized_seq  BIGINT NOT NULL,                -- 已覆盖到的 message.seq
  covered_turn_count   INT NOT NULL,                   -- 已覆盖回合数
  source               ENUM('EXTRACTION','FALLBACK_RULE') NOT NULL,  -- 生成方式
  content_hash         VARCHAR(64) NOT NULL,           -- md5(content)
  created_at           DATETIME NOT NULL,
  updated_at           DATETIME NOT NULL
);
CREATE INDEX idx_session_memory_updated ON session_memory(updated_at);
```

**单行 per session**——区别于 transcript 的 append-only 历史，session memory 是**当前活跃状态的压缩视图**。

#### 7.2.2 Redis 缓存

```
key:    session:memory:{conversationId}
value:  session_memory.content (Markdown 文本)
TTL:    3600s（1h，与 context.MessageChainCache 一致）
```

**为什么用 Redis**：
- session memory **每回合读取**（注入 system prompt），不能每次 SELECT
- session memory **写入**异步，Redis 缓存 miss → MySQL 加载 → 回填
- **写入策略**：先写 MySQL（事实源），再失效/刷新 Redis 缓存
- **失败兜底**：Redis miss → 从 MySQL 加载；Redis 写失败 → 不阻断（下次 miss 重建）

### 7.3 阈值设计：阈值不入 transcript（关键不变量）

#### 7.3.1 阈值状态分两类

| 类别 | 例子 | 持久化？ |
|---|---|:---:|
| **事实状态** | `last_summarized_seq`、`covered_turn_count` | **是**（落 MySQL） |
| **运行时计算状态** | 当前 content token 累积、上次提取以来新增回合数、阈值（10K/3 轮） | **否**（每次重算） |

**为什么阈值不入 transcript**：

> **退出/重入会话时，阈值重新计算——从 `last_summarized_seq` 之后开始算「已累积多少」「是否触发提取」。这保证已覆盖的消息不被重复处理，也避免阈值状态污染事实源。**

具体场景：
- 用户对话 5 回合后关闭页面
- 阈值触发提取 → content 含 5 回合要点，`last_summarized_seq = 50`
- 重新进入会话 → 加载 `session_memory.content`（事实）+ 加载 `message` 表
- **重算**：上次提取覆盖到 seq=50，现在最新 seq=120，增量 = 70 条消息、约 5 回合
- **判定**：若增量 token 数 ≥ 10K → 触发新一次提取
- **提取范围**：从 seq=51 开始的 70 条消息（不重复处理已覆盖的 1-50）

#### 7.3.2 阈值规则

```
extract_if:
  since_last_extract_token >= 10000   # 累积 10K token 触发（可配）
  OR
  since_last_extract_turn >= 3         # 累积 3 回合触发（可配）
```

默认两个条件满足任一即触发。配置项：
- `pixflow.agent.session-memory.threshold.tokens`（默认 10000）
- `pixflow.agent.session-memory.threshold.turns`（默认 3）

#### 7.3.3 运行时阈值状态对象（不持久化）

```java
public record SessionMemoryThreshold(
    long lastSummarizedSeq,        // 事实，从 MySQL 来
    int coveredTurnCount,          // 事实
    Instant lastExtractTime,       // 运行时，进程内
    int sinceLastExtractTurn,      // 运行时计算
    long sinceLastExtractTokens    // 运行时计算（jtokkit 估算）
) {
    public boolean shouldExtract() {
        return sinceLastExtractTokens >= thresholdTokens
            || sinceLastExtractTurn >= thresholdTurns;
    }
}
```

`SessionMemoryService` 每回合末重算 `SessionMemoryThreshold`，判定是否触发。

### 7.4 提取流程（异步 + 同步两种路径）

#### 7.4.1 异步路径：TURN_STOPPED hook 触发

```
TURN_STOPPED 事件
  → TurnStoppedSessionMemoryHook.handle()
  → 提交 SessionMemoryExtractionTask 到线程池（与主循环解耦）
  → 立即返回 noop()（hooks.md §5.2「异步副作用由回调自理」）
  → 后台：
     1. 加载 session_memory.content（从 Redis cache → MySQL fallback）
     2. 计算 lastSummarizedSeq 之后的增量 messages（从 MessageStore）
     3. 计算 SessionMemoryThreshold → 判定 shouldExtract
     4. 命中阈值 → 调 SessionMemoryExtractor（fork child LLM）
        - 输入：历史 content + 增量 messages
        - 输出：新 content（替换式，非追加式）
     5. 写 MySQL → 失效/刷新 Redis 缓存
```

**关键不变量**：
- **异步不阻塞回合结束**——用户已收完本回合 SSE 流
- **失败不重试**——下一回合末再次判定时若仍超阈值会再触发
- **断路器**：连续失败 ≥ 3 次降级为 `SessionMemoryUpdater`（fallback 规则式）

#### 7.4.2 同步路径：手工触发

- 用户调 `/memory compact` 命令 → 立刻同步走一遍异步流程
- 用于「我准备退出了，先把 session memory 收一遍」的兜底

#### 7.4.3 提取 prompt（fork child LLM）

```
# 当前 session memory
{previous_content}

# 本回合增量对话（lastSummarizedSeq 之后）
{new_messages_json}

# 任务
将本回合关键信息合并进 session memory，保持以下 Markdown 结构：

## 任务状态
- 当前进行中：...
- 已完成：...
- 待办：...

## 已确认决策
- 用户确认/拒绝/修改：...

## 用户偏好更新
- 用户表达了新偏好：...

## 待澄清问题
- 尚未回答的用户追问：...

## 关键数据/事实
- 本回合出现的关键数据：...

约束：
- 保持简洁（≤ 10K token）
- 删除已完成的临时信息
- 保留对未来轮次有用的状态
- 不要重复历史 content 中已有的信息

输出：纯 Markdown 文本（不包含"以下是更新后的 session memory"等元说明）
```

**child 工具集**：本期**无工具**（summarization 与 session memory 提取都不给 child 工具——见 [第八章 8.4](#84-child-运行时工具集裁剪) 复用同一 child runner 配置）。

### 7.5 Fallback：`SessionMemoryUpdater`（规则式）

连续失败 ≥ 3 次触发 fallback：

```
content +=
  "## (Fallback) 最近对话摘要\n"
  "- User: {last_user_message}\n"
  "- Assistant: {last_assistant_message 截断 500 字}\n"
```

**纯规则拼接，不调 LLM**。保证 session memory 永远能累积（即使 LLM 不可用）。

### 7.6 注入 Prompt 的形态

```markdown
## 会话记忆

### 任务状态
- 当前进行中：处理 SKU-A/B/C 的白底图批处理
- 已完成：用户确认了 5 个 SKU 的去背景方案
- 待办：剩余 3 个 SKU 的方案待确认

### 已确认决策
- 用户确认了 SKU-A 的去背景白底 + 800x800 + 压缩到 200KB 方案
- 用户拒绝了 SKU-B 的蓝底方案，要求改白底

### 用户偏好更新
- 用户希望水印统一放右上角
- 用户要求所有图保留原文件名

### 待澄清问题
- 用户未说明 SKU-C 是否要加水印
```

**fingerprint**：`"{lastSummarizedSeq}.{contentHash}"`，`contentHash = md5(content)`。

### 7.7 SessionMemoryPort SPI

`harness/context` 定义 SPI，agent 实现：

```java
public interface SessionMemoryPort {
    /** 加载 session memory（先 Redis cache，miss 走 MySQL） */
    Optional<SessionMemoryContent> load(String conversationId);
    /** 写入 session memory（先 MySQL 事实源，再失效 Redis 缓存） */
    void save(String conversationId, SessionMemoryContent content, long lastSummarizedSeq);
    /** 计算运行时阈值（从 lastSummarizedSeq 之后开始算） */
    SessionMemoryThreshold computeThreshold(String conversationId, long currentHeadSeq, int currentTurnNo);
    /** 异步触发提取（投递线程池，立即返回） */
    void scheduleExtraction(String conversationId, int turnNo);
}
```

**SPI 倒置**：与 `context.TranscriptPort` ← session、`common.ErrorRecorder` ← eval 同款手法。

### 7.8 与 `module/memory` 的边界

| 维度 | Session Memory | module/memory (analysis_insight) |
|---|---|---|
| 作用域 | **单会话** | **跨会话**（类目级） |
| 粒度 | 「这场对话发生了什么」 | 「类目/品类的普遍规律」 |
| 存储 | MySQL `session_memory` + Redis | MySQL `analysis_insight` + Qdrant |
| 检索 | 全量读 + 注入 prompt | 向量召回 + RRF + 衰减 |
| 衰减 | 不衰减（随会话结束消失） | 衰减 + 强化 + 抑制 |
| 用途 | 让模型「记得这场对话的上下文」 | 让模型「了解这个品类的历史知识」 |
| 注入 section | `session_memory` | `long_term_memory` |

两者**不冲突互补**——session memory 每回合注入 `session_memory` section，module/memory 召回注入 `long_term_memory` section，按 `(section_key, fingerprint)` 分别缓存。

### 7.9 Session Memory 的可观测

新增 Micrometer 指标：
- `pixflow.session_memory.size{conversation_id}`（gauge，content 长度）
- `pixflow.session_memory.extraction{result=ok|fallback|failed}`：提取结果分布
- `pixflow.session_memory.threshold{since_tokens, since_turns}`：触发时的累积量
- `pixflow.session_memory.cache{source=hit|miss}`：Redis 缓存命中率
- `pixflow.session_memory.latency`：LLM 提取耗时

---

## 八、Subagent Runner（agent 工具 / 摘要 / Session Memory 三处复用）

`SubagentRunner` 是**单一类**，三处复用：
1. **`agent` 工具**（harness/tools §3.3）：`agent(type=explore)`
2. **`SummarizationPort` 实现**（destructive compaction 备用路径，agent 模块实现）
3. **`SessionMemoryExtractionService`**（Session Memory 累积提取，agent 模块实现）

三处的**差异仅在** child runtime 装配（工具集、system prompt、message chain seed 方式），**SubagentRunner 主干流程统一**。

### 8.1 主干流程

```java
public SubagentResult run(SubagentRequest req) {
    // 1. 构造 child RuntimeState（继承父的 conversationId / 模型配置 / messageChainCache 等）
    RuntimeState childState = childRuntimeFactory.build(req);

    // 2. 构造 ephemeral MessageStore（不绑 TranscriptPort，不写 message 表）
    MessageStore childStore = MessageStore.ephemeral(seedMessages(req));

    // 3. 构造 child ToolRegistry（按 type/usage 裁剪工具集）
    ToolRegistry childRegistry = childToolFilter.build(req);

    // 4. 构造 child ContextEngine + ToolExecutor + AgentLoop
    AgentLoop childLoop = new AgentLoop(
        childState, childStore,
        childContextEngine, childToolExecutor,
        childHookRegistry /* 共享父级 */,
        childModelClient /* 共享父级 infra/ai */,
        childTraceRecorder /* 共享父级 eval SPI */
    );

    // 5. 阻塞跑 continueStream（child 不流式，本期仅同步）
    AgentEventSink childSink = CapturingSink.create();  // 不 emit 到前端
    childLoop.continueStream(childSink);

    // 6. 抽取最终结果
    return SubagentResult.from(childSink.captured(), childState.usage());
}
```

**child 与父的关键差异**：
- **ephemeral MessageStore**：不绑 `TranscriptPort`，child 中间消息**不落 MySQL**
- **工具集裁剪**：递归禁用 `agent`、按 type 裁剪能力
- **共享底层**：ModelClient、PermissionContext、HookRegistry、TraceRecorder 与父共享
- **不流式**：child 不 emit 到前端 SSE，仅内部捕获（用户看不到 child 的迭代过程）

### 8.2 父 RuntimeState 字段继承表

| 字段 | 父 → child | 说明 |
|---|---|---|
| `conversationId` | 继承 | 用于 trace 关联 |
| `runtimeScope` | 改写 | `subagent=true`, `subagentType=<type>` |
| `usage` | 新建（不继承） | 累加到 `metadata.childUsage`，不混进父 usage |
| `iterationCount` | 新建 | child 独立计数 |
| `hasAttemptedReactiveCompact` | 不继承 | child 自己管 |
| `maxOutputRecoveryCount` | 不继承 | child 自己管 |
| `lastTransition` | 不继承 | child 自己管 |
| `metadata.subagent` | 设 true | hooks self-gate 用 |
| `metadata.readOnlyAgent` | **继承** | child 多数是只读 |
| `metadata.deniedTools` | 扩展 | child 额外禁用 `agent` |
| `metadata.disabledTools` | 继承 | |
| `metadata.hiddenTools` | 继承 | |
| `metadata.planMode` | 继承 | child 跟随父的 Plan 模式 |
| `metadata.modelRequestOverrides` | 继承 | child 用父的 max output 等 |
| `metadata.isForkChild` | **本期不设** | fork 模式本期不实现 |

### 8.3 child MessageStore seed

**三种 seed 模式**（参考 subagent-architecture.md）：

| 模式 | 何时用 | PixFlow 本期 |
|---|---|:---:|
| **clean** | child 跑全新任务 | **是**——`agent` 工具 / summarization / session memory 提取都用 clean |
| **fork** | child 继承父历史（深拷贝 + 占位 tool_result） | **否**——本期不实现 |
| **resume** | child 续接已有 child session | **否**——本期不实现 |

`clean` 模式下 child message chain：
```
[system message: child 任务指令]
[user message: {req.prompt}]
```

### 8.4 child 运行时工具集裁剪

| 调用方 | 默认工具集 | 禁用 |
|---|---|---|
| `agent(type=explore)` | core 7 工具 | 禁用 `submit_image_plan` / `submit_imagegen_plan` / `plan`（child 只读 + 探索） |
| `SummarizationPort`（destructive compaction 摘要） | **无工具** | 全部禁用（只看不调） |
| `SessionMemoryExtractor`（session memory 提取） | **无工具** | 全部禁用（只看不调） |

**Skill 工具是否在 child 中暴露**：
- `agent(type=explore)`：**是**——child 可能需要 skill 知识
- `SummarizationPort` / `SessionMemoryExtractor`：**否**——摘要是「读历史写摘要」，不需要 skill

### 8.5 `agent` 工具 handler

#### 8.5.1 `agent(type=explore)`

```java
ToolHandlerOutput handle(ToolInvocation inv) {
    SubagentRequest req = SubagentRequest.explore(
        parentState.conversationId(),
        inv.toolCallId(),
        inv.arguments()
    );
    SubagentResult result = subagentRunner.run(req);
    return new ToolHandlerOutput(result.finalText(), ...);
}
```

**实际能力**：child 跑一个**完整的多轮决策循环**，可用 `search` / `read` 与允许的 Skill 工具，汇总探索结果回主 Agent。商品外观事实由主 Agent 直接调用 `get_product_visual_facts` 获取，不通过 explore child 转调。

### 8.6 ChildTraceRecorder

child 的 trace **不独立写 `agent_trace` 行**（避免 trace 重复）——child 的 tool spans **合并到父回合的 `tool_calls_json` 数组**中。

具体机制：
- child 跑完后，agent 工具 handler 把 child 的 tool spans 转为父的 `ToolExecutionResult.metadata.childToolSpans`
- loop 的 `recordToolCall` 投递时，把 child tool spans 作为 `agent` 工具 span 的子条目
- `TurnTrace.recordToolCall` 接收后递归写

**trace 责任上移**——child 的可见性由父 trace 携带，eval 回放时按 `parent_tool_call_id` 关联。

### 8.7 Subagent 不可恢复错误处理

- child 抛不可恢复异常 → `SubagentResult.isError = true` + `finalText = 错误描述（脱敏）`
- 父 agent 工具 handler 转为 `ToolExecutionResult(error=true)` 回填模型
- **不冒泡到 loop**——subagent 异常与工具异常语义对齐，loop 照常 append + 续轮
- child 的 trace 记 `subagent_error` 事件（与参考 subagent-architecture 一致）

### 8.8 Subagent 并发

child 跑在 `CompletableFuture.supplyAsync` 的有界线程池（**独立于 tools 并发池**）：

```yaml
pixflow:
  agent:
    subagent:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 100
        keep-alive-seconds: 60
```

**为什么独立池**：explore、摘要与 Session Memory 提取都可能运行独立模型循环，与主循环的工具并发**不应抢资源**。VLM 商品分析不使用该线程池，由 vision 模块自行调度。

### 8.9 Subagent 可观测

- `pixflow.agent.subagent{type, result}`：调用结果（success/error/timeout）
- `pixflow.agent.subagent.latency{type}`：耗时分布
- `pixflow.agent.subagent.usage{type=input|output}`：token 用量
- `pixflow.agent.subagent.iterations{type}`：child 迭代次数分布
- `pixflow.agent.subagent.tool_calls{type}`：child 工具调用数

---

## 九、`SummarizationPort` 实现（destructive compaction 备份路径）

`context.SummarizationPort` SPI 由 `harness/context` 定义，**agent 层实现**。本期实现是 `ForkChildSummarizationPort`——复用 `SubagentRunner` fork child 跑 LLM 摘要。

### 9.1 SPI 契约

```java
public interface SummarizationPort {
    SummaryResult summarize(SummarizationRequest request);
}
```

`SummarizationRequest`（context.md §10.1）：
- `messages`：待压缩消息（context 已选好 tail）
- `focus`：可选聚焦指令
- `summaryInstructions`：来自 `PreCompact` hook metadata

`SummaryResult`：
- `summaryText`：Markdown 文本摘要
- `success` / `fallback` / `failed` 状态

### 9.2 实现路径

```java
public SummaryResult summarize(SummarizationRequest req) {
    try {
        // 1. 构造 child SubagentRequest
        SubagentRequest subReq = SubagentRequest.summary(
            req.conversationId(),
            req.turnNo(),
            req.messages(),
            req.focus(),
            req.summaryInstructions()
        );

        // 2. 跑 SubagentRunner（child 工具集 = 空，见 8.4）
        SubagentResult result = subagentRunner.run(subReq);

        // 3. 抽取最终文本
        if (result.isError()) {
            return SummaryResult.failed(result.errorMessage());
        }
        return SummaryResult.ok(result.finalText());
    } catch (Exception e) {
        // 上报 context 断路器（让 context 计数失败次数）
        circuitBreaker.recordFailure();
        return SummaryResult.failed(e.getMessage());
    }
}
```

### 9.3 child prompt 构造

```java
public SubagentRequest.Submission summary(SubagentRequest req) {
    String prompt = """
        # 摘要任务

        ## 上下文
        {summaryInstructions}        // 来自 PreCompact hook

        ## 待摘要对话
        {messages_markdown}

        ## 输出要求
        总结上述对话为简洁 Markdown 文本（≤ 5K token），保留：
        - 关键决策（已确认/已拒绝的方案）
        - 当前任务状态（进行中/待办）
        - 关键数据点（SKU ID、电商指标、参数）
        - 用户偏好更新

        删除：
        - 临时推理步骤
        - 重复确认的同类信息
        - 已撤回/被否的方案细节

        输出纯 Markdown 文本，不包含"以下是摘要"等元说明。
        """;
    return new SubagentRequest(prompt, /* type = "summarization" */, ...);
}
```

### 9.4 child 工具集 = 空

destructive compaction 摘要 child **不持有任何工具**——理由：
- 摘要的输入是 `req.messages`（已选好）
- 摘要是"读 + 总结"，不需要"查外部数据"
- 保留工具会触发额外 LLM 调用，compaction 路径不可控

schema 完全省略，模型自然只输出文本。

### 9.5 失败兜底

context.md §10.3 要求「断路器触发后回退确定性优先级裁剪」：

- `circuitBreaker.recordFailure()`：连续失败 ≥ 3 次后，context 不再调 `summarize()`，回退到「用户最新指令 > 任务状态 > Hooks 强规则 > 关键记忆 > 历史对话」裁剪
- agent 层不实现裁剪逻辑——只负责把 success/failure 上报给 context

### 9.6 PreCompact 指令注入

agent 模块订阅 `PRE_COMPACT` 事件（通过 `PreCompactSummaryHook` 实现）：

```java
public HookResult handle(HookEvent event, HookPayload payload) {
    if (event == PRE_COMPACT) {
        return HookResult.withMetadata(Map.of(
            "compact.summaryInstructions",
            "PixFlow 电商运营 Agent：保留 SKU 处理状态、用户确认/拒绝决策、电商数据指标、用户偏好更新。"
        ));
    }
    return HookResult.noop();
}
```

**关键**：hook 不直接改 `SummarizationRequest`——它通过 metadata 注入指令，context 读 metadata 后构造 `SummarizationRequest.summaryInstructions`（与 hooks.md §7.4 一致）。

### 9.7 与 Session Memory 提取的差异

| 维度 | SummarizationPort（auto compact 备份） | SessionMemoryExtractor（主要压缩） |
|---|---|---|
| 触发 | 应急（CONTEXT_LIMIT 或 token 仍超阈值） | 频繁（每回合末/阈值） |
| 输出 | 替换活动链的整段历史 | 累积式追加 session_memory.content |
| 失败兜底 | 确定性优先级裁剪 | Fallback 规则式 + 断路器 |
| 用户可见 | 不直接可见（只走 context 改写） | 注入 session_memory section，间接可见 |

**两者不重复**——auto compact 触发时，session memory 的 content 可作为「压缩指令注入」的一部分，辅助摘要聚焦。

---

## 十、压缩策略分层（cheap pipeline / Session Memory / auto compact）

PixFlow 的**压缩策略是三层金字塔**——从下到上成本递增、触发频率递减、保留度递减。

```
                ┌─────────────────────────────────┐
                │  Auto Compact（应急备份）         │  罕见触发
                │  - 改写活动链（边界+摘要+tail）    │  - 高成本
                │  - destructive                  │  - 严重失真
                │  - 仅上下文完全塞不下时触发       │
                └─────────────────────────────────┘
                            ▲ 兜底
                            │
                ┌─────────────────────────────────┐
                │  Session Memory 累积提取          │  频繁触发
                │  - 注入 session_memory section  │  - 低成本
                │  - 不丢弃 message 历史          │  - 高保真
                │  - 主要压缩手段                  │
                └─────────────────────────────────┘
                            ▲ 主路径
                            │
                ┌─────────────────────────────────┐
                │  Cheap Pipeline（基础治理）       │  每轮必跑
                │  - 结果预算外置 MinIO            │  - 零成本
                │  - 投影滑窗（保配对）             │  - 不改写链
                │  - microcompact                  │
                └─────────────────────────────────┘
```

### 10.1 cheap pipeline（基础治理，无成本）

- **位置**：`harness/context.ContextCompactionService.prepare()`
- **触发**：每轮 `buildForModel` 前必跑
- **动作**：
  1. 结果预算：单条 `tool_result` > 50KB → 外置 MinIO + 模型只见引用+preview
  2. 投影滑窗：保 tool call/result 配对，最多 N 条
  3. microcompact：旧 `tool_result` content 降级为占位符
  4. jtokkit token 估算 → `usageHints.tokenAfter`
- **不调 LLM**，**不改写活动链**
- **永远跑**

### 10.2 Session Memory 累积提取（主要压缩手段）

- **位置**：`agent.sessionmemory.SessionMemoryService`
- **触发**：每回合末 + content 累积超阈值
- **动作**：
  1. 计算 `SessionMemoryThreshold`（运行时，不持久化）
  2. 命中阈值 → 异步 fork child 提取当回合要点
  3. 写 MySQL + 失效 Redis 缓存
  4. 下一回合 `session_memory` section 注入新 content
- **不调 LLM 仅在 fallback**（连续失败 3 次后）
- **不丢弃 message 历史**
- **主要压缩手段**

### 10.3 auto compact（应急备份）

- **位置**：`harness/context.ContextCompactionService.maybeAutoCompact()` / `reactiveCompact()`
- **触发**：
  - `token_after ≥ auto_compact_threshold`（约 93K）→ `maybeAutoCompact`
  - `CONTEXT_LIMIT` 异常 → `reactiveCompact`（仅首次）
- **动作**：
  1. 调 `SummarizationPort`（agent 实现）→ fork child 跑 LLM 摘要
  2. 产物 `(boundary, summary, tail)` → `replaceMessagesForCompaction` 改写活动链
  3. tail 是 `seq > covered_up_to_seq` 的普通消息
- **改写活动链**（destructive）
- **仅上下文完全塞不下时触发**

### 10.4 三层关系

```
每轮 buildForModel:
  → cheap pipeline 必跑（无成本）
  → 注入 session_memory section（每回合）

回合末:
  → Session Memory 异步提取（频繁）

CONTEXT_LIMIT / 仍超阈值:
  → auto compact（罕见）
```

**关键不变量**：
- cheap pipeline 永远跑（无副作用）
- Session Memory 主要压缩，频繁触发
- auto compact 仅在 cheap + session memory 都不够时触发
- **三者不互斥**——同一回合内 cheap pipeline 必跑、Session Memory 按阈值跑、auto compact 按需跑

### 10.5 阈值与计费

| 操作 | 触发频率 | LLM 调用次数 | 上下文影响 |
|---|---|---|---|
| cheap pipeline | 每轮 | 0 | 微（仅外置大字节） |
| Session Memory 提取 | ~每 3 回合 / ~每 10K token | 1 次/触发 | 注入 section（~1-10K token） |
| Auto compact | 罕见（每会话 0-N 次） | 1 次/触发 | 改写活动链（节省 ~50K token） |

**Session Memory 的成本**：每 3 回合一次 LLM 提取 ≈ 平均每回合 0.33 次 LLM 调用 vs Auto compact 1 次 ≈ 平均每会话 1-3 次。**Session Memory 让 auto compact 频率大幅降低**。

### 10.6 Session Memory 提取的回退路径

连续失败 ≥ 3 次 → `SessionMemoryUpdater`（fallback 规则式）：

- 不调 LLM
- 拼接"最近 N 条 user message + 最后 1 条 assistant message 关键句"
- 永远能累积（即使 LLM 不可用）

`SessionMemoryUpdater` 是**最后兜底**——它不解决 LLM 摘要质量，但保证 session memory 不会因 LLM 失败而停滞。

---

## 十一、Plan 模式控制器

`harness/tools §3.6` 定义 `plan` / `plan_exit` 工具，handler 由 **agent 模块实现**。Plan 模式的"效果靠 permission + prompt + 可见集三层强制"，状态归属 `RuntimeState.metadata.planMode`。

### 11.1 状态归属

```java
public enum PlanModeState {
    OFF,
    ACTIVE;
}
```

- **状态字段**：`RuntimeState.metadata.planMode`（runtime 持有，不持久化）
- **写入方**：`plan` / `plan_exit` 工具的 handler（调 `PlanModeController.enter()` / `exit()`）
- **读取方**：
  - `harness/tools` `visibleDescriptors()`（过滤带后果工具）
  - `permission.PermissionPolicy.evaluate()`（Plan 模式下对非只读工具硬 deny）
  - `agent.prompt.WorkspaceStateSection`（prompt 反映 Plan 模式）
  - `harness/loop`（emit `PENDING_PLANS` 等事件时附 Plan 模式标记）

### 11.2 `plan` 工具 handler

```java
ToolHandlerOutput handle(ToolInvocation inv) {
    PlanModeState current = state.metadata().planMode();
    if (current == PlanModeState.ACTIVE) {
        return ToolHandlerOutput.error("Already in Plan mode. Use plan_exit to leave.");
    }
    state.metadata().put("planMode", PlanModeState.ACTIVE);
    hookRegistry.dispatch(PLAN_MODE_ENTERED, new PlanModePayload(...));
    return new ToolHandlerOutput("Entered Plan mode. Read-only tools only; write tools hidden.", ...);
}
```

**关键不变量**：
- handler **不写** `RuntimeState`，**调** `PlanModeController.enter(state)` 改写
- 派发自定义 `PlanModeEvent`（未来用于前端/audit）

### 11.3 `plan_exit` 工具 handler

```java
ToolHandlerOutput handle(ToolInvocation inv) {
    String draftPlan = (String) inv.arguments().get("draftPlan");
    state.metadata().put("planMode", PlanModeState.OFF);
    state.metadata().put("lastPlanDraft", draftPlan);
    return new ToolHandlerOutput("Exited Plan mode. Plan draft saved.", Map.of("draftPlan", draftPlan));
}
```

**关键**：
- 退出 Plan 模式时**保留草拟计划**（可作为后续回合的初始 prompt 上下文）
- 草拟计划存入 `metadata.lastPlanDraft`，下一回合的 `WorkspaceStateSection` 渲染时附带

### 11.4 三层强制

按 `harness/tools §12.2`：

1. **可见集（UX）**：Plan 模式下 `visibleDescriptors()` 过滤掉 `submit_image_plan` / `submit_imagegen_plan` / `plan`（`plan_exit` 必保留）
2. **permission（权威）**：Plan 模式下对 `readOnly==false` 的工具硬 deny（input-aware 分类）
3. **prompt（引导）**：`WorkspaceStateSection` 反映 Plan 模式 + behavior_rules 强调"只读不写"

### 11.5 Plan 模式下的工具裁剪

| 工具 | Plan 模式可见 | 备注 |
|---|:---:|---|
| `search` | 是 | 只读探索 |
| `read` | 是 | 只读 |
| `get_product_visual_facts` | 是 | 只读；读取当前 SKU/图片视觉事实，普通 SKU lookup 不启动新分析 generation |
| `agent(explore)` | 是 | 只读 |
| `skill__*` | 是 | 只读 |
| `plan` | **否** | 已在 Plan 模式 |
| `plan_exit` | **是** | 必须可见，否则退不出 |
| `submit_image_plan` | **否** | 带后果 |
| `submit_imagegen_plan` | **否** | 带后果 |

### 11.6 Plan 模式的可观测

- `pixflow.agent.planmode{action=enter|exit, result}`：模式切换结果
- `pixflow.agent.planmode.draft_size`：草拟计划长度分布

---

## 十二、Agent Orchestrator 入口

`AgentOrchestrator` 是 web 层（如 `module/conversation` 的 REST 端点）调用 agent 模块的入口。

### 12.1 接口

```java
public interface AgentOrchestrator {
    /** 发起新回合（用户消息） */
    void streamNewTurn(String conversationId, String prompt, List<MessageReference> references, AgentEventSink sink);

    /** 续接回合（恢复 / 子 Agent fork） */
    void continueTurn(String conversationId, AgentEventSink sink);
}
```

### 12.2 装配流程

```
streamNewTurn:
  1. 校验 conversationId 存在（查 conversation 表）
  2. 会话级锁（Redisson lock:conversation:{id}，防并发回合）
  3. rehydrate RuntimeState（重新加载 session memory / skill / preference）
  4. 构造 child-free RuntimeState
  5. 构造 MessageStore（经 SessionMessageStoreFactory rehydrate from session.load）
  6. 构造 ToolExecutionContext（含 PermissionContext / TurnTrace 句柄 / RuntimeScope=MAIN）
  7. 构造 AgentEventSink 适配为 SseEmitter
  8. 调 AgentLoop.stream(prompt, references, sink)
  9. 回合结束释放锁
```

### 12.3 依赖注入

```java
public class AgentOrchestrator {
    // 必注入
    private final AgentLoop loop;                              // harness/loop
    private final AgentProperties props;                       // agent 配置
    private final SessionMemoryService sessionMemoryService;  // agent 自身
    private final MemoryRecallPlanner memoryRecallPlanner;    // agent 自身
    private final DynamicPromptAssembler promptAssembler;     // agent 自身
    private final SkillToolRegistrar skillRegistrar;          // agent 自身
    private final PlanModeController planModeController;      // agent 自身
    private final SubagentRunner subagentRunner;              // agent 自身
    private final SessionConversationLock conversationLock;   // infra/cache (Redisson)
    private final SessionService sessionService;              // harness/session (load session memory 等)
    private final MemoryService memoryService;                // module/memory
    private final SseEmitterRegistry sseRegistry;             // app 级（SSE 连接管理）

    // 注入的钩子（agent 自身实现）
    private final HookCallback turnStoppedHook;               // TURN_STOPPED → 触发 session memory
    private final HookCallback preCompactHook;                // PRE_COMPACT → 注入 summaryInstructions
}
```

### 12.4 回合结束后的钩子联动

```
AgentLoop.stream() 返回（COMPLETED 或抛错）
  → AgentOrchestrator 收尾:
     1. 释放会话级锁
     2. 触发 TURN_STOPPED hook（TurnStoppedSessionMemoryHook 异步提交流程）
     3. flush 消息缓冲（harness/session.flush）
     4. flush session memory 缓存（如有变更）
     5. emit COMPLETED 事件给前端
     6. 关闭 SseEmitter
```

### 12.5 不可恢复异常处理

- agent 层不处理不可恢复异常——**向上抛**给 web 层
- web 层归一化为 HTTP/SSE error 帧
- 异常信息经 `common.Sanitizer` 脱敏后展示给用户
- `TurnTrace.abort(err)` + `ErrorRecorder.record(err)` 在 loop 出口已完成（loop.md §十）

### 12.6 错误归一化

agent 自治码 `AgentErrorCode`（`enum implements ErrorCode`）：

| code | category | 场景 |
|---|---|---|
| `AGENT_PROMPT_ASSEMBLY_FAILED` | INTERNAL | section 渲染失败 |
| `AGENT_SKILL_LOAD_INVALID` | VALIDATION | SKILL.md frontmatter 不合法 |
| `AGENT_SKILL_NOT_FOUND` | NOT_FOUND | skill__<name> 工具调用时 skill 不存在 |
| `AGENT_MEMORY_RECALL_FAILED` | DEPENDENCY | 召回通道失败（非致命，降级） |
| `AGENT_SESSION_MEMORY_EXTRACTION_FAILED` | DEPENDENCY | session memory 提取失败（计入断路器） |
| `AGENT_SUBAGENT_TIMEOUT` | TIMEOUT | subagent 跑超阈值（默认 60s） |

---

## 十三、与各模块的接缝契约

| 对接方 | 契约 |
|---|---|
| `harness/loop` | loop 是引擎；agent 调 `loop.stream/continueStream`；每轮 `buildForModel` 前 loop 调 `PromptProvider.systemPrompt/toolSchemas`；agent 提供 `SummarizationPort` 实现（倒置接入） |
| `harness/tools` | agent 实现 `agent` 工具 + `plan` / `plan_exit` 工具的 handler 与 `ToolDescriptor` bean；ToolRegistry 自动收集；agent 装配期从 skill 表动态注册 `skill__<name>` |
| `harness/context` | agent 实现 `context.SummarizationPort`（destructive compaction 摘要）；agent 实现 `context.SessionMemoryPort`（累积提取）；agent 不实现 `context.TranscriptPort`（归 session） |
| `harness/session` | session 是 `message` 表唯一写者；agent 读 message 序列**经 `context.MessageStore.currentMessages()`**（不直连 MySQL） |
| `harness/hooks` | agent 仅实现 `TurnStopped` / `PreCompact` 回调服务同会话上下文压缩；不实现 assistant 消息到业务记忆的写回 Hook；`PRE_TOOL_USE` 仍走 harness/tools 标准管线 |
| `harness/eval` | agent 不写 trace；loop 持有的 `TurnTrace` 句柄由 loop 转投 eval；agent 提供 `PromptSummary` 视图供 trace 抽取 |
| `harness/state` | agent 不直接消费 state（任务进度查询归 module/task）；Plan 模式标记是会话级，归 `RuntimeState.metadata`，不归 state |
| `harness/permission` | agent 不评估权限；权限评估在 tools 执行管线（deny-first）；agent 提供 `PlanModeController` 影响 PermissionContext |
| `module/memory` | agent 只读调用 `MemoryService.prepareContext(MemoryContextRequest)`；module-memory 负责召回规划、混合检索、RRF、衰减、降级和 trace；当前不允许任何对话结果写回偏好、SKU 历史或分析结论 |
| `module/vision` | 贡献 `get_product_visual_facts(referenceKey)`；参数可为 PACKAGE/SKU/IMAGE，具体可接受种类由工具 schema 声明。主 Agent 对依赖外观的营销文案和重绘 Prompt 必须先获取对应视觉事实 |
| `module/imagegen` | `submit_imagegen_plan(referenceKey, prompt, params)` 只接受一个具体 IMAGE key；每次成功调用发布一个独立已校验 Proposal，一张源图对应一个 task 和一张结果；单回合最多 20 个 |
| `module/dag` | `submit_image_plan(referenceKeys, dag)` 接受 PACKAGE/SKU/IMAGE key 列表并由后端展开、去重、校验；每次成功调用发布一个独立 Proposal；agent 不直连 dag 内部 |
| `module/commerce` | `search` / `read` 工具的 handler 在 module/commerce；agent 不直连 commerce（只通过工具调用） |
| `module/conversation` | conversation 调 `AgentOrchestrator.streamNewTurn` 发起回合；把消息 references 注入 Agent；接收 `proposal_ready` SSE 事件，并为每个 Proposal 提供独立 confirm/reject 边界 |
| `module/file` | 提供 canonical Asset Reference Resolver 与只读 inspection/search 工具；agent 只传 `referenceKey/referenceKeys`，不调用 `PackageService.getSkuIds(packageId)` 形成第二套身份契约 |
| `infra/ai` | agent 不直接持有 `ModelClient`——经 `AgentLoop` 注入；subagent runner 复用父的 `ModelClient` |
| `infra/storage` | skill body 大结果外置经 `ToolResultStorage`（与其它 tool 一致）；session memory content 大时也走外置 |
| `infra/cache` | agent 经 `SessionMemoryCache` 读 session memory；Redisson 会话级锁防并发回合 |
| `infra/vector` | agent 不直连 Qdrant——经 `MemoryService.prepareContext(...)` 间接调 |
| `infra/thirdparty` | Agent 不经此模块调用 VLM；商品视觉调用由 `module/vision -> infra/ai` 的 OpenAI-compatible provider 路径承担 |
| `common` | agent 自治错误用 `AgentErrorCode`（`enum implements ErrorCode`）；文案经 `common.Sanitizer` 脱敏；订阅 `common.ErrorRecorder` 不写（由 eval 实现） |

**关键不变量**：
- agent 不持 LLM 客户端（不直连 `infra/ai` 的 `ModelClient`，经 loop 注入）
- agent 不写 `message` 表（经 `context.MessageStore.appendXxx` → `TranscriptPort`）
- agent 不评估权限（让 harness/tools 在执行管线内做）
- agent 不写 trace（让 loop 经 eval SPI 投）
- agent 不生成 challenge、确认令牌或客户端幂等键；Proposal 的唯一业务幂等身份是后端生成的 `proposalId`

---

## 十四、配置项

```yaml
pixflow:
  agent:
    # Prompt 装配
    prompt:
      section-cache:
        max-entries: 1000          # 进程内 section 缓存 LRU 上限
      enable-section-cache: true   # 关闭则每次 buildForModel 重渲染（排障用）

    # Skill 机制
    skill:
      enabled: true
      max-body-bytes: 32768       # 单 skill body ≤ 32KB（超出拒绝同步）
      description-max-chars: 200  # frontmatter description 上限
      when-to-use-max-chars: 500  # frontmatter when_to_use 上限
      # skills/<name>/SKILL.md 自动同步入表（启动期）

    # 自动记忆召回
    memory:
      recall:
        max-tokens: 4000                  # 传给 MemoryContextRequest 的 tokenBudget；具体召回/RRF/过滤配置归 pixflow.memory.*

    # Session Memory
    session-memory:
      enabled: true
      threshold:
        tokens: 10000                    # 累积 10K token 触发
        turns: 3                         # 累积 3 回合触发
      circuit-breaker:
        max-consecutive-failures: 3      # 连续失败断路阈值
      cache:
        ttl-seconds: 3600                # Redis 缓存 TTL
      max-content-tokens: 12000          # content 上限（超则截断尾部）
      max-content-bytes: 65536           # content 字节上限（防 LLM 提取爆量）

    # Subagent Runner
    subagent:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 100
        keep-alive-seconds: 60
      timeout-seconds: 60                # 单次 explore/摘要/Session Memory child 超时
      share-skill-tools: true            # child 工具集是否包含 skill__<name>
      share-readonly-tools: true         # child 工具集是否包含 search/read

    # Plan 模式
    plan-mode:
      auto-exit-on-completion: false    # 回合自然结束是否自动退 Plan（默认否，需 plan_exit 显式退）
      keep-draft-on-exit: true          # 退出 Plan 时是否保留草拟计划

    # 整体
    orchestrator:
      conversation-lock-ttl-seconds: 300  # 会话级锁 TTL（防死锁）
```

---

## 十五、可观测与指标

### 15.1 Micrometer 指标

#### 15.1.1 Prompt 装配

- `pixflow.agent.turn{plan_mode, proposal_count}` + 耗时：回合健康度；Proposal 计数仅属当前 runtime
- `pixflow.agent.prompt.section.hit{key}` / `miss{key}`：section cache 命中率
- `pixflow.agent.prompt.section.size{key}`：各 section 渲染大小分布
- `pixflow.agent.prompt.assemble.latency`：完整 systemPrompt 渲染耗时

#### 15.1.2 Skill 机制

- `pixflow.agent.skill.registered{count}`（gauge）：当前已注册 skill 数
- `pixflow.agent.skill.invocation{skill_name, result}`：skill__<name> 调用结果
- `pixflow.agent.skill.invalid{path}`：启动期 SKILL.md 校验失败计数
- `pixflow.agent.skill.body_size{skill_name}`：body 大小分布

#### 15.1.3 记忆召回

- `pixflow.agent.memory.prepare_context{result=ok|degraded|failed}`：同步调用 `MemoryService.prepareContext` 的结果
- `pixflow.agent.memory.sections.count`：本轮 `MemoryContext.sections` 数量
- `pixflow.agent.memory.degraded`：本轮 memory context 是否降级
- 具体通道命中、RRF、SKU 抽取和混合召回指标归 `module-memory`

#### 15.1.4 Session Memory

- `pixflow.agent.session_memory.size{conversation_id}`（gauge）：content 长度
- `pixflow.agent.session_memory.extraction{result=ok|fallback|failed|skipped}`：提取结果分布
- `pixflow.agent.session_memory.threshold{since_tokens, since_turns}`：触发时的累积量
- `pixflow.agent.session_memory.cache{source=hit|miss}`：Redis 缓存命中率
- `pixflow.agent.session_memory.latency`：LLM 提取耗时

#### 15.1.5 Subagent

- `pixflow.agent.subagent{type, result}`：调用结果（success/error/timeout）
- `pixflow.agent.subagent.latency{type}`：耗时分布
- `pixflow.agent.subagent.usage{type=input|output}`：token 用量
- `pixflow.agent.subagent.iterations{type}`：child 迭代次数分布
- `pixflow.agent.subagent.tool_calls{type}`：child 工具调用数
- `pixflow.agent.subagent.pool{metric=active|queue|completed}`：线程池状态

#### 15.1.6 Plan 模式

- `pixflow.agent.planmode{action=enter|exit, result}`：模式切换结果
- `pixflow.agent.planmode.draft_size`：草拟计划长度分布

#### 15.1.7 压缩策略

- `pixflow.agent.compaction.layer{level=cheap|session_memory|auto_compact, result}`：各层触发与结果
- `pixflow.agent.compaction.auto_compact.frequency`：auto compact 频率（应罕见）

### 15.2 trace 责任

agent 不写 `agent_trace`——但提供**只读视图**供 eval 抽取：

- `PromptSummary`（每轮 systemPrompt 摘要，不暴露完整 prompt）
- `RecallSummary`（每轮召回统计：通道、命中数、token）
- `SessionMemoryDigest`（每轮 session memory 注入摘要）
- `ActiveSkillsDigest`（每轮 active skills 列表）

loop 的 `TraceFanout` 在 `recordInput` 时把上述视图一并投递。

---

## 十六、测试策略

### 16.1 单元层（无 Spring 上下文）

- **`DynamicPromptAssembler`**：
  - 固定 section 顺序、cache 命中/失效
  - 空 body section 过滤
  - fingerprint 计算正确性
  - section 重排不影响 fingerprint

- **`PromptSectionCache`**：
  - LRU 淘汰
  - 失效按 key
  - 并发安全
  - stats 正确性

- **`MemoryRecallPlanner`**：
  - 同步调用 `MemoryService.prepareContext(...)`
  - `MemoryContextRequest` 包含 conversationId、turnNo、traceId、userPrompt、references、categoryHints、metadata、tokenBudget
  - 不依赖 `module-memory` 内部 service / mapper / recall 包

- **`PreferenceSection` / `LongTermMemorySection`**：
  - `user_preferences.renderedText` 注入 `instruction_memory`
  - `sku_history` 与 `analysis_insights` 注入 `long_term_memory`
  - 空 section 跳过；不渲染 `insights.vector` / `insights.fulltext`

- **`ForkChildSummarizationPort`**：
  - 正常路径返回 summary
  - 失败上报 circuit breaker
  - child 工具集为空

- **`SessionMemoryService`**：
  - 阈值计算正确性（重算场景）
  - 异步提交不阻塞
  - 断路器降级
  - MySQL + Redis 写入顺序

- **`SubagentRunner`**：
  - child runtime 字段继承表
  - ephemeral store 不绑 TranscriptPort
  - 工具集裁剪
  - child 中间消息不写 message 表

- **`SkillLoader` / `SkillToolRegistrar`**：
  - SKILL.md 格式校验
  - 启动期同步幂等性
  - 工具描述符正确性

- **`PlanModeController`**：
  - 模式切换
  - 草拟计划保留
  - 重复 enter 拒绝

- **`PreCompactSummaryHook`**：
  - metadata 注入正确
  - 非 PRE_COMPACT 事件忽略

### 16.2 集成层（Testcontainers）

- **全栈回合**：Testcontainers MySQL + Redis + Qdrant + MinIO + mock LLM
  - user → agent → loop → model mock → tool calls → final text
  - 验证 prompt 含召回片段、session memory 注入、skill 加载

- **记忆召回**：上传素材包 → 注入分析结论 → 用户提问 → 验证 prompt 含召回片段

- **子 Agent**：验证 child 工具集裁剪 + 递归禁用 + 父链不被污染

- **压缩策略分层**：
  - cheap pipeline 必跑
  - Session Memory 阈值触发
  - auto compact 仅在 cheap + session memory 都不够时触发

- **Session Memory 重算**：构造「上次提取已覆盖 seq=50」的会话，重新进入后从 seq=51 开始算增量，不重复处理 1-50

- **多回合累积**：跑 10 回合，验证 session memory 累积不超过阈值上限；阈值触发后异步提取

- **Auto compact 兜底**：mock LLM 失败 3 次 → 验证回退确定性优先级裁剪

- **Skill 端到端**：写 SKILL.md → 启动同步入表 → 调 `skill__<name>` → 验证 body 返回

### 16.3 ArchUnit 守护

- `com.pixflow.agent..` 不允许反向依赖未声明模块
- agent 依赖的所有模块在 `module-info` / `pom.xml` 显式列出
- `SubagentRunner` 不持有 `harness/loop` 之外的循环入口
- `DynamicPromptAssembler` 不持有 Spring AI `ChatMemory` / `Advisor` 类型
- `AgentOrchestrator` 不持有 `ModelClient` / `MessageStore` 实现（仅持有接口）
- `SkillToolRegistrar` 不在运行时修改 `ToolDescriptor` 集合（启动期一次性注册）
- `SessionMemoryService` 不直连 MySQL（经 Repository 抽象）
- `MemoryRecallPlanner` 只依赖 `MemoryService` 与 `com.pixflow.module.memory.context..`，不依赖 `preference..`、`skuhistory..`、`insight..`、`recall..` 等 module-memory 内部包

### 16.4 属性测试

- 任意「工具调用/无调用/CONTEXT_LIMIT/Session Memory 阈值」事件序列下：
  - session memory 累积单调（内容只追加/替换，不丢）
  - auto compact 频率 ≤ Session Memory 触发频率的 10%
  - 退出/重入会话：阈值重算正确
  - 任意「加载/调用/修改 skill」序列下，工具集一致

---

## 十七、暂不考虑

- **跨会话共享 Session Memory**：session memory 严格 per session，不跨会话拼接
- **Session Memory 的 LLM 二次推理（用 content + query 答问题）**：本期纯累加，不做"基于 memory 答 query"
- **Skill 的运行时热加载**：启动期一次性注册，skill 增删需重启
- **Skill 的 entry 工具**：skill 只是知识，不引入"skill 附带的可调用工具"
- **Skill 的子分类/标签**：本期 N 个 skill 平铺展示，不做分类
- **MCP server instructions 注入**：本期不接 MCP（与 harness/tools §18 一致）
- **Skill 的 TEAN（运营上传）来源**：本期只接 BUILTIN（仓库 SKILL.md），未来加
- **Subagent 的 fork 模式**（继承父历史）：本期不实现，child runtime 全部 clean 装配
- **Subagent 的 background / 跨进程子 Agent**：本期 child 同步跑在回合线程内
- **Fallback model / task budget**：loop.md §18 已明确不引入
- **Reactive compact 之外的 compact 触发源**：仅 token 超阈值与 CONTEXT_LIMIT，不引入"用户手动 /compact"外的触发
- **Provider context caching**：prompt 复用走 agent section cache，不依赖服务商
- **多租户 skill 隔离**：design.md §16 暂不考虑
- **Session Memory 的版本控制 / 回滚**：content 是单行累积，不维护历史版本
- **session memory 的差分合并**：本期是替换式（child 输出完整新 content），不做 diff 合并

---

## Revision Notes

- 2026-06-30 / Codex: 新建 `agent` 设计文档。确立 agent 为 `harness/loop` 之上的薄装配层：手写 think-act-observe 主循环之外的所有业务装配。引入 Skill 工具化（每个 skill = 一个 `skill__<name>` 工具，渐进披露 schema→prompt→body 三层）、Session Memory 累积提取（MySQL + Redis 缓存，**阈值不入 transcript**，重入会话时重算）、压缩策略三层金字塔（cheap pipeline / Session Memory / auto compact，主次关系为 Session Memory 主、auto compact 备）、SubagentRunner 单一类三处复用（`agent` 工具 / `SummarizationPort` / `SessionMemoryExtractor`）、`SummarizationPort` 与 `SessionMemoryPort` 两个 SPI 在 agent 层实现。明确 agent 不持 LLM 客户端、不写 message 表、不评估权限、不写 trace 的边界。

- 2026-06-30 / Codex: 完成 `pixflow-agent` Maven 模块落地。Wave 5 Agent 装配层全部 9 个子包（prompt / skill / memory / sessionmemory / subagent / summarization / planmode / hooks / config）按 Java 21 + Spring Boot 3.5.16 实现。73 个源文件 + 7 个单元测试类（含 ArchUnit 5 条断言），`mvn -pl pixflow-agent test` 28 测试方法全绿。`mvn -pl pixflow-app -am compile` BUILD SUCCESS，agent bean 装配不影响现有模块。详见 exec-plans/agent-module-implementation-plan.md。

- 2026-07-08 / Codex: 同步 `agent-memory-recall-refactor-plan.md` 的实现结果。agent 记忆召回边界从旧 provider/RRF 通道体系改为同步调用 `MemoryService.prepareContext(MemoryContextRequest)`；`PromptRuntimeContext` 持有 `MemoryContext`；Prompt 只注入 `user_preferences`、`sku_history`、`analysis_insights`；RRF、SKU 信号提取、混合检索、降级和 trace 均归 `module-memory`。`mvn -pl pixflow-agent -am test` BUILD SUCCESS，agent 模块 33 个测试全绿。

- 2026-07-08 / Codex: 同步 `context-autoconfiguration-refactor-plan.md` 的实现结果。agent 自动配置声明 `@AutoConfiguration(after = ContextAutoConfiguration.class)`，表达 `AgentOrchestrator` 依赖 context 基础 Bean 的装配顺序；`TokenEstimator`、`ContextBudgetService`、`ContextCompactionService` 的默认实现归 `pixflow-context` 发布，agent 不拥有这些默认 Bean。

- 2026-07-09 / Codex: 同步 `review-agent-correctness-refactor-plan.md` 的第一批实现结果。`AgentOrchestrator` 的回合入口改为从 `TranscriptPort.load(conversationId)` rehydrate 活动链并 seed per-turn `MessageStore`，按普通 USER 消息数计算目标 turn number，`streamNewTurn` / `continueTurn` 共享驱动路径并在异常路径归一化、flush。Session Memory 保存改为数据库层单调 upsert，异步提取 executor 由 Spring 托管且有界，断路器按 conversation 隔离。`SubagentRunner` 删除 prompt echo 成功路径，真实 child runtime 尚未接通时返回结构化 unavailable error；subagent tools 改为类型化参数解析和 timeout/error 归一化。Prompt fingerprint 的已落地 section 使用 UTF-8 输入，skill loader 和相关 hook 日志保留 throwable 诊断，agent migration 改为强约束版本。真实 child runtime、真实 message seq 增量提取、完整配置校验和 public record 契约仍按 ExecPlan 后续推进。
