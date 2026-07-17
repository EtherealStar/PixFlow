# 重构 Agent SSE 实时渲染为 Vue Timeline

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树和这份计划，就应能完成 Agent SSE 实时 timeline 渲染重构，并知道为什么这样做。

## Purpose / Big Picture

用户发送一条消息后，前端必须实时显示 Agent 主循环中的所有可见过程：模型文本增量、工具调用准备、工具执行中、工具结果、下一轮模型继续输出，以及最终完成状态。现在 PixFlow Web 只维护一个 `deltas` 字符串，并且 `tool_call_ready`、`tool_started`、`tool_result` 主要进入 `console.debug`，所以当模型输出工具调用或进入多轮工具循环时，用户会感觉“没有 AI 消息”或“SSE 一直等到超时”。完成本计划后，`completed` 只表示整个用户回合结束；消息内容和工具过程都直接由 SSE 事件实时驱动 Vue timeline 渲染。

本计划不照搬 CLI 的 checkpoint / static scrollback 提交机制。参考文档 `docs/references/cli-message-rendering-architecture.md` 的关键思想是“运行中 UI 消费 AgentEvent，而不是从 transcript 反推”；CLI 需要 checkpoint 是因为终端动态区和静态 scrollback 会互相覆盖。Vue 前端没有这个约束，因此本计划采用更直接的机制：`SSE AgentEvent -> useAgentTurn reducer -> StreamingTurnState.timeline -> MessageStream`。不要为了迁移式安全保留旧 `deltas` 渲染路径；实现完成后旧的单字符串 `deltas` API 应删除或只在测试迁移期间短暂存在，最终代码不能同时维护两套 UI 状态源。

## Progress

- [x] (2026-07-09 23:20+08:00) 阅读 `PLANS.md`，确认 ExecPlan 必须自包含、包含 Progress / Surprises / Decision Log / Outcomes，并且写入 Markdown 文件时不需要外层三反引号。
- [x] (2026-07-09 23:22+08:00) 复核 `docs/design-docs/exec-plans/`，当前没有 active 计划文件，只有 `completed/` 目录；本计划作为新的 active plan 写在该目录直属位置。
- [x] (2026-07-09 23:24+08:00) 阅读 `docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/stores-runtime.md`、`docs/design-docs/frontend/chat.md`，确认已补充“Agent Timeline 渲染语义”“Agent Streaming Timeline”“Agent 消息流渲染”等设计内容。
- [x] (2026-07-09 23:26+08:00) 阅读当前关键代码路径：`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java`、`pixflow-loop/src/main/java/com/pixflow/harness/loop/stream/ModelStreamConsumer.java`、`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java`、`pixflow-web/src/runtime/useAgentTurn.ts`、`pixflow-web/src/components/chat/MessageStream.vue`、`pixflow-web/src/types/agent.ts`，确认当前 Web 只渲染用户消息和单个 assistant delta 字符串，工具事件没有进入可见 timeline。
- [x] (2026-07-09 23:30+08:00) 新建本 ExecPlan，明确最终目标是删除旧 `deltas` UI 状态源，改为 Vue 响应式 timeline；不引入 CLI checkpoint，不保留双路径。
- [x] (2026-07-10 00:07+08:00) 实施后端事件归属字段：`AgentLoop` 每次模型调用生成 `assistantCallId`、`modelTurnIndex`、`iteration`、`traceId`、`turnNo`，并传给 assistant delta/completed、tool lifecycle、transition、completed 事件；`ModelStreamConsumer` consume 签名已接收事件 metadata。
- [x] (2026-07-10 00:07+08:00) 实施前端 timeline 类型、reducer 和状态机：`types/agent.ts` 新增 `TimelineItem` 系列类型，`useAgentTurn.ts` 用 `timeline` 替换运行中 `deltas`，工具事件进入 reducer。
- [x] (2026-07-10 00:07+08:00) 实施 `MessageStream` 渲染：props 改为 `timeline`，assistant 文本、工具 queued/running/completed/error、流内 error 均可见；移除了工具事件仅 `console.debug` 的处理路径。
- [x] (2026-07-10 00:07+08:00) 更新测试：前端覆盖纯文本流、工具调用 lifecycle + 下一轮 assistant、断流保留 timeline；后端覆盖 `SseAgentEventSink` 归属字段投影和 `ModelStreamConsumer` 新签名。
- [x] (2026-07-10 00:07+08:00) 运行前后端相关验证命令，并把结果写入 `Outcomes & Retrospective`。

## Surprises & Discoveries

- Observation: 前端文档索引里提到根级 `docs/design-docs/api.md`，但当前工作树中该文件显示已删除或移动；本计划不依赖该文件，SSE 设计以 `docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/stores-runtime.md`、`docs/design-docs/frontend/chat.md`、`docs/design-docs/harness/loop.md` 和代码为准。
  Evidence: `git status --short` 显示 `D docs/design-docs/api.md`；`Get-ChildItem docs/design-docs` 未列出 `api.md`。

- Observation: `docs/design-docs/frontend/` 在当前工作树里显示为未跟踪目录，但这些文件已经被项目索引用作前端模块文档。本计划仍以这些文档为设计依据，后续提交时应确保它们被纳入版本控制。
  Evidence: `git status --short` 显示 `?? docs/design-docs/frontend/`。

- Observation: 现有 Web 端 `MessageStream.vue` 只接收 `deltas: string` 和 `userMessages`，模板只渲染用户泡泡和一个 assistant 泡泡；`useAgentTurn.ts` 对 `tool_call_ready`、`tool_started`、`tool_result` 的处理基本是 `console.debug`。
  Evidence: `pixflow-web/src/components/chat/MessageStream.vue` 当前 `defineProps` 包含 `deltas: string`；`pixflow-web/src/runtime/useAgentTurn.ts` 中工具事件分支不更新可渲染状态。

- Observation: 后端已经有 `assistant_delta`、`assistant_message_completed`、`tool_call_ready`、`tool_started`、`tool_result`、`transition`、`completed` 这些事件，但事件 payload 缺少稳定归属字段，前端无法可靠判断某个 tool/result/delta 属于哪一次模型调用。
  Evidence: `SseAgentEventSink.toPayload` 当前只投影各事件业务字段；`AgentLoop` 的每次 iteration 没有显式生成 `assistantCallId` 或 `modelTurnIndex` 并传入所有事件。

- Observation: 当前工作树里 `mvn -pl pixflow-loop -am test` 会先在上游 `pixflow-context` 模块失败，导致 reactor 没跑到 `pixflow-loop`；失败与本计划触达文件无关。
  Evidence: `ContextBudgetServiceTest.externalizesLargeToolResultAndMicrocompactsOldResults` 和 `ContextProjectorTest.backsUpWindowStartToKeepToolPair` 失败；`mvn -pl pixflow-loop -am -DskipTests compile` 成功，单跑 `ModelStreamConsumerTest` 成功。

- Observation: 单跑 `AgentLoopContinuationDecisionTest` 时，当前工作树已有的 `ChatRequestShapeAssert.assertContainsToolProtocol` 失败，第二轮模型请求只剩 `[USER]`，缺少 assistant/tool pair；本计划新增的事件 metadata 断言在该断言之前已通过，但该测试类整体仍失败。
  Evidence: `mvn -pl pixflow-loop -Dtest=AgentLoopContinuationDecisionTest test` 报 `NoSuchElementException` 于 `AgentLoopContinuationDecisionTest.java:171`。

## Decision Log

- Decision: Web 端不实现 CLI 的 checkpoint / coordinator / static commit 机制。
  Rationale: CLI 需要 checkpoint 是因为终端动态区和静态 scrollback 不能自然响应式更新；Vue 可以直接更新同一个 timeline item。引入 checkpoint 会把 Web 实现复杂化，并把“completed 才提交”的误解重新带回来。
  Date/Author: 2026-07-09 / Codex

- Decision: 删除旧 `deltas` UI 状态源，最终只保留 `StreamingTurnState.timeline`。
  Rationale: 同时维护 `deltas` 和 `timeline` 会让 assistant 文本出现两个事实源，容易造成重复渲染、错序和测试不稳定。用户明确要求不要为了迁移式安全保留旧代码，本计划按开发期项目处理，直接替换旧路径。
  Date/Author: 2026-07-09 / Codex

- Decision: `completed` 只表示整个用户回合结束，不再承担“开始显示 AI 消息”或“提交 assistant 消息”的 UI 语义。
  Rationale: 主循环设计允许一条用户消息内有多次模型调用和工具调用。用户可见过程必须从 `assistant_delta` 和 `tool_*` 事件开始实时显示；等 `completed` 才显示会在工具调用回合中造成长时间空白。
  Date/Author: 2026-07-09 / Codex

- Decision: 后端事件必须补 `assistantCallId`、`modelTurnIndex`、`iteration` 这类归属字段，前端短期可本地兜底但最终不能依赖猜测。
  Rationale: 多轮工具调用时，同一用户回合内会出现多段 assistant 输出和多个工具结果。没有归属字段，前端 reducer 只能把事件归到“当前最后一个 item”，在并发工具结果、重试和多轮模型调用时会出错。
  Date/Author: 2026-07-09 / Codex

- Decision: 本计划不处理“模型双重 retry”问题，除非实现时发现它直接阻断本重构的验证。
  Rationale: 双重 retry 可能导致 SSE 超时，但它属于 infra/ai 与 loop 的模型调用恢复策略；本计划聚焦“已经产生的 SSE 事件如何实时渲染”。若后续需要修复双重 retry，应另起或扩展 loop/infra-ai 计划，避免混淆验收范围。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

已实施代码。后端新增并传播的 SSE 归属字段包括：`assistantCallId`、`modelTurnIndex`、`iteration`、`traceId`、`turnNo`。`SseAgentEventSink` 现在把这些字段投影到 assistant delta/completed、tool lifecycle、transition、completed payload；`tool_result` 额外投影 `error`，前端不再需要解析结果文本判断失败。

前端删除了运行中 UI 的 `deltas` 状态源：`useAgentTurn` 对外暴露 `timeline`，`useChatSession` 暴露 `streamTimeline`，`ChatPage` 向 `MessageStream` 传入 timeline。工具事件不再只进入 `console.debug`，而是归约为可渲染 tool item。

已通过验证：

    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck
    mvn -pl pixflow-loop -am -DskipTests compile
    mvn -pl pixflow-conversation -Dtest=SseAgentEventSinkTest test
    mvn -pl pixflow-loop -Dtest=ModelStreamConsumerTest test

未完全通过的验证：

    mvn -pl pixflow-loop -am test

该命令被上游 `pixflow-context` 当前失败阻断，失败测试为 `ContextBudgetServiceTest.externalizesLargeToolResultAndMicrocompactsOldResults` 与 `ContextProjectorTest.backsUpWindowStartToKeepToolPair`。此外，单跑 `AgentLoopContinuationDecisionTest` 仍失败在既有 `ChatRequestShapeAssert.assertContainsToolProtocol`，原因是当前 context 投影生成的第二轮请求缺少 assistant/tool pair。该问题不属于本计划的 SSE timeline 渲染改动，后续应由 context/loop 协议测试单独处理。

尚未做浏览器手工验收；本轮仅完成代码级和自动化验证。

## Context and Orientation

PixFlow 的 Agent 对话链路分四层。第一层是后端 Agent 主循环，核心文件是 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java`。这里的“主循环”是一次用户消息内的显式 `while` 循环：构造模型请求，消费模型流，解析工具调用，执行工具，把工具结果回填，再决定继续或结束。`completed` 只有在模型本轮没有工具调用时才会发出，表示整个用户回合结束。

第二层是后端 SSE 投影，核心文件是 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java`。它把 loop 里的 `AgentEvent` 转成前端可消费的 SSE event name 和 JSON payload。`MessageController.submit` 创建 `SseEmitter` 并把 `SseAgentEventSink` 传给 turn runner。

第三层是前端传输与状态机。`pixflow-web/src/transport/sse.ts` 负责用 `fetch + ReadableStream` 解析 SSE 字节流。`pixflow-web/src/runtime/useAgentTurn.ts` 负责 Agent 回合状态机，目前它维护 `deltas` 字符串和 `phase` 摘要。这个文件必须改成 reducer：每个 SSE 事件都进入纯粹的事件归约函数，更新 `StreamingTurnState.timeline`。

第四层是前端 Chat 渲染。`pixflow-web/src/components/chat/MessageStream.vue` 当前只渲染用户消息和一个 assistant 文本泡泡。完成本计划后它应渲染 `TimelineItem[]`：assistant item 展示正在流式生成或已完成的文本；tool item 展示 queued、running、completed、error 四种状态和结果摘要；transition 默认可不作为消息展示。

术语说明：

“SSE” 是 Server-Sent Events，后端保持一个 HTTP 响应打开，不断发送 `event: name` 和 `data: json` 块。这里它用于把 Agent 回合中的文本和工具事件推到浏览器。

“timeline” 是前端运行中的可见消息列表。它不是数据库消息表，也不是模型上下文。它只是用户正在看的 UI 状态，来自 SSE event stream。

“归属字段” 是每个事件用于说明它属于哪次模型调用的字段。本计划使用 `assistantCallId` 表示一次模型调用产生的 assistant 片段的稳定 ID，使用 `modelTurnIndex` 表示同一用户回合内第几次模型调用，使用 `iteration` 表示 loop 内部迭代计数。

“checkpoint” 是参考 CLI 中把动态内容提交到终端 scrollback 的机制。Web 不使用它。

## Design Search Keywords

后续执行者可以用以下关键词在设计文档中定位相关设计。所有命令从仓库根目录 `D:\study\PixFlow` 执行。

查前端 SSE 和 timeline 协议：

    rg -n "Agent Timeline|Agent Streaming Timeline|SSE AgentEvent|assistant_delta|tool_call_ready|completed.*回合|StreamingTurnState|timeline" docs/design-docs/frontend docs/design-docs/web.md

查 Chat 页面渲染边界：

    rg -n "Agent 消息流渲染|MessageStream|turn.timeline|deltas|工具调用过程必须可见|SSE reducer" docs/design-docs/frontend/chat.md docs/design-docs/frontend/stores-runtime.md

查后端主循环设计：

    rg -n "AgentLoop|主循环|while 循环|续轮判定|TOOL_USE|completed|tool_calls|无 maxTurns" docs/design-docs/harness/loop.md

查参考项目为什么有 checkpoint：

    rg -n "checkpoint|CliStreamUiState|TerminalOutputCoordinator|assistant_call_id|model_turn_index|tool_result" docs/references/cli-message-rendering-architecture.md

这些关键词也是验收文档是否同步的依据。若实现过程中调整了事件名、字段名或 UI 机制，必须同步更新这些关键词所在文档，避免设计文档和代码再次分叉。

## Plan of Work

第一步改后端事件归属，而不是先改 UI。`AgentLoop` 每次进入模型调用前已经调用 `state.incrementIteration()`，这就是生成 `modelTurnIndex` 的稳定位置。在 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 的 `while (true)` 中，紧接 `state.incrementIteration()` 后生成：

    int modelTurnIndex = state.iterationCount();
    String assistantCallId = state.traceId() + ":assistant:" + modelTurnIndex;

如果担心 traceId 中有特殊字符，可以用 `UUID.nameUUIDFromBytes` 或普通 UUID；关键是同一次模型调用内稳定，不需要跨会话永久稳定。构造一个只用于 SSE 的 metadata map，包含 `assistantCallId`、`modelTurnIndex`、`iteration`、`traceId`、`turnNo`。不要把这些 UI 归属字段写入 `RuntimeState.metadata()`，因为它们不是模型上下文事实。

修改 `ModelStreamConsumer.consume` 的签名，让它接收这份事件 metadata：

    public ModelOutcome consume(Flux<ChatStreamEvent> flux,
                                AgentEventSink sink,
                                RuntimeState state,
                                Map<String, Object> eventMetadata)

`assistant_delta` 和 `RATE_LIMIT_RETRY` transition 都使用 `eventMetadata` 合并必要字段后 emit。不要继续只传 `state.metadata()`，否则前端拿不到当前模型调用归属。

修改 `AgentLoop` 中 `assistantCompleted`、`transition`、`toolCallReady`、`toolStarted`、`toolResult`、`completed` 的 emit metadata。工具事件必须带同一轮的 `assistantCallId` 和 `modelTurnIndex`，并且工具事件 payload 必须保留 `toolCallId`。`completed` 也可以带最后一个 `assistantCallId`，但前端只应把它当 turn 终态。

修改 `SseAgentEventSink.toPayload`，把 metadata 中的归属字段投影到每个相关 payload。推荐每个事件 payload 都包含：

    assistantCallId
    modelTurnIndex
    iteration
    traceId
    turnNo

对于 `tool_*` 事件，还必须包含：

    toolCallId
    toolName

如果某个字段缺失，sink 不应伪造业务意义错误的默认值。可以为空字符串，但测试应覆盖正常路径字段存在。

第二步改前端类型。修改 `pixflow-web/src/types/agent.ts`，删除以单字符串 `deltas` 为中心的公开类型假设，新增 `TimelineItem`、`AssistantTimelineItem`、`ToolTimelineItem`、`TransitionTimelineItem`、`ErrorTimelineItem`。`AgentEventName` 必须包含 `assistant_delta`、`assistant_message_completed`、`tool_call_ready`、`tool_started`、`tool_result`、`transition`、`completed`、`error`。对应 payload 类型增加可选归属字段，短期兼容旧后端时字段可以标为 optional，但 reducer 内应集中处理 fallback。

第三步重写 `pixflow-web/src/runtime/useAgentTurn.ts`。删除 `const deltas = ref<string>('')` 作为对外 UI 状态，新增：

    const timeline = ref<TimelineItem[]>([])

新增 `reduceAgentEvent(state, event)` 或等价的局部函数。它必须只根据事件更新 timeline，不直接操作 DOM，不 import 组件。规则如下：`assistant_delta` 找到当前 assistant item 追加文本，没有则创建；`assistant_message_completed` 标记当前 assistant completed；`tool_call_ready` 创建 queued tool item；`tool_started` 改 running；`tool_result` 改 completed 或 error 并填 result；`transition: TOOL_USE` 让下一次 assistant_delta 创建新的 assistant item；`completed` 标记 turn 完成并解锁；`error` 保留已有 timeline 并标记错误。原先工具事件的 `console.debug` 不能作为唯一处理逻辑保留。

第四步改 `pixflow-web/src/components/chat/MessageStream.vue`。把 props 从 `deltas: string` 改为 `timeline: TimelineItem[]`，保留 `userMessages`。模板按 timeline item 类型渲染。可以先在同一文件内用简单分支渲染，不必过早抽象多个组件；但工具 item 必须有可见 UI：工具名、状态、结果摘要。状态文案不要写成教学说明，使用简洁标签即可，例如“等待执行”“执行中”“完成”“失败”。如果项目已有图标库，按前端设计要求使用已有图标或 lucide 图标；若当前 chat 组件没有图标依赖，本计划允许先用纯文本和 Tailwind token 实现，后续再视觉优化。

第五步改调用方。`pixflow-web/src/runtime/useChatSession.ts` 当前有 `streamDeltas` 计算值，`pixflow-web/src/pages/ChatPage.vue` 和相关测试可能传 `deltas` 给 `MessageStream`。这些必须改成 `streamTimeline` 或 `timeline`。不要同时传 `deltas` 和 `timeline`。如果某个测试还断言 `deltas.value`，改为断言 timeline 中 assistant item 的 `text`。

第六步补测试。前端至少补三类测试：一是 `useAgentTurn` reducer 接收 `assistant_delta -> assistant_message_completed -> completed` 后 timeline 中有一条 completed assistant item；二是 `tool_call_ready -> tool_started -> tool_result -> assistant_delta -> completed` 后工具 item 状态和 assistant 文本都可见；三是 SSE close 前未收到 `completed` 时保留已有 timeline 并进入 error。后端至少补 `SseAgentEventSinkTest` 或 `AgentLoop` 事件测试，证明 `assistant_delta`、`tool_call_ready`、`tool_result`、`completed` payload 带 `assistantCallId` 和 `modelTurnIndex`。

第七步更新设计文档和收尾。本计划开始前已经更新了 `docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/stores-runtime.md`、`docs/design-docs/frontend/chat.md`。实施过程中若字段名或状态名变化，必须同步这三份文档。不要修改无关文档和未触达模块。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认当前代码中的旧路径：

    rg -n "deltas|tool_call_ready|tool_started|tool_result|console\\.debug|MessageStream|assistant_message_completed" pixflow-web/src
    rg -n "AgentEvent\\.delta|assistantCompleted|toolCallReady|toolStarted|toolResult|SseAgentEventSink|ModelStreamConsumer" pixflow-loop/src/main/java pixflow-conversation/src/main/java

预期会看到 `useAgentTurn.ts` 维护 `deltas`，`MessageStream.vue` 接收 `deltas`，工具事件只 debug 或没有进入可渲染状态。后端会看到 `AgentLoop` emit 事件时没有 `assistantCallId` / `modelTurnIndex`。

实现后端归属字段后运行：

    mvn -pl pixflow-loop,pixflow-conversation -am -DskipTests compile

预期输出中 `pixflow-loop` 和 `pixflow-conversation` 所在 reactor 模块编译成功，末尾出现 `BUILD SUCCESS`。如果只改了测试可先跳过编译，但最终必须运行测试命令。

实现前端类型和 reducer 后运行：

    pnpm --dir pixflow-web test
    bun typecheck

如果项目当前实际使用的前端命令不是上面两个，执行者应先看 `pixflow-web/package.json` 的 `scripts`，使用其中现有的 `test` 和 `typecheck`。成功时应看到测试通过和 TypeScript 类型检查无错误。

完成全部实现后运行：

    mvn -pl pixflow-loop,pixflow-conversation -am test
    pnpm --dir pixflow-web test
    bun typecheck

若 Maven 聚合测试被无关模块阻断，只记录阻断模块、错误名和为什么它不属于本计划；但 `pixflow-loop`、`pixflow-conversation`、`pixflow-web` 的相关测试必须能单独通过。

收尾检查：

    rg -n "deltas" pixflow-web/src
    rg -n "console\\.debug\\('\\[agent\\] tool_|console\\.debug\\(\"\\[agent\\] tool_" pixflow-web/src/runtime/useAgentTurn.ts
    rg -n "assistantCallId|modelTurnIndex|StreamingTurnState|TimelineItem|Agent Timeline|Agent Streaming Timeline" pixflow-web/src docs/design-docs/frontend pixflow-loop/src/main/java pixflow-conversation/src/main/java

第一条不应命中生产 UI 状态源；如果命中测试或历史注释，必须确认不会参与运行中渲染。第二条不应显示工具事件只 debug 的路径。第三条应命中新机制实现和设计文档。

## Validation and Acceptance

最小可验证行为一：纯文本回复实时显示。用测试 fake SSE 依次发送：

    event: assistant_delta
    data: {"text":"你","assistantCallId":"a1","modelTurnIndex":1}

    event: assistant_delta
    data: {"text":"好","assistantCallId":"a1","modelTurnIndex":1}

    event: assistant_message_completed
    data: {"finalText":"你好","messageId":"m1","assistantCallId":"a1","modelTurnIndex":1}

    event: completed
    data: {"finalText":"你好","traceId":"t1","turnNo":1}

前端应在第一个 delta 后立即显示 assistant item 文本“你”，第二个 delta 后显示“你好”，收到 `completed` 后只把回合 phase 置为 completed，不新增重复 assistant 泡泡。

最小可验证行为二：工具调用过程实时显示。用测试 fake SSE 依次发送：

    event: tool_call_ready
    data: {"toolName":"search","toolCallId":"tc1","toolInput":{"q":"sku"},"assistantCallId":"a1","modelTurnIndex":1}

    event: tool_started
    data: {"toolCallId":"tc1","toolName":"search","assistantCallId":"a1","modelTurnIndex":1}

    event: tool_result
    data: {"toolCallId":"tc1","toolName":"search","content":"found 3 items","externalized":false,"assistantCallId":"a1","modelTurnIndex":1}

    event: assistant_delta
    data: {"text":"已找到 3 个商品。","assistantCallId":"a2","modelTurnIndex":2}

    event: completed
    data: {"finalText":"已找到 3 个商品。","traceId":"t1","turnNo":1}

前端应先显示一个 search 工具 item，从 queued 到 running 到 completed，再显示下一段 assistant 文本。用户不应看到空白等待直到 `completed`。

最小可验证行为三：多轮工具调用不会混淆归属。构造两个 assistantCallId：`a1` 产生工具 `tc1`，`a2` 产生最终文本。测试断言 `tc1` 的 tool item 带 `assistantCallId=a1`，最终文本 item 带 `assistantCallId=a2`。这证明 reducer 不是简单把所有事件塞给最后一个 item。

错误行为验收：如果 SSE 在已有 assistant delta 后断开且没有收到 `completed`，前端应保留已经显示的文本，并把 phase 置为 `error`，错误码为 `STREAM_INTERRUPTED`。不得清空 timeline。

后端验收：运行后端测试时，`SseAgentEventSink` 的 `assistant_delta`、`tool_call_ready`、`tool_started`、`tool_result`、`completed` payload 都能包含 `assistantCallId`、`modelTurnIndex`、`traceId`、`turnNo`。`AgentLoop` 中每次 `state.incrementIteration()` 后生成的字段必须随该轮所有事件一致。

手工验收：启动后端和前端后，在浏览器进入 `/chat/new`，发送一条会触发工具的提示。观察消息流：工具卡片应在工具开始时出现，工具完成时状态改变，随后 assistant 继续输出，最后输入框解锁。浏览器开发者工具 Network 中该 SSE 请求应在 `completed` 后正常结束。

## Idempotence and Recovery

本计划的代码修改可以分阶段重复执行。若前端类型修改后测试大量失败，不要恢复 `deltas` 旧路径；应逐个调用方改成 timeline。若后端归属字段导致某些测试需要更新 fake event payload，应更新测试表达新协议，不要在生产代码中接受“无归属字段就静默归到最后一条”的长期行为。

如果中途只完成后端字段而前端尚未改完，浏览器旧 UI 仍可能只显示 `deltas`，这是中间状态，不是验收完成。继续执行前端 reducer 和 `MessageStream` 替换。

如果中途只完成前端 timeline 而后端尚未提供 `assistantCallId`，前端可以用本地递增序号兜底以便测试推进，但必须在同一计划后续阶段补后端字段，并删除“长期协议”意义上的 fallback 依赖。保留兼容分支只能用于防御旧响应，不得让新测试依赖它。

工作树可能已有大量用户或其他任务的改动。不要 revert 与本计划无关的文件。只编辑本计划列出的后端事件文件、前端 runtime/组件/类型/测试文件，以及已列出的前端设计文档。

## Artifacts and Notes

当前关键旧代码形态：

    pixflow-web/src/components/chat/MessageStream.vue:
        defineProps<{ deltas: string; userMessages?: ... }>()
        <ChatBubble v-if="hasAssistantText" role="assistant" :text="deltas" />

    pixflow-web/src/runtime/useAgentTurn.ts:
        const deltas = ref<string>('')
        case 'assistant_delta': deltas.value += p.text ?? ''
        case 'tool_call_ready': console.debug(...)
        case 'tool_started': console.debug(...)
        case 'tool_result': console.debug(...)

    pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java:
        while (true) {
            state.incrementIteration();
            ...
            outcome = streamConsumer.consume(flux, sink, state);
            ...
            sink.emit(AgentEvent.assistantCompleted(...));
            if (!outcome.hasToolCalls()) {
                sink.emit(AgentEvent.completed(...));
                return finalText;
            }
            sink.emit(AgentEvent.transition(TransitionReason.TOOL_USE, ...));
            executeToolCalls(...);
        }

目标形态摘要：

    assistant_delta -> timeline assistant item text += delta
    assistant_message_completed -> assistant item status = completed
    tool_call_ready -> timeline tool item status = queued
    tool_started -> tool item status = running
    tool_result -> tool item status = completed/error
    transition TOOL_USE -> stay streaming; next delta creates next assistant item
    completed -> turn terminal only

## Interfaces and Dependencies

后端事件归属字段应通过 `AgentEvent.metadata()` 或 payload 投影暴露给 SSE。建议新增一个局部 helper，避免到处手写 Map：

    private Map<String, Object> eventMetadata(String assistantCallId, int modelTurnIndex, Map<String, Object> extra)

该 helper 返回不可变或防御性复制后的 map，包含：

    assistantCallId: String
    modelTurnIndex: int
    iteration: int
    traceId: String
    turnNo: int

`ModelStreamConsumer.consume` 的最终签名应包含 event metadata：

    public ModelOutcome consume(Flux<ChatStreamEvent> flux,
                                AgentEventSink sink,
                                RuntimeState state,
                                Map<String, Object> eventMetadata)

前端类型应在 `pixflow-web/src/types/agent.ts` 中定义：

    export type TimelineItem =
      | AssistantTimelineItem
      | ToolTimelineItem
      | TransitionTimelineItem
      | ErrorTimelineItem

    export interface AssistantTimelineItem {
      id: string
      type: 'assistant'
      assistantCallId: string
      modelTurnIndex: number
      text: string
      status: 'streaming' | 'completed'
    }

    export interface ToolTimelineItem {
      id: string
      type: 'tool'
      assistantCallId: string
      modelTurnIndex: number
      toolCallId: string
      toolName: string
      input?: unknown
      result?: string
      status: 'queued' | 'running' | 'completed' | 'error'
      externalized?: boolean
    }

`useAgentTurn` 应返回：

    timeline
    summary
    phase
    send
    abort
    confirm
    reject

不应再返回运行中渲染用的 `deltas`。若某些外部调用方仍需要最终文本摘要，应从 timeline 中最后一个 assistant item 或 `completed.finalText` 派生，不要恢复 `deltas`。

`MessageStream.vue` props 应改为：

    defineProps<{
      timeline: TimelineItem[]
      userMessages?: Array<{ id: string; text: string }>
    }>()

不要在 `MessageStream.vue` 内解析 SSE；它只负责渲染传入状态。

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是用户指出 Agent 主循环应显式、SSE 过程事件应实时渲染到 Vue 前端，而不是等循环结束才显示 AI 消息；同时用户明确认为 Web 不需要 CLI 的阶段性提交机制。本计划据此确定直接用 SSE reducer 驱动响应式 timeline，删除旧 `deltas` 路径，不保留迁移式双实现，并记录可在设计文档中搜索的关键词。
