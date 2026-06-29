# 完整实现 module/task：异步任务编排外壳（两条产图路径的 worker、调度壳、进度/取消/恢复/下载）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 `module/task` 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。所有架构与边界决策以 `docs/design-docs/module/task.md` 为唯一权威；本计划只补充"如何落地"。

## 设计文档关键词定位索引

执行本计划时，所有架构与边界决策以 `docs/design-docs/module/task.md` 为唯一权威。下列关键词可在该文档内快速定位对应段落：

- 模块核心边界（task 是 dag/imagegen 的异步外壳，3 张表格 + 1 句话总结）→ 搜 `task 是 dag/imagegen 的异步外壳` 或 `一句话`
- 模块结构与依赖位置（Maven 工程结构、24 子包）→ 搜 `模块结构与依赖位置` 或 `api/`
- TaskService 两个对外接口与反向约定 → 搜 `TaskService：对外暴露的唯一入口` 或 `TaskCommandService`
- CreateTaskService：从确认令牌到 process_task 落库 + Idempotency 语义 → 搜 `CreateTaskService` 或 `Idempotency 语义`
- 两条产图路径的统一执行壳（WorkerRouter / ProcessWorker / ImageGenWorker）→ 搜 `WorkerRouter` 或 `统一执行壳`
- WorkUnit 与 WorkUnitScheduler（线程池 + 背压 + 取消 + Redisson 锁）→ 搜 `WorkUnit 与 WorkUnitScheduler`
- 状态机：process_task / process_result 的迁移表 → 搜 `状态机：process_task` 或 `TaskStateMachine`
- ProgressAggregator：Redis 计数 + 实时推送 → 搜 `ProgressAggregator` 或 `progress:task`
- FailureIsolator 与 TerminalStateJudge → 搜 `FailureIsolator` 或 `TerminalStateJudge`
- 断点恢复（RecoveryService + 单元级幂等）→ 搜 `断点恢复：RecoveryService` 或 `RecoveryService`
- 取消：协作式协议 + 工作单元边界检查 → 搜 `取消：协作式协议` 或 `CancellationReader`
- 下载分发（MinIO presigned URL + zip bundle）→ 搜 `下载分发：MinIO Presigned URL`
- TaskErrorCode 11 条错误码目录 → 搜 `TaskErrorCode` 或 `错误与降级`
- 14 类 Micrometer 指标 → 搜 `可观测性` 或 `指标清单`
- 对其他模块的契约 + ArchUnit 反向约束 → 搜 `对其他模块的契约` 或 `反向约束`
- 13 项对 design.md 的细化 → 搜 `对 design.md 与依赖计划的细化`

辅助阅读：`docs/design-docs/design.md`（§9 DAG 确定性执行引擎、§13 数据模型）；`docs/design-docs/module/dag.md`（确定性执行器 SPI / `BranchExpander` / `UnitOutcome`）；`docs/design-docs/module/imagegen.md`（生成式执行器 SPI / `GenerativeUnitSpec`）；`docs/design-docs/harness/state.md`（`CheckpointReadPort` SPI、`RecoveryCoordinator` 协调方、`CancellationReader` 读侧协议）；`docs/design-docs/infra/mq.md`（`MessagePublisher` / `ConsumerErrorHandler` SPI / DLQ 机制 / ack-then-process）；`docs/design-docs/infra/cache.md`（`LockTemplate` / `AtomicCounter` / `CacheStore` / `CacheNamespace`）；`docs/design-docs/infra/storage.md`（`ObjectStorage` / `StorageKeys` / `presignedGetUrl`）；`docs/design-docs/base/common.md`（`PixFlowException` / `ErrorCode` / `Sanitizer`）；`docs/design-docs/harness/tools.md`（task 模块不直接被 tools 消费，但 consumer REST 走 tools 派发）；`docs/design-docs/exec-plans/dag-module-implementation-plan.md`（与本计划对称的确定性核心执行计划，依赖图与 SPI 倒置同构）；`docs/design-docs/exec-plans/imagegen-module-implementation-plan.md`（生成式核心执行计划，task 主动 import `DefaultImageGenExecutor`）。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级的**异步任务编排外壳**，把 Agent 决策落到真实运行。用户能看到的最终效果是：在对话中 Agent 给出"白底 + 800×800 + 压缩 quality 85"或者"这 10 张图用 A 风格重绘"的方案，用户在前端点确认后看到进度条秒级刷新、单元完成 / 失败分项可查、终态判定明确（全部失败 / 部分成功 / 全成功）；任何单张图 / 单条支路失败只隔离该单元，不影响同批其他图；用户中途点取消时已开始的单元会跑完自然结束，未开始的单元标 SKIPPED，整个任务最终落 `PARTIAL` 终态；任务在 worker 崩溃或重启后能根据 `process_result` 跳过已成功单元、把未完成单元整条支路幂等重算；运维面板能看到 `pixflow.task.*` 14 类指标，按 `create` / `worker` / `heartbeat` / `recovery` / `cancel` / `terminal` / `progress` / `download` / `lock` / `state.transition` 10 组维度组织。

本计划不是 MVP。实现必须覆盖完整的 TaskCommandService / TaskQueryService 两套对外 API、`IdempotencyGuard` 防重、`process_task.dag_json` 共用列 + `task_type` 分流、`WorkerRouter` 派发 + `ProcessWorker` / `ImageGenWorker` 共壳调用、`WorkUnitScheduler` 双线程池 + 信号量 + 背压 + 取消检查、`TaskStateMachine` / `ResultStateMachine` 7 态 / 5 态纯函数（属性测试覆盖）、`ProgressAggregator` Redis 计数 + WebSocket 推送、`FailureIsolator` 单单元隔离 + 终态判定、`RecoveryService` `@Scheduled` 重扫 + 单元级幂等、协作式取消、`DownloadService` + `DownloadBundleBuilder`（`max-bundle-bytes` 兜底）、MQ 重试与 DLQ 监听、14 类 `pixflow.task.*` 指标、ArchUnit 6 条反向约束守护，以及一组能证明"task 是 dag/imagegen 的异步外壳、确定性底座不被污染"的 Sentinel 单测。

## Progress

<!-- 在执行过程中按时间顺序追加条目，每条带 ISO 时间戳。 -->

## Surprises & Discoveries

<!-- 实现过程中发现的意料之外的点（库行为、依赖冲突、API 不匹配等），每条带证据片段。 -->

## Decision Log

<!-- 记录执行期间所有重要决策、理由、时间 / 作者。 -->

## Outcomes & Retrospective

<!-- 完成度、关键交付清单、设计 vs 实现一致性、实施期发现、留待下个迭代等。 -->

## Context and Orientation

### 当前状态

`docs/design-docs/module/task.md` 已经完成生产级细化设计（2026-06-29 Kiro 撰写，923 行），确立了 8 项模块专属设计原则、3 张「模块边界对照表」、24 子包 Maven 工程结构、TaskCommandService / TaskQueryService 两套对外 API、CreateTaskServiceImpl 完整时序、WorkerRouter + ProcessWorker + ImageGenWorker 统一执行壳、WorkUnitScheduler 双线程池 + 背压 + Redisson 锁、TaskStateMachine 7 态 + ResultStateMachine 5 态纯函数迁移表、ProgressAggregator Redis 计数 + 实时推送、FailureIsolator 单单元隔离 + TerminalStateJudge 终态判定、RecoveryService `@Scheduled` 重扫 + 单元级幂等、协作式取消 + 工作单元边界检查、DownloadService 单结果 + DownloadBundleBuilder 批量打包（`max-bundle-bytes` 兜底）、11 条 `TaskErrorCode` 错误码目录、14 类 `pixflow.task.*` Micrometer 指标、ArchUnit 6 条反向约束守护，以及 12 项对 design.md / `module-dependency-dag-plan.md` / dag.md / imagegen.md 的细化建议。

仓库根目录 `pom.xml` 已有 `pixflow-common` / `pixflow-contracts` / `pixflow-infra-mq` / `pixflow-infra-cache` / `pixflow-infra-storage` / `pixflow-module-dag` / `pixflow-module-imagegen` / `pixflow-harness-state` / `pixflow-harness-hooks` / `pixflow-harness-permission` 等依赖可被 `pixflow-module-task` 引用。`pixflow-module-dag` 已先实现并完成全部 4 个里程碑（IR / Validator / Expander、UnitExecutor 骨架 + 错误归一化、Group / Copy 执行器 + watermark 缓存 + DagFacade、pending_plan 表 + SubmitImagePlanHandler + SchemaRegistryValidator），`pixflow-module-imagegen` 已先实现 5 个里程碑（提案侧纯函数、SPI 接缝 + DefaultImageGenExecutor、`ImagegenConfirmationSupport` + `ImagegenMetrics` + 6 类指标、ToolHandler 接线 + ImagegenPlanService、pixflow-app 装配回归 + Sentinel 守护）。`pixflow-infra-mq` 已实现 `MessagePublisher`（Publisher Confirms）、`ConsumerErrorHandler` SPI、原生 TTL + DLX 延迟重试、ack-then-process 消费模型、`MessageEnvelope` 信封、`QueueTopologyBuilder` 声明式拓扑；`pixflow-infra-cache` 已实现 `CacheNamespace` 键工厂、`CacheStore` 带 TTL 泛型 KV、`AtomicCounter` 原子计数、`LockTemplate` 看门狗分布式锁模板、`DistributedSemaphore` 许可带租约的信号量；`pixflow-harness-state` 已实现 `CheckpointReadPort` SPI（由 task 实现）、`RunStateRefStore` 引用读写 facade、`ProgressReader` 双源对账、`CancellationReader` 协作式取消读协议、`RecoveryCoordinator` 可跳过单元计算（MySQL 权威）、`ExecutionStateService` 统一状态快照。本计划在 Wave 4 的 task 模块把这套设施串成完整的异步编排壳。

### 关键术语

- **task 模块（Wave 4 主循环 + 编排模块）**：消费 RabbitMQ 任务消息、按 `[图片×支路]/[组×支路]`（确定性路径）与 `[1 源图→1 重绘]`（生成式路径）两种单元粒度 fan-out、`process_task` / `process_result` 落库、Redis 进度 / 取消 / 锁协调、下载分发、两条产图路径的共用执行壳、断点恢复扫描（`task.md §一`）。
- **异步外壳 vs 确定性核心**：task 是 `module/dag` 与 `module/imagegen` 的**有状态异步执行壳**——把任务消息拆成工作单元、调线程池 fan-out、落 `process_result`、管进度 / 锁 / 取消 / 恢复 / 下载。task 内**不出现**任何像素工具逻辑、任何 DAG 节点派发、任何生图模型调用。dag 与 imagegen 是被 task **逐单元调用**的纯服务（无状态确定性库 / 无状态能力模块）。
- **两条产图路径共用同一套异步壳**：`WorkerRouter` 按 `process_task.task_type` 路由到 `ProcessWorker`（调 `dag.UnitExecutor`）或 `ImageGenWorker`（调 `imagegen.ImageGenExecutor`）；调度壳（线程池 / 进度 / 锁 / 取消 / 恢复 / 下载）100% 复用（`task.md §一.3`）。
- **MySQL 是事实源，Redis 只做同次运行内的轻量优化**：持久断点以 `process_result` 为唯一权威（`design.md §5.4 / §9.4`）；Redis 进度 / 取消 / 引用均为可丢可重建；**原始字节一律不进 Redis**（`task.md §一.2`）。
- **状态机迁移是显式纯函数**：`TaskStateMachine` / `ResultStateMachine` 纯函数（**不在数据库触发器里**、**不在 worker 的 if-else 里**），可被属性测试覆盖（`task.md §一.4`）。
- **失败隔离是 task 的核心契约**：单个工作单元失败 → 该 `process_result` 标 FAILED、记脱敏 `error_msg`、不保留半成品 `output_path`；同图其余支路、批次其余图片、其他组继续（`task.md §一.5`）。
- **取消是协作式协议，绝不打断运行中的工作单元**：task worker 在工作单元**边界**检查 `cancel:task:*` 标志；像素工具调用、模型调用、MinIO I/O **不在中途打断**（避免数据损坏）。已开始的单元跑完自然结束（`task.md §一.6`）。
- **API 与 internal 物理隔离**：`api/` 包对其他模块可见，`internal/` 包封装类全部包内可见。agent / conversation 只 import `api`，**看不到 worker / scheduler / recovery / cancel 等内部细节**（`task.md §一.8`），ArchUnit 守护。
- **`UnitKey`**：`harness/state` 模块定义的（`taskId, kind, memberId, branchId`）中立 record，是 task worker 与 `RecoveryCoordinator` 协调恢复跳过集合的唯一词汇（`state.md §五`）；本计划在 `domain/model/UnitKind` 镜像一份 enum（`BRANCH` / `GROUP` / `GENERATIVE`），对齐 `state.UnitKind` 多一个 `GENERATIVE` 给生成式单元用。
- **CheckpointReadPort**：`harness/state` 定义的 SPI，本模块的 `CreateTaskServiceImpl` / `RecoveryService` / `TerminalStateJudge` 通过它读 `process_result` 成功单元集 + 终态权威计数（`state.md §六`）；task **实现**而非消费这一 SPI。
- **`module/conversation` 确认 REST 边界**：`permission.verifyAndConsume` 通过 → 算 `payloadHash` / `expectedCount` → 调 `TaskCommandService.createAndEnqueue(cmd)`；task 负责创建 `process_task` + 发 MQ。依赖方向：`conversation → task`（`task.md §一.8 / §四`）。
- **`Idempotency-Key`**：REST 边界要求客户端传的 HTTP header；同 key 重复请求 → 直接返回原 taskId；key 写入 Redis `task:idempotency:{key}`（24h TTL）+ MySQL `process_task.idempotency_key` UNIQUE 兜底（`task.md §五.2`）。
- **`process_task.dag_json` 共用列 + `task_type` 分流**：确定性路径存 `ValidatedDag` 序列化、生成式路径存 `ImagegenPlan`（有序 sourceImageIds + prompt + params）；worker 据 `task.taskType()` 反序列化（`task.md §五.3`）。
- **PayloadHasher 共享 Canonical Form**：确定性 `ValidatedDag` 用 dag 的 `CanonicalJson` 算 payload_hash；生成式 `ImagegenPlan` 用 imagegen 的 `ImagegenPayloadHasher`（按 imageId 字典序 + prompt trim + params 白名单归一）；两套规范化逻辑由各自模块负责，task 不重写（`task.md §十九.10` + dag.md §7.4 + imagegen.md §七）。
- **`@RabbitListener` + ack-then-process**：消费回调在「成功接管」（抢到锁 + 置 RUNNING + 提交线程池）后立即 ack，**不**等整批跑完（与 `infra/mq.md §六` 一致）。
- **Resilience4j 重试 + Redisson 信号量**：单工作单元层面的重试 / 限流由 `infra/thirdparty` 与 `infra/ai` 各自封装（`infra/cache.md §十`）；task **不再**自建工作单元级重试层——这与 imagegen.md §八「ai 内置重试、不在 task 重试」口径一致。
- **WebSocket 归属**：`task.md §十九.8` 明确——task **不直连** WebSocket / STOMP / SSE；进度推送由 `harness/state` 的 `ProgressEventPublisher`（app 级实现）负责；task 只发 `ProgressEvent`（domain event）到 publisher。这是 `design.md §5.4` 的细化（应改为 state 模块）。

### 模块结构与依赖位置

源码包：`com.pixflow.module.task`。Maven 模块：`pixflow-module-task`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。

```
module/task/
├── api/                          # 对外暴露（其他模块只能依赖此包）
│   ├── TaskCommandService.java
│   ├── TaskQueryService.java
│   ├── command/
│   │   ├── CreateTaskCommand.java
│   │   └── CancelTaskCommand.java
│   ├── query/
│   │   ├── TaskStatusView.java
│   │   ├── TaskSummary.java
│   │   ├── ResultSelector.java
│   │   ├── DownloadHandle.java
│   │   └── PageQuery.java
│   └── event/
│       ├── ProgressEvent.java
│       ├── TaskCreatedEvent.java
│       └── TaskCompletedEvent.java
├── domain/
│   ├── model/
│   │   ├── ProcessTask.java          # process_task 表 ORM 实体
│   │   ├── ProcessResult.java        # process_result 表 ORM 实体
│   │   ├── ProcessResultMember.java  # process_result_member 表 ORM 实体
│   │   ├── WorkUnit.java             # 内部值对象：图片×支路 / 组×支路 / 1 源图→1 重绘
│   │   ├── TaskType.java             # enum: IMAGE_PROCESS | IMAGE_GEN
│   │   ├── TaskStatus.java           # enum: PENDING/QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/PARTIAL
│   │   ├── ResultStatus.java         # enum: PENDING/RUNNING/SUCCESS/FAILED/SKIPPED
│   │   └── UnitKind.java             # enum: BRANCH | GROUP | GENERATIVE（对齐 state.UnitKind 多一个 GENERATIVE）
│   ├── statemachine/
│   │   ├── TaskStateMachine.java     # 纯函数：合法迁移表 + canTransit/from/to
│   │   └── ResultStateMachine.java   # 纯函数
│   ├── idempotency/
│   │   ├── IdempotencyKey.java
│   │   └── IdempotencyGuard.java     # 防重复创建任务（Redis + MySQL UNIQUE 兜底）
│   ├── branch/
│   │   ├── BranchInstance.java       # 内部 record：来自 dag.UnitOutcome 的支路实例
│   │   └── CopyContext.java          # 来自 dag（中立投影，不直连 asset_copy；imagegen 重算自资产_copy 不由 task 持）
│   ├── progress/
│   │   └── ProgressSnapshot.java     # 内部 record：total/done/failed 计数
│   └── error/
│       └── TaskErrorCode.java        # enum implements common.ErrorCode
├── internal/
│   ├── create/
│   │   └── CreateTaskServiceImpl.java   # @Component 实现 TaskCommandService
│   ├── cancel/
│   │   └── CancellationService.java     # 取消标志设置 + 单元间检查（边界）
│   ├── worker/
│   │   ├── TaskWorker.java              # 入口：@RabbitListener 消费
│   │   ├── WorkerRouter.java            # 按 task_type 路由
│   │   ├── ProcessWorker.java           # 调 dag.UnitExecutor
│   │   ├── ImageGenWorker.java          # 调 imagegen.ImageGenExecutor
│   │   └── UnitExecutionContext.java    # 锁/cancel token/attempt 计数 上下文
│   ├── scheduler/
│   │   ├── WorkUnitScheduler.java       # 线程池 + 信号量 + 背压
│   │   └── WorkUnitSubmitter.java       # 提交到线程池，包装为 CompletableFuture
│   ├── branch/
│   │   └── BranchExecutionAdapter.java  # 包一层 dag.UnitExecutor，喂 ImageDescriptor/CopyContext
│   ├── progress/
│   │   └── ProgressAggregator.java      # Redis incr + 推 ProgressEvent
│   ├── failure/
│   │   └── FailureIsolator.java         # 把异常归一化为 ResultStatus.FAILED + 脱敏 error_msg
│   ├── terminal/
│   │   └── TerminalStateJudge.java      # 据 progress 判定 COMPLETED/FAILED/PARTIAL/CANCELLED
│   ├── recovery/
│   │   ├── RecoveryService.java         # @Scheduled 扫 status=RUNNING 重新入队
│   │   └── HeartbeatWriter.java         # worker 周期性写心跳
│   ├── download/
│   │   ├── DownloadService.java         # 拼 presigned URL
│   │   └── DownloadBundleBuilder.java   # 打包 zip（多结果批量下载）
│   ├── query/
│   │   └── TaskQueryServiceImpl.java    # @Component 实现 TaskQueryService
│   ├── publish/
│   │   └── TaskEventPublisher.java      # 发 TaskCreatedEvent/TaskCompletedEvent
│   ├── mq/
│   │   ├── TaskMessageListener.java     # @RabbitListener（在 infra/mq 之上接 TaskWorker）
│   │   └── ConsumerErrorHandlerImpl.java # 实现 infra/mq.ConsumerErrorHandler SPI（按 common recovery 给 RetryDecision）
│   └── stateadapter/
│       └── CheckpointReadPortImpl.java  # 实现 harness/state.CheckpointReadPort SPI
├── infra/
│   ├── persistence/
│   │   ├── ProcessTaskMapper.java       # MyBatis-Plus
│   │   ├── ProcessResultMapper.java
│   │   └── ProcessResultMemberMapper.java
│   ├── cache/
│   │   ├── TaskCacheKeys.java           # key 命名空间集中
│   │   ├── TaskProgressCounter.java     # AtomicLong incr / get
│   │   ├── TaskCancelFlag.java          # cancel:task:{id} 读写
│   │   ├── TaskIdempotencyStore.java    # task:idempotency:{key}
│   │   ├── TaskHeartbeatStore.java      # task:heartbeat:{taskId}:{workerId}
│   │   └── TaskLockManager.java         # Redisson 看门狗执行器封装
│   └── metrics/
│       └── TaskMetrics.java             # 14 类 Micrometer 指标
└── bootstrap/
    └── TaskAutoConfiguration.java      # @Configuration 装配
```

依赖方向（实现期严格遵守，违反则单元测试用 ArchUnit 守护）：

```
module/task ──► infra/mq        （任务消息 publish/consume、DLQ 监听、重试模板 — ConsumerErrorHandler SPI）
module/task ──► infra/cache     （Redisson 锁/进度/取消/idempotency/heartbeat — LockTemplate/AtomicCounter/CacheStore）
module/task ──► infra/storage   （结果落 MinIO、presigned URL 生成 — ObjectStorage + StorageKeys）
module/task ──► module/dag      （BranchExpander.expand / UnitExecutor.execute / ImageDescriptor / CopyContext）
module/task ──► module/imagegen （ImageGenExecutor.redraw / GenerativeUnitSpec；Wave 4 真正消费）
module/task ──► harness/state   （CheckpointReadPort SPI 由本模块实现；CancellationReader 消费读协议）
module/task ──► common          （PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer）
module/task ──► MySQL/MyBatis-Plus（process_task / process_result / process_result_member 3 张表）

module/conversation ──► module/task（确认 REST 端点调 TaskCommandService.createAndEnqueue）
harness/hooks       ──► module/task（订阅 TaskCreatedEvent / TaskCompletedEvent）
agent               ──► module/task（执行期查询 TaskQueryService.getStatus / subscribe）
```

**反向约束**（ArchUnit 守护）：`com.pixflow.module.task..` **不依赖** `module/dag` 的 `ir/validate/propose` 子包（只调用 `expand/exec` 子包）、**不依赖** `module/file`（不直连 `asset_*` 表，由 conversation / agent 喂入中立投影）、**不依赖** `harness/loop` / `agent` / `harness/tools` / `harness/permission` / `harness/context` / `harness/session` / `harness/eval`，**不出现** Spring AI 注解、**不调用** LLM。

## Plan of Work

按 5 个里程碑从内核到外壳、从纯函数到 Spring 装配推进；每个里程碑结束都有「单测全绿 + 验证命令」的可观察断言。

### 里程碑 1：纯函数核心 + 数据模型骨架（最优先、最易测、零外部依赖）

**目标**：搭建 Maven 模块骨架 + `domain/model` 实体 + `domain/statemachine` 纯函数 + `domain/idempotency` 幂等键模型。能脱离 Spring 上下文完整单测，先把"状态机迁移"与"幂等键形状"这两条最容易漂移的口径钉死。

**新增文件**：

- `pixflow-module-task/pom.xml`：依赖 `pixflow-common`（错误模型 + 脱敏）、`pixflow-contracts`（确认令牌 SPI 接口，但本模块不依赖确认令牌本身，仅镜像 `TaskId` / `ConversationId` 等中立 ID 类型）、`pixflow-harness-state`（`UnitKey` 镜像 enum + `CheckpointReadPort` SPI 接口）、`jackson-databind`、`mybatis-plus-spring-boot3-starter`。
- 根 `pom.xml` 的 `<modules>` 新增 `<module>pixflow-module-task</module>`；`dependencyManagement` 注入本模块版本号。
- `src/main/java/com/pixflow/module/task/domain/model/`：`TaskType.java`（enum `IMAGE_PROCESS` / `IMAGE_GEN`）、`TaskStatus.java`（enum 7 态：`PENDING`/`QUEUED`/`RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED`/`PARTIAL`，每态带 int code 与 `isTerminal()`）、`ResultStatus.java`（enum 5 态：`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`/`SKIPPED`）、`UnitKind.java`（enum `BRANCH` / `GROUP` / `GENERATIVE`，对齐 `state.UnitKind` 多一个 `GENERATIVE`）、`ProcessTask.java`（ORM 实体，13 个生产级字段：`task_type` / `idempotency_key`（UNIQUE） / `priority` / `cancel_reason` / `enqueued_at` / `started_at` / `finished_at` / `worker_id` / `attempt_count` / `last_error` + design.md §13.1 原有 8 个字段）、`ProcessResult.java`（ORM 实体，4 个生产级增量字段：`attempt_count` / `started_at` / `finished_at` / `bytes_in` / `bytes_out` / `worker_run_id` + design.md §13.1 原有 9 个字段）、`ProcessResultMember.java`（组支路成员明细）、`WorkUnit.java`（内部 record：`UnitKind` + `taskId` + `UnitKey` + `WorkUnitPayload`）、`WorkUnitPayload.java`（内部 record：`ObjectLocation sourceLocation` + `CopyContext copyContext`（可空） + `GenerativeUnitSpec generativeSpec`（可空） + `BranchSpec branchSpec`（可空））。
- `src/main/java/com/pixflow/module/task/domain/statemachine/`：`TaskStateMachine.java`（final class，private constructor，纯函数集合，含 `ALLOWED` Map<TaskStatus, Set<TaskStatus>> + `canTransit(TaskStatus from, TaskStatus to)` 抛 `IllegalStateTransitionException` + `from(TaskStatus to)` / `to(TaskStatus from)` 便捷方法）、`ResultStateMachine.java`（同上，5 态迁移表）。
- `src/main/java/com/pixflow/module/task/domain/idempotency/`：`IdempotencyKey.java`（record 包装 `String` value，非空校验 + 长度上下界）、`IdempotencySnapshot.java`（record 包装 `taskId` + `taskStatus`，从 Redis 反序列化的形态）。
- `src/main/java/com/pixflow/module/task/domain/progress/`：`ProgressSnapshot.java`（record `total` / `done` / `failed`，含 `incrementDone()` / `incrementFailed()` / `merge(...)` 等纯函数）。
- `src/main/java/com/pixflow/module/task/domain/branch/`：`BranchInstance.java`（record：`branchId` / `memberId` / `unitKind` / `outputs` Map<String,String>，来自 dag.UnitOutcome 的支路实例投影）、`CopyContext.java`（record 镜像：`skuId` / `productName` / `keywords` / `description`，中性投影，不直连 asset_copy；本模块**不持**，`WorkUnit` 实际承载的是传给 dag 的 `dag.CopyContext`，import 一份避免反向依赖）。
- `src/test/java/com/pixflow/module/task/domain/`：纯函数单测。
- `src/test/java/com/pixflow/module/task/architecture/`：`TaskModuleArchitectureTest.java`（ArchUnit 6 条反向约束，见 `task.md §十七.3`）。

**关键实现细节**：

- `TaskStateMachine.ALLOWED` 必须**穷举**所有合法迁移（见 `task.md §八.1 / §八.2`）：
  - `PENDING → {QUEUED, FAILED}`
  - `QUEUED → {RUNNING, CANCELLED}`
  - `RUNNING → {COMPLETED, FAILED, CANCELLED, PARTIAL}`
  - `COMPLETED → {}`（终态）
  - `FAILED → {}`（终态）
  - `CANCELLED → {}`（终态）
  - `PARTIAL → {}`（终态）
- `ResultStateMachine.ALLOWED`：`PENDING → {RUNNING, SKIPPED}` / `RUNNING → {SUCCESS, FAILED}` / `SKIPPED → {}` / `SUCCESS → {}` / `FAILED → {}`。
- `TaskStateMachine.canTransit` 命中非法迁移抛 `IllegalStateTransitionException`（继承 `RuntimeException`，带 from/to 字段）；**所有写库代码**必须经过这一校验（`task.md §八`），但**不在数据库触发器**——强制在 `@Component` 写入口校验。
- `ProcessTask.idempotency_key` 字段加 `@TableField(value = "idempotency_key")` + 在 MyBatis-Plus 配置里手动加 UNIQUE 索引注解（SQL 迁移脚本同步）。
- `UnitKind` 是 `state.UnitKind` 的镜像 enum（多一个 `GENERATIVE`），`BranchExpander.expand` 返回的 `ExecutableBranch.kind` 是 `BRANCH | GROUP`，本模块在装配为 `WorkUnit` 时把 `ImagegenPlan.sourceImageIds` 映射为 `GENERATIVE`。

**测试要点**：

- `TaskStateMachineTest`：属性测试（jqwik）随机 from / to 组合，断言**仅合法迁移通过**；非法迁移抛 `IllegalStateTransitionException` 且异常 from/to 字段正确。
- `ResultStateMachineTest`：同上，5 态空间。
- `TaskStatusTest`：每态 `isTerminal()` 判定正确（`COMPLETED` / `FAILED` / `CANCELLED` / `PARTIAL` 返回 true）。
- `IdempotencyKeyTest`：null / 空串 / 超长 / 含特殊字符 边界。
- `ProgressSnapshotTest`：merge / incrementDone / incrementFailed 纯函数行为，含 done + failed ≤ total 不变量。
- `TaskModuleArchitectureTest`（ArchUnit 6 条）：
  1. `com.pixflow.module.task..` 不依赖 `module/file`（直连 `asset_*` 表的子包名）。
  2. 不依赖 `harness/loop` / `agent` / `harness/tools` / `harness/permission` / `harness/context` / `harness/session` / `harness/eval`。
  3. 不依赖 `module/dag.ir` / `module/dag.validate` / `module/dag.propose` 子包（只调 `expand/exec`）。
  4. 不出现 `org.springframework.ai` 注解 / import。
  5. 不出现 `MyBatis-Plus` / `JDBC` 引用除非在 `infra/persistence/` 子包下。
  6. `com.pixflow.module.task.internal..` 所有类**非 public**（package-private 或 default）。

**验证**：`mvn -pl pixflow-module-task -am compile` 不依赖 Spring；`mvn -pl pixflow-module-task -am test` 全绿（含 ArchUnit）。

### 里程碑 2：mapper + SQL 迁移 + CheckpointReadPort 实现

**目标**：建 `process_task` / `process_result` / `process_result_member` 三张表 + MyBatis-Plus mapper + 实现 `harness/state.CheckpointReadPort` SPI。把"事实源读写"这一层做掉，让里程碑 3 的 worker 可以直接调 mapper 落库。

**新增文件**：

- `src/main/resources/db/migration/Vxxx__create_process_task.sql`：表结构（见 `task.md §八 / §十九.5`），含 `idx_process_task_status_started`（恢复扫描）、`uq_process_task_idempotency_key`（幂等兜底）。
- `src/main/resources/db/migration/Vxxx__create_process_result.sql`：表结构，含 `idx_process_result_task_image_branch` / `idx_process_result_task_group_branch`（单元幂等查询）。
- `src/main/resources/db/migration/Vxxx__create_process_result_member.sql`：组支路结果成员明细表。
- `src/main/java/com/pixflow/module/task/infra/persistence/`：`ProcessTaskMapper.java`（BaseMapper<ProcessTask> + 自定义方法 `findByStatusAndStartedAtBefore` / `findByIdempotencyKey` / `updateStatusWithOptimisticLock`）、`ProcessResultMapper.java`（含 `upsertSkippingSuccess` / `findSucceededUnitKeys(taskId, kind)` 用于 CheckpointReadPort）、`ProcessResultMemberMapper.java`。
- `src/main/java/com/pixflow/module/task/internal/stateadapter/`：`CheckpointReadPortImpl.java`（`@Component implements harness.state.CheckpointReadPort`，5 个方法：`loadCompletedUnits` / `loadCounts` / `loadTaskStatus` / `listRunningTaskIds` / `PersistedCounts` 投影）。
- `src/main/java/com/pixflow/module/task/internal/worker/`：`UnitExecutionContext.java`（record 封装 `taskId` / `workerId` / `attemptCount` / `cancelToken`）。
- `src/test/java/com/pixflow/module/task/infra/persistence/`：mapper 单测（用 `@MybatisPlusTest` + H2 / Testcontainers MySQL）。
- `src/test/java/com/pixflow/module/task/internal/stateadapter/`：`CheckpointReadPortImplTest`（H2 + 真实 mapper，断言 `loadCompletedUnits` 只返回 `SUCCESS` 记录；`loadCounts` 按 status 聚合正确；`listRunningTaskIds` 含时间过滤）。

**关键实现细节**：

- `process_result.upsertSkippingSuccess(taskId, imageId|groupKey, branchId, ...)` 用 `INSERT ... ON DUPLICATE KEY UPDATE`（MySQL upsert）。重跑覆盖旧记录，**仅 status=RUNNING/PENDING 时覆盖**；`SUCCESS` 跳过（防破坏 checkpoint）。
- `process_task.updateStatusWithOptimisticLock` 用 `updated_at` 字段做乐观锁（`UPDATE ... WHERE id=? AND updated_at=?`），并发重投天然续跑。
- `loadCompletedUnits`：先按 `kind=BRANCH` 查 `image_id != NULL` 的 SUCCESS 行；再按 `kind=GROUP` 查 `group_key != NULL AND image_id IS NULL` 的 SUCCESS 行；最后按 `kind=GENERATIVE`（本模块新增）查 `branch_id = 'gen'` 的 SUCCESS 行；三种合并去重返回 `Set<UnitKey>`。
- `loadCounts`：单 SQL `SELECT COUNT(CASE WHEN status='SUCCESS' THEN 1 END), COUNT(CASE WHEN status='FAILED' THEN 1 END), COUNT(*) FROM process_result WHERE task_id=?`。

**测试要点**：

- mapper 单测：每张表的 CRUD + 唯一约束（重复 idempotency_key 抛 `DuplicateKeyException`）；upsert 仅 RUNNING/PENDING 覆盖、SUCCESS 不动。
- `CheckpointReadPortImplTest`：造 5 条 SUCCESS + 2 条 FAILED 的结果，断言 `loadCompletedUnits.size() == 5`；造 RUNNING + 30 分钟前的 started_at 断言 `listRunningTaskIds` 命中；空 taskId 抛 `STATE_TASK_NOT_FOUND`。

**验证**：`mvn -pl pixflow-module-task -am test` 全绿；H2 in-memory 与 Testcontainers MySQL 都跑（Testcontainers 按 env 跳过策略）。

### 里程碑 3：执行壳 + 状态机迁移点（worker / scheduler / failure / terminal / cancel）

**目标**：实现 `WorkUnitScheduler` + `WorkUnitSubmitter` + `WorkerRouter` + `ProcessWorker` + `ImageGenWorker` + `FailureIsolator` + `TerminalStateJudge` + `CancellationService` + `BranchExecutionAdapter` + `TaskMetrics`。**全部走 fake infra 端口 + fake dao 桩做单元测试**，先把 worker 内部的状态机迁移点、并发模型、失败隔离、终态判定、取消边界**全部闭环**。这一步是模块的核心，实现完毕后再装 MQ / Redis 真实设施。

**新增文件**：

- `src/main/java/com/pixflow/module/task/infra/cache/`：`TaskCacheKeys.java`（`taskIdempotencyKey(key)` / `taskProgressKey(taskId)` / `taskCancelKey(taskId)` / `taskHeartbeatKey(taskId, workerId)` / `taskExecutionLockKey(taskId)` 五个工厂方法）、`TaskIdempotencyStore.java`（接口 + 内部默认实现，组合 `infra/cache.CacheStore`；用 Lua 脚本 `SET key value NX EX 86400` 原子占位）、`TaskHeartbeatStore.java`（写心跳 + 检查存活；TTL 30s）、`TaskCancelFlag.java`（CacheStore 包装）、`TaskLockManager.java`（`LockTemplate.runWithLock(taskExecutionLockKey(taskId), waitTime, action)`）。
- `src/main/java/com/pixflow/module/task/internal/scheduler/`：`WorkUnitScheduler.java`（管两个 `ThreadPoolExecutor`：`process-pool` (core=8, max=16, queue=1000, CallerRunsPolicy) + `imagegen-pool` (core=4, max=8, queue=200, CallerRunsPolicy)；`submit(WorkUnit)` 返回 `CompletableFuture<UnitOutcome>`；`awaitAll(Collection<WorkUnit>)` 用 `CompletableFuture.allOf().join()` + `judgeTimeout` 兜底）、`WorkUnitSubmitter.java`（包装 `submit`、异常捕获 → `ResultStatus.FAILED` 永不抛出、unit 间取消检查）。
- `src/main/java/com/pixflow/module/task/internal/worker/`：`TaskWorker.java`（接口 `handle(TaskMessage)`）、`WorkerRouter.java`（`switch (msg.taskType()) { case IMAGE_PROCESS → processWorker.handle; case IMAGE_GEN → imageGenWorker.handle; }`）、`ProcessWorker.java`（按 `task.md §6.2` 实现：load task + load images (fake FileReader) + `BranchExpander.expand` → `List<ExecutableBranch>` → 装配 `WorkUnit` → 调 `state.RecoveryCoordinator.computeSkippedSet` → 过滤跳过单元 → `scheduler.submit` → `awaitAll` → `terminalJudge.judge`）、`ImageGenWorker.java`（按 `task.md §6.3` 实现：load task + 反序列化 `ImagegenPlan` → `List<WorkUnit>` (GENERATIVE) → 跳过集计算 → submit → judge）、`UnitExecutionContext.java`（已在里程碑 2）。
- `src/main/java/com/pixflow/module/task/internal/branch/`：`BranchExecutionAdapter.java`（把 `dag.UnitExecutor` 包一层，喂 `dag.ImageDescriptor` / `dag.CopyContext` 中立输入；返回 `dag.UnitOutcome`）。
- `src/main/java/com/pixflow/module/task/internal/progress/`：`ProgressAggregator.java`（`increment(taskId, kind)` → Redis `INCR progress:task:{taskId}` + 推 `ProgressEvent{taskId, done, total, failed}`；`finalize(taskId)` → 终态后清理 progress key）。
- `src/main/java/com/pixflow/module/task/internal/failure/`：`FailureIsolator.java`（`handle(unit, throwable)` → 归一化异常 → `Sanitizer.sanitizeMessage` → 写 `process_result(status=FAILED, error_msg, attempt_count++)` → `progress.incrementFailed` → 推 `ProgressEvent` → **不抛**）。
- `src/main/java/com/pixflow/module/task/internal/terminal/`：`TerminalStateJudge.java`（`judge(taskId)` → 等所有 `CompletableFuture` 完成（`allOf().join()` + `judgeTimeout=60s`）→ 统计 `doneSet`（SUCCESS/FAILED/SKIPPED）→ 写 `process_task.status` 终态 + `finished_at` + 推 `TaskCompletedEvent` → 清理心跳 + 释放锁 + `progressAggregator.finalize`）。
- `src/main/java/com/pixflow/module/task/internal/cancel/`：`CancellationService.java`（`requestCancel(taskId)` → `CacheStore.put(cancelKey, true)` + 状态机 `QUEUED → CANCELLED` / `RUNNING → PARTIAL`；`isCancelled(taskId)` → CacheStore.get，与 `harness/state.CancellationReader.throwIfCancelled` 对齐）。
- `src/main/java/com/pixflow/module/task/infra/metrics/`：`TaskMetrics.java`（14 类指标，见 `task.md §十六.1`）。
- `src/main/java/com/pixflow/module/task/internal/publish/`：`TaskEventPublisher.java`（发 Spring `ApplicationEvent` 包装 `TaskCreatedEvent` / `TaskCompletedEvent`，由 `harness/hooks` 订阅）。
- `src/test/java/com/pixflow/module/task/internal/`：所有 internal 子包的单元测试。
- `src/test/java/com/pixflow/module/task/integration/`：`WorkerEndToEndTest`（fake infra 端口 + fake dao + fake MQ，跑通 create → worker → terminal 全链路；不连真实 Redis / RabbitMQ）。

**关键实现细节**：

- `WorkerRouter` 是**纯派发**（switch + 调 worker），不持业务状态。两个 worker 都实现 `TaskWorker` 接口（`handle(TaskMessage)`），由 Router 派发。
- `WorkUnitScheduler` 线程池参数由 `@ConfigurationProperties("pixflow.task.worker")` 注入；拒绝策略 `CallerRunsPolicy` + `Metrics` 指标 `pixflow.task.worker.pool.rejected` 自增。
- `WorkUnitSubmitter.submit` 用 `try { Future.get(judgeTimeout) } catch` 包装 **永不抛出**，异常转 `ResultStatus.FAILED`（与 dag.md §8.1 单元执行器永不抛业务异常同款）。
- `ProcessWorker.loadImages(packageId)` 调 SPI `TaskAssetReader.findByPackage(packageId)` → `List<ImageDescriptor>`；**不直连 `asset_image`**（由 file 模块实现 SPI 倒置；本里程碑先做 fake 让 worker 跑通，Wave 4 file 落地后接通真实 SPI）。
- `ImageGenWorker.loadImagegenPlan(taskId)` 反序列化 `process_task.dag_json` 为 `ImagegenPlan`（与 imagegen.md §五的 record 形状对齐）。
- `FailureIsolator.handle` 在 try-finally 中：捕获异常 → `ErrorNormalizer.normalize(throwable, errorCode)` → 转 `TaskErrorCode` → `Sanitizer.sanitizeMessage(throwable.getMessage(), 1000)` → mapper 写 `process_result(status=FAILED, error_msg, attempt_count=...)`（用里程碑 2 的 `upsertSkippingSuccess` 仅 RUNNING/PENDING 覆盖）→ `progressAggregator.incrementFailed` → **不重新抛出**。
- `TerminalStateJudge.judge(taskId)` 必须等所有 `CompletableFuture` 完成（`allOf().join()` + `judgeTimeout=60s`），避免与 `WorkUnitSubmitter` 的取消检查点产生竞态（`task.md §八.3`）。
- `CancellationService.requestCancel` 不抢 Redis cancel：仅写 `CacheStore.put(cancelKey, true)` + 状态机写终态（QUEUED → CANCELLED 直接终态；RUNNING → PARTIAL 让 worker 自然完成）。
- `ProgressAggregator` 与 `task.md §九` 一致：双源对账由 harness/state 处理；本模块只写 Redis 实时计数 + 推 `ProgressEvent`（domain event，最终由 `harness/state` 推到 WebSocket）。
- `TaskMetrics` 14 类指标按 `task.md §十六.1` 实现，**不带业务字段**（taskId/skuId/imageId 不入 label）。
- `WorkerEndToEndTest`：造 5 张图 + 简单 DAG（remove_bg→resize）→ 创建任务 → worker 拉起 → 全部 SUCCESS → 终态 COMPLETED。注入 fake `dag.UnitExecutor` 抛 `ImageProcessingException` 两次 → 终态 COMPLETED（不全失败）。注入 fake `imagegen.ImageGenExecutor` 抛 `ImageGenError` → 同。

**测试要点**：

- `TaskStateMachineApplicationTest`：模拟 worker 完整状态流转，每步经过 `TaskStateMachine.canTransit`；构造非法迁移直接尝试写 mapper 抛 `IllegalStateTransitionException`。
- `WorkUnitSchedulerTest`：线程池满 `CallerRunsPolicy` 触发；线程池满 `AbortPolicy` 触发；正常提交流程；异常包装（提交后抛异常不污染 worker）。
- `FailureIsolatorTest`：异常归一化（各类异常 → 对应 `TaskErrorCode`）；脱敏（error_msg 不含凭证 / 绝对路径）；进度 incr；写 `process_result` 后不抛异常。
- `TerminalStateJudgeTest`：全 SUCCESS → COMPLETED；全 FAILED → FAILED；部分 SUCCESS + cancel → PARTIAL；全 SKIPPED + cancel → CANCELLED。
- `WorkerRouterTest`：按 `task_type` 派发到正确 worker；unknown task_type 抛 `TASK_DAG_PAYLOAD_INVALID`。
- `WorkerEndToEndTest`：create → worker → terminal 端到端（fake MQ、fake Redis、fake MinIO）。
- `TaskMetricsTest`：14 类指标的注册 + 标签 + 计数。

**验证**：`mvn -pl pixflow-module-task -am test` 全绿，`WorkerEndToEndTest` 跑通。

### 里程碑 4：MQ 接线 + 恢复扫描 + 心跳 + Redis 真实设施 + 创建端点

**目标**：接通真实 RabbitMQ + Redisson，`@RabbitListener` 拉起任务消息；`@Scheduled` 恢复扫描；心跳写入；`CreateTaskServiceImpl` 真实落库 + 发 MQ；下载分发。本里程碑让 task 真正能上线（Wave 5 agent 集成的前置依赖）。

**新增文件**：

- `src/main/java/com/pixflow/module/task/internal/mq/`：`TaskMessageListener.java`（`@RabbitListener(queues = "${pixflow.task.queue:main}")` 入口，调 `WorkerRouter.route(msg)`）、`TaskMessagePublisher.java`（经 `infra/mq.MessagePublisher.publish` 发 `TaskMessage{taskId, taskType, attempt, schemaVersion}` + 读 `PublishResult` 做投递补偿）、`ConsumerErrorHandlerImpl.java`（`@Component implements infra/mq.ConsumerErrorHandler`：按 `common.RecoveryHint` 给 `RetryDecision`：RETRY → `Retry(backoff)` / SKIP → `AckDrop` / TERMINATE → `DeadLetter("task failed permanently")`）、`TaskDlqListener.java`（监听 `pixflow.task.dlq`，调 `markFailedByDlq(taskId, reason)` 写 `process_task.status=FAILED + last_error`，推 `TaskCompletedEvent`）。
- `src/main/java/com/pixflow/module/task/internal/recovery/`：`RecoveryService.java`（`@Scheduled(cron = "${pixflow.task.recovery.cron:0 */1 * * * *}")` 入口；`recover()` → `findByStatusAndStartedAtBefore(RUNNING, now-staleAfter)` → 检查 `heartbeatStore.isAlive(taskId)` → `lockManager.forceUnlock(taskId)` + `messagePublisher.publish(TaskMessage.of(task))`；`requeued|skipped|error` 指标）、`HeartbeatWriter.java`（worker 周期写心跳，TTL 30s，grace 5m）。
- `src/main/java/com/pixflow/module/task/internal/create/`：`CreateTaskServiceImpl.java`（按 `task.md §五` 实现：`IdempotencyGuard.checkAndReserve` → 写 `process_task(status=PENDING)` → 状态机 `PENDING → QUEUED` → `messagePublisher.publish` 读 `PublishResult`：confirmed 保持 QUEUED / failed 保持 PENDING（由 `PublishGapRescan` 补发）→ `publish TaskCreatedEvent` → 返回 taskId；并发补偿 `PublishGapRescan` 用 `@Scheduled` 扫 status=PENDING 且 `enqueued_at < now - 30s` 重发）。
- `src/main/java/com/pixflow/module/task/internal/query/`：`TaskQueryServiceImpl.java`（`getStatus(taskId)` 调 `harness/state.ExecutionStateService.snapshot(taskId)` + 加 source 标记 / `subscribe(taskId)` 返回 `Flux<ProgressEvent>` 由 state 推到 / `listByConversation(cid, pageQuery)` 查 mapper / `getResultDownload(taskId, selector)` 调 `DownloadService`）。
- `src/main/java/com/pixflow/module/task/internal/download/`：`DownloadService.java`（`getResultDownload(taskId, ResultSelector.of(skuId, imageId, branchId))` → 查 `process_result.output_minio_key` → `infra/storage.ObjectStorage.presignedGetUrl(BucketType.RESULTS, key, expiry=15min)`）、`DownloadBundleBuilder.java`（`buildBundle(taskId)` → 异步跑 `download-pool` 线程（2-4 个） → 用 `ZipOutputStream` 写到 `task-downloads/{taskId}.zip` 临时桶 → 完成后返回 presigned URL；`max-bundle-bytes=2GB` 超限抛 `TASK_DOWNLOAD_BUNDLE_TOO_LARGE`）。
- `src/main/java/com/pixflow/module/task/bootstrap/`：`TaskAutoConfiguration.java`（`@Configuration` 装配全部 bean：`@MapperScan("com.pixflow.module.task.infra.persistence")` + `TaskMessageListener` + `RecoveryService` + `HeartbeatWriter` + `CreateTaskServiceImpl` + `TaskQueryServiceImpl` + `DownloadService` + 所有 worker）+ `TaskProperties.java`（`@ConfigurationProperties("pixflow.task")`，见 `task.md §十五`）。
- `src/test/java/com/pixflow/module/task/internal/mq/`：`TaskMessageListenerTest`（fake infra.mq.MessagePublisher + fake worker handle，断言收到消息 → 调 router）。
- `src/test/java/com/pixflow/module/task/internal/recovery/`：`RecoveryServiceTest`（Testcontainers Redis + fake TaskMapper，造 RUNNING + stale → 断言 forceUnlock + 重发 + requeued 指标）。
- `src/test/java/com/pixflow/module/task/integration/`：`TaskEndToEndTest`（Testcontainers MySQL + Redis + RabbitMQ + MinIO，跑 create → MQ → worker → terminal 全链路）。

**关键实现细节**：

- `TaskMessage` 是中立 record（`taskId` + `taskType` + `attempt` + `schemaVersion`），不依赖任何业务类型。
- `ConsumerErrorHandlerImpl.onError`：用 `common.ErrorNormalizer.normalize(throwable)` → 拿 `recovery` 字段 → 转 `RetryDecision`：RETRY → `Retry(backoff(retryCount))` / SKIP → `AckDrop`（已标 FAILED 不冒泡）/ TERMINATE → `DeadLetter`。
- `RecoveryService.recover` 不修改 `process_task.status`（保持 RUNNING 让 worker 自然完成）。
- `HeartbeatWriter` 由 worker 入口处启动 `ScheduledExecutorService`，每 30s 写一次心跳 `task:heartbeat:{taskId}:{workerId}`，TTL 60s。
- `DownloadBundleBuilder.buildBundle` 启动独立线程（不抢 worker pool），跑完即销毁。临时桶 `task-downloads/` 用完即删（避免 MinIO 长期堆积）。
- `TaskProperties` 完整对齐 `task.md §十五`：create / worker / lock / recovery / cancel / download / terminal / progress 8 段。

**测试要点**：

- `TaskMessageListenerTest`：正常消息派发到正确 worker；unknown task_type 不抛（直接 ack + 记 `pixflow.task.state.transition.rejected`）。
- `ConsumerErrorHandlerImplTest`：RecoveryHint → RetryDecision 三态映射；超过 max-retries（5）转 DeadLetter。
- `RecoveryServiceTest`：RUNNING + stale + heartbeat 缺失 → 强制解锁 + 重发（断言 messagePublisher.publish 被调一次）；heartbeat 存在 → 跳过；status != RUNNING → 跳过。
- `CreateTaskServiceImplTest`：幂等命中（key 已存在 RUNNING → 返回原 taskId）；幂等命中（key 已存在 COMPLETED → 抛 `TASK_ALREADY_COMPLETED`）；MQ confirmed 路径；MQ failed 路径（PENDING 保持 + 触发补偿）；状态机非法迁移直接抛。
- `DownloadServiceTest`：拼 presigned URL 正确；result 不存在抛 `TASK_DOWNLOAD_RESULT_NOT_READY`。
- `DownloadBundleBuilderTest`：bundle 大小超 `max-bundle-bytes` → `TASK_DOWNLOAD_BUNDLE_TOO_LARGE`；正常路径 → presigned URL 返回 + 临时桶清理。

**验证**：`mvn -pl pixflow-module-task -am test` 全绿；`mvn -pl pixflow-app -am test` BUILD SUCCESS（task bean 装配不影响 app 现有测试）。

### 里程碑 5：pixflow-app 装配回归 + 端到端联调 + 可观测性面板

**目标**：让 `pixflow-app` 自动接入 task 模块；端到端冒烟从 REST 入参到 `process_result` 落库跑通；验证 14 类 `pixflow.task.*` 指标在 Actuator 暴露。

**新增文件**（仅端到端与装配调整）：

- 调整 `pixflow-app/pom.xml` 加入 `pixflow-module-task` 依赖（如果还没有）。
- 在 `pixflow-app/src/main/java/.../scheduler/` 加入 `TaskPublishGapRescan`（`@Scheduled` 扫 status=PENDING 补发 MQ 缺口，对齐 `infra/mq.md §5.2`）— task 模块不持有 `@Scheduled` 之外的恢复以外调度器。
- `src/test/java/com/pixflow/module/task/e2e/`：`TaskE2EIT.java`（Testcontainers MySQL + Redis + RabbitMQ + MinIO + 真实 dag module Bean；从 conversation 确认 REST 端点（fake controller）发请求 → `TaskCommandService.createAndEnqueue` → MQ → worker → 调真实 `dag.UnitExecutor`（fake image/thirdparty 桩）→ `process_result` 落库 → 终态判定 → 通知 → presigned URL 可下载）。

**关键实现细节**：

- `pixflow-app` 的 `application.yml` 加 `pixflow.task.*` 配置（线程池大小、恢复 cron、cancellation TTL 等）。
- 端到端测试用 spring-boot-starter-test + `@SpringBootTest(webEnvironment = RANDOM_PORT)`。
- 真实装配时，task 模块的 `WorkerRouter` 需要 `ProcessWorker` 注入 `dag.UnitExecutor` + `BranchExpander` + `dag.ImageDescriptor` 中立投影；`ImageGenWorker` 注入 `imagegen.ImageGenExecutor` + `imagegen.ImagegenPlan` 反序列化器。

**测试要点**：

- 端到端冒烟：5 张图 + 简单 DAG（remove_bg → resize）→ 创建任务 → 推 `TaskCreatedEvent` → MQ 消费 → worker fan-out 5 单元 → 全部 SUCCESS → 推 `TaskCompletedEvent` → 终态 COMPLETED → presigned URL 可下。
- 失败隔离冒烟：5 张图 + fake executor 抛 `ImageProcessingException` 2 张 → `process_result.status=FAILED` 2 条 + `SUCCESS` 3 条 → 终态 COMPLETED → 通知。
- 取消冒烟：worker 跑到一半时调 `TaskCommandService.cancel(cmd)` → 后续单元标 SKIPPED → 已开始单元自然完成 → 终态 PARTIAL。
- 断点恢复冒烟：worker 跑到一半时强制 kill 进程（不发 TaskCompletedEvent）→ `@Scheduled` 扫描 → heartbeat 缺失 → 重新入队 → 新 worker 拉起 → 跳过已成功单元 → 终态 COMPLETED。
- 幂等冒烟：同 `Idempotency-Key` 二次 `createAndEnqueue` → 返回原 taskId + 不创建新任务。
- 生图路径冒烟：3 张源图 + ImagegenPlan → worker 拉起 → 调 fake `imagegen.ImageGenExecutor` → 全部 SUCCESS → 终态 COMPLETED → `process_result.source_path` 标生成式 + `output_minio_key` 在 GENERATED 桶。
- 可观测冒烟：起 app，访问 `/actuator/prometheus` → 找到 14 类 `pixflow.task.*` 指标 + 没有 `taskId`/`skuId`/`imageId` 入标签。

**验证**：
- `mvn -pl pixflow-module-task -am test` 全绿（含 ArchUnit 6/6 + 全部单测 + E2E）。
- `mvn -pl pixflow-app -am test` BUILD SUCCESS。
- `mvn -pl pixflow-app spring-boot:run` 启动 → `/actuator/prometheus` 看到 14 类指标。

## Concrete Steps

### 工作目录

所有命令在 `D:\study\PixFlow`（PowerShell）下运行。

### 里程碑 1 命令序列

```
# 创建模块骨架
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/main/java/com/pixflow/module/task/{api/command,api/query,api/event,domain/model,domain/statemachine,domain/idempotency,domain/branch,domain/progress,domain/error}
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/test/java/com/pixflow/module/task/{domain,architecture}

# 写入 pom.xml（参考 pixflow-module-imagegen/pom.xml 的依赖结构）
# 在根 pom.xml <modules> 加入 <module>pixflow-module-task</module>

# 写 domain model + statemachine + idempotency + progress + branch + error code + 单测

# 验证
mvn -pl pixflow-module-task -am compile
mvn -pl pixflow-module-task -am test
```

预期输出：`Tests run: 80+, Failures: 0, Errors: 0, Skipped: 0`；ArchUnit 6 条反向约束守护绿；`mvn compile` 不依赖 Spring 上下文（仅依赖 jackson + mybatis-plus + common + harness-state SPI 镜像 enum）。

### 里程碑 2 命令序列

```
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/main/java/com/pixflow/module/task/{infra/persistence,internal/stateadapter}
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/main/resources/db/migration
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/test/java/com/pixflow/module/task/{infra/persistence,internal/stateadapter}

# 写 SQL 迁移脚本 + mapper + CheckpointReadPort 实现 + 单测

# 验证
mvn -pl pixflow-module-task -am test
```

预期：`Tests run: 130+, Failures: 0, Errors: 0, Skipped: 0`；H2 单测 + Testcontainers MySQL 集成测试按 env 跳过策略。

### 里程碑 3 命令序列

```
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/main/java/com/pixflow/module/task/{internal/{create,cancel,worker,scheduler,branch,progress,failure,terminal,publish},infra/{cache,metrics}}
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/test/java/com/pixflow/module/task/internal
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/test/java/com/pixflow/module/task/integration

# 写 cache/store + scheduler/submitter + worker/router/process/imagegen + BranchExecutionAdapter + ProgressAggregator + FailureIsolator + TerminalStateJudge + CancellationService + TaskMetrics + WorkerEndToEndTest

# 验证
mvn -pl pixflow-module-task -am test
```

预期：`Tests run: 250+, Failures: 0, Errors: 0, Skipped: 0`；`WorkerEndToEndTest` 跑通 create → worker → terminal 全链路。

### 里程碑 4 命令序列

```
New-Item -ItemType Directory -Force -Path pixflow-module-task/src/main/java/com/pixflow/module/task/internal/{mq,recovery,create,query,download,bootstrap}

# 写 @RabbitListener + ConsumerErrorHandlerImpl + RecoveryService + HeartbeatWriter + CreateTaskServiceImpl + TaskQueryServiceImpl + DownloadService + DownloadBundleBuilder + TaskAutoConfiguration + TaskProperties + 集成测试

# 在 pixflow-app/pom.xml 加 pixflow-module-task 依赖（如果还没有）
# 在 pixflow-app/src/main/java/.../scheduler/ 加 TaskPublishGapRescan @Scheduled

# 验证
mvn -pl pixflow-module-task -am test
mvn -pl pixflow-app -am test
```

预期：`Tests run: 350+, Failures: 0, Errors: 0, Skipped: 0`；`mvn -pl pixflow-app -am test` BUILD SUCCESS。

### 里程碑 5 命令序列

```
# 调整 pixflow-app 装配 + application.yml 配置
# 写 TaskE2EIT 端到端集成测试

# 验证
mvn -pl pixflow-module-task -am verify
mvn -pl pixflow-app -am verify
mvn -pl pixflow-app spring-boot:run    # 启动后访问 /actuator/prometheus 看到 14 类指标
```

预期：所有单测 + 集成测试 + E2E 全绿；E2E 跑通 5 张图 remove_bg→resize 全链路；`/actuator/prometheus` 暴露 `pixflow.task.*` 14 类指标。

### 关键代码片段参考（写代码时的参考骨架，不是最终抄写）

**TaskStateMachine 纯函数（`domain/statemachine/TaskStateMachine.java`）**：

    public final class TaskStateMachine {
        public static final TaskStateMachine INSTANCE = new TaskStateMachine();
        private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.ofEntries(
            Map.entry(TaskStatus.PENDING, Set.of(TaskStatus.QUEUED, TaskStatus.FAILED)),
            Map.entry(TaskStatus.QUEUED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.RUNNING, Set.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.PARTIAL)),
            Map.entry(TaskStatus.COMPLETED, Set.of()),
            Map.entry(TaskStatus.FAILED, Set.of()),
            Map.entry(TaskStatus.CANCELLED, Set.of()),
            Map.entry(TaskStatus.PARTIAL, Set.of())
        );
        private TaskStateMachine() {}
        public boolean canTransit(TaskStatus from, TaskStatus to) {
            return ALLOWED.getOrDefault(from, Set.of()).contains(to);
        }
        public void verify(TaskStatus from, TaskStatus to) {
            if (!canTransit(from, to)) throw new IllegalStateTransitionException(from, to);
        }
    }

**WorkerRouter 派发（`internal/worker/WorkerRouter.java`）**：

    @Component
    public class WorkerRouter {
        private final ProcessWorker processWorker;
        private final ImageGenWorker imageGenWorker;
        public WorkerRouter(ProcessWorker pw, ImageGenWorker iw) { this.processWorker = pw; this.imageGenWorker = iw; }
        public void route(TaskMessage msg) {
            switch (msg.taskType()) {
                case IMAGE_PROCESS -> processWorker.handle(msg);
                case IMAGE_GEN    -> imageGenWorker.handle(msg);
                default -> throw new PixFlowException(TaskErrorCode.TASK_DAG_PAYLOAD_INVALID, "unknown taskType: " + msg.taskType());
            }
        }
    }

**FailureIsolator 单单元隔离（`internal/failure/FailureIsolator.java`）**：

    @Component
    public class FailureIsolator {
        // ... 依赖 mapper / progressAggregator / sanitizer
        public void handle(WorkUnit unit, Throwable t, UnitExecutionContext ctx) {
            try {
                PixFlowException pfe = errorNormalizer.normalize(t, TaskErrorCode.TASK_RESULT_WRITE_FAILED);
                String safeMsg = sanitizer.sanitizeMessage(t.getMessage(), 1000);
                processResultMapper.upsertSkippingSuccess(unit, ResultStatus.FAILED, pfe.code(), safeMsg, ctx.attemptCount());
                progressAggregator.incrementFailed(unit.taskId());
                metrics.recordFailure(unit, pfe.code());
            } catch (Throwable inner) {
                log.error("FailureIsolator swallowed inner exception", inner);
            }
        }
    }

### 依赖清单（pixflow-module-task/pom.xml）

    <dependencies>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-common</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-contracts</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-harness-state</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-harness-hooks</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-mq</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-cache</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-infra-storage</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-module-dag</artifactId></dependency>
      <dependency><groupId>com.pixflow</groupId><artifactId>pixflow-module-imagegen</artifactId></dependency>
      <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId></dependency>
      <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
      <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-amqp</artifactId></dependency>
      <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId></dependency>
      <!-- 测试 -->
      <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
      <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
      <dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><scope>test</scope></dependency>
      <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers</artifactId><scope>test</scope></dependency>
    </dependencies>

## Validation and Acceptance

### 行为级验收（人工可观察）

1. **任务创建 + 入队幂等**：发送同 `Idempotency-Key` 两次 → 返回同一 `taskId`；后台只看到一条 `process_task` 记录。
2. **确定性路径执行**：5 张图 + remove_bg→resize DAG → 创建任务 → worker fan-out 5 单元 → 全部 SUCCESS → 终态 COMPLETED → presigned URL 可下。
3. **失败隔离**：5 张图 + fake executor 抛 `ImageProcessingException` 2 张 → `process_result.status=FAILED` 2 条 + `SUCCESS` 3 条 → 终态 COMPLETED。
4. **取消协作**：worker 跑到一半时调 cancel → 后续单元标 SKIPPED → 已开始单元自然完成 → 终态 PARTIAL。
5. **断点恢复**：worker 跑到一半时强制 kill → `@Scheduled` 扫描 → heartbeat 缺失 → 重新入队 → 新 worker 跳过已成功单元 → 终态 COMPLETED。
6. **生成式路径执行**：3 张源图 + ImagegenPlan → worker fan-out → 调 `imagegen.ImageGenExecutor` → 全部 SUCCESS → 终态 COMPLETED → `output_minio_key` 在 GENERATED 桶。
7. **状态机非法迁移**：构造 `RUNNING → RUNNING` 直接尝试写 mapper → 抛 `IllegalStateTransitionException`。
8. **背压触发**：线程池满 → `CallerRunsPolicy`（提交方线程跑）+ `pixflow.task.worker.pool.rejected` 指标自增。
9. **下载打包超限**：构造任务结果 > 2GB → `DownloadBundleBuilder.buildBundle` 抛 `TASK_DOWNLOAD_BUNDLE_TOO_LARGE`。
10. **可观测性**：访问 `/actuator/prometheus` → 看到 `pixflow.task.*` 14 类指标 + 没有 `taskId`/`skuId`/`imageId` 入标签。

### 测试命令

    # 单测
    mvn -pl pixflow-module-task -am test

    # 集成测试（Testcontainers 需要 Docker）
    mvn -pl pixflow-module-task -am verify -Pintegration

    # 端到端冒烟（pixflow-app 启动后）
    mvn -pl pixflow-app spring-boot:run
    # 用 curl 模拟任务创建与下载
    Invoke-WebRequest -Uri http://localhost:8080/api/conversations/{cid}/confirm -Method POST -Body @{ planId = "..." } | ConvertTo-Json

### 验收判定

- `mvn -pl pixflow-module-task -am test` 全绿（含 ArchUnit 6/6 反向约束守护）。
- `mvn -pl pixflow-app -am test` BUILD SUCCESS（task bean 装配不影响 app 现有测试）。
- `mvn -pl pixflow-module-task -am verify` 产物含 `CreateTaskServiceImpl` / `WorkerRouter` / `ProcessWorker` / `ImageGenWorker` / `RecoveryService` 等关键 bean。
- 端到端冒烟 10 项人工验收全部通过。

## Idempotence and Recovery

- 所有 mvn 命令可重复运行（idempotent）：文件创建用 `New-Item -ItemType Directory -Force`；SQL 迁移脚本覆盖写不破坏旧内容（按 `Vxxx__*.sql` 顺序仅迁移一次）。
- 单测失败时定位到具体类，修复后 `mvn -pl pixflow-module-task test -Dtest=具体类` 重跑。
- Testcontainers 集成测试若 Docker 不可用，按 `pixflow-infra-storage` 已有的 profile 跳过策略（不视为失败）。
- 不需要任何数据迁移：task 模块新建 `process_task` / `process_result` / `process_result_member` 三张表，不修改现有表（`asset_*` / `pending_plan` 由 dag / file 模块拥有）。
- 回滚策略：移除 `pixflow-module-task` 模块的 Maven 引用即可，不影响其他模块。

## Artifacts and Notes

### 关键 SQL 迁移参考（实际写时按需调整）

**`Vxxx__create_process_task.sql`**：

    CREATE TABLE process_task (
      id              BIGINT NOT NULL AUTO_INCREMENT,
      task_type       VARCHAR(32) NOT NULL COMMENT 'IMAGE_PROCESS | IMAGE_GEN',
      conversation_id VARCHAR(64) NOT NULL,
      package_id      BIGINT NOT NULL,
      idempotency_key VARCHAR(128) NOT NULL COMMENT 'REST Idempotency-Key',
      priority        INT NOT NULL DEFAULT 0,
      status          VARCHAR(16) NOT NULL COMMENT 'PENDING/QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/PARTIAL',
      cancel_reason   VARCHAR(256) DEFAULT NULL,
      total_count     INT NOT NULL DEFAULT 0,
      done_count      INT NOT NULL DEFAULT 0,
      dag_json        MEDIUMTEXT NOT NULL COMMENT 'ValidatedDag 或 ImagegenPlan 序列化（按 task_type 分流）',
      schema_version  VARCHAR(16) NOT NULL DEFAULT '1.0',
      worker_id       VARCHAR(64) DEFAULT NULL,
      attempt_count   INT NOT NULL DEFAULT 0,
      enqueued_at     DATETIME(3) DEFAULT NULL,
      started_at      DATETIME(3) DEFAULT NULL,
      finished_at     DATETIME(3) DEFAULT NULL,
      last_error      TEXT DEFAULT NULL,
      error_msg       VARCHAR(1000) DEFAULT NULL,
      created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (id),
      UNIQUE KEY uq_process_task_idempotency_key (idempotency_key),
      KEY idx_process_task_status_started (status, started_at),
      KEY idx_process_task_conversation (conversation_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

**`Vxxx__create_process_result.sql`**：

    CREATE TABLE process_result (
      id              BIGINT NOT NULL AUTO_INCREMENT,
      task_id         BIGINT NOT NULL,
      kind            VARCHAR(16) NOT NULL COMMENT 'BRANCH | GROUP | GENERATIVE',
      image_id        BIGINT DEFAULT NULL COMMENT 'BRANCH/GENERATIVE 非空',
      sku_id          VARCHAR(64) DEFAULT NULL,
      group_key       VARCHAR(64) DEFAULT NULL COMMENT 'GROUP 非空 / BRANCH/GENERATIVE 为空',
      view_id         VARCHAR(64) DEFAULT NULL,
      branch_id       VARCHAR(128) NOT NULL,
      source_path     TEXT DEFAULT NULL COMMENT '源图引用 / 生成式标 "GENERATIVE"',
      output_minio_key VARCHAR(512) DEFAULT NULL,
      generated_copy  MEDIUMTEXT DEFAULT NULL,
      status          VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
      error_msg       VARCHAR(1000) DEFAULT NULL,
      attempt_count   INT NOT NULL DEFAULT 0,
      started_at      DATETIME(3) DEFAULT NULL,
      finished_at     DATETIME(3) DEFAULT NULL,
      bytes_in        BIGINT DEFAULT NULL,
      bytes_out       BIGINT DEFAULT NULL,
      worker_run_id   VARCHAR(64) DEFAULT NULL,
      created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      PRIMARY KEY (id),
      UNIQUE KEY uq_process_result_task_image_branch (task_id, image_id, branch_id),
      KEY idx_process_result_task_group_branch (task_id, group_key, branch_id),
      KEY idx_process_result_task_status (task_id, status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

### 关键测试片段参考

**WorkerEndToEndTest 端到端（fake infra）**：

    @Test
    void worker_runsToCompletion_forImageProcess() throws Exception {
      CreateTaskCommand cmd = new CreateTaskCommand(IMAGE_PROCESS, conversationId, packageId, idempotencyKey, dagJson);
      TaskId taskId = createTaskService.createAndEnqueue(cmd);

      // 模拟 MQ 投递
      taskMessageListener.onMessage(new TaskMessage(taskId.value(), IMAGE_PROCESS, 1, "1.0"));

      // 等 worker 跑完
      await().atMost(30, SECONDS).until(() -> {
        TaskStatusView view = taskQueryService.getStatus(taskId);
        return view.status() == TaskStatus.COMPLETED;
      });

      assertThat(processResultMapper.findByTaskId(taskId.value())).hasSize(5);
      assertThat(processResultMapper.findByTaskId(taskId.value())).allMatch(r -> r.status() == ResultStatus.SUCCESS);
    }

**TaskStateMachine 属性测试**：

    @Property
    void valid_transitions_pass(@ForAll TaskStatus from, @ForAll TaskStatus to) {
      assumeTrue(stateMachine.canTransit(from, to));
      assertThatCode(() -> stateMachine.verify(from, to)).doesNotThrowAnyException();
    }

    @Property
    void invalid_transitions_throw(@ForAll TaskStatus from, @ForAll TaskStatus to) {
      assumeFalse(stateMachine.canTransit(from, to));
      assertThatThrownBy(() -> stateMachine.verify(from, to))
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining(from.name()).hasMessageContaining(to.name());
    }

## Interfaces and Dependencies

### 对上游模块的契约（task 是被消费方）

| 上游模块 | 契约 |
|---|---|
| `module/conversation` | 调 `TaskCommandService.createAndEnqueue(cmd)`（确认 REST 边界通过后）；调 `TaskCommandService.cancel(cmd)`（用户取消）；订阅 `TaskCompletedEvent` 用于会话级状态汇总 |
| `harness/hooks` | 订阅 `TaskCreatedEvent` / `TaskCompletedEvent`；`harness/hooks` 不反向调 task 内部 |
| `agent`（Wave 5） | 调 `TaskQueryService.getStatus / subscribe`；不调 `TaskCommandService`（确认执行走 conversation REST 边界） |
| `pixflow-app` | 提供 STOMP broker 配置 + 装配 task 的 controller / consumer / @Scheduled |

### 对下游模块的契约（task 是消费方）

| 下游模块 | 契约 |
|---|---|
| `infra/mq` | `TaskMessagePublisher.publish` 经 `MessagePublisher.publish` 发消息；`TaskMessageListener` `@RabbitListener`；`ConsumerErrorHandlerImpl` 实现 `ConsumerErrorHandler`（按 common recovery 给 RetryDecision） |
| `infra/cache` | `TaskLockManager` 经 `LockTemplate.runWithLock` 看门狗锁；`TaskIdempotencyStore` 经 `CacheStore` + Lua 原子占位；`TaskProgressCounter` 经 `AtomicCounter` |
| `infra/storage` | `DownloadService` 经 `ObjectStorage.presignedGetUrl(BucketType.RESULTS, key, expiry=15min)`；`DownloadBundleBuilder` 经 `ObjectStorage.put` 写临时桶 |
| `module/dag` | 调 `BranchExpander.expand(ValidatedDag, List<ImageDescriptor>)`；调 `UnitExecutor.execute(ExecutableBranch, UnitInput)`；据 `UnitOutcome` 写 `process_result`/`process_result_member`；喂 `ImageDescriptor`/`CopyContext` 中立输入 |
| `module/imagegen` | 调 `ImageGenExecutor.redraw(GenerativeUnitSpec)`；据 `GeneratedArtifact` 写 `process_result` |
| `harness/state` | **实现** `CheckpointReadPort`（由本模块在 `internal/stateadapter/CheckpointReadPortImpl.java` 提供，state 调用）；**消费** `CancellationReader.throwIfCancelled` 在单元边界 |
| `module/file`（Wave 2 已就绪） | 实现 `TaskAssetReader` SPI（按 packageId 解析 `asset_image`）—— imagegen 已有同款 `SourceImageReader` SPI 倒置，task 镜像一份 `TaskAssetReader` 或复用 imagegen 的 `SourceImageReader` 同一 SPI |
| `common` | 抛 `PixFlowException(TaskErrorCode)`；进度 / 错误文案经 `Sanitizer`；`TaskErrorCode` 并入 `common` 启动期聚合测试 |

### 关键 SPI 形态（task 端对外）

**TaskCommandService（`api/TaskCommandService.java`）**：

    public interface TaskCommandService {
        TaskId createAndEnqueue(CreateTaskCommand cmd);
        boolean cancel(CancelTaskCommand cmd);
    }

**TaskQueryService（`api/TaskQueryService.java`）**：

    public interface TaskQueryService {
        TaskStatusView getStatus(TaskId taskId);
        Flux<ProgressEvent> subscribe(TaskId taskId);
        Page<TaskSummary> listByConversation(ConversationId cid, PageQuery q);
        DownloadHandle getResultDownload(TaskId taskId, ResultSelector selector);
    }

### 关键配置（pixflow.task.*）

    pixflow:
      task:
        create:
          idempotency-ttl: 24h
        worker:
          process-pool: { core-size: 8, max-size: 16, queue-capacity: 1000, rejected-policy: CALLER_RUNS }
          imagegen-pool: { core-size: 4, max-size: 8, queue-capacity: 200, rejected-policy: CALLER_RUNS }
          download-pool: { core-size: 2, max-size: 4, queue-capacity: 100 }
          heartbeat-interval: 30s
        lock:
          task-execution-ttl: 10m
          force-unlock-on-recovery: true
        recovery:
          cron: "0 */1 * * * *"
          stale-after: 30m
          heartbeat-grace: 5m
        cancel:
          check-interval: 0
        download:
          single-url-expiry: 15m
          max-bundle-bytes: 2147483648    # 2GB
          bundle-temp-prefix: "task-downloads/"
        terminal:
          judge-timeout: 60s
        progress:
          counter-ttl: 1h

## Revision Notes

- 2026-06-29 / Kiro: 创建本执行计划。确认 task 是 Wave 4 主循环 + 编排模块，依赖 `infra/{mq,cache,storage}` + `module/{dag,imagegen}` + `harness/state` + `common`，被 `module/conversation` / `harness/hooks` / `agent` / `pixflow-app` 消费。所有架构与边界决策以 `task.md` 为唯一权威，本计划按 5 个里程碑从内核到外壳推进：① 纯函数核心 + 数据模型 + ArchUnit；② mapper + SQL 迁移 + CheckpointReadPort 实现；③ 执行壳 + worker + 状态机迁移点 + 失败隔离 + 终态判定（fake infra）；④ MQ 接线 + 恢复扫描 + 心跳 + 真实 Redis + 创建端点 + 下载分发；⑤ pixflow-app 装配回归 + 端到端联调 + 14 类指标。
