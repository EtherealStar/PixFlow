# 重构 SSE 回合生命周期、异步鉴权边界与协作取消

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要拥有当前工作树和这份计划，就应能完成 Spring Security dispatcher 边界、SSE 生命周期、Agent 回合取消与执行器容量控制的完整重构，并理解每项改动为什么必要。

## Purpose / Big Picture

当前用户发送 Agent 消息后，后端用 Spring MVC `SseEmitter` 持续返回模型文本和工具事件。响应一旦开始发送，HTTP 响应就已经提交。SSE 完成、超时、浏览器断开或 worker 抛错时，Tomcat 会做 `ASYNC` 或 `ERROR` 内部分派；当前 Spring Security 对这些内部分派再次执行 `.anyRequest().authenticated()`，但自定义 JWT filter 不会在二次分派中重新建立认证，最终产生 `AuthorizationDeniedException`。Security 随后尝试返回 403，却发现 SSE 响应已经提交，于是抛出 `Unable to handle the Spring Security Exception because the response is already committed`。这个异常是生命周期和鉴权边界耦合后的二次故障，不应通过吞日志或放宽全部 API 鉴权来掩盖。

完成本计划后，JWT 只认证浏览器发起的初始 `REQUEST`，容器内部的 `ASYNC` 与 `ERROR` 分派不再重复做业务鉴权。conversation 模块在响应提交前完成用户归属、请求、附件、runner、回合锁和执行容量准备；响应提交后由单一 `SseTurnSession` 对象拥有 emitter、心跳、worker、取消令牌和终止状态。成功、业务失败、客户端断开、超时和服务器关闭都通过一个幂等 terminal gate 收敛，保证最多发送一次终态、最多关闭一次 emitter、worker 退出后只释放一次回合锁。浏览器主动取消或网络断开会真正取消模型订阅和尚未执行的工具任务，不再作为内部错误写入 `ErrorRecorder`。

用户可以通过三类行为观察结果。正常 Agent 回合仍实时收到 `assistant_delta`、工具事件和 `completed`；模型或业务错误在已提交的 SSE 中收到安全的 `event: error` 后正常关闭，不进入 Servlet 错误页；浏览器中止请求后，服务端在协作取消边界停止回合、释放锁，日志中没有 committed-response Security 异常。执行容量耗尽时，请求会在 SSE 提交前得到结构化 HTTP 503，而不是占用无界线程或返回半截流。

## Progress

- [x] (2026-07-10) 阅读根目录 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和两个 active ExecPlan，确认本工作必须独立建计划，不能混入模型 retry 或前端 timeline 重构。
- [x] (2026-07-10) 阅读 `docs/design-docs/infra/auth.md`、`docs/design-docs/module/conversation.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/api.md` 与 `docs/design-docs/web.md` 中的鉴权、SSE、断流和错误契约。
- [x] (2026-07-10) 核对 `AuthAutoConfiguration`、`JwtAuthenticationFilter`、`MessageController`、`TurnDispatchService`、`SseAgentEventSink`、`SseHeartbeat`、`ConversationAutoConfiguration`、`AgentTurnRunner`、`AgentLoop`、`ModelStreamConsumer`、`ToolExecutionContext`、`RegistryToolExecutor` 和前端 `transport/sse.ts` 的当前实现。
- [x] (2026-07-10) 确认根因和结构缺口：Security 未区分 dispatcher，SSE 生命周期由 controller/sink/heartbeat 分散管理，回合无显式取消令牌，模型消费阻塞于 `blockLast`，工具并行 future 不响应回合取消，conversation worker 使用无界 cached thread pool，SSE 打开失败不解析后端错误 envelope。
- [x] (2026-07-10) 新建本中文 ExecPlan，固定目标机制、模块边界、实施顺序、测试矩阵和设计文档同步范围。
- [x] (2026-07-10) 实施 dispatcher 鉴权边界、关闭 request cache，并补 `AuthDispatcherSecurityTest` 与 JWT dispatcher 测试。
- [x] (2026-07-10) 实施公共协作取消原语与 trace `CANCELLED` 终态，取消不写 `TraceError`。
- [x] (2026-07-10) 用 `TurnPreparationService`、`PreparedTurn` 和 `SseTurnSession` 替换分散式 SSE 生命周期，删除 `TurnDispatchService`。
- [x] (2026-07-10) 贯通 conversation → agent → loop → model stream → tools 的同一取消令牌，并覆盖模型流、工具 future、trace 与锁释放竞态。
- [x] (2026-07-10) 用有界即时拒绝执行器替换无界 cached thread pool，并让前端普通 HTTP/SSE 打开阶段共享结构化错误解析。
- [x] (2026-07-10) 完成相关生产模块编译、认证/会话/Agent/前端完整套件、取消与竞态精选测试及设计文档同步。真实浏览器断连未启动完整外部依赖环境手工演练，由 session/loop/tool 自动化测试覆盖等价终止路径。

## Surprises & Discoveries

- Observation: `SecurityErrorWriter` 已经检查 `response.isCommitted()`，但仍然出现 committed-response 异常。
  Evidence: `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/filter/SecurityErrorWriter.java` 在写响应前直接 return；附件堆栈却由 `ExceptionTranslationFilter` 抛错，说明异常发生在自定义 `AccessDeniedHandler` 被调用之前，不能靠 writer 内的 committed guard 修复。

- Observation: 当前 Security 链只按 URL 和 HTTP method 授权，没有 dispatcher 规则。
  Evidence: `AuthAutoConfiguration.pixflowSecurityFilterChain` 末尾是 `.anyRequest().authenticated()`，没有 `dispatcherTypeMatchers`；堆栈同时出现 `CoyoteAdapter.asyncDispatch`、`StandardHostValve.throwable` 和 `ApplicationDispatcher.doInclude`。

- Observation: 当前 conversation 设计文档建议 SSE 错误帧后调用 `completeWithError`，这与已提交流的安全收口目标冲突。
  Evidence: `docs/design-docs/module/conversation.md` §9 和 §15.3 写了 `event: error` 后 `completeWithError`；Spring MVC 的 `completeWithError` 会触发异步错误分派，正是本问题需要避免的路径。实现当前使用 `event: error` + `complete()`，但生命周期仍分散，文档与实现也不一致。

- Observation: transport 断连会被 AgentLoop 当成普通内部错误。
  Evidence: `SseAgentEventSink.emit` 将 `IOException` 包装为 `IllegalStateException("SSE client disconnected")`；`AgentLoop.runLoop` 捕获所有 `RuntimeException` 后调用 `ErrorRecorder.record` 和 `TurnTrace.abort`。用户关闭页面属于取消，不应污染服务端故障指标。

- Observation: 浏览器断开并不会立即停止模型或工具执行。
  Evidence: `AgentTurnRunner` 没有 cancellation 参数；`ModelStreamConsumer.consume` 用 `Flux.publishOn(...).blockLast(Duration.ofMinutes(5))` 阻塞；`RegistryToolExecutor` 用 `CompletableFuture.supplyAsync` 后逐个 `join`，没有取消信号或 future cancel。

- Observation: conversation worker 没有容量上限。
  Evidence: `ConversationAutoConfiguration.conversationExecutor` 使用 `Executors.newCachedThreadPool`。模型流或工具长时间阻塞时，该 executor 可以持续创建线程，无法在响应提交前给出可预测的 503。

- Observation: 前端 SSE 客户端无法保留预提交错误的业务错误码。
  Evidence: `pixflow-web/src/transport/sse.ts` 对非 2xx 响应固定构造 `HTTP_<status>`，不读取 `ApiResponse` JSON；普通 HTTP client 已经有 envelope 错误归一化逻辑，但未与 SSE 打开阶段共享。

- Observation: `docs/design-docs/index.md` 和 `docs/design-docs/web.md` 仍把根级 `docs/design-docs/api.md` 写成接口权威文档，但该文件已在提交 `b0298a5` 删除，当前实际可读的接口摘要位于 `docs/design-docs/frontend/api.md`。
  Evidence: `git log -- docs/design-docs/api.md` 显示该文件被删除；当前工作树中 `Get-Content docs/design-docs/api.md` 失败。本计划不会恢复一份可能已经过时的 680 行旧文档，只同步现存的 conversation 与 frontend API 文档，并在 `index.md` 中修正本项目当前的文档指向。

- Observation: Redisson 的普通 `unlock()` 依赖当前线程身份，prepare 请求线程获取的锁不能由 worker 线程直接释放。
  Evidence: `TurnLockHandle` 必须记录获取锁时的 owner thread id，并在 worker 真正退出后调用 `unlockAsync(ownerThreadId)`；对应测试验证跨线程 close 与幂等释放。

- Observation: `FutureTask.cancel(true)` 会先触发 `done()`，运行中的 callable 可能尚未退出。
  Evidence: 如果在 `done()` 中立即 close `PreparedTurn`，同 conversation 的下一回合可能在旧 worker 仍运行时获得锁。`SseTurnSession.OwnedFutureTask` 因此分别记录 started/exited，只有未启动或已退出时才释放所有权。

- Observation: `CompletableFuture.cancel(true)` 不能可靠中断 `supplyAsync` 的底层工具任务。
  Evidence: tools 改用 `ExecutorService.submit` 返回的 `Future`，取消信号调用 `Future.cancel(true)`；取消测试验证阻塞 handler 收到线程中断且后续工具不再启动。

- Observation: Reactor 对共享取消 signal 的订阅也可能反向取消该 signal，且取消与 `publishOn` 排队存在高负载竞态。
  Evidence: 公共 token 只暴露 `minimalCompletionStage`，模型侧使用 `Mono.fromFuture(..., true)` 禁止反向取消，并采用 `publishOn(...).takeUntilOther(...)` 顺序；高负载取消测试在调整后稳定通过。

- Observation: 仓库当前完整测试仍有与本计划无关的既有失败。
  Evidence: context 侧为 `ContextBudgetServiceTest.externalizesLargeToolResultAndMicrocompactsOldResults`、`ContextProjectorTest.backsUpWindowStartToKeepToolPair`；`pixflow-loop` 完整套件为 `AgentLoopContinuationDecisionTest.executesToolCallsWhenModelRequestsToolAndCompletesOnNextRound` 与 `RuntimeScopeTranslatorTest.subagentEvalMapsToSubagentHooks`。本次新增取消测试在同一套件中全部通过。

## Decision Log

- Decision: 保留 Spring MVC + `SseEmitter`，不迁移 WebFlux，也不把 Agent 主循环改成响应式主循环。
  Rationale: `module/conversation.md` 与 `harness/loop.md` 已明确阻塞式 AgentLoop、同步 `AgentEventSink` 和 Spring MVC 边界。问题来自生命周期所有权和 dispatcher 授权，不来自 MVC 本身。迁移 WebFlux 会扩大到 context、hooks、session、eval、tools 等模块，不能作为本问题的替代修复。
  Date/Author: 2026-07-10 / Codex

- Decision: 初始 `DispatcherType.REQUEST` 完成 JWT 鉴权和 owner 解析；`ASYNC`、`ERROR` 内部分派显式 `permitAll`。
  Rationale: 外部请求无法自行伪造 Servlet dispatcher 类型。初始请求已经完成身份与资源归属校验，内部完成/错误分派不应再次依赖已清理的 `SecurityContext`。按 dispatcher 放行比仅放行 `/error` 正确，因为错误分派可能保留原始 URL。
  Date/Author: 2026-07-10 / Codex

- Decision: 异步 worker 不传播、读取或恢复 `SecurityContextHolder`；只消费同步阶段捕获的不可变 `ownerUserId`、`conversationId` 和 turn request。
  Rationale: 隐式线程上下文会把认证正确性绑定到 executor 和 async dispatcher。显式参数能被测试、审计和跨线程安全传递，也符合当前 conversation service 已采用 owner-aware 方法的设计。
  Date/Author: 2026-07-10 / Codex

- Decision: `TurnPreparationService.prepare` 在 controller 返回 emitter 之前完成 conversation owner 校验、请求/附件准备、runner 解析和回合锁获取，返回拥有锁的 `PreparedTurn`。
  Rationale: 这些失败都发生在响应提交前时，可以使用正常 HTTP 4xx/5xx。旧实现把 `dispatch` 放在 worker 中，导致 conversation 不存在、锁忙、附件非法和 runner 缺失都只能在已提交 SSE 中处理。
  Date/Author: 2026-07-10 / Codex

- Decision: `SseTurnSession` 是 emitter、sink、heartbeat、worker future、cancellation source 和终止状态的唯一生命周期所有者。
  Rationale: 当前 controller、sink 和 heartbeat 都能关闭或影响连接，存在重复发送、重复完成和回调竞态。单一所有者配合 CAS terminal gate 可以固定 exactly-once 清理语义。
  Date/Author: 2026-07-10 / Codex

- Decision: 已提交 SSE 的预期业务错误使用 `event: error` + `emitter.complete()`；客户端断开不再发送错误帧；预期路径不使用 `completeWithError()`。
  Rationale: `completeWithError` 会进入 Servlet `ERROR` dispatcher，并且响应已提交后无法可靠改写 HTTP 状态。SSE 协议已经有终态 `error` 事件，发送后正常完成连接即可。真正的 transport failure 已经没有可写通道，只应取消执行并记录终止原因。
  Date/Author: 2026-07-10 / Codex

- Decision: 协作取消原语放在 `pixflow-common`，由 conversation、loop、tools 和 model stream 共同使用。
  Rationale: 如果 cancellation 类型放在 loop，tools 需要反向依赖 loop，破坏模块 DAG；放在 common 能保持依赖方向。取消是通用并发原语，不包含 conversation 或 Agent 业务语义。
  Date/Author: 2026-07-10 / Codex

- Decision: 删除旧 `AgentTurnRunner.stream(String, String, List, AgentEventSink)` 签名和旧 `TurnDispatchService.DispatchHandle`，不保留迁移重载。
  Rationale: 项目处于开发阶段，旧接口无法表达 cancellation 和准备/执行边界。保留重载会让生产或测试继续绕过新机制，无法证明所有回合都可取消。
  Date/Author: 2026-07-10 / Codex

- Decision: trace 增加 `CANCELLED` 终态；取消不调用 `ErrorRecorder`，但仍关闭 trace，并派发 `TURN_STOPPED` cancelled reason。
  Rationale: 客户端关闭、SSE 超时和服务停机不是模型/工具故障。把它们记成 `ABORTED + INTERNAL_ERROR` 会污染错误率；完全不收口 trace 又会留下 OPEN 记录。独立状态能表达真实生命周期。
  Date/Author: 2026-07-10 / Codex

- Decision: conversation worker 使用 `SynchronousQueue` 的有界即时拒绝 `ThreadPoolExecutor`，默认 `max-concurrency=16`，不排队。
  Rationale: SSE 回合可能持续数分钟。排队会让请求在未开始执行时长时间持有客户端连接，若先获取锁还会占用 turn lock。即时拒绝可在响应提交前返回 503，并给单机部署明确的资源上限。
  Date/Author: 2026-07-10 / Codex

- Decision: 不在本计划中实现 Last-Event-ID 重连、断流续跑或把回合迁移成后台 task。
  Rationale: 当前产品契约明确 V1 断流即失败。这里解决的是资源取消、错误收口和鉴权递归，不改变 SSE 的恢复语义。
  Date/Author: 2026-07-10 / Codex

## Outcomes & Retrospective

本计划已完成实现。Security 只在真实 `REQUEST` 上建立 JWT 认证，`ASYNC/ERROR` dispatcher 显式放行；conversation 入口变为同步 prepare 与单一 `SseTurnSession` 所有权，业务错误只发送一个 `event:error` 后普通完成，disconnect/timeout/shutdown 统一协作取消。取消令牌贯通 agent、loop、Reactor 模型流和工具 future，trace 使用独立 `CANCELLED` 终态且不写内部错误。conversation executor 改为默认最大并发 16、`SynchronousQueue` 即时拒绝，前端能保留预提交 503 的 `code/message/traceId`。

验证结果：`pixflow-infra-auth` 37/37、`pixflow-conversation` 27/27、`pixflow-agent` 40/40、前端 Vitest 93/93，前端 TypeScript 检查通过；common/eval、tools/loop 精选取消与恢复测试、相关 26 模块生产编译均通过。`pixflow-loop` 完整套件运行 64 项，本次 3 项取消测试全部通过，仅保留两项既有失败；更大 reactor 仍受两项既有 context 测试失败阻断。`git diff --check` 无空白错误，仅报告工作树 LF 将按 Git 配置转 CRLF 的提示。

协作取消仍是 best-effort：若第三方模型客户端或工具 handler 同时忽略 token、线程中断和自身超时，Java 无法安全强杀执行线程。实现因此坚持 worker 真正退出后才释放会话锁，避免旧回合与新回合重叠。自动化测试未观察到重复 error 帧、锁提前释放或 committed-response Security 递归；本轮未启动完整 AI/Redis 外部环境做真实浏览器手工断连演练。

## Context and Orientation

请求入口位于 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java`。浏览器调用 `POST /api/conversations/{conversationId}/messages`，controller 创建 `SseEmitter`、`SseAgentEventSink` 和 `SseHeartbeat`，再把 worker 提交给 `conversationExecutor`。worker 内部调用 `TurnDispatchService.dispatch`；该方法做 owner 校验、附件收集、conversation lock 获取和 `AgentTurnRunner` 解析，并返回一个必须由 controller 在 emitter complete 后关闭的 `DispatchHandle`。

`SseAgentEventSink` 是 loop `AgentEventSink` 到 SSE 的投影点。它既发送普通 Agent 事件，又通过 `error(Throwable)` 发送错误事件并完成 emitter。`SseHeartbeat` 自己注册 emitter completion/timeout/error callbacks 并停止调度任务。由于 controller、sink 和 heartbeat 各自持有一部分生命周期职责，无法从一个位置判断终止是否已经发生。

`AgentTurnRunner` 定义在 `pixflow-loop`，由 `pixflow-agent` 的 `AgentOrchestrator` 实现。当前签名只带 conversationId、prompt、attachments 和 sink。`AgentLoop` 是阻塞式 while 主循环；每轮调用 `ChatModelClient.stream` 得到 Reactor `Flux<ChatStreamEvent>`，`ModelStreamConsumer` 用 `publishOn(boundedElastic) + blockLast(5m)` 阻塞消费。工具执行由 `RegistryToolExecutor` 完成，并行安全的工具通过 `CompletableFuture.supplyAsync` 提交后 `join`。这两条路径目前都没有取消令牌。

“响应提交前”是指 controller 还没有返回 `SseEmitter`，Servlet 容器尚未写出 HTTP headers/body。此时错误可以安全映射成 HTTP JSON。“响应提交后”是指 SSE 已经开始；此时 HTTP status 不可再修改，只能发送 SSE frame 或在通道已断开时静默清理。

“dispatcher” 是 Servlet 容器对一次请求所处阶段的分类。浏览器直接请求是 `REQUEST`；异步请求恢复是 `ASYNC`；错误处理是 `ERROR`。它不是 URL，也不是客户端 header。Spring Security 的 URL 规则默认仍可能作用于所有 dispatcher，因此本计划必须显式定义授权边界。

“协作取消”不是强制杀死 Java 线程。它由一个可查询、可订阅的 token 表达取消请求；模型 Flux、AgentLoop 和 ToolExecutor 在稳定边界检查 token，并取消 Reactor subscription 或 `CompletableFuture`。第三方 handler 如果完全忽略线程中断和 token，Java 无法安全强杀它，因此所有外部 I/O 仍必须有超时。回合锁只能在 worker 真正退出后释放，不能因浏览器断开就提前释放并允许同一 conversation 并发运行。

## Plan of Work

第一里程碑修复并固定 Security dispatcher 边界。编辑 `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthAutoConfiguration.java`，导入 `jakarta.servlet.DispatcherType`，在所有 URL matcher 之前加入 `dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()`。同时关闭 stateless API 不需要的 request cache：`requestCache(AbstractHttpConfigurer::disable)`。不要把 `/api/**`、消息 endpoint 或 `/error` 粗暴设为匿名，也不要在 `AccessDeniedHandler` 中吞掉 committed response 作为主修复。

在 `JwtAuthenticationFilter` 中显式覆盖 `shouldNotFilterAsyncDispatch()` 和 `shouldNotFilterErrorDispatch()` 返回 true，作为本模块契约说明；这与 `OncePerRequestFilter` 当前默认行为一致，但能防止后续维护者误以为二次 dispatcher 会重建 JWT principal。新增 `pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/config/AuthDispatcherSecurityTest.java`，使用 Spring Security test + MockMvc 验证：匿名 `REQUEST` 访问受保护 endpoint 返回 401；`ASYNC` 和 `ERROR` dispatcher 不被 AuthorizationFilter 改写成 401/403；dispatcher 放行不影响初始请求鉴权。

第二里程碑引入公共取消原语和 trace 取消终态。在 `pixflow-common/src/main/java/com/pixflow/common/concurrent/` 新增 `CancellationToken`、`CancellationSource`、`CancellationReason` 和 `OperationCancelledException`。`CancellationToken` 至少提供 `boolean isCancellationRequested()`、`Optional<CancellationReason> reason()`、`CompletionStage<Void> cancellationSignal()` 和 `void throwIfCancellationRequested()`；`CancellationSource.cancel(reason)` 必须 CAS only-once，并完成 cancellation signal。提供不可取消的 `CancellationToken.NONE`，只用于明确不需要取消的离线/测试入口，不允许 conversation 生产路径使用 NONE。

在 `pixflow-eval` 中把 `TurnStatus` 扩展为 `CANCELLED(3)`，给 `TurnTrace` 增加 `cancel()`，更新 `BufferedTurnTrace`、`NoopTraceRecorder`、测试 fake、payload codec 和查询/持久化测试。`cancel()` 不写 `TraceError`。如果数据库或序列化只保存整数状态，确认 code 3 能往返；如果 migration 有枚举/check 约束，同步扩展约束。取消原因保留在 conversation 的结构化日志和指标 tag 中，本计划不为 trace 表增加自由文本列。

第三里程碑重构 conversation 的同步准备边界。删除 `TurnDispatchService` 和内部 `DispatchHandle`，新增：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/TurnPreparationService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/PreparedTurn.java

`TurnPreparationService.prepare(long ownerUserId, String conversationId, MessageSubmitRequest request)` 同步完成 `conversationService.requireActive`、请求归一化、附件收集、conversation lock 获取和 runner 解析。它返回不可变 `PreparedTurn`，后者拥有 `TurnLockHandle`，暴露 `execute(AgentEventSink sink, CancellationToken cancellation)` 和幂等 `close()`。准备阶段在获取锁后的任何失败都必须关闭锁；准备成功后锁所有权转移给 `PreparedTurn`。不要在准备阶段创建 heartbeat 或提交 worker。

为 runner 不可用、conversation busy、附件非法等路径保留现有错误码和 HTTP 映射。新增 `TURN_CAPACITY_EXCEEDED` 到 `ConversationErrorCode`，category 为 `DEPENDENCY`，用于执行器拒绝时映射 503。新增 `TurnPreparationServiceTest`，覆盖 owner 校验发生在附件读取前、锁后失败释放、成功准备不提前释放、重复 close 幂等。

第四里程碑建立唯一 `SseTurnSession` 生命周期所有者。新增：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseTurnSession.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseTurnSessionFactory.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseTerminationReason.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseTurnMetrics.java

`SseTurnSessionFactory.create(PreparedTurn)` 创建 emitter、共享 send lock、sink、heartbeat、cancellation source 和 session。`SseTurnSession` 至少持有 `AtomicReference<State>`，状态为 `NEW`、`RUNNING`、`CANCELLING`、`TERMINATED`；`start()` 提交 worker，成功后返回 emitter。executor rejection 必须在 controller 返回 emitter 前被抛出：session 关闭 prepared turn，然后抛 `TURN_CAPACITY_EXCEEDED`。

worker 必须使用自定义 `FutureTask<Void>`，并由其 `done()` 作为唯一 worker 收口点。future 可能在 executor 接受后、任务真正进入 `run()` 前被取消；这种情况下 callable 内的 `finally` 不会执行，但 `FutureTask.done()` 仍然执行。`done()` 根据 session state 完成 success/failure/cancelled 的最终收口，确认 transport 已关闭后再关闭 `PreparedTurn`。executor rejection 表示 task 从未被接受，此时由 `start()` catch 分支关闭 prepared turn；一旦 executor 接受 task，prepared turn 只能由该 task 的 `done()` 关闭。

session 统一注册 `emitter.onCompletion`、`onTimeout` 和 `onError`。正常服务端完成时，terminal gate 先停止 heartbeat，再调用 `emitter.complete()`；worker task 的 `done()` 随后关闭 prepared turn。`onCompletion` 可能由本方 `complete()` 触发，因此回调必须检查 terminal state，不能把正常完成反向标记为客户端取消。客户端 disconnect、timeout 或 transport error 先 CAS 到 CANCELLING，停止 heartbeat，调用 `CancellationSource.cancel`，并对 worker task 做 `cancel(true)` 的 best-effort interrupt。task 无论正在运行还是尚未进入 callable，最终都通过 `done()` 将 CANCELLING 改为 TERMINATED、关闭仍可关闭的 transport、记录 metrics 并释放 prepared turn。只有 executor 明确拒绝、task 从未被接受时，`start()` 才直接关闭 prepared turn。

将 `SseAgentEventSink` 收窄为“只发送 frame”。保留现有 `AgentEvent -> payload` 投影和安全 error payload 逻辑，但删除 `error(Throwable)` 中的 emitter completion。新增 `sendError(PixFlowException)`，只在 session 判定通道仍可写时调用。所有 send 同时捕获 `IOException` 与 emitter 已完成造成的 `IllegalStateException`，通知 session transport failure，并抛 `OperationCancelledException`，不能再抛含糊的 `IllegalStateException("SSE client disconnected")`。

将 `SseHeartbeat` 收窄为“只调度和发送 heartbeat”。它不再注册 emitter callbacks；构造参数增加 transport-failure callback。heartbeat send failure 通知 session，由 session 决定取消和清理。`stop()` 保持原子幂等。

重写 `MessageController`，使其只完成四步：调用 `TurnPreparationService.prepare`；调用 `SseTurnSessionFactory.create`；调用 `session.start()`；返回 `session.emitter()`。controller 不直接创建 sink/heartbeat，不直接操作 executor，不捕获 worker 异常，不持有 turn lock。

第五里程碑贯通 Agent 回合取消。新增 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentTurnRequest.java`：

    public record AgentTurnRequest(
            String conversationId,
            String prompt,
            List<Attachment> attachments,
            CancellationToken cancellation) {}

把 `AgentTurnRunner` 改为：

    String stream(AgentTurnRequest request, AgentEventSink sink);

删除旧四参数方法。更新 `AgentTurnRunnerRegistry.UNAVAILABLE`、`AgentAutoConfiguration.agentTurnRunner`、`AgentOrchestrator.streamNewTurn` 和所有测试/fake。AgentOrchestrator 把 token 传给新建的 `AgentLoop` 调用，不从线程本地或 SecurityContext 获取取消状态。

扩展 `AgentLoop.stream`、`continueStream`、`runLoop`、`executeToolCalls` 和 transition/recovery 边界，使其接收同一个 `CancellationToken`。在写 user message 前、每次 while 开始、模型订阅前后、每次 sink emit 前、工具执行前后和进入下一轮前调用 `throwIfCancellationRequested()`。捕获 `OperationCancelledException` 时调用 `TurnTrace.cancel()`，派发一次 `TURN_STOPPED`，reason 为 `cancelled:<reason>`，不调用 `ErrorRecorder`，不 emit `completed`，然后继续向 web 边界抛出 cancellation exception 供 session 识别。其他 RuntimeException 仍维持 ErrorRecorder + trace abort 语义。

修改 `ModelStreamConsumer.consume` 增加 cancellation 参数。用 `Mono.fromCompletionStage(cancellation.cancellationSignal())` 生成 cancellation publisher，并在模型 Flux 上使用 `takeUntilOther` 或等价 operator；`blockLast` 返回后必须再次 `throwIfCancellationRequested()`，避免取消被误判成空模型成功。`doOnNext` 在修改 text builder 和调用 sink 前检查 token。保留现有 5 分钟防御超时和 infra/ai retry 单一所有权，不在本计划中重新实现模型 retry。

第六里程碑让 tools 响应同一个 token。给 `ToolExecutionContext` 增加 `CancellationToken cancellation`，删除不传 cancellation 的生产构造路径；测试可以显式传 `CancellationToken.NONE`。修改 `RegistryToolExecutor`：处理每个 call 前检查 token；并行 batch 创建 future 后注册 cancellation callback，对未完成 future 调 `cancel(true)`；join 前后检查 token；`executeSingle` 必须先单独捕获并重新抛出 `OperationCancelledException`，不能被现有 `catch (RuntimeException)` 转成普通 tool error。工具 handler 仍需遵守自身 HTTP/数据库 timeout；token 和 interrupt 是协作机制，不承诺强杀忽略中断的第三方代码。

新增 `RegistryToolExecutorCancellationTest`，用 latch 控制两个并行 handler，取消后断言未开始的 handler 不执行、future 被取消、executor 向上抛 cancellation 而非返回 `ToolExecutionResult.error`。新增 `AgentLoopCancellationTest`，覆盖模型订阅前取消、模型流中取消、工具前取消、取消不写 ErrorRecorder、trace 状态为 CANCELLED、无 completed 事件。

第七里程碑替换 conversation worker executor。扩展 `ConversationProperties`：

    pixflow.conversation.turn-executor.max-concurrency: 16
    pixflow.conversation.turn-executor.keep-alive: 60s

校验 max-concurrency >= 1、keep-alive > 0。`ConversationAutoConfiguration` 使用 `ThreadPoolExecutor`：corePoolSize=0、maximumPoolSize=max-concurrency、workQueue=`SynchronousQueue`、handler=`AbortPolicy`，线程名保持 `conversation-sse`，daemon=true，允许空闲线程回收。不要配置等待队列。执行器拒绝由 session start 转成 `TURN_CAPACITY_EXCEEDED`，prepared lock 必须立即释放。

`SseTurnMetrics` 使用可选 `MeterRegistry`，至少记录：活跃 session gauge、`pixflow.conversation.sse.terminated{reason}`、late write counter、executor rejection counter。日志包含 turnId、conversationId、ownerUserId、traceId（拿到后）、terminationReason、responseCommitted；不要记录 prompt、token、附件内容或 provider 原始 body。

第八里程碑修复前端 SSE 打开阶段的 HTTP 错误解析。把 `pixflow-web/src/api/client.ts` 中 envelope 错误解析提取为 `pixflow-web/src/transport/httpError.ts` 或等价共享 helper，让普通 HTTP client 和 `transport/sse.ts` 共同使用。SSE `fetch` 返回非 2xx 时读取 JSON/text body，保留后端的 `TURN_CAPACITY_EXCEEDED`、`LOCK_ACQUISITION_FAILED`、`CONVERSATION_NOT_FOUND` 等 errorCode/message/traceId，不再统一改成 `HTTP_503`。这不改变已经打开后的 SSE event schema，也不实现自动重试；POST Agent 回合不能由 transport 自动重试。

新增前端测试：503 envelope 能转成 `TURN_CAPACITY_EXCEEDED`；404 conversation error 能保留业务码；非 JSON 代理错误仍回退到 `HTTP_<status>`；用户 AbortSignal 不重复触发 onError 和 onClose。现有 `useAgentTurn` 在打开失败时应进入 error phase，并保留解析后的 ApiError。

第九里程碑补齐 conversation 生命周期和完整异步测试。新增 `SseTurnSessionTest`，用 fake emitter、fake prepared turn、可控 executor 和 latch 覆盖：正常完成；业务错误；send IOException；send IllegalStateException；timeout 与 worker success 同时发生；onCompletion 回调重入；客户端断开；executor rejection；executor 接受后但 callable 启动前取消；重复终止；heartbeat send 与 complete 竞态。每个场景都断言 error frame <= 1、complete <= 1、lock close = 1，并断言 lock close 发生在 emitter complete/transport closed 之后。启动前取消场景必须证明 `FutureTask.done()` 仍释放 prepared turn。

新增 `MessageControllerLifecycleTest`，验证准备阶段错误走 HTTP JSON，成功请求启动 async SSE，worker 错误走 `event: error` + 正常 complete。新增 app 或 auth/conversation 联合集成测试，用真实 SecurityFilterChain + MockMvc async dispatch 复现旧故障：带 JWT 的消息请求启动 async，完成或错误 dispatch 后不抛 `AuthorizationDeniedException`，日志/捕获异常中不包含 `Unable to handle the Spring Security Exception because the response is already committed`。

最后执行设计文档同步。更新 `docs/design-docs/base/common.md`，记录通用 cancellation token/source 的 only-once、signal 和非错误语义。更新 `docs/design-docs/harness/eval.md`，增加 trace `CANCELLED` 状态和与 ABORTED 的区别。更新 `docs/design-docs/harness/tools.md`，记录 `ToolExecutionContext.cancellation`、future cancel 和 handler timeout 要求。更新 `docs/design-docs/harness/loop.md`，记录新 `AgentTurnRequest`、loop cancellation checkpoints、模型 Flux cancellation 和取消不进入 ErrorRecorder。

更新 `docs/design-docs/infra/auth.md` 第八节，明确 REQUEST 鉴权、ASYNC/ERROR permitAll、async worker 不依赖 SecurityContext。更新 `docs/design-docs/module/conversation.md` 第八、九、十五节，替换旧 controller 伪代码和 `completeWithError` 描述，写入 prepare/commit 两阶段、`SseTurnSession` 状态机、terminal ordering、executor capacity、disconnect/timeout cancellation 和锁释放规则。更新 `docs/design-docs/frontend/transport-api.md`、`docs/design-docs/frontend/api.md` 与 `docs/design-docs/web.md`，写明 SSE 打开失败使用 HTTP ApiError，打开后使用 SSE error event，AbortController 会触发后端协作取消但 V1 不续传。更新 `docs/design-docs/index.md`，删除对不存在根级 `docs/design-docs/api.md` 的错误权威引用，明确当前接口摘要路径；不要恢复已删除的旧 API 文档。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

实施前保存当前证据：

    rg -n "dispatcherTypeMatchers|anyRequest\(\)\.authenticated|newCachedThreadPool|completeWithError|SSE client disconnected|blockLast|CompletableFuture\.supplyAsync|AgentTurnRunner" pixflow-infra-auth/src/main/java pixflow-conversation/src/main/java pixflow-loop/src/main/java pixflow-tools/src/main/java docs/design-docs

实施前预期：Security 没有 dispatcher matcher；conversation 使用 cached thread pool；文档仍有 completeWithError；sink 抛 `SSE client disconnected`；模型消费 blockLast；runner 没有 cancellation。

完成第一里程碑后运行：

    mvn -pl pixflow-infra-auth -Dtest=AuthDispatcherSecurityTest,JwtAuthenticationFilterTest test

预期两个测试类通过。新测试在旧代码上应因 ASYNC/ERROR 被 401/403 或抛 AuthorizationDenied 而失败，在新代码上通过。匿名普通 REQUEST 仍必须返回 401。

完成公共取消与 trace 后运行：

    mvn -pl pixflow-common,pixflow-eval -am test

预期 cancellation source only-once、signal completion、throwIfCancelled 和 TurnStatus CANCELLED 往返测试全部通过；现有 COMMITTED/ABORTED 行为不变。

完成 conversation session 后运行：

    mvn -pl pixflow-conversation -am -Dtest=SseTurnSessionTest,TurnPreparationServiceTest,MessageControllerLifecycleTest,SseAgentEventSinkTest test

预期 session 测试覆盖所有 terminal race，旧 `TurnDispatchService` 不再存在，`SseAgentEventSinkTest` 只测试 frame 投影和 send，不测试 emitter lifecycle。

完成 loop/tools 取消后运行：

    mvn -pl pixflow-tools,pixflow-loop,pixflow-agent -am -DskipTests compile
    mvn -pl pixflow-tools -Dtest=RegistryToolExecutorCancellationTest test
    mvn -pl pixflow-loop -Dtest=AgentLoopCancellationTest,ModelStreamConsumerTest,AgentLoopErrorRecoveryTest test
    mvn -pl pixflow-agent -Dtest=AgentOrchestratorTest test

预期编译不再命中旧 runner 签名；取消测试证明模型和工具路径停止，错误恢复测试证明非取消异常仍按原协议处理。

完成前端错误解析后运行：

    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck

预期 SSE transport 测试能保留后端 errorCode，AbortSignal 不产生重复 terminal callback，全部现有 timeline/retry 测试继续通过。

运行全量相关验证：

    mvn -pl pixflow-infra-auth,pixflow-eval,pixflow-tools,pixflow-loop,pixflow-agent,pixflow-conversation,pixflow-app -am test
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck

如果聚合测试被与本计划无关的外部容器或环境依赖阻断，必须记录具体模块、测试类、异常和单模块通过证据；Security dispatcher、SseTurnSession、AgentLoop cancellation、ToolExecutor cancellation 和前端 SSE error parsing 测试不能跳过。

收尾搜索：

    rg -n "newCachedThreadPool|completeWithError|SSE client disconnected|TurnDispatchService|DispatchHandle|stream\(String conversationId, String prompt, List<Attachment>" pixflow-conversation/src/main/java pixflow-loop/src/main/java pixflow-agent/src/main/java docs/design-docs

生产代码和更新后的设计文档不应再命中旧生命周期路径。历史 completed ExecPlan 可以保留旧文字，不作为生产契约。

    rg -n "DispatcherType\.ASYNC|DispatcherType\.ERROR|SseTurnSession|TurnPreparationService|CancellationToken|CANCELLED|TURN_CAPACITY_EXCEEDED|SynchronousQueue" pixflow-common/src/main/java pixflow-infra-auth/src/main/java pixflow-conversation/src/main/java pixflow-loop/src/main/java pixflow-tools/src/main/java pixflow-eval/src/main/java docs/design-docs

这条搜索应命中新机制实现、测试和设计文档。

## Validation and Acceptance

鉴权验收：匿名浏览器 `REQUEST` 调受保护 API 仍得到 401；有效 JWT 调消息 endpoint 正常启动 SSE；同一请求的 ASYNC completion 和 ERROR dispatch 不触发 401/403，不调用 SecurityErrorWriter，不出现 committed-response ServletException。该验收证明修复没有把业务 API 公开，只移除了内部二次鉴权。

准备阶段验收：conversation 不存在、owner 不匹配、conversation busy、附件非法、runner 未装配和 executor 满载都在 controller 返回 emitter 前失败。响应是结构化 HTTP JSON，前端保留具体 errorCode。失败后 heartbeat 未启动、worker 未运行、turn lock 不泄漏。

正常回合验收：fake runner 发送两个 delta 和 completed。客户端按顺序收到事件；session 停止 heartbeat、调用 emitter.complete 一次；onCompletion 回调不触发 cancellation；worker task `done()` 在 complete 调用之后关闭 prepared turn；trace 为 COMMITTED。

业务错误验收：runner 在已经发送一个 delta 后抛 `PixFlowException`。session 发送一次安全 `event: error`，随后调用普通 `complete()`；不调用 `completeWithError`；Security 不处理 ERROR dispatcher；trace 为 ABORTED，ErrorRecorder 记录一次原始业务错误。

客户端断开验收：模型 Flux 尚未完成时触发 emitter onError 或前端 AbortController。session 将 cancellation reason 设为 CLIENT_DISCONNECTED，停止 heartbeat，取消 worker task；ModelStreamConsumer 退出 blockLast；AgentLoop 不 emit error/completed、不调用 ErrorRecorder，trace 为 CANCELLED；task `done()` 收口后锁释放。已经关闭的通道上没有第二次 error send。

超时验收：SSE timeout 与模型 completed 同时触发时只有一个 terminal winner。若 timeout 先赢，回合取消且没有 completed；若 completed 先赢，timeout callback 不反向取消。两种顺序都只有一次 complete/lock close，heartbeat 无残留 scheduled future。

工具取消验收：两个并行工具中一个正在等待 latch，另一个尚未开始。取消后未开始工具不得执行；submitted future 被 best-effort cancel；OperationCancelledException 不被转换成 tool error；同一 conversation 的新回合只能在旧 worker 真正退出并释放锁后开始。

容量验收：把 `max-concurrency` 设为 1，启动一个受控长回合，再发第二个回合请求。第二个请求立即得到 HTTP 503 `TURN_CAPACITY_EXCEEDED`，不创建 SSE、不开 heartbeat、不持有 conversation lock。第一个回合取消并退出后，第三个请求可以正常开始。

前端验收：SSE 打开阶段的 404/409/503 显示后端 message/errorCode；流已打开后收到 `event: error` 仍按当前 reducer 进入 error；浏览器主动 abort 只结束当前 turn，不弹出重复的 STREAM_INTERRUPTED；V1 仍不自动续传或重试 POST。

日志验收：上述全部场景中搜索应用日志，不应出现：

    Unable to handle the Spring Security Exception because the response is already committed
    AuthorizationDeniedException: Access Denied

针对真实未授权初始请求允许出现受控 401 日志，但不能出现在 ASYNC/ERROR dispatcher。metrics 中 terminated reason 与测试场景一致，active gauge 最终回到 0，late_write 为 0。

## Idempotence and Recovery

本计划按里程碑实施时，先增加新类型并更新调用链，再删除旧类型；每个里程碑结束后运行对应测试。不要长期保留新旧 `AgentTurnRunner` 双签名、双 session 路径或 controller feature flag。并行实现只允许作为同一里程碑内的短暂编译过渡，提交前必须移除旧生产入口。

`PreparedTurn.close`、`SseHeartbeat.stop`、`CancellationSource.cancel`、worker task `done()` 和 session terminal gate 都必须幂等，因此失败后可以安全重试测试。不要使用 `git reset --hard` 或恢复用户现有改动。若某个文件同时有用户修改，保留其行为并围绕新边界调整。

如果 cancellation 改造导致 tools 测试大量编译失败，应逐个给测试 `ToolExecutionContext` 显式传 `CancellationToken.NONE`，不要恢复无 token 的生产构造器。如果 trace persistence 不接受 CANCELLED code 3，应修改 mapper/codec/schema 约束并补往返测试，不要把取消退回 ABORTED INTERNAL_ERROR。

如果外部工具 handler 忽略 interrupt，session 不得因为收到 disconnect callback 就提前释放 conversation lock。worker task 只有在 callable 实际退出或 future 在启动前成功取消后才进入 `done()` 并释放锁；运行中的 handler 必须等自身 timeout/返回。记录 cancellation latency，后续可以针对该 handler 补超时，但不能使用 `Thread.stop` 或类似不安全强杀。

如果 MockMvc 无法自然构造 ASYNC/ERROR dispatcher，测试可以通过 request post-processor 设置 `MockHttpServletRequest.setDispatcherType`，或使用真实 async controller + `asyncDispatch(result)`。验收必须覆盖真实 SecurityFilterChain，不能只测试 matcher 配置字符串。

## Artifacts and Notes

当前失败链摘要：

    browser REQUEST with JWT
      -> MessageController returns SseEmitter, response becomes committed
      -> emitter completion / timeout / transport error
      -> Tomcat ASYNC or ERROR dispatch
      -> JwtAuthenticationFilter does not rebuild authentication
      -> anyRequest().authenticated denies
      -> ExceptionTranslationFilter wants to write 403
      -> response already committed
      -> ServletException

目标链摘要：

    REQUEST
      -> JWT authentication
      -> owner-aware TurnPreparationService.prepare
      -> PreparedTurn owns lock
      -> SseTurnSession.start accepted
      -> controller returns emitter

    worker
      -> AgentTurnRequest(cancellation)
      -> AgentLoop checkpoints
      -> Model Flux / ToolExecutor observe same token
      -> success | business failure | cancellation
      -> one terminal gate
      -> stop heartbeat
      -> optional terminal SSE frame
      -> emitter.complete
      -> worker FutureTask.done closes PreparedTurn lock

    ASYNC / ERROR dispatch
      -> Security permitAll because initial REQUEST already authenticated

推荐的 session terminal 伪代码：

    void succeed() {
        if (!state.compareAndSet(RUNNING, TERMINATED)) return;
        heartbeat.stop();
        safeCompleteEmitter();
    }

    void fail(Throwable raw) {
        if (!state.compareAndSet(RUNNING, TERMINATED)) return;
        heartbeat.stop();
        PixFlowException error = errorNormalizer.normalize(raw);
        sink.trySendError(error);
        safeCompleteEmitter();
    }

    void cancel(CancellationReason reason) {
        if (!state.compareAndSet(RUNNING, CANCELLING)) return;
        heartbeat.stop();
        cancellationSource.cancel(reason);
        cancelWorkerFutureBestEffort();
    }

这里的伪代码省略自定义 worker `FutureTask.done()`。锁关闭必须位于 `done()`；仅当 task 从未被 executor 接受时，start 失败路径才直接关闭 prepared turn。不能只把 close 放在 callable 的 finally，因为启动前取消不会进入 callable。

## Interfaces and Dependencies

`pixflow-common` 新增的公共接口应稳定为：

    public interface CancellationToken {
        CancellationToken NONE = ...;
        boolean isCancellationRequested();
        Optional<CancellationReason> reason();
        CompletionStage<Void> cancellationSignal();
        void throwIfCancellationRequested();
    }

    public final class CancellationSource {
        public CancellationToken token();
        public boolean cancel(CancellationReason reason);
    }

    public enum CancellationReason {
        CLIENT_DISCONNECTED,
        TIMEOUT,
        SERVER_SHUTDOWN,
        CALLER_ABORTED
    }

`pixflow-loop` 的 web/agent 边界应变为：

    public record AgentTurnRequest(
            String conversationId,
            String prompt,
            List<Attachment> attachments,
            CancellationToken cancellation) {}

    @FunctionalInterface
    public interface AgentTurnRunner {
        String stream(AgentTurnRequest request, AgentEventSink sink);
    }

conversation 准备接口应为：

    public final class TurnPreparationService {
        public PreparedTurn prepare(long ownerUserId,
                                    String conversationId,
                                    MessageSubmitRequest request);
    }

    public final class PreparedTurn implements AutoCloseable {
        public String execute(AgentEventSink sink, CancellationToken cancellation);
        public String conversationId();
        public long ownerUserId();
        public void close();
    }

SSE session 边界应为：

    public final class SseTurnSession {
        public void start();
        public SseEmitter emitter();
    }

`MessageController` 不应看到 `ExecutorService`、`ScheduledExecutorService`、`SseHeartbeat`、`SseAgentEventSink` 或 `TurnLockHandle`；这些依赖全部由 factory/session 管理。`SseAgentEventSink` 不应拥有 complete/completeWithError。`SseHeartbeat` 不应注册 emitter lifecycle callbacks。

`ToolExecutionContext` 必须显式包含 `CancellationToken`。tools 模块只依赖 common，不依赖 loop 或 conversation。`ModelStreamConsumer` 可以依赖 Reactor 把 JDK `CompletionStage` 适配成 cancellation publisher，但公共 token 本身不依赖 Reactor。

## Revision Notes

2026-07-10 / Codex: 新建本 ExecPlan。原因是用户提供的异常显示 Spring Security 在 SSE 响应提交后的 ASYNC/ERROR dispatcher 上再次授权并失败。静态检查进一步发现生命周期所有权分散、断连误记内部错误、模型/工具不可取消、无界 conversation executor 和前端 SSE 打开错误丢失业务码。计划据此选择完整重构：REQUEST-only 鉴权、prepare/stream 两阶段、单一 SseTurnSession、公共协作取消、trace CANCELLED、有界即时拒绝 executor 和文档契约同步；保留 Spring MVC + SseEmitter、现有 SSE event schema 和 V1 不续传决策。

2026-07-10 / Codex: 完成计划实施并回填实际结果。补充 Redisson 跨线程 owner id、`FutureTask.done()` 早于 callable 退出、`CompletableFuture` 中断限制、Reactor signal 反向取消与 operator 竞态等实现发现；记录各模块完整测试结果、相关生产编译结果，以及与本次改动无关的 context/loop 既有测试阻断。
