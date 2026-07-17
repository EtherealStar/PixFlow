# 重构模型重试所有权与 Agent SSE 错误契约

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树和这份计划，就应能完成模型调用 retry 与 Agent SSE 错误契约的完整重构，并知道为什么这样做。

## Purpose / Big Picture

用户发送一条消息后，如果模型供应商临时超时、限流或中途断流，前端不应该像“卡死”一样等到 SSE 超时，也不应该收到一个终态 `error` 后后端还在继续重试。完成本计划后，模型调用的重试只有一个所有者：`pixflow-infra-ai` 的 `ModelRetryRunner`。可恢复错误在重试期间通过非终态 `transition: RATE_LIMIT_RETRY` 推给前端 timeline，让用户看到“模型正在重试”；只有重试耗尽或不可恢复错误才发终态 `event: error` 并关闭 SSE。

这次是完整重构，不保留旧的迁移式双路径。必须删除 `AgentLoop` 外层模型 retry、删除 `AgentOrchestrator` 中只为 loop 传参而持有的 `ModelRetryRunner` 字段和构造参数、更新所有测试与设计文档。不能为了兼容旧行为保留“loop 再包一层 retry”的分支。

## Progress

- [x] (2026-07-10 00:35+08:00) 阅读 `PLANS.md`，确认本计划必须自包含、包含 Progress / Surprises / Decision Log / Outcomes，并且写入 Markdown 文件时不需要外层三反引号。
- [x] (2026-07-10 00:37+08:00) 阅读当前 active plan `docs/design-docs/exec-plans/web-agent-sse-timeline-refactor-plan.md`，确认它已经完成 SSE timeline 渲染，但明确把“模型双重 retry”排除在该计划范围外。
- [x] (2026-07-10 00:40+08:00) 搜索并核对当前代码，确认 `AgentLoop` 在 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 里仍对 `modelClient.stream(request)` 外包一层 `retryRunner.run(...)`，而 `DefaultChatModelClient.stream` 在 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java` 内部已经使用同一个 `ModelRetryRunner`。
- [x] (2026-07-10 00:42+08:00) 阅读 `docs/design-docs/infra/ai.md` 的 §七，确认设计口径是模型调用级重试归 `infra/ai`，`AttemptReset` 是重试中的非终态流事件，`CONTEXT_LIMIT` 不在 retry runner 内重试。
- [x] (2026-07-10 00:45+08:00) 新建本 ExecPlan，明确修复范围为 retry 单一所有权、retry transition payload、终态 error SSE payload、前端 timeline retry 可见性和验证闭环。
- [x] (2026-07-10 00:49+08:00) 删除 `AgentLoop` / `AgentOrchestrator` 对 `ModelRetryRunner` 的注入、字段和构造参数，生产代码中 `pixflow-loop` 与 `pixflow-agent` 不再命中 `ModelRetryRunner` / `retryRunner`。
- [x] (2026-07-10 00:50+08:00) 扩展 `AttemptReset -> RATE_LIMIT_RETRY` metadata 与 SSE payload，包含 `attempt`、`retriesRemaining`、`errorCode`、`message`、`retrying`，并让终态 `error` payload 输出脱敏后的 `errorCode` / `message` / `traceId`。
- [x] (2026-07-10 00:51+08:00) 前端 `TransitionPayload`、`useAgentTurn` reducer、`MessageStream` 已支持 `RATE_LIMIT_RETRY` timeline 状态行，且该 transition 不会切换到 error phase 或 reject 当前 send promise。
- [x] (2026-07-10 00:52+08:00) 更新 `docs/design-docs/infra/ai.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/chat.md`，同步 retry 单一所有权和前端 transition 契约。
- [x] (2026-07-10 00:52+08:00) 验证通过：`mvn -pl pixflow-loop,pixflow-agent,pixflow-conversation,pixflow-infra-ai -am -DskipTests compile`，`ModelRetryRunnerTest`，`ModelStreamConsumerTest,AgentLoopErrorRecoveryTest`，`SseAgentEventSinkTest`，`AgentOrchestratorTest`，`pnpm --dir pixflow-web test`，`pnpm --dir pixflow-web typecheck`。

## Surprises & Discoveries

- Observation: 当前工作树里模型 retry 有双重所有权。`AgentLoop` 外层调用 `retryRunner.run(ModelRole.PRIMARY_CHAT, attempt -> modelClient.stream(request))`，而 `DefaultChatModelClient.stream` 内部也调用 `retryRunner.run(request.role(), attempt -> streamOnce(request))`。
  Evidence: `rg -n "retryRunner\\.run\\(|modelClient\\.stream\\(" pixflow-loop/src/main/java pixflow-infra-ai/src/main/java` 命中 `AgentLoop.java:251` 和 `DefaultChatModelClient.java:95`。

- Observation: 设计文档已经把模型调用级重试归到 `infra/ai`，loop 只消费 `ChatModelClient.stream` 和解释 `AttemptReset` / `StopReason.LENGTH`。当前 `AgentLoop` 外层 retry 与设计冲突。
  Evidence: `docs/design-docs/infra/ai.md` §七写明 `ModelRetryRunner` 是模型调用级重试，§十三接口表写明 `harness/loop` 消费 `ChatModelClient.stream`。

- Observation: 当前 SSE 的终态 `error` 语义不能用于“报错后继续重试”。前端 `pixflow-web/src/runtime/useAgentTurn.ts` 收到 `event: error` 后会 `setPhase('error')` 并 reject 当前回合。
  Evidence: `useAgentTurn.ts` 的 `case 'error'` 分支构造 `ApiError`，设置 phase 为 `error`，随后调用 `fail(err)`。

- Observation: `ModelStreamConsumer` 已经把 `ChatStreamEvent.AttemptReset` 转成 `TRANSITION(RATE_LIMIT_RETRY)`，但当前 SSE 投影只保留 `reason` 和归属字段，不把重试错误码、重试次数和剩余次数投影给前端。
  Evidence: `ModelStreamConsumer.java` 在 `AttemptReset` 分支放入 `attempt` 与 `retriesRemaining`；`SseAgentEventSink.basePayload` 只投影 `assistantCallId`、`modelTurnIndex`、`iteration`、`traceId`、`turnNo`，`TRANSITION` 分支只放 `reason`。

- Observation: 已完成的 timeline 计划修复了“工具过程不可见”的问题，但故意没有处理双重 retry。本计划必须独立覆盖该缺口，不能把它塞回 timeline 计划。
  Evidence: `web-agent-sse-timeline-refactor-plan.md` Decision Log 写明“本计划不处理模型双重 retry 问题”。

## Decision Log

- Decision: 模型调用 retry 的唯一生产所有者是 `pixflow-infra-ai` 的 `ModelRetryRunner`，`AgentLoop` 不再注入或调用 `ModelRetryRunner`。
  Rationale: retryable 判定依赖 provider HTTP 状态、`Retry-After`、网络异常和流式首次发射边界，源头在 infra/ai 最准确。loop 外层再次 retry 会把完整 `ChatModelClient.stream` 重跑一遍，造成指数级等待和 SSE 超时风险。
  Date/Author: 2026-07-10 / Codex

- Decision: 可恢复模型错误重试中只发非终态 `transition: RATE_LIMIT_RETRY`，不发 `event: error`。
  Rationale: 前端当前把 `error` 当作终态失败并关闭本轮。若后端发 error 后继续 retry，协议就自相矛盾，用户会看到失败但后台仍运行。`RATE_LIMIT_RETRY` 是已存在的非终态 transition，适合表达“正在重试”。
  Date/Author: 2026-07-10 / Codex

- Decision: retry transition payload 必须包含用户可理解且可测试的字段：`reason`、`attempt`、`retriesRemaining`、`errorCode`、`message`、`assistantCallId`、`modelTurnIndex`、`traceId`、`turnNo`。
  Rationale: 只有 reason 不足以让前端 timeline 渲染明确状态，也不利于日志和测试定位。错误详情必须是安全消息，不能把 provider 原始敏感响应直接发给浏览器。
  Date/Author: 2026-07-10 / Codex

- Decision: 重试耗尽或不可恢复错误由 conversation SSE 边界发终态 `event: error`，并带 `errorCode`、`message`、`traceId`。
  Rationale: `AgentLoop` 的设计是不可恢复错误向上抛，不自己 emit error。`MessageController` / `SseAgentEventSink` 是 web 边界，最适合把异常归一化成前端协议。
  Date/Author: 2026-07-10 / Codex

- Decision: 不引入 `maxTurns`、`maxIteration` 或工具循环次数上限来解决本问题。
  Rationale: 本问题是模型 retry 所有权和 SSE 错误语义的问题，不是正常 tool-call 循环终止语义的问题。`docs/design-docs/harness/loop.md` 已明确无 maxTurns，不能用次数上限掩盖 retry 协议错误。
  Date/Author: 2026-07-10 / Codex

## Outcomes & Retrospective

本计划已实施。模型调用 retry 的唯一生产所有者保留在 `pixflow-infra-ai` 的 `ChatModelClient.stream` 内部；`AgentLoop` 和 `AgentOrchestrator` 不再持有或调用 `ModelRetryRunner`。模型已发射后发生可恢复错误时，`AttemptReset` 会被投影成非终态 `RATE_LIMIT_RETRY` transition，SSE payload 带重试次数、剩余次数、错误码和脱敏消息；前端在 timeline 中显示“模型连接中断，正在重试”状态行并保持 `streaming`。只有 retry 耗尽或不可恢复异常到达 conversation 边界时，才发送终态 `event: error` 并关闭 SSE。

## Context and Orientation

PixFlow 的 Agent 对话链路从浏览器到模型分为五层。第一层是前端 `pixflow-web/src/transport/sse.ts`，它用 `fetch + ReadableStream` 读取 SSE 字节流。第二层是前端状态机 `pixflow-web/src/runtime/useAgentTurn.ts`，它把 SSE 事件归约成 timeline。第三层是后端 conversation 边界，`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java` 创建 `SseEmitter`，`SseAgentEventSink.java` 把 loop 事件投影成 SSE JSON。第四层是 Agent 主循环，`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 构造模型请求、消费模型流、执行工具并决定是否继续。第五层是模型供应商客户端，`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/chat/DefaultChatModelClient.java` 调 DashScope OpenAI-compatible streaming API。

“SSE” 是 Server-Sent Events，后端保持一个 HTTP 响应打开，不断发送 `event: name` 和 `data: json` 块。这里它用于把 Agent 回合中的文本、工具事件、重试状态和终态推给浏览器。

“retry” 是失败后自动再次尝试同一个模型请求。这里的 retry 只针对限流、网络错误和供应商临时错误。鉴权错误、参数错误、配置错误不应该 retry。

“AttemptReset” 是 `infra/ai` 在流式模型调用中发出的非终态事件。它表示一次模型 attempt 已经发过一些文本但随后失败，并且系统会重新发起下一次 attempt。下游 UI 应把之前半截输出保留或标记为中断，再接收新 attempt 的输出。

“终态 error” 是 SSE 的 `event: error`。在当前前端协议里它表示本轮 Agent 回合失败，前端会进入 error phase 并关闭/清理本轮。它不能用来表达“正在重试”。

当前关键问题是：`AgentLoop` 与 `DefaultChatModelClient` 都在使用同一个 `ModelRetryRunner` 包裹模型 stream。默认 retry 策略最多 10 次，单次模型 timeout 默认 60 秒。双层 retry 会让失败场景等待时间大幅放大，前端看起来像 SSE 一直没有成功返回。

## Plan of Work

第一步重构 retry 所有权。编辑 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java`，删除 `ModelRetryRunner` import、字段、构造参数和赋值。把当前：

    Flux<ChatStreamEvent> flux = retryRunner.run(
            ModelRole.PRIMARY_CHAT,
            attempt -> modelClient.stream(request));

改成：

    Flux<ChatStreamEvent> flux = modelClient.stream(request);

这不是功能降级，因为 `DefaultChatModelClient.stream` 已经在 `infra/ai` 内部执行模型调用级 retry。删除外层 retry 后，`AgentLoop` 仍然能收到 `ChatStreamEvent.AttemptReset`，因为它由内层 `ModelRetryRunner` 发出。同步删除 `ModelRole` import，如果只为外层 retry 使用。

第二步删除上层无用依赖。编辑 `pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java`，删除 `ModelRetryRunner` import、字段、构造参数和赋值。`AgentOrchestrator.buildAgentLoop` 调用 `new AgentLoop(...)` 时不再传 `modelRetryRunner`。如果 `pixflow-agent` 的测试或 Spring auto configuration 只为 orchestrator 注入 `ModelRetryRunner`，同步删除对应参数。`DefaultChatModelClient` 仍然从 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiAutoConfiguration.java` 注入 `ModelRetryRunner`，这条依赖必须保留。

第三步更新所有 `AgentLoop` 构造调用。搜索：

    rg -n "new AgentLoop\\(|ModelRetryRunner" pixflow-loop pixflow-agent pixflow-conversation pixflow-app

逐个更新测试 helper、fake builder、单元测试构造器。不要提供一个带旧参数的过载构造器，因为用户明确要求不要为了迁移式安全保留旧代码。完成后生产代码中 `pixflow-loop` 不应再 import `com.pixflow.infra.ai.resilience.ModelRetryRunner`。

第四步强化 retry transition payload。编辑 `pixflow-loop/src/main/java/com/pixflow/harness/loop/stream/ModelStreamConsumer.java`。在 `AttemptReset` 分支，从 `reset.error()` 取安全字段，合并到 transition metadata 中。字段建议为：`attempt` 使用 `reset.nextAttempt()`，`retriesRemaining` 使用 `reset.retriesRemaining()`，`errorCode` 使用 `reset.error().code().code()` 或现有错误码访问器，`message` 使用安全 message，`retrying` 固定为 `true`。如果 `PixFlowException` 没有直接的安全 message getter，使用当前错误 message，但必须避免把 provider 原始 body 或 API key 透出；必要时在 `SseAgentEventSink` 做最后一层裁剪。

第五步让 SSE 投影完整保留 retry 信息。编辑 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java`。`TRANSITION` payload 除 `reason` 外，还要把 event metadata 中的 `attempt`、`retriesRemaining`、`errorCode`、`message`、`retrying` 投影给前端。不要只投影归属字段。建议新增 helper：

    private static void putMetadataIfPresent(Map<String, Object> payload, AgentEvent event, String key)

已经存在，可扩展调用列表，而不是写多个 ad hoc 分支。

第六步规范终态 error payload。仍在 `SseAgentEventSink.java`，重构 `error(Throwable error)`：当 error 是 `PixFlowException` 时，payload 至少包含 `errorCode`、`message`、`traceId`。非 PixFlowException 使用 `INTERNAL_ERROR` 或现有公共错误码，并保留安全 message。`MessageController` 仍然只在 catch 分支调用 `sink.error(ex)`，不在 retry 中调用。这样重试中是 transition，耗尽或不可恢复才是 error。

第七步让前端显示 retry transition。编辑 `pixflow-web/src/types/agent.ts`，扩展 `TransitionPayload`，加入可选 `attempt`、`retriesRemaining`、`errorCode`、`message`、`retrying`。编辑 `pixflow-web/src/runtime/useAgentTurn.ts` 的 `applyTransitionEvent`：当 `payload.reason === 'RATE_LIMIT_RETRY'` 时，在 timeline 中追加或更新一个 transition item，状态文案表达“模型请求失败，正在重试”，同时保持 phase 为 `streaming`，不得调用 `setPhase('error')`。如果之后收到新的 `assistant_delta` 或 `completed`，该 transition item 应保留为历史提示或标记为 completed；不要清空已有 assistant/tool timeline。

第八步调整 `MessageStream.vue`。如果当前 transition item 没有可见渲染，补一个小型 timeline 行显示 retry 信息。文案要简洁，不写教学说明。例如：`模型连接中断，正在重试 2/11` 或 `模型限流，稍后重试`。这条 UI 不需要营销化视觉，只要在正在生成的消息流中可见。

第九步补测试。后端至少增加或更新四类测试。第一，`AgentLoop` 测试证明 retryable 模型错误不会被 loop 外层再次订阅：fake `ChatModelClient.stream` 返回 retryable error，运行 loop 后断言 fake client 的 subscription count 为 1。第二，`ModelStreamConsumerTest` 构造 `AttemptReset`，断言 sink 收到 `TRANSITION` 且 metadata 有 `RATE_LIMIT_RETRY`、`attempt`、`retriesRemaining`、`errorCode`。第三，`SseAgentEventSinkTest` 断言 transition SSE payload 投影 retry 字段。第四，`SseAgentEventSinkTest` 或 controller 层测试断言 `sink.error(new PixFlowException(...))` 输出 `event: error` 且 payload 有 `errorCode` 和 `message`。

前端至少增加或更新两类测试。第一，`useAgentTurn` 收到 `transition: RATE_LIMIT_RETRY` 后 phase 仍是 `streaming`，timeline 出现 retry transition item，没有 reject 当前 send promise。第二，`transition: RATE_LIMIT_RETRY -> assistant_delta -> completed` 后 send promise resolve，timeline 同时保留 retry 提示和最终 assistant 文本。

第十步同步设计文档。更新 `docs/design-docs/infra/ai.md`，明确 retry 单一所有权：`ChatModelClient.stream` 是唯一模型调用 retry 边界，loop 不再外包 `ModelRetryRunner`。更新 `docs/design-docs/harness/loop.md`，把 `RATE_LIMIT_RETRY` 描述成由 `ChatModelClient.stream` 中的 `AttemptReset` 转成 transition，而不是 loop 持有 retry runner。更新 `docs/design-docs/frontend/transport-api.md` 和 `docs/design-docs/frontend/chat.md`，补充 `transition: RATE_LIMIT_RETRY` payload 和 timeline 渲染语义。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认当前双重 retry 旧路径：

    rg -n "retryRunner\\.run\\(|ModelRetryRunner|modelClient\\.stream\\(" pixflow-loop/src/main/java pixflow-agent/src/main/java pixflow-infra-ai/src/main/java

实施前预期命中 `AgentLoop.java` 的外层 `retryRunner.run`、`AgentOrchestrator.java` 的 `ModelRetryRunner` 字段或构造参数，以及 `DefaultChatModelClient.java` 的内层 retry。实施后，`pixflow-loop/src/main/java` 和 `pixflow-agent/src/main/java` 不应再命中 `ModelRetryRunner`，但 `pixflow-infra-ai/src/main/java` 仍应命中。

修改后运行编译：

    mvn -pl pixflow-loop,pixflow-agent,pixflow-conversation,pixflow-infra-ai -am -DskipTests compile

预期相关 reactor 模块编译成功，末尾出现 `BUILD SUCCESS`。如果编译失败并提示 `AgentLoop` 构造参数不匹配，说明还有测试 helper 或生产装配没更新；用 `rg -n "new AgentLoop\\("` 找到并更新。

运行后端重点测试：

    mvn -pl pixflow-infra-ai -Dtest=ModelRetryRunnerTest test
    mvn -pl pixflow-loop -Dtest=ModelStreamConsumerTest test
    mvn -pl pixflow-conversation -Dtest=SseAgentEventSinkTest test

预期三条命令均 `BUILD SUCCESS`。`ModelRetryRunnerTest` 证明 infra/ai 仍负责 retry；`ModelStreamConsumerTest` 证明 retry reset 会变成非终态 transition；`SseAgentEventSinkTest` 证明 SSE payload 正确。

运行前端测试：

    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck

如果项目脚本变化，先看 `pixflow-web/package.json` 的 scripts，再使用其中现有的 `test` 和 `typecheck`。成功时应看到测试通过和 TypeScript 类型检查无错误。

收尾搜索：

    rg -n "ModelRetryRunner|retryRunner" pixflow-loop/src/main/java pixflow-agent/src/main/java
    rg -n "RATE_LIMIT_RETRY|retriesRemaining|retrying|errorCode" pixflow-loop/src/main/java pixflow-conversation/src/main/java pixflow-web/src docs/design-docs

第一条不应命中生产代码。第二条应命中新机制实现、测试和设计文档。

## Validation and Acceptance

验收一：无双重 retry。构造 fake `ChatModelClient`，它的 `stream` 每次订阅都立即抛 retryable `PixFlowException`。运行 `AgentLoop.stream` 后断言 fake client 只被订阅一次，且异常向上抛给 conversation 边界。旧实现会因为外层 `retryRunner.run` 多次重新调用 `modelClient.stream` 而失败；新实现通过。

验收二：retry 中不是终态 error。构造 `Flux` 依次发出 `TextDelta("半截")`、`AttemptReset(error, 2, 9)`、`TextDelta("重试成功")`、`Completed("重试成功", ...)`。`ModelStreamConsumer` 应 emit 一个 `assistant_delta`、一个 `transition` 且 reason 为 `RATE_LIMIT_RETRY`、再 emit 新的 `assistant_delta`，最终返回 completed outcome。整个过程中不得 emit `event: error`。

验收三：SSE retry payload 可见。`SseAgentEventSink.toPayload` 对 `TRANSITION` 事件输出：

    {
      "reason": "RATE_LIMIT_RETRY",
      "attempt": 2,
      "retriesRemaining": 9,
      "errorCode": "MODEL_PROVIDER_ERROR",
      "message": "...",
      "assistantCallId": "...",
      "modelTurnIndex": 1,
      "traceId": "...",
      "turnNo": 1
    }

验收四：终态错误才用 `event: error`。当模型 retry 耗尽或鉴权错误不可恢复时，`MessageController` catch 分支调用 `sink.error(ex)`，SSE payload 至少有 `message` 和 `errorCode`，随后 emitter complete。前端进入 `error` phase，并保留已有 timeline。

验收五：前端用户可见。用 fake SSE 依次发送：

    event: assistant_delta
    data: {"text":"正在分析","assistantCallId":"a1","modelTurnIndex":1}

    event: transition
    data: {"reason":"RATE_LIMIT_RETRY","attempt":2,"retriesRemaining":9,"errorCode":"MODEL_PROVIDER_ERROR","message":"model stream interrupted","assistantCallId":"a1","modelTurnIndex":1}

    event: assistant_delta
    data: {"text":"重试后继续","assistantCallId":"a1","modelTurnIndex":1}

    event: completed
    data: {"finalText":"重试后继续","assistantCallId":"a1","modelTurnIndex":1}

前端应保持 streaming，显示 retry 提示，随后显示最终文本并进入 completed。不得在 transition 时进入 error。

## Idempotence and Recovery

本计划可以分阶段实施。删除 `AgentLoop` 的 `ModelRetryRunner` 后，如果编译失败，不要恢复旧构造器过载；应该更新所有调用点和测试 helper。项目处于开发阶段，用户明确要求不要保留迁移式旧代码。

如果 retry transition payload 扩展导致前端 schema 失败，应更新 `pixflow-web/src/runtime/useAgentTurn.ts` 的 Zod schema 和 `pixflow-web/src/types/agent.ts`，不要在后端删掉字段迁就旧前端。

如果 `mvn -pl ... -am test` 被无关上游模块阻断，记录阻断模块和失败测试，但本计划触达模块的单测必须能单独通过。不要通过跳过本计划相关测试来宣称完成。

如果手工测试中 provider 真实不可用或没有 API key，使用 fake provider 单测验证 retry 和 SSE 事件语义。真实 DashScope 连通性不是本计划的必要条件，但终态 error payload 必须能正确告诉前端配置错误或鉴权错误。

## Artifacts and Notes

当前旧路径摘要：

    AgentLoop.runLoop:
        Flux<ChatStreamEvent> flux = retryRunner.run(
            ModelRole.PRIMARY_CHAT,
            attempt -> modelClient.stream(request));

    DefaultChatModelClient.stream:
        return retryRunner.run(request.role(), attempt -> streamOnce(request));

目标路径摘要：

    AgentLoop.runLoop:
        Flux<ChatStreamEvent> flux = modelClient.stream(request);
        outcome = streamConsumer.consume(flux, sink, state, eventMetadata);

    DefaultChatModelClient.stream:
        return retryRunner.run(request.role(), attempt -> streamOnce(request));

    ModelStreamConsumer:
        AttemptReset -> AgentEvent.transition(RATE_LIMIT_RETRY, retry metadata)

    SseAgentEventSink:
        transition RATE_LIMIT_RETRY -> non-terminal SSE payload
        error(Throwable) -> terminal SSE error payload

    useAgentTurn:
        transition RATE_LIMIT_RETRY -> timeline retry item, phase remains streaming
        error -> phase error, active turn rejects

## Interfaces and Dependencies

`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 的构造器最终不应接收 `ModelRetryRunner`：

    public AgentLoop(RuntimeState state,
                    MessageStore messageStore,
                    ContextEngine contextEngine,
                    ContextCompactionService compactionService,
                    ChatModelClient modelClient,
                    ToolExecutor toolExecutor,
                    PermissionPolicy permissionPolicy,
                    ToolResultStorage resultStorage,
                    PlanModeView planModeView,
                    HookRegistry hookRegistry,
                    TraceRecorder traceRecorder,
                    PermissionContextFactory permissionContextFactory,
                    ErrorRecorder errorRecorder,
                    LoopProperties properties,
                    ObjectMapper jsonMapper,
                    ExecutorService toolExecutorService)

`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java` 的构造器最终不应接收或保存 `ModelRetryRunner`。它仍接收 `ChatModelClient`，因为具体模型调用和 retry 都封装在该接口后面。

`pixflow-loop/src/main/java/com/pixflow/harness/loop/stream/ModelStreamConsumer.java` 继续消费 `ChatStreamEvent.AttemptReset`，但 transition metadata 必须包含 retry 信息：

    reason: RATE_LIMIT_RETRY
    attempt: int
    retriesRemaining: int
    errorCode: String
    message: String
    retrying: true

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java` 的 `TRANSITION` payload 必须投影这些字段。`error(Throwable)` 必须输出终态 error payload：

    message: String
    errorCode: String
    traceId: String, optional

`pixflow-web/src/types/agent.ts` 的 `TransitionPayload` 必须允许：

    export interface TransitionPayload extends AgentEventAttribution {
      reason: string
      attempt?: number
      retriesRemaining?: number
      errorCode?: string
      message?: string
      retrying?: boolean
    }

`pixflow-web/src/runtime/useAgentTurn.ts` 的 reducer 必须保证 `RATE_LIMIT_RETRY` 不会进入 error phase。终态失败只能来自 SSE `event: error`、网络断流未收到 `completed`、用户主动 abort，或确认接口失败。

## Revision Notes

2026-07-10 / Codex: 新建本 ExecPlan。原因是用户发现发送一条消息后 SSE 长时间不返回成功 AI 消息，并追问模型调用出错时是否应向前端报错再重试。代码检查确认当前存在 `AgentLoop` 与 `DefaultChatModelClient` 双重 retry；同时前端把 `event: error` 视为终态失败，因此不能用 error 表达“正在重试”。本计划决定完整重构 retry 所有权和 SSE 错误契约：删除 loop 外层 retry，保留 infra/ai 单一 retry，重试中用非终态 `RATE_LIMIT_RETRY` transition 可见化，耗尽后才发终态 error。用户明确要求不要为了迁移式安全保留旧代码，因此计划要求删除旧构造参数和旧调用路径。

2026-07-10 / Codex: 执行完成本计划。删除 `AgentLoop` 外层 `retryRunner.run(...)` 与 `AgentOrchestrator` 的 `ModelRetryRunner` 传参链；补齐 retry transition / terminal error SSE payload；前端 timeline 渲染 `RATE_LIMIT_RETRY` 且保持 streaming；更新后端、前端测试和设计文档，并完成验证。收尾搜索确认生产代码中 `pixflow-loop/src/main/java` 与 `pixflow-agent/src/main/java` 不再命中 `ModelRetryRunner` / `retryRunner`。
