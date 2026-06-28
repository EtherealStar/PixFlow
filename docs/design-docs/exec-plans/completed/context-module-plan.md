# 实现 harness/context 运行期工作内存与压缩编排

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/context` 模块本身及其与 `session`、`loop`、`conversation`、`hooks`、`agent`、`infra/storage`、`infra/cache` 的接线，不实现业务记忆、不实现任务态持久化、不实现模型客户端，也不把 MVP 的旧上下文实现作为本轮依据。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级的上下文管理核心。它能在每一轮对话中稳定重建消息链，把消息投影到模型窗口内，按预算自动裁剪超长结果，在必要时触发摘要式压缩，并为子 Agent fork 提供一致的上下文继承。用户能间接看到的效果是：长对话不再因为上下文膨胀而频繁中断，工具结果不会无限撑大提示词，`CONTEXT_LIMIT` 可以先压缩再重试，而不是直接把回合打断。

这个计划的验收重点不是“类是否存在”，而是可观察行为：运行上下文相关测试后，应能看到消息追加、投影保配对、结果外置、cheap pipeline 裁剪、摘要式压缩改写、`PRE_COMPACT` 指令注入和子 Agent 继承行为都按设计工作。

## Progress

- [x] (2026-06-28 17:10+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/harness/eval.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/base/common.md` 以及 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，确认 context 属于 Wave 2 harness 基础。
- [x] (2026-06-28 17:10+08:00) 已确认当前设计明确区分 `context`、`session`、`conversation`、`state` 四个边界，且 `message` 表唯一写者是 `session`。
- [x] (2026-06-28 17:10+08:00) 已确认 `context` 计划必须以生产级完整实现为目标，不按 MVP 缩减 cheap pipeline、destructive compaction、snapshot、fork 继承与大结果外置。
- [x] (2026-06-28 17:24+08:00) 新增 `pixflow-context` Maven 模块，并将其加入根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>`。
- [x] (2026-06-28 17:24+08:00) 已实现 context 的消息模型、`TranscriptPort` / `MessageChainCache` 接缝、投影、cheap pipeline、摘要式压缩编排、快照与 `CurrentModelContext`，并补齐核心单元测试。
- [x] (2026-06-28 17:24+08:00) 已运行 `mvn -pl pixflow-context -am test`，上下文核心测试通过；context 模块 9 个测试通过，前置 common/storage/cache 也通过。
- [ ] 在 `harness/loop`、`harness/session`、`module/conversation`、`agent`、`harness/hooks`、`infra/cache`、`infra/storage` 接线时补齐集成测试。

## Surprises & Discoveries

- Observation: 现有设计文档已经把 context 定义为“运行期工作内存”，而不是简单 transcript 容器。
  Evidence: `docs/design-docs/harness/context.md` 明确写出 `append-only 内存链`、`ContextSnapshot`、`cheap pipeline`、`destructive compaction` 和 `fork 继承`，说明实现必须覆盖完整上下文治理链路。

- Observation: `context` 的持久化语义不是“自己写 MySQL”，而是通过 `TranscriptPort` 倒置给 `session`。
  Evidence: `context.md` 与 `design.md` 都强调 `message` 表唯一写者是 `harness/session`，`context` 自身不直连 MySQL。

- Observation: 摘要式压缩的 LLM 调用不属于 `context` 自己，必须倒置给 `agent` 层。
  Evidence: `context.md` 规定 `SummarizationPort` 由 `agent` 实现，`context` 只负责编排与兜底，不持有模型客户端。

- Observation: 当前 `infra/storage` 已提供 `ObjectStorage` 与 `StorageKeys.toolResult(...)`，但尚未提供独立的共享 `ToolResultStorage` 类型。
  Evidence: `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/` 中只有纯 I/O 抽象；本轮在 context 内定义 `ToolResultExternalizer`，并提供基于 `ObjectStorage` 的薄适配实现，后续 tools/eval 若新增共享 `ToolResultStorage` 可替换该适配层。

- Observation: 当前实现先提供 `TokenEstimator` 接口与保守估算实现，未引入新的外部 jtokkit 依赖。
  Evidence: 本轮目标是使 `pixflow-context` 在现有依赖条件下可编译、可测试；后续接模型配置时可用同一接口替换为 jtokkit-backed 实现。

## Decision Log

- Decision: `harness/context` 新增为独立 Maven 模块 `pixflow-context`，包名使用 `com.pixflow.harness.context`。
  Rationale: 当前仓库已采用多模块分层，`context` 是 Wave 2 harness 基础件，独立模块能清楚表达它对 `common`、`infra/storage`、`infra/cache` 的依赖，以及它被 `session`、`loop`、`conversation`、`agent` 倒置接入的关系。
  Date/Author: 2026-06-28 / Codex

- Decision: `context` 必须同时实现确定性 cheap pipeline 和摘要式 destructive compaction。
  Rationale: 设计文档明确要求上下文治理分两层。只做滑窗裁剪会缺少长对话恢复能力，只做摘要压缩则会把回合稳定性押给 LLM。两层同时存在，才能兼顾可预测性和压缩能力。
  Date/Author: 2026-06-28 / Codex

- Decision: `context` 的 LLM 摘要通过 `SummarizationPort` 倒置给 `agent`，而不是在 `context` 内直接调用模型客户端。
  Rationale: 这样可以保持 `context` 对模型供应商、提示词模板和 fork 子 Agent 细节的解耦，同时让摘要失败时的断路兜底仍然属于 `context` 自己。
  Date/Author: 2026-06-28 / Codex

- Decision: `message` 表唯一写者继续由 `session` 承担，`conversation` 只做业务展示和只读查询。
  Rationale: 这条边界可以避免 transcript 写入路径分裂，也能让 context 保持可重建的运行期内存语义。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

本计划当前完成的是设计澄清和实现规划，不包含 Java 代码。最重要的目标是把 context 的职责钉死为“可重建、可压缩、可继承的运行期工作内存”，而不是消息列表、缓存层或持久化层。后续实现时，任何把 MySQL、LLM、业务记忆或任务态直接塞进 `pixflow-context` 的尝试，都应先回到本计划和 `docs/design-docs/harness/context.md` 重新评估边界。

## Context and Orientation

PixFlow 当前处于完整重写阶段。`docs/design-docs/design.md` 把 context 定义为执行循环中的上下文治理层，负责消息链、窗口治理、压缩、快照和子 Agent 继承。`docs/design-docs/harness/context.md` 是 context 的权威设计文档；`docs/design-docs/harness/hooks.md` 定义了 `PRE_COMPACT`、`POST_COMPACT` 和 `COMPACT_FAILED` 的接缝；`docs/design-docs/harness/state.md` 说明 state 只管任务态，不碰消息链；`docs/design-docs/harness/eval.md` 说明 context 不自己写 trace；`docs/design-docs/infra/storage.md` 说明大结果外置由共享存储层承担；`docs/design-docs/infra/ai.md` 说明 `CONTEXT_LIMIT` 由模型调用层上抛，context 再负责压缩和重试。

这里定义几个读者需要先理解的词。运行期工作内存是指对话执行时暂存和加工消息链的内存结构，它不是数据库，也不是最终 transcript。投影是指把完整消息链裁成模型能见的一段，同时保住 `tool call/result` 的配对。cheap pipeline 是完全确定性的裁剪链路，不依赖 LLM。destructive compaction 是会改写活动链的摘要压缩链路，它会调用 LLM，把旧历史压成边界、摘要和尾部消息。快照是每轮模型调用前的可回溯输入集合，包含系统提示、消息、工具可见视图和预算信息。fork 继承是指子 Agent 复用父轮已渲染的上下文状态，但在独立的临时消息链里继续运行。

实现时应优先阅读这些位置，并可用括号中的关键词快速定位设计文本。

在 `docs/design-docs/harness/context.md` 中搜索：`运行期工作内存`、`append-only`、`TranscriptPort`、`ContextProjector`、`jtokkit`、`cheap pipeline`、`SummarizationPort`、`ContextSnapshot`、`CurrentModelContext`、`tool call/result 配对`、`确定性兜底`。这些位置解释 context 的核心边界、主要类型和治理顺序。

在 `docs/design-docs/design.md` 中搜索：`Execution Loop`、`ContextSnapshot`、`Context Manager`、`destructive compaction`、`CONTEXT_LIMIT`、`fork`、`message` 表唯一写者。这些位置解释为什么 context 必须存在、它与主循环的关系，以及压缩和快照在总架构中的位置。

在 `docs/design-docs/harness/hooks.md` 中搜索：`PRE_COMPACT`、`compact.summaryInstructions`、`COMPACT_FAILED`、`harness/context`、`摘要式压缩各阶段`。这些位置解释 context 如何从 hooks 接收摘要指令，以及压缩失败时为何要发观察事件。

在 `docs/design-docs/harness/state.md` 中搜索：`不拥有业务表`、`CheckpointReadPort`、`任务态`、`消息链`。这些位置解释为什么 state 和 context 不能互相越界。

在 `docs/design-docs/harness/eval.md` 中搜索：`ContextSnapshot`、`零 eval 依赖`、`trace`、`模型调用前后落 trace`。这些位置解释 context 不自己记 trace，而是由 loop 侧经 eval SPI 记录。

在 `docs/design-docs/infra/storage.md` 中搜索：`共享 ToolResultStorage`、`纯 I/O`、`对象存储`、`tool-result`。这些位置解释大结果外置为什么应该交给共享存储层。

在 `docs/design-docs/infra/ai.md` 中搜索：`CONTEXT_LIMIT`、`上下文裁剪 / 压缩`、`recover`、`TokenUsage`。这些位置解释 context 为什么要接住模型超窗错误并触发压缩重试。

## Plan of Work

第一阶段建立模块边界。新增 `pixflow-context/` 目录和 `pom.xml`，把它接入根工程，并确保它只直接依赖 `pixflow-common`、`pixflow-infra-storage`、`pixflow-infra-cache` 的接缝能力以及测试所需依赖，不反向依赖 `session`、`loop`、`conversation`、`agent`、`hooks` 的实现代码。这个阶段的目标不是先做功能，而是先把依赖方向固定住。

第二阶段实现内部消息模型与存储接缝。创建 `model/Message.java`、`MessageRole.java`、`MessageMetadata.java`、`ToolResultReference.java`，以及 `store/MessageStore.java`、`store/TranscriptPort.java`。`MessageStore` 负责回合内 append-only 内存链，所有对外暴露的集合都必须防御性拷贝。`TranscriptPort` 只定义 append、load、replaceForCompaction 这类能力，由 `session` 在后续实现。这个阶段要保证“内存链能重建、外部不能改写内部链”。

第三阶段实现投影和预算。创建 `projection/ContextProjector.java` 与 `budget/TokenEstimator.java`、`budget/ContextBudgetService.java`。`ContextProjector` 只做确定性的滑窗投影和配对修复，不能理解业务语义。`ContextBudgetService` 负责 cheap pipeline：大结果外置、截断投影、microcompact 和 token 预算估算。这里的核心不是压得最狠，而是让行为稳定、可测、不会破坏 provider 协议。

第四阶段实现压缩编排。创建 `compaction/ContextCompactionService.java`、`SummarizationPort.java`、`CompactionTrigger.java`、`CompactionConfig.java`、`CompactionResult.java`。`ContextCompactionService` 必须同时支持 `MICRO`、`AUTO`、`MANUAL`、`REACTIVE` 四类触发，并在 `SummarizationPort` 缺失或连续失败时回退到确定性裁剪。这个阶段要把“可以不调用 LLM 也能继续工作”做实。

第五阶段实现快照和运行时 holder。创建 `snapshot/PreparedContext.java`、`snapshot/ContextSnapshot.java`、`engine/ContextEngine.java`、`runtime/CurrentModelContext.java`。`ContextEngine` 负责把消息链、预算结果和调用方传入的 system prompt、工具可见视图组装成快照，并把最近一次快照记录到 `CurrentModelContext`。这个阶段要确保子 Agent fork 可以继承父轮已渲染状态，而不是重新拼一遍 prompt。

第六阶段实现与 hooks、session、conversation、agent、infra/cache、infra/storage 的接线测试。`PRE_COMPACT` 的 metadata 必须进入摘要请求，`POST_COMPACT` 和 `COMPACT_FAILED` 必须在合适阶段发出，`session` 必须成为 `message` 表唯一写者，`conversation` 必须只能读和触发 append，`agent` 必须承担 `SummarizationPort` 实现，`infra/cache` 必须只做可丢可重建的热缓存，`infra/storage` 必须承担共享大结果外置。这个阶段的目标是把边界从文档变成可观察测试。

第七阶段补齐单元测试和属性测试。测试必须覆盖消息不可变、配对保留、工具结果外置、rehydrate、cache 命中和失效、cheap pipeline、destructive compaction、断路兜底、子 Agent fork 和 `ContextSnapshot` 持有行为。再补一个小型装配测试，证明 `ContextEngine` 与 `CurrentModelContext` 的协作不会丢上下文。

## Concrete Steps

从仓库根目录开始执行：

    cd D:\study\PixFlow
    git status --short

如果当前工作区已有与 context 无关的用户改动，不要回滚，只在本计划涉及的文件范围内继续推进。

确认上下文相关设计文档位置：

    cd D:\study\PixFlow
    rg -n "ContextSnapshot|TranscriptPort|SummarizationPort|cheap pipeline|destructive compaction" docs\design-docs\harness\context.md

确认依赖波次与模块顺序：

    cd D:\study\PixFlow
    rg -n "Wave 2 harness基础|harness/context|context" docs\design-docs\exec-plans\module-dependency-dag-plan.md

实现模块后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-context -am test

成功时应看到 Maven `BUILD SUCCESS`，并且上下文核心测试全部通过。

后续接线阶段运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-context,pixflow-hooks,pixflow-permission -am test

如果 `session`、`agent`、`conversation`、`loop` 后续已拆成独立模块，再把对应模块一并加入命令。该命令应证明上下文的压缩、快照和 hooks 接缝都能稳定工作。

## Validation and Acceptance

本计划的验收标准是可观察行为，不是“编译过了”。

第一类验收是消息模型和不可变性。向 `MessageStore` 追加用户、assistant、tool_result、attachment 后，`currentMessages()` 返回的集合与元素都不能被外部修改，修改副本不会污染内部链。

第二类验收是投影配对。构造一段包含未闭合 `tool_result` 的消息链，`ContextProjector` 必须回退起点或丢弃尾部到不会产出孤立 `tool_result`，并且 `ATTACHMENT` 不应被投影器误处理。

第三类验收是 cheap pipeline。构造超长 `tool_result`，`ContextBudgetService` 应把它外置成引用加 preview，随后进行 snip 和 microcompact，最后给出可执行的 token 估算。

第四类验收是 rehydrate 与缓存。缓存命中时从缓存重建消息链，缓存 miss 时从 `TranscriptPort.load` 重建，压缩后缓存必须失效或刷新；外置结果缺失时应保留 preview 并标记缺失，而不是直接失败。

第五类验收是 destructive compaction。AUTO、MANUAL、REACTIVE 三种触发都应生成边界、摘要和 tail 结构；连续失败后应走确定性兜底并派发 `COMPACT_FAILED`。

第六类验收是 hooks 接缝。`PRE_COMPACT` 的 `compact.summaryInstructions` 必须进入 `SummarizationPort` 请求；`POST_COMPACT` 和 `COMPACT_FAILED` 必须在压缩成功、失败或断路时按设计发出。

第七类验收是子 Agent fork。fork 后应继承父轮已渲染的 system prompt 和消息链，但使用独立的临时 `MessageStore`，中间消息不能写回父链或 `message` 表。

## Idempotence and Recovery

本计划推荐的实现是 additive changes。新增 `pixflow-context` 模块、添加纯模型、添加预算与压缩服务、再补测试，这些步骤都可以重复运行，不应该破坏已有数据。

如果 `MessageStore`、`TranscriptPort` 或 `SummarizationPort` 相关测试失败，先把失败缩小到纯内存实现，再看是否是边界或配对规则的问题。不要先引入 MySQL、Redis 或 LLM 客户端来掩盖问题。

如果新模块过程中根 `pom.xml` 被改乱，只保留 `pixflow-context` 模块和它的 dependencyManagement 条目，其他无关改动不要回滚用户现有工作。

如果后续发现 `hooks` 的 `PRE_COMPACT` metadata 命名或 `common` 的 `CONTEXT_LIMIT` 归一化细节与本文假设不一致，以当前 Java 源码和设计文档为准，并在本计划的 `Surprises & Discoveries` 与 `Decision Log` 中记录修正原因。

## Artifacts and Notes

本计划创建前已确认设计文档中的关键位置如下。

    在 `docs/design-docs/harness/context.md` 中，`TranscriptPort`、`SummarizationPort`、`ContextSnapshot`、`cheap pipeline`、`destructive compaction`、`CurrentModelContext` 是最关键的定位词。

    在 `docs/design-docs/design.md` 中，`Execution Loop`、`Context Manager`、`CONTEXT_LIMIT`、`message` 表唯一写者、`fork` 是最关键的定位词。

    在 `docs/design-docs/harness/hooks.md` 中，`PRE_COMPACT`、`compact.summaryInstructions`、`COMPACT_FAILED` 是最关键的定位词。

    在 `docs/design-docs/harness/state.md` 中，`不拥有业务表`、`任务态`、`消息链` 是最关键的定位词。

    在 `docs/design-docs/harness/eval.md` 中，`ContextSnapshot` 和 `零 eval 依赖` 是最关键的定位词。

## Interfaces and Dependencies

最终应存在以下 Maven 模块、包和 Java 类型。实际文件路径应位于项目根目录下的 `pixflow-context/src/main/java/com/pixflow/harness/context/...`。

在根 `pom.xml` 中，新增模块和 dependencyManagement 条目：

    <module>pixflow-context</module>

    <dependency>
        <groupId>com.pixflow</groupId>
        <artifactId>pixflow-context</artifactId>
        <version>${project.version}</version>
    </dependency>

在 `pixflow-context/pom.xml` 中，至少依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-infra-storage
    com.pixflow:pixflow-infra-cache
    org.springframework.boot:spring-boot-starter-test (test scope)

在 `com.pixflow.harness.context.model` 中定义：

    MessageRole
    Message
    MessageMetadata
    ToolResultReference

在 `com.pixflow.harness.context.store` 中定义：

    MessageStore
    TranscriptPort

在 `com.pixflow.harness.context.projection` 中定义：

    ContextProjector

在 `com.pixflow.harness.context.budget` 中定义：

    TokenEstimator
    ContextBudgetService

在 `com.pixflow.harness.context.compaction` 中定义：

    SummarizationPort
    CompactionTrigger
    CompactionConfig
    CompactionResult
    ContextCompactionService

在 `com.pixflow.harness.context.snapshot` 中定义：

    PreparedContext
    ContextSnapshot

在 `com.pixflow.harness.context.engine` 中定义：

    ContextEngine

在 `com.pixflow.harness.context.runtime` 中定义：

    CurrentModelContext

`context` 只直接依赖 `common`、`infra/storage`、`infra/cache`。它不能直接依赖 `session`、`loop`、`conversation`、`hooks`、`agent`、`state` 的实现代码。`session`、`agent`、`infra/cache`、`infra/storage` 通过实现 `TranscriptPort`、`SummarizationPort`、缓存接缝和共享存储接缝倒置接入 `context`。

## Change Notes

2026-06-28 / Codex: 创建 `harness/context` 中文 ExecPlan 初稿，依据当前设计文档把 context 的职责收束为运行期工作内存、投影/预算/压缩编排、快照和 fork 继承，并补充设计定位关键词与实现边界。
