# module/task —— 任务调度与异步执行外壳（Wave 4 主循环 + 编排模块）

> 本文是 PixFlow 完整重写阶段 `module/task` 模块的设计文档，对应 `design.md` 第九章「DAG 确定性执行引擎」（§9.2 异步任务分发、§9.3 并发保障、§9.4 断点恢复与失败隔离、§9.5 分组聚合）、第十三章数据模型、第十四章异步执行时序，以及 `module-dependency-dag-plan.md` 的 **Wave 4 主循环 + 编排模块**。
> 范围：消费 RabbitMQ 任务消息、按 `[图片×支路]/[组×支路]`（确定性路径）与 `[1 源图→1 重绘]`（生成式路径）两种单元粒度 fan-out、`process_task` / `process_result` 落库、Redis 进度/取消/锁协调、下载分发、两条产图路径的共用执行壳、断点恢复扫描。
> 配套阅读：`module/dag.md`（dag 是无状态确定性单元执行器、被 task 逐单元调用）、`module/imagegen.md`（生图执行器 SPI、被 task 同款外壳消费）、`harness/state.md`（运行态读模型聚合、恢复协调权威、双源对账）、`infra/mq.md`（RabbitMQ 封装、DLQ/重试）、`infra/cache.md`（Redisson 锁/信号量/进度计数）、`infra/storage.md`（MinIO 桶与路径约定）、`base/contracts.md`（确认令牌形状）、`harness/hooks.md`（TaskCreated/TaskCompleted 事件订阅方）。本文不涉及 MVP 既有实现，从生产级需求重新推导。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、核心边界：task 是 dag/imagegen 的异步外壳](#二核心边界task-是-dagimagegen-的异步外壳)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、TaskService：对外暴露的唯一入口](#四taskservice对外暴露的唯一入口)
- [五、CreateTaskService：从确认令牌到 process_task 落库](#五createtaskservice从确认令牌到-process_task-落库)
- [六、两条产图路径的统一执行壳](#六两条产图路径的统一执行壳)
- [七、WorkUnit 与 WorkUnitScheduler：线程池 + 背压 + 取消](#七workunit-与-workunitscheduler线程池--背压--取消)
- [八、状态机：process_task / process_result 的迁移表](#八状态机process_task--process_result-的迁移表)
- [九、ProgressAggregator：Redis 计数 + 实时推送](#九progressaggregatorredis-计数--实时推送)
- [十、FailureIsolator：失败隔离与终态判定](#十failureisolator失败隔离与终态判定)
- [十一、断点恢复：RecoveryService + 单元级幂等](#十一断点恢复recoveryservice--单元级幂等)
- [十二、取消：协作式协议 + 工作单元边界检查](#十二取消协作式协议--工作单元边界检查)
- [十三、下载分发：MinIO Presigned URL](#十三下载分发minio-presigned-url)
- [十四、错误与降级](#十四错误与降级)
- [十五、配置](#十五配置)
- [十六、可观测性](#十六可观测性)
- [十七、对其他模块的契约](#十七对其他模块的契约)
- [十八、测试策略](#十八测试策略)
- [十九、对 design.md 与依赖计划的细化](#十九对-designmd-与依赖计划的细化)
- [二十、暂不考虑](#二十暂不考虑)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`module/task` 在依赖 DAG 中处于 **Wave 4**，依赖 `infra/mq`（任务消息 publish/consume）、`infra/cache`（Redisson 锁/进度/信号量/取消）、`infra/storage`（结果落 MinIO）、`module/dag`（`BranchExpander` + `UnitExecutor`）、`harness/state`（checkpoint 读侧聚合 + 取消读协议 + 运行态引用）、`common`（错误归一化/脱敏）。被 `module/conversation`（确认 REST 端点调 `TaskCommandService.createAndEnqueue`）、`harness/hooks`（订阅 `TaskCreatedEvent` / `TaskCompletedEvent`）、`agent`（执行期查询/订阅）、前端（轮询/WebSocket）消费。

模块专属设计原则：

1. **task 是异步外壳，不做像素语义**。`module/dag` 是无状态确定性单元执行器（dag.md §二），`module/imagegen` 是无状态单图重绘执行器（imagegen.md §二），task 是它们的**有状态异步执行壳**——把任务消息拆成工作单元、调线程池 fan-out、落 `process_result`、管进度/锁/取消/恢复。task 内**不出现**任何像素工具逻辑、任何 DAG 节点派发、任何生图模型调用。
2. **MySQL 是事实源，Redis 只做同次运行内的轻量优化**。持久断点以 `process_result` 为唯一权威（design.md §5.4/§9.4）；Redis 进度/取消/引用均为可丢可重建，**原始字节一律不进 Redis**。任何恢复判定、终态判定一律以 MySQL 为准。
3. **两条产图路径共用同一套异步壳**。确定性的 `[图片×支路]/[组×支路]` 与生成式的 `[1 源图→1 重绘]` 都跑在 task 的 worker 上，由 `WorkerRouter` 按 `process_task.task_type` 路由到 `ProcessWorker` / `ImageGenWorker`；调度壳（线程池/进度/锁/取消/恢复/下载）100% 复用。imagegen 不自建异步（imagegen.md §八），dag 也不自建异步（dag.md §二）。
4. **状态机迁移是显式纯函数**。`process_task.status` 与 `process_result.status` 的所有合法迁移集中在一个 `TaskStateMachine` / `ResultStateMachine` 类里（纯函数，**不在数据库触发器里**、**不在 worker 的 if-else 里**），可被属性测试覆盖。
5. **失败隔离是 task 的核心契约**。单个工作单元失败 → 该 `process_result` 标 FAILED、记脱敏 `error_msg`、不保留半成品 `output_path`；同图其余支路、批次其余图片、其他组继续。这是 task 模块对调用方（确认 REST 端点 / 前端）最硬的承诺。
6. **取消是协作式，绝不打断运行中的工作单元**。task worker 在工作单元**边界**检查 `cancel:task:*` 标志；像素工具调用、模型调用、MinIO I/O **不在中途打断**（避免数据损坏）。已开始的单元跑完自然结束。
7. **生产级、不简化**。分布式锁、信号量、心跳、task 级与支路级重试分离、DLQ 语义、idempotency key、presigned URL、metrics 全套齐备；不做 MVP 式"先把流程跑通"的简化。
8. **API 与 internal 物理隔离**。`api/` 包对其他模块可见，`internal/` 包封装类全部包内可见。agent / conversation 只 import `api`，**看不到 worker 内部**——这是防止业务模块乱调 task 内部方法的关键。

---

## 二、核心边界：task 是 dag/imagegen 的异步外壳

`design.md §12` 把「任务调度」列在 `module/task`，但与 `module/dag`「执行引擎」字面上重叠。本模块据 `design.md` 设计原则一/二（两层循环分离、确定性底座不被污染）与 dag.md §二、imagegen.md §二的边界，把这条线**明确钉死**：

| 维度 | `module/dag` | `module/imagegen` | `module/task`（本模块） |
|---|---|---|---|
| 性质 | 无状态、确定性库 | 无状态、能力模块 | **有状态、并发、异步**的编排外壳 |
| 拥有 | 像素工具白名单、`DagValidator`、`BranchExpander`、确定性单元执行器 | `ImageGenExecutor` SPI、`submit_imagegen_plan` handler | 线程池、Redisson 锁/进度/取消/信号量、MQ 消费与发布、`@Scheduled` 恢复扫描、`process_task`/`process_result` 落库、presigned URL 生成 |
| 调用 task 的入口 | 无 | 无 | `WorkerRouter` → `ProcessWorker` 调 `UnitExecutor.execute`；`ImageGenWorker` 调 `ImageGenExecutor.redraw` |
| 被 task 调用 | `BranchExpander.expand`、`UnitExecutor.execute` | `ImageGenExecutor.redraw`、`GenerativeUnitSpec` 装配 | —— |
| 失败隔离 | 单元执行器**捕获并归一化**单元内异常 → 返回 `UnitOutcome.FAILED`（**从不抛出去打断批次**） | executor 抛 `PixFlowException`，由 task 隔离 | 据 `FAILED` outcome / 异常 → 落 `process_result.status=FAILED` + 脱敏 `error_msg` + 继续兄弟单元；终态判定（至少一条成功→完成 / 全失败→失败） |
| 不碰 | 线程池、MQ、Redis 锁/进度/取消、`process_*` 落库、下载、presigned URL | 线程池、MQ、Redis 锁/进度/取消、`process_*` 落库、下载 | 像素语义、DAG 结构解析、生图模型调用细节、`generate_copy` 文案生成 |

**一句话**：dag 回答「这条支路对这张图/这组图跑出来是什么」，imagegen 回答「这张源图重绘出来是什么」，task 回答「这一批要跑哪些单元、并发多少、断点怎么续、进度怎么报、结果怎么下、终态怎么判」。dag 与 imagegen 的「执行引擎」是**被 task worker 逐单元调用的纯服务**，不是自己跑批的东西。

> **设计取舍**：为什么不把 fan-out 也放 dag/imagegen？因为 fan-out 必然绑定线程池、进度计数、取消检查、`process_result` checkpoint 这些**有状态运行态**，一旦进 dag/imagegen，二者就不再是可独立属性测试的确定性库/能力模块。把并发与状态全留在 task，是「确定性底座不被污染」与「能力模块无副作用累积」的硬要求。

---

## 三、模块结构与依赖位置

Maven 模块：`pixflow-module-task`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.task`。

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
│   │   └── UnitKind.java             # enum: BRANCH | GROUP | GENERATIVE（对齐 state.UnitKind）
│   ├── statemachine/
│   │   ├── TaskStateMachine.java     # 纯函数：合法迁移表 + canTransit/from/to
│   │   └── ResultStateMachine.java   # 纯函数
│   ├── idempotency/
│   │   ├── IdempotencyKey.java
│   │   └── IdempotencyGuard.java     # 防止重复创建任务
│   ├── branch/
│   │   ├── BranchInstance.java       # 内部 record：来自 dag.UnitOutcome 的支路实例
│   │   └── CopyContext.java          # 来自 dag（中立投影，不直连 asset_copy）
│   ├── progress/
│   │   └── ProgressSnapshot.java     # 内部 record：total/done/failed 计数
│   └── error/
│       └── TaskErrorCode.java        # enum implements common.ErrorCode
├── internal/
│   ├── create/
│   │   └── CreateTaskServiceImpl.java   # @Component 实现 TaskCommandService
│   ├── cancel/
│   │   └── CancellationService.java     # 取消标志设置 + 单元间检查
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
│   └── publish/
│       └── TaskEventPublisher.java      # 发 TaskCreatedEvent/TaskCompletedEvent
├── infra/
│   ├── persistence/
│   │   ├── ProcessTaskMapper.java       # MyBatis-Plus
│   │   ├── ProcessResultMapper.java
│   │   └── ProcessResultMemberMapper.java
│   ├── mq/
│   │   ├── TaskMessage.java             # 中立 record（taskType/taskId/...）
│   │   ├── TaskMessagePublisher.java
│   │   └── TaskMessageListener.java     # @RabbitListener
│   ├── cache/
│   │   ├── TaskCacheKeys.java           # key 命名空间集中
│   │   ├── TaskProgressCounter.java     # AtomicLong incr / get
│   │   ├── TaskCancelFlag.java          # cancel:task:{id} 读写
│   │   ├── TaskIdempotencyStore.java    # task:idempotency:{key}
│   │   ├── TaskHeartbeatStore.java      # task:heartbeat:{taskId}:{workerId}
│   │   └── TaskLockKeys.java            # lock:task:{id}:execution
│   └── lock/
│       └── TaskLockManager.java         # Redisson 看门狗 + 释放
└── bootstrap/
    └── TaskAutoConfiguration.java      # @Configuration 装配
```

依赖方向：

```
module/task ──► infra/mq        （任务消息 publish/consume、DLQ 监听、重试模板）
module/task ──► infra/cache     （Redisson 锁/进度/信号量/取消/idempotency/heartbeat）
module/task ──► infra/storage   （结果落 MinIO、presigned URL 生成）
module/task ──► module/dag      （BranchExpander.expand / UnitExecutor.execute / ImageDescriptor / CopyContext）
module/task ──► module/imagegen （ImageGenExecutor.redraw / GenerativeUnitSpec；Wave 4 真正消费）
module/task ──► harness/state   （CheckpointReadPort → 可跳过集合 / 取消读协议）
module/task ──► common          （PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer）

module/conversation ──► module/task（确认 REST 端点调 TaskCommandService.createAndEnqueue）
harness/hooks       ──► module/task（订阅 TaskCreatedEvent / TaskCompletedEvent）
agent               ──► module/task（执行期查询/订阅，Wave 5）
harness/state       ──► module/task（实现 CheckpointReadPort，倒置接入；state.md §六）
```

**反向约束**（ArchUnit 守护）：`com.pixflow.module.task..` **不依赖** `module/dag` 的 `ir/validate/propose` 子包（只调用 `expand/exec` 子包）、**不依赖** `module/file`（不直连 `asset_*` 表，由 conversation/agent 喂入中立投影）、**不依赖** `harness/loop` / `agent` / `harness/tools`、**不出现** Spring AI 注解、**不调用** LLM。

---

## 四、TaskService：对外暴露的唯一入口

task 模块对外只暴露两个接口，所有调用方（包括 conversation REST 端点、agent 内部、harness/hooks 订阅方、前端）只看到这两个接口，看不到 worker / scheduler / recovery 等内部细节。

### 4.1 TaskCommandService

```java
public interface TaskCommandService {
    /**
     * 创建并入队一个任务。
     * 由确认 REST 端点调用，permission 已校验通过、dag 已二次校验、group preflight 已通过（或 user 已二次确认）。
     * @param cmd 包含 taskType、packageId、conversationId、idempotencyKey、payload（dag.json 或 imagegen plan）
     * @return taskId
     * @throws TaskAlreadyExistsException 同 idempotencyKey 已存在未完成任务时
     */
    TaskId createAndEnqueue(CreateTaskCommand cmd);

    /**
     * 取消任务。幂等：重复调用不报错。
     * 已完成/已失败任务返回 false；进行中任务返回 true 并设置 cancel 标志。
     */
    boolean cancel(CancelTaskCommand cmd);
}
```

### 4.2 TaskQueryService

```java
public interface TaskQueryService {
    /** 状态查询（轮询）；返回统一快照融合 MySQL/Redis 数据。 */
    TaskStatusView getStatus(TaskId taskId);

    /** 订阅进度事件（WebSocket 数据源）；返回 Flux 由 harness/state 实际推送。 */
    Flux<ProgressEvent> subscribe(TaskId taskId);

    /** 列出某会话下所有任务（前端历史面板）。 */
    Page<TaskSummary> listByConversation(ConversationId cid, PageQuery q);

    /** 拼装下载 handle（MinIO presigned URL 或 zip bundle 入口）。 */
    DownloadHandle getResultDownload(TaskId taskId, ResultSelector selector);
}
```

### 4.3 反向约定

- `TaskCommandService` 与 `TaskQueryService` 是**唯一**对其他模块可见的类。
- `api/event/` 下的 event record 是**事件源**（task publish），订阅方在 hooks 模块注册。
- `internal/*` 包类全部 `package-private` 或 `default` 可见性，**禁止** `public`（ArchUnit 守护）。
- `domain/model/WorkUnit` 等内部值对象**不进入 api 包**——它是 task worker 内部的线程池调度单位，外部调用方无理由持有。

---

## 五、CreateTaskService：从确认令牌到 process_task 落库

`createAndEnqueue` 是确认 REST 端点调用的入口，承担「已校验提案 → 持久化 → 入队」的窄职责。**它不做 DAG 校验、不做 group preflight、不做权限校验**——这些都已经在 conversation 的确认 REST 边界完成。

### 5.1 序列化时序

```
conversation.confirm REST 端点（permission 校验通过、DagValidator 二次校验通过、group preflight 通过）
  → TaskCommandService.createAndEnqueue(CreateTaskCommand{
        taskType, packageId, conversationId, idempotencyKey, payload  // payload: dag_json or imagegen plan
    })
  → IdempotencyGuard.checkAndReserve(idempotencyKey)            // 命中已存在未完成任务 → 直接返回原 taskId
  → 创建 process_task(status=PENDING, taskType=..., dagJson=payload) + 落库
  → 状态机迁移 PENDING → QUEUED
  → TaskMessagePublisher.publish(TaskMessage{taskId, taskType, ...})
  → 状态机迁移 QUEUED → PENDING（MQ 发布成功后保持 QUEUED；如发布失败 → 状态机迁移 PENDING → PENDING + 触发补偿或直接 FAILED）
  → publish TaskCreatedEvent（异步，hooks 订阅方各自处理）
  → return taskId
```

### 5.2 Idempotency 语义

设计决策（与你确认一致）：**REST 边界要求客户端传 `Idempotency-Key` header**（与 `IdempotencyGuard` 强绑定），防止用户连点确认按钮 / 网络重试导致的重复创建。

- key 写入 Redis `task:idempotency:{key}` → taskId 映射，TTL 24h；
- 同 key 重复请求：未完成任务（`PENDING/QUEUED/RUNNING/PARTIAL`）→ 直接返回原 taskId；已完成任务（`COMPLETED/FAILED/CANCELLED`）→ 抛 `TaskAlreadyCompletedException`（前端按"已执行"提示用户）。
- key 由客户端生成（UUID / 业务唯一键），task 不内置生成策略。
- MySQL `process_task.idempotency_key` 加 `UNIQUE` 约束，作为 Redis miss 时的兜底。

### 5.3 payload 落库

- **确定性路径**：`process_task.dag_json` = 确认时二次校验通过的 `ValidatedDag` 序列化（**不存** Agent 原始提案的 `DagDocument`，存校验后的不可变形式）。
- **生成式路径**：`process_task.dag_json` 位置存「有序 sourceImageIds + prompt + params」载荷（`type=IMAGEGEN` 区分），与 dag 共用 `dag_json` JSON 列、靠 `task_type` 字段分流；具体载荷形状由 `module/imagegen` 的 `ImagegenPlan` 规范定义（imagegen.md §五）。
- **恢复时不需回查 dag/imagegen 模块**：worker 拉起任务后从 `process_task.dag_json` 读出 payload，**不调用** `submit_image_plan` / `submit_imagegen_plan` handler 重做提案侧校验——这是 task 异步壳的"自包含"。

### 5.4 状态机迁移（PENDING → QUEUED）

- 创建：`status=PENDING`，入库。
- 入队（`TaskMessagePublisher.publish` 返回成功）：`PENDING → QUEUED`。
- 入队失败（MQ 暂时不可用）：`PENDING → PENDING` 保持，**不创建任务消息**；由调用方（conversation REST 端点）重试；如连续失败 → `PENDING → FAILED` + 记 `last_error`，由 `TaskCreatedEvent` 钩子通知前端。

> **关键约束**：`PENDING → QUEUED` 是唯一在 `createAndEnqueue` 路径上的迁移；其余迁移全部在 worker / recovery / cancel 路径上。

---

## 六、两条产图路径的统一执行壳

`ProcessWorker`（确定性）与 `ImageGenWorker`（生成式）共用同一套执行壳：WorkUnitScheduler + ProgressAggregator + FailureIsolator + CancellationService + TerminalStateJudge + RecoveryService。差异仅在「工作单元的装配」与「执行器的调用」两处。

### 6.1 WorkerRouter

```java
public class WorkerRouter {
    public void route(TaskMessage msg) {
        switch (msg.taskType()) {
            case IMAGE_PROCESS -> processWorker.handle(msg);
            case IMAGE_GEN    -> imageGenWorker.handle(msg);
        }
    }
}
```

`ProcessWorker` 与 `ImageGenWorker` 都实现相同的 `TaskWorker` 接口（`handle(TaskMessage)`），由 `WorkerRouter` 在 `@RabbitListener` 入口处路由。

### 6.2 ProcessWorker（确定性路径）

```java
public class ProcessWorker {
    public void handle(TaskMessage msg) {
        // 1. 加载 process_task + 校验 schema_version（与 dag schema 大版本对齐）
        ProcessTask task = taskMapper.findById(msg.taskId());
        ValidatedDag dag = dagJsonReader.read(task.getDagJson());   // 反序列化已校验 DAG

        // 2. 加载 asset_image 列表（packageId 下全部），装配为 ImageDescriptor
        List<ImageDescriptor> images = imageReader.findByPackage(task.getPackageId())
            .stream().map(ImageDescriptor::from).toList();

        // 3. 调 dag.BranchExpander.expand(dag, images) → List<ExecutableBranch>
        List<ExecutableBranch> branches = branchExpander.expand(dag, images);

        // 4. 装配 WorkUnit（每条支路 + 每张图/每个组 = 一个 WorkUnit）
        List<WorkUnit> units = assembleWorkUnits(branches, images, task.getId());

        // 5. 跳过已成功单元（state.CheckpointReadPort → Set<UnitKey>）
        Set<UnitKey> doneSet = state.recovery().computeSkippedSet(task.getId(), branches, images);

        // 6. 把未跳过单元提交到 WorkUnitScheduler
        units.stream()
             .filter(u -> !doneSet.contains(u.unitKey()))
             .forEach(scheduler::submit);

        // 7. 等所有单元完成（CompletableFuture.allOf）→ TerminalStateJudge 写终态
        scheduler.awaitAll(units).thenRun(() -> terminalJudge.judge(task.getId()));
    }
}
```

### 6.3 ImageGenWorker（生成式路径）

```java
public class ImageGenWorker {
    public void handle(TaskMessage msg) {
        ProcessTask task = taskMapper.findById(msg.taskId());
        ImagegenPlan plan = imagegenPayloadReader.read(task.getDagJson());   // 反序列化生图提案

        // 生成式单元 =「1 源图 → 1 重绘」，不需要 BranchExpander
        List<WorkUnit> units = plan.sourceImageIds().stream()
            .map(imageId -> WorkUnit.generative(task.getId(), imageId, plan, ...))
            .toList();

        // 跳过已成功单元（同一 CheckpointReadPort）
        Set<UnitKey> doneSet = state.recovery().computeSkippedSet(task.getId(), ...);

        units.stream()
             .filter(u -> !doneSet.contains(u.unitKey()))
             .forEach(scheduler::submit);

        scheduler.awaitAll(units).thenRun(() -> terminalJudge.judge(task.getId()));
    }
}
```

### 6.4 关键差异点

| 维度 | ProcessWorker | ImageGenWorker |
|---|---|---|
| 工作单元数 | = ∑(支路 × 图) 或 ∑(组支路 × 组)（BranchExpander 展开结果） | = 源图张数 |
| 单元载荷 | `UnitInput`（含图片字节来源 + 可选 CopyContext） | `GenerativeUnitSpec`（含 prompt + params + 源图引用） |
| 执行器调用 | `unitExecutor.execute(branch, input)` → `UnitOutcome` | `imageGenExecutor.redraw(spec)` → `GeneratedArtifact` |
| 跳过集计算 | 调 `state.RecoveryCoordinator.computeSkippedSet(taskId, branches, images)` | 同左，但参数为 imageIds 而非 branches |
| 进度计数单位 | 每条支路（含 group）一条记录 | 每张源图一条记录 |
| 落 `process_result` 字段 | `output_minio_key`、`branch_id`、`group_key`、`image_id`、`generated_copy` | `output_minio_key`（GENERATED 桶）、`image_id`（= 源图 id）、`source_path` 标生成式 |

**共用的部分**（所有 worker 共享）：

- Redisson 锁 `lock:task:{id}:execution`
- 心跳 `task:heartbeat:{taskId}:{workerId}`（30s 周期写）
- 进度 incr + WebSocket 推送
- 失败隔离（单工作单元失败 → FAILED + 继续兄弟）
- 取消标志单元间检查
- 终态判定

### 6.5 为什么不各开一个 worker 模块

- 调度壳、恢复扫描、终态判定、进度聚合、下载分发的逻辑在两条路径上**几乎完全相同**；拆出去就是重复实现。
- 复用 task 模块，imagegen 只暴露 `ImageGenExecutor` SPI（imagegen.md §四），保持「纯能力 + 不自持异步」的边界。
- worker 内部 `WorkerRouter` 已经把差异点收敛在「装配 + 执行器调用」两处，未来若生成式路径与确定性路径执行语义分叉过大，再评估独立化。

---

## 七、WorkUnit 与 WorkUnitScheduler：线程池 + 背压 + 取消

### 7.1 WorkUnit 内部值对象

```java
public record WorkUnit(
    UnitKind kind,                    // BRANCH | GROUP | GENERATIVE
    TaskId taskId,
    UnitKey unitKey,                  // (taskId, memberId, branchId)，对齐 state.UnitKey
    WorkUnitPayload payload           // 内部载荷（图片引用 / 文案 / 提示词）
) {}

public record WorkUnitPayload(
    ObjectLocation sourceLocation,    // 源图 MinIO 位置（确定性/生成式共用）
    CopyContext copyContext,          // 可空：仅 generate_copy 支路需要
    GenerativeUnitSpec generativeSpec,// 可空：仅生成式单元
    BranchSpec branchSpec             // 可空：仅确定性支路
) {}
```

`WorkUnit` **不是**对外 API（`api/` 包不可见），是 worker 内部的线程池调度单位。

### 7.2 WorkUnitScheduler

- **两个线程池**，按 `taskType` 隔离：
  - `process-pool`：core=8, max=16, queue=1000（确定性处理，IO + 像素计算为主）
  - `imagegen-pool`：core=4, max=8, queue=200（生图，外部模型调用为主，**额外叠加** `infra/ai.GlobalConcurrencyLimiter` 封顶）
- **背压**：队列满 → 拒绝策略 = `CallerRunsPolicy`（提交方线程跑，防任务丢失但防止无界堆积），同时 `Metrics` 指标 `pixflow.task.scheduler.rejected` 自增。
- **取消检查点**：每个 `WorkUnit` 提交后、线程池取出前 / 单元结束后，由 `WorkUnitSubmitter` 检查 `TaskCancelFlag.isCancelled(taskId)`；已取消则不入队 / 不开始下一个。
- **超时**：单工作单元无硬超时（像素处理可能很慢），由 `harness/state` 的"心跳超时 → @Scheduled 重扫"代替——单工作单元挂死不释放线程由 `CompletableFuture.get(超时)` 在 terminal judge 阶段兜底。
- **异常包装**：线程池抛出的任何异常都被 `WorkUnitSubmitter` 捕获 → 转 `ResultStatus.FAILED` + 归一化 `error_msg`，**不让异常逃逸到 worker 主流程**。

### 7.3 Redisson 锁的边界

- **任务级锁 `lock:task:{id}:execution`**：防止两个 worker 节点同时拉起同一任务。锁粒度 = 整个 worker.handle() 期间，watchdog 自动续期，handle 退出（正常/异常）→ 释放。
- **不做**工作单元级锁：同一任务下的不同工作单元可并行，task 内部不需要按 unit 抢锁。
- **锁的 owner = workerId**（节点标识 + 进程内 UUID），与 heartbeat 联动：worker 崩溃 → 锁 TTL 到期 + heartbeat 缺失 → @Scheduled 重扫触发恢复。

---

## 八、状态机：process_task / process_result 的迁移表

`process_task.status` 与 `process_result.status` 的所有合法迁移集中在 `TaskStateMachine` / `ResultStateMachine` 纯函数里（不依赖 Spring 上下文、不查数据库），可被属性测试覆盖。**所有写库代码都必须经过 `stateMachine.transit(status, newStatus)` 校验**，否则拒绝（`@Aspect` 或显式调用）。

### 8.1 process_task.status 迁移表

```
PENDING(0)    → QUEUED(1)            # createAndEnqueue 入队成功
PENDING(0)    → FAILED(4)            # 入队失败（如 MQ 不可用）
QUEUED(1)     → RUNNING(2)           # worker 拉起（@RabbitListener 入口）
RUNNING(2)    → COMPLETED(3)         # 全部完成，至少一条成功
RUNNING(2)    → FAILED(4)            # 全部失败
RUNNING(2)    → CANCELLED(5)         # 用户取消
RUNNING(2)    → PARTIAL(6)           # 部分完成 + 取消（用户取消时已成功的单元保留）
QUEUED(1)     → CANCELLED(5)         # 用户在入队后、消费前取消（罕见但可能）
FAILED(4)     → （终态）              # 不允许从 FAILED 复活
COMPLETED(3)  → （终态）              # 不允许从 COMPLETED 复活
CANCELLED(5)  → （终态）
PARTIAL(6)    → （终态）              # PARTIAL 是"部分完成"语义，不允许再迁移
```

### 8.2 process_result.status 迁移表

```
PENDING(0)    → RUNNING(1)           # worker 开始执行该单元
PENDING(0)    → SKIPPED(4)           # 取消后未开始的单元
RUNNING(1)    → SUCCESS(2)           # 单元执行成功
RUNNING(1)    → FAILED(3)            # 单元执行失败（隔离）
SKIPPED(4)    → （终态）
SUCCESS(2)    → （终态）
FAILED(3)     → （终态）
```

### 8.3 关键约束

- `RUNNING` 状态的写入与读取由 `TerminalStateJudge` 协调：所有工作单元完成后才判定终态（避免"还在跑就标 FAILED"的并发漏洞）。
- 终态判定时必须等所有 `RUNNING` 的工作单元 `CompletableFuture` 完成（`allOf().join()`），避免与 `WorkUnitSubmitter` 的取消检查点产生竞态。
- 状态机迁移是**单调的**：不允许从 SUCCESS → FAILED、COMPLETED → RUNNING 等回退（属性测试守护）。

### 8.4 状态机实现

```java
public final class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
        PENDING,   Set.of(QUEUED, FAILED),
        QUEUED,    Set.of(RUNNING, CANCELLED),
        RUNNING,   Set.of(COMPLETED, FAILED, CANCELLED, PARTIAL),
        COMPLETED, Set.of(),
        FAILED,    Set.of(),
        CANCELLED, Set.of(),
        PARTIAL,   Set.of()
    );

    public static void canTransit(TaskStatus from, TaskStatus to) {
        if (!ALLOWED.get(from).contains(to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
```

---

## 九、ProgressAggregator：Redis 计数 + 实时推送

### 9.1 双重权威

- **MySQL `process_task.total_count` / `done_count`**：权威计数（design.md §13.1）。
- **Redis `progress:task:{id}`**：`done` 计数器（`AtomicLong incr`），用于实时推送与前端秒级刷新。
- **对账**（harness/state §八）：终态判定时由 `state.ProgressReconciler` 比对 MySQL 与 Redis，**MySQL 胜**。`progress:task:*` 在 task 完成后立即清空（避免 Redis 长期堆积）。

### 9.2 推送协议

- task 写 `done_count` → `ProgressAggregator.publish(ProgressEvent{taskId, done, total, failed, ...})`
- `ProgressEvent` 通过 `harness/state.ProgressEventPublisher` 发布（不直连 WebSocket，state 负责推到 WS session）
- 前端订阅：`WebSocket /task/{taskId}/progress` → 收 `ProgressEvent`
- 轮询兜底：前端每 5s 调 `TaskQueryService.getStatus(taskId)`（state 融合 MySQL+Redis 返回 `TaskStatusView`）

### 9.3 done / failed / total 的语义

- `total` = 工作单元总数（`workUnits.size()`，worker 拉起分支展开后确定）
- `done` = `SUCCESS + FAILED + SKIPPED`（终态即不再变）
- `failed` = `FAILED`（仅失败数，不含成功 / 跳过）
- 终态时：
  - 至少一条 `SUCCESS` 且无 `RUNNING` → `COMPLETED`
  - 全部 `FAILED` → `FAILED`
  - 部分 `SUCCESS` + 用户取消 → `PARTIAL`
  - 全部 `SKIPPED`（用户取消前无单元完成）→ `CANCELLED`

---

## 十、FailureIsolator：失败隔离与终态判定

### 10.1 单工作单元失败

```
WorkUnit.run() throws Exception (or returns UnitOutcome.FAILED)
  → FailureIsolator.handle(unit, exception)
       1. 归一化异常 → DagErrorCode / ImagegenErrorCode / TaskErrorCode（透传已有 code，新增"task 自己抛"的）
       2. Sanitizer 脱敏 → error_msg（≤1000 字）
       3. 写 process_result(status=FAILED, error_msg, attempt_count++)
       4. 进度 incr（done++, failed++）
       5. publish ProgressEvent
       6. 写 process_result 后不抛任何异常 → 继续兄弟单元
```

### 10.2 任务级失败

`TerminalStateJudge` 在所有 `CompletableFuture` 完成后触发：

- `doneSet` = 所有工作单元的最终 status
- 若 `doneSet` 全是 `FAILED` → task.status `RUNNING → FAILED` + 写 `last_error`
- 若 `doneSet` 全是 `SKIPPED` → task.status `RUNNING → CANCELLED`
- 若至少一条 `SUCCESS` → task.status `RUNNING → COMPLETED`
- 若用户中途取消 + 部分 SUCCESS → `RUNNING → PARTIAL`
- 终态写入后 `publish TaskCompletedEvent`

### 10.3 系统级失败（worker 崩溃）

- **不进入** FailureIsolator 的判定路径——worker 崩溃时无 `TaskCompletedEvent` 发布。
- 由 `RecoveryService`（§十一）的 `@Scheduled` 扫描发现心跳缺失 → 重新入队 + worker 重启后重跑未完成单元。

### 10.4 错误码

`TaskErrorCode implements common.ErrorCode`（码自治，并入 `common` 启动期聚合测试）：

| code | category | recovery | 场景 |
|---|---|---|---|
| `TASK_NOT_FOUND` | NOT_FOUND | TERMINATE | taskId 不存在 |
| `TASK_ALREADY_COMPLETED` | BUSINESS_RULE | TERMINATE | idempotency key 命中已完成任务 |
| `TASK_INVALID_STATE_TRANSITION` | VALIDATION | TERMINATE | 状态机迁移非法（如 COMPLETED → RUNNING） |
| `TASK_DAG_PAYLOAD_INVALID` | VALIDATION | TERMINATE | 反序列化 `process_task.dag_json` 失败（恢复时 payload 损坏） |
| `TASK_IMAGEGEN_PAYLOAD_INVALID` | VALIDATION | TERMINATE | 反序列化生图 payload 失败 |
| `TASK_RESULT_WRITE_FAILED` | STORAGE | RETRY | 写 `process_result` 失败（数据库瞬时不可用） |
| `TASK_PROGRESS_PUBLISH_FAILED` | DEPENDENCY | SKIP | 进度事件发布失败（不影响主流程，仅指标告警） |
| `TASK_CANCEL_NOT_PERMITTED` | BUSINESS_RULE | TERMINATE | 终态任务尝试取消（已 SUCCESS/FAILED/CANCELLED） |
| `TASK_DOWNLOAD_RESULT_NOT_READY` | BUSINESS_RULE | SKIP | 结果未就绪时请求下载（前端在 done=0 时请求） |
| `TASK_DOWNLOAD_BUNDLE_TOO_LARGE` | VALIDATION | TERMINATE | 打包 zip 超过 `max-bundle-bytes`（防 OOM） |
| `TASK_RECOVERY_LOCK_CONTENTION` | DEPENDENCY | SKIP | 恢复扫描时锁被占用（下一轮重试） |

---

## 十一、断点恢复：RecoveryService + 单元级幂等

### 11.1 恢复粒度（design.md §9.4）

**task 级 + 支路/单元级**两级恢复：

- **task 级**：`@Scheduled` 扫 `status=RUNNING` 且 `started_at < now - 30min` 或 `heartbeat` 缺失 → 重新入队一条任务消息。
- **单元级**：worker 拉起后调 `state.RecoveryCoordinator.computeSkippedSet(taskId, branches, images)` → 跳过已成功 `process_result` 的单元；其余整体幂等重算。

**禁止节点级续传**（与 design.md §9.4 一致）：支路本就是独立单元，节点级续传处理参数/中间态/分支共享复杂度爆炸。

### 11.2 单元幂等保证

- **Deterministic UnitKey**：`UnitKey(taskId, memberId, branchId)` 由 task 自己从 BranchExpander 结果 + 源图列表装配。`memberId` 对齐 `asset_image.id`（确定性）或源图 id（生成式）；`branchId` 由 dag 的 BranchExpander 确定性派生（dag.md §7.3）。
- **`process_result` 唯一索引**：`UNIQUE(task_id, image_id, branch_id)`（普通支路）/ `UNIQUE(task_id, group_key, branch_id)`（组支路）/ `UNIQUE(task_id, image_id, branch_id='gen')`（生成式单元），保证「同一单元多次重跑不产生重复记录」。
- **写入策略**：`INSERT ... ON DUPLICATE KEY UPDATE`（MySQL upsert），重跑覆盖旧记录（仅 `status=RUNNING/PENDING` 时覆盖；`SUCCESS` 跳过）。

### 11.3 RecoveryService 实现

```java
@Scheduled(cron = "${pixflow.task.recovery.cron:0 */1 * * * *}")
public void recover() {
    // 1. 查 status=RUNNING 的 process_task
    List<ProcessTask> running = taskMapper.findByStatusAndStartedAtBefore(
        TaskStatus.RUNNING, Instant.now().minus(taskProps.getRecoveryStaleAfter())
    );

    // 2. 对每个 task 检查 heartbeat（task:heartbeat:{taskId}:*）
    //    - heartbeat 存在 → 仍在跑（可能是慢任务），跳过
    //    - heartbeat 缺失 → 重新入队
    for (ProcessTask task : running) {
        if (heartbeatStore.isAlive(task.getId())) {
            metrics.record("skipped", task.getId());
            continue;
        }
        // 释放可能残留的分布式锁
        lockManager.forceUnlock(task.getId());
        // 重新发布任务消息
        taskMessagePublisher.publish(TaskMessage.of(task));
        metrics.record("requeued", task.getId());
    }
}
```

### 11.4 关键约束

- 恢复扫描是**幂等**的：连续触发不会产生重复任务消息（`TaskMessagePublisher.publish` 按 `taskId` 去重）。
- 恢复不修改 `process_task.status`：扫描到的 task 保持 `RUNNING`，让 worker 拉起后自然流转到终态。
- 扫描频率默认 1 分钟（`pixflow.task.recovery.cron: 0 */1 * * * *`），生产可调为 5 分钟。
- 恢复扫描器跑在 `@Scheduled` 线程，**不抢** worker 线程池。

---

## 十二、取消：协作式协议 + 工作单元边界检查

### 12.1 取消协议

```
用户点取消 → REST cancel → TaskCommandService.cancel(cmd)
  → 1. 查 process_task.status
       - 终态（COMPLETED/FAILED/CANCELLED）→ return false（幂等）
       - 非终态 → 继续
  → 2. 设置 Redis cancel:task:{id} = 1（立即生效，让正在跑的 worker 在单元边界看到）
  → 3. 状态机迁移（QUEUED → CANCELLED / RUNNING → CANCELLED 或 PARTIAL）
       - QUEUED 状态：直接 CANCELLED（worker 拉起后看到 cancel 标志会立即退出，见 §12.3）
       - RUNNING 状态：写 CANCELLED 不准确（可能有单元正在跑），先标 PARTIAL（终态由 TerminalStateJudge 跑完所有单元后写）
```

### 12.2 取消标志的读侧

- task worker 在以下边界检查 `cancel:task:{id}`：
  1. `@RabbitListener` 入口（拉起任务时）
  2. 每个工作单元提交到线程池前
  3. 每个工作单元结束后、下一个开始前
  4. `awaitAll(units)` 之后做终态判定时
- **不在**像素工具调用 / 模型调用 / MinIO I/O **中途打断**——避免数据损坏（与 design.md §9.4 一致）。
- 检查由 `harness/state.CancellationReader.throwIfCancelled(taskId)` 提供（state.md §九），task 不直连 Redis 取消键。

### 12.3 取消后的工作单元命运

- **未开始的单元**：worker 检测到 cancel → 写 `process_result.status=SKIPPED`，不调执行器。
- **正在跑的单元**：跑完当前单元自然结束（不打断），结果按 SUCCESS/FAILED 正常落库。
- **失败的单元**（取消前已开始但未完成的）：不视为 FAILED（避免污染统计），由 `SKIPPED` 覆盖（**调整**：取消时已开始的单元如果失败，保留 FAILED 但记 `error_msg=CANCELLED` 标识取消上下文——见 §12.4）。

### 12.4 边界细节

- 取消时已 RUNNING 的单元**不被强制中断**（防数据损坏）；但若单元自然失败 → status=FAILED、`error_msg` 追加 `[cancelled at {ts}]` 标识。
- 已 SUCCESS 的单元保留（不会因取消回退）。
- 最终 task 终态由 `TerminalStateJudge` 在所有 CompletableFuture 完成后判定：
  - 有 SUCCESS + cancel → `PARTIAL`
  - 无 SUCCESS + 全 SKIPPED → `CANCELLED`
  - 无 SUCCESS + 有 FAILED + cancel → `CANCELLED`（FAILED 是取消前的真实失败，不计 COMPLETED）

---

## 十三、下载分发：MinIO Presigned URL

### 13.1 单结果下载

- 用户在前端点单个结果 → `TaskQueryService.getResultDownload(taskId, ResultSelector.of(skuId, imageId, branchId))`
- `DownloadService` 查 `process_result.output_minio_key` → 调 `infra/storage.ObjectStorage.presignedGetUrl(bucket, key, expiry=15min)` 返回 `DownloadHandle{url, expiresAt}`。
- 前端直接 `<a href={url} download>`，**不经过应用层**——避免大文件穿透。

### 13.2 批量打包下载

- 用户在前端点"下载全部" → `DownloadService.buildBundle(taskId)`
- 后端异步流式打包（用 `ZipOutputStream` 直接写到 `ObjectStorage` 临时桶 `task:downloads:{taskId}.zip`），完成后返回该 zip 的 presigned URL。
- 打包过程**必须**：
  - 在 `infra/storage` 中转（不堆在内存），超 `max-bundle-bytes`（默认 2GB）→ `TASK_DOWNLOAD_BUNDLE_TOO_LARGE` 拒绝。
  - 完成后清理临时桶条目（避免 MinIO 长期堆积）。
  - 跑在独立线程池（`download-pool`），不抢 worker 线程。

### 13.3 下载权限

- 当前阶段**不做**细粒度下载鉴权（design.md §十六）；只要求前端带 `Idempotency-Key` 同源的 `taskId` 即可访问（前端在会话内已通过 conversation 鉴权）。
- 未来若加租户隔离：presigned URL 加 tenant 字段，MinIO policy 强制 bucket 隔离。

---

## 十四、错误与降级

### 14.1 业务错误（task 模块自身）

见 §10.4 `TaskErrorCode` 表。

### 14.2 基础设施错误

| 来源 | 错误归一化 | task 行为 |
|---|---|---|
| MySQL 写入失败 | `STORAGE/RETRY` | 重试 3 次（指数退避），仍失败 → `TASK_RESULT_WRITE_FAILED` + 单元标 FAILED |
| Redis 进度 incr 失败 | `DEPENDENCY/SKIP` | 跳过（不影响结果落库），指标告警 |
| Redis 取消标志读取失败 | `DEPENDENCY/SKIP` | 跳过（视为未取消，继续执行；下次边界再查） |
| MinIO 写失败（dag 单元执行器已归一化） | `STORAGE/RETRY` | 透传，由 `FailureIsolator` 标 FAILED |
| MQ 消费失败 | `DEPENDENCY/RETRY` | Resilience4j 重试，3 次后入 DLQ（`infra/mq` 的 DLQ 监听器） |
| MQ DLQ 消息 | —— | DLQ 监听器落 `task.last_error` + 状态机迁移 `RUNNING → FAILED` + publish TaskCompletedEvent（带 `lastError`） |

### 14.3 降级原则

- **进度推送失败** → 不影响主流程，仅指标告警。
- **取消标志读取失败** → 视为未取消（最坏情况是用户取消按钮失效，但不会让任务错乱）。
- **process_result 写失败** → 单元标 FAILED + 重试，**绝不**让"结果正确但落库失败"的单元被错误地标 SUCCESS。
- **DLQ 消息处理失败** → 入二级 DLQ + 告警（不无限重试）。

---

## 十五、配置

`@ConfigurationProperties(prefix = "pixflow.task")`：

```yaml
pixflow:
  task:
    create:
      idempotency-ttl: 24h            # Redis task:idempotency:* TTL
    worker:
      process-pool:
        core-size: 8
        max-size: 16
        queue-capacity: 1000
        rejected-policy: CALLER_RUNS  # CALLER_RUNS | ABORT
      imagegen-pool:
        core-size: 4
        max-size: 8
        queue-capacity: 200
        rejected-policy: CALLER_RUNS
      download-pool:
        core-size: 2
        max-size: 4
        queue-capacity: 100
      heartbeat-interval: 30s         # worker 写心跳周期
    lock:
      task-execution-ttl: 10m         # lock:task:{id}:execution 看门狗 TTL
      force-unlock-on-recovery: true  # 恢复扫描时强制释放残留锁
    recovery:
      cron: "0 */1 * * * *"           # 恢复扫描周期（默认 1 分钟）
      stale-after: 30m                # status=RUNNING 超此时长视为可疑
      heartbeat-grace: 5m             # heartbeat 缺失容忍时间
    cancel:
      check-interval: 0               # 单元间检查（同步检查，无间隔）
    download:
      single-url-expiry: 15m          # 单结果 presigned URL 过期
      max-bundle-bytes: 2147483648    # 2GB；打包 zip 超过此值拒绝
      bundle-temp-prefix: "task-downloads/"
    terminal:
      judge-timeout: 60s              # 终态判定等待所有 CompletableFuture 的最大时长
    progress:
      counter-ttl: 1h                # progress:task:* Redis TTL（任务完成后清理）
```

- 线程池参数与 design.md §9.3「默认并发 8」对齐，可按部署规模调。
- 取消 / 锁的 TTL 与 design.md §9.4 / §6.3 对齐。
- 进度计数器 TTL 防止 Redis 长期堆积（任务完成后由 `TerminalStateJudge` 主动清理，此处兜底）。

---

## 十六、可观测性

task 模块通过 Micrometer 暴露指标，**不依赖 `harness/eval`**（honor 依赖 DAG 中 `task` 无 `→ eval` 边）。指标由 Spring Boot Actuator 端点暴露，Prometheus/Grafana 直接消费。

### 16.1 指标清单

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `pixflow.task.create` | counter | `task_type=image_process\|image_gen`, `result=ok\|idempotent_hit\|failed` | 任务创建结果分布 |
| `pixflow.task.create.duration` | timer | `task_type` | 创建到入队耗时（应 P99 < 50ms） |
| `pixflow.task.worker.exec` | counter | `task_type`, `outcome=success\|failed\|skipped` | 单元执行结果分布 |
| `pixflow.task.worker.exec.duration` | timer | `task_type`, `outcome` | 单元执行耗时分布（应 P99 < 5s 普通支路 / 30s 生图） |
| `pixflow.task.worker.pool` | gauge | `pool=process\|imagegen\|download` | 线程池活跃线程数 / 队列大小 |
| `pixflow.task.worker.pool.rejected` | counter | `pool` | 线程池拒绝次数（背压触发） |
| `pixflow.task.heartbeat.miss` | counter | `task_id`（低基数，按 worker 节点） | 心跳写入失败次数 |
| `pixflow.task.recovery.scan` | counter | `result=requeued\|skipped\|error` | 恢复扫描结果分布 |
| `pixflow.task.cancel` | counter | `state=queued\|running\|terminal` | 取消请求结果分布 |
| `pixflow.task.terminal` | counter | `state=completed\|failed\|cancelled\|partial` | 任务终态分布 |
| `pixflow.task.progress.publish` | counter | `result=ok\|failed` | 进度事件发布结果 |
| `pixflow.task.download` | counter | `type=single\|bundle`, `result=ok\|too_large\|failed` | 下载请求结果 |
| `pixflow.task.lock.contention` | counter | `lock=task_execution` | 锁竞争次数 |
| `pixflow.task.state.transition.rejected` | counter | `from`, `to` | 状态机非法迁移次数（应为 0） |

### 16.2 关键约束

- 指标**不带业务字段**（taskId、skuId、imageId 不入标签），避免高基数打爆指标存储。
- `state.transition.rejected` 出现 > 0 即为 bug 告警（状态机迁移应 100% 合法）。
- timer 直方图分位点（SLO 友好）默认启用 `publishPercentileHistogram()`。
- 异常时仅记录 `code + safeMessage + traceId`；不记录图像字节、提示词等业务内容。

### 16.3 trace 与日志

- task 关键路径写 `agent_trace` 表由 harness/eval 负责（worker.handle 入口/出口、`createAndEnqueue`、终态判定三处）。
- SLF4J + traceId 输出（traceId 由 `common.PixFlowException.traceId` 提供）。
- 与 dag/imagegen 同款 `common.Sanitizer` 脱敏。

---

## 十七、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `module/conversation` | 调 `TaskCommandService.createAndEnqueue(cmd)`（确认 REST 边界通过后）；调 `TaskCommandService.cancel(cmd)`（用户取消）；订阅 `TaskCompletedEvent` 用于会话级状态汇总 |
| `harness/hooks` | 订阅 `TaskCreatedEvent` / `TaskCompletedEvent`；`harness/hooks` 不反向调 task 内部 |
| `agent`（Wave 5） | 调 `TaskQueryService.getStatus / subscribe`；不调 `TaskCommandService`（确认执行走 conversation REST 边界） |
| `module/dag` | 调 `BranchExpander.expand(ValidatedDag, List<ImageDescriptor>)`；调 `UnitExecutor.execute(ExecutableBranch, UnitInput)`；据 `UnitOutcome` 写 `process_result`/`process_result_member`；喂 `ImageDescriptor`/`CopyContext` 中立输入 |
| `module/imagegen`（Wave 4 落地） | 调 `ImageGenExecutor.redraw(GenerativeUnitSpec)`；据 `GeneratedArtifact` 写 `process_result` |
| `harness/state` | 调 `CheckpointReadPort`（由 task 实现，state 调用）→ `Set<UnitKey>` 可跳过集合；调 `CancellationReader.throwIfCancelled(taskId)` 在单元边界 |
| `infra/mq` | 任务消息 publish/consume；DLQ 监听器调用 `task` 的 `markFailedByDlq(taskId, errorMsg)` |
| `infra/cache` | Redisson 锁/进度/取消/信号量/心跳/idempotency；键命名空间由 task 决定（`TaskCacheKeys`），value 序列化由 cache 抽象负责 |
| `infra/storage` | 调 `ObjectStorage.put(StorageKeys.results/results-gen, bytes)` 写结果；调 `presignedGetUrl` 拼下载 URL；调 `getStream` 拉源图字节（task 不直拉，由 dag/imagegen 拉） |
| `common` | 抛 `PixFlowException(TaskErrorCode)`；进度/错误文案经 `Sanitizer`；`TaskErrorCode` 并入 `common` 启动期聚合测试 |

**反向约束**（ArchUnit 守护）：
- task **不依赖** `harness/loop` / `harness/tools` / `harness/context` / `harness/hooks`（除订阅 SPI 外的反向调用）
- task **不依赖** `module/file`（不直连 `asset_*` 表，由 conversation/agent 喂入中立投影）
- task **不依赖** `agent`（执行期查询走 `TaskQueryService`，不导入 agent 包）
- task **不出现** Spring AI 注解 / LLM 调用

---

## 十八、测试策略

### 18.1 单元测试

- **`TaskStateMachine` / `ResultStateMachine`**：所有合法迁移 + 所有非法迁移（属性测试：随机 from/to 组合，断言仅合法迁移通过）。
- **`CreateTaskServiceImpl`**：幂等命中（key 已存在未完成任务 → 返回原 taskId）；幂等命中（key 已存在已完成任务 → 抛 `TASK_ALREADY_COMPLETED`）；MQ 发布失败 → 状态机迁移 `PENDING → FAILED`。
- **`WorkUnitScheduler`**：线程池满时 `CallerRunsPolicy` 触发；线程池满时 `AbortPolicy` 触发；正常提交流程；异常包装（提交后抛异常不污染 worker）。
- **`FailureIsolator`**：异常归一化（各类异常 → 对应 `TaskErrorCode`）；脱敏（error_msg 不含凭证/绝对路径）；进度 incr；写 `process_result` 后不抛异常。
- **`TerminalStateJudge`**：全 SUCCESS → COMPLETED；全 FAILED → FAILED；部分 SUCCESS + cancel → PARTIAL；全 SKIPPED + cancel → CANCELLED。
- **`IdempotencyGuard`**：Redis 命中 / miss；MySQL UNIQUE 兜底（Redis miss + MySQL 命中 → 仍走幂等）。
- **`DownloadService`**：单结果 URL 拼装；bundle 超 `max-bundle-bytes` → `TASK_DOWNLOAD_BUNDLE_TOO_LARGE`；bundle 临时文件清理。

### 18.2 集成测试（fake MQ / fake MinIO / fake Redis）

- **create → worker → terminal 端到端**：造 5 张图 + 简单 DAG（remove_bg→resize）→ 创建任务 → worker 拉起 → 全部 SUCCESS → 终态 COMPLETED。
- **失败隔离**：造 5 张图，其中 2 张损坏 → worker 跑出 2 FAILED + 3 SUCCESS → 终态 COMPLETED（不全失败）。
- **取消协作**：worker 跑到一半时调 cancel → 后续单元标 SKIPPED → 已开始单元自然完成 → 终态 PARTIAL。
- **断点恢复**：worker 跑到一半时强制 kill（不发 TaskCompletedEvent）→ @Scheduled 扫描 → heartbeat 缺失 → 重新入队 → 新 worker 拉起 → 跳过已成功单元 → 终态 COMPLETED。
- **idempotency key 重复**：同 key 二次 createAndEnqueue → 返回原 taskId + 不创建新任务。
- **生图路径**：造 3 张源图 + ImagegenPlan → worker 拉起 → 调 fake ImageGenExecutor → 全部 SUCCESS → 终态 COMPLETED；`process_result` 字段正确（`source_path` 标生成式、`output_minio_key` 在 GENERATED 桶）。

### 18.3 边界守护（ArchUnit）

```java
@ArchTest
static final ArchRule task_module_boundaries = classes()
    .that().resideInAPackage("com.pixflow.module.task..")
    .should().onlyAccessClassesThat().resideInAnyPackage(
        "com.pixflow.module.task..",
        "com.pixflow.module.dag.expand..", "com.pixflow.module.dag.exec..",
        "com.pixflow.module.dag.ir..",                  // ValidatedDag / DagNode record
        "com.pixflow.module.imagegen..",
        "com.pixflow.harness.state..",
        "com.pixflow.infra.mq..", "com.pixflow.infra.cache..", "com.pixflow.infra.storage..",
        "com.pixflow.common..", "com.pixflow.contracts..",
        "java..", "org.springframework..", "io.micrometer..", "reactor.."
    );

@ArchTest
static final ArchRule task_api_internal_isolation = classes()
    .that().resideInAPackage("com.pixflow.module.task.internal..")
    .should().notBePublic();
```

### 18.4 错误码目录一致性

`TaskErrorCode` 并入 `common` 启动期聚合测试：code 唯一 + i18n 齐全 + category 非空。

### 18.5 端到端

- 与 dag / imagegen / state / mq / cache / storage 联合跑全链路（Testcontainers 拉起 MySQL/Redis/MinIO/RabbitMQ）：
  - 真实 DAG 端到端：上传素材包 → Agent 提案 → 用户确认 → task 执行 → 终态 → 通知 → 下载
  - 真实生图端到端：同上传素材包 → Agent 提案 → 用户确认 → task 执行 → 终态 → 下载
  - 故障注入：MQ DLQ、worker 崩溃、MinIO 暂时不可用、Redis 不可用 → 行为符合设计预期

---

## 十九、对 design.md 与依赖计划的细化

本模块对 `design.md` 与相邻设计/计划文档做如下**细化（非冲突）**，需同步记录：

1. **status enum 重新对齐**（`design.md §13.1`）：`process_task.status` 由 design.md 隐含的 3 态（待执行/执行中/完成/失败）扩展为 **7 态** `PENDING/QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/PARTIAL`；`process_result.status` 由 2 态扩展为 **5 态** `PENDING/RUNNING/SUCCESS/FAILED/SKIPPED`。补 `PARTIAL`（部分完成）以区分"全失败"和"部分成功 + 取消"。
2. **`process_task` 增量字段**（`design.md §13.1`）：新增 `task_type`、`idempotency_key` (UNIQUE)、`priority`、`cancel_reason`、`enqueued_at/started_at/finished_at`、`worker_id`、`attempt_count`、`last_error` 共 9 个生产级字段。
3. **`process_result` 增量字段**（`design.md §13.1`）：新增 `attempt_count`、`started_at/finished_at`、`bytes_in/bytes_out`、`worker_run_id` 共 4 个生产级字段。
4. **Redis 键补强**（`design.md §13.3`）：补 `task:idempotency:{key}`（24h TTL）、`task:heartbeat:{taskId}:{workerId}`（30s TTL）、`lock:task:{taskId}:execution`（10m 看门狗）共 3 类键。
5. **MySQL 索引补强**（`design.md §13.1`）：`idx_process_task_status_started`（恢复扫描）、`uq_process_task_idempotency_key`（幂等兜底）、`idx_process_result_task_image_branch` 与 `idx_process_result_task_group_branch`（单元幂等）。
6. **新增 `imagegen → task` 依赖边**（`module-dependency-dag-plan.md`）：Wave 4 落地时由 task 引入，与 `dag → task` 边对称（dag.md / imagegen.md 已登记）。
7. **task 与 dag/imagegen 的「执行引擎」边界**（`design.md §9/§12`、`dag.md §二`、`imagegen.md §二`）：本模块钉死——dag 与 imagegen 是被 task 调用的**无状态确定性/能力单元**；task 是**有状态异步外壳**。失败隔离分两半——单元内归一化在 dag/imagegen（返回 FAILED outcome），批次内继续在 task（落 `process_result.status=FAILED` + 兄弟单元继续）。
8. **WebSocket 归属**（`design.md §5.4`）：task **不直连** WebSocket / STOMP / SSE；进度推送由 `harness/state.ProgressEventPublisher` 负责，task 只发 `ProgressEvent`（domain event）到 publisher。这细化 design.md §4 「WebSocket 推送」的归属（应改为 state 模块）。
9. **DLQ 语义**（`design.md §9.2`）：业务错误（dag 校验失败）不入 DLQ；系统错误（worker 抛 NPE / DB 挂）入 DLQ → 监听器落 `last_error` + 状态机迁移 RUNNING → FAILED；第三方错误（抠图/生图 5xx）→ 走支路级 Resilience4j 重试 + FailureIsolator，不入 DLQ。
10. **`process_task.dag_json` 字段含义**（`design.md §13.1`）：明确该字段存**已校验的不可变 DAG 快照**（确定性路径）或**生图提案规范化载荷**（生成式路径）；恢复时不需回查 dag/imagegen 模块。task 自包含、避免循环依赖。
11. **生成式路径 task 模块边界**（`imagegen.md §八`）：task 模块包办两条产图路径的异步执行壳，imagegen 只暴露 `ImageGenExecutor` SPI；由 `WorkerRouter` 按 `task_type` 路由到 `ProcessWorker` / `ImageGenWorker`。该边界在 `module-dependency-dag-plan.md` 已有（task 模块本就设计为承接两条路径），本文落实实现细节。
12. **可观测性 14 类指标**（与 dag 的 11 类、imagegen 的 6 类在 shape 上互补）：按 `create/worker/heartbeat/recovery/cancel/terminal/progress/download/lock/state.transition` 10 组维度组织；不依赖 `harness/eval`；不带业务字段；细分到 `TaskErrorCode` 的 label。

> 以上为对既有设计的补充落地，不改变 `design.md §5.4`「MySQL 是事实源」、§9.4「支路幂等重算」原则、§13.3「Redis 引用而非字节」口径。

---

## 二十、暂不考虑

- **节点级断点续传**：恢复以支路/单元为幂等单元整体重算（design.md §9.4），不做节点级续传。
- **任务优先级调度**：`process_task.priority` 字段已建但本期不实现优先级队列（RabbitMQ 不直接支持，需额外实现 priority queue）；生产可调线程池并发上限达到类似效果。
- **下载细粒度鉴权**：本期按 taskId 即可下载（前端已在会话内鉴权）；多租户隔离留待权限层细化。
- **任务级工作流编排**（DAG 任务的子任务 / 任务依赖）：本期每个 task 独立执行，无 task 间依赖；如需"批量完成后触发下游任务"留待后续工作流模块。
- **任务级重试上限**：`attempt_count` 字段已建但本期不实现自动重试（DLQ 是 task 级失败出口，单元级重试在 dag/imagegen 的 Resilience4j 内）。
- **应用层代理下载**：本期用 MinIO presigned URL，**绝不**经应用层代理大文件（避免 OOM / 阻塞 worker 线程）。
- **跨 task 的资源复用**（如多 task 共用 watermark 缓存）：本期 watermark 缓存放 dag 进程内（dag.md §11.4），不跨 task；如需跨 task 共享由 task 引入分布式缓存（待成本瓶颈出现再评估）。

---

## Revision Notes

2026-06-29 / Kiro: 新增 `module/task` 设计文档。确立核心边界「task 是 dag/imagegen 的异步外壳」——dag 是无状态确定性单元执行器、imagegen 是无状态单图重绘能力模块、task 是有状态并发编排外壳。确定 12 项对既有设计的细化（§十九），核心包括：① 状态机 7 态 + 5 态扩展；② `process_task` / `process_result` 增量 13 个生产级字段；③ Redis 键补强 3 类；④ 两条产图路径共用同一执行壳（`WorkerRouter` + `ProcessWorker` + `ImageGenWorker`）；⑤ 失败隔离分两半（单元内归一化在 dag/imagegen，批次内继续在 task）；⑥ 取消是协作式协议、绝不打断运行中单元；⑦ WebSocket 归属改为 `harness/state`，task 只发 domain event；⑧ 恢复粒度 = task 级 + 单元级，单元级幂等靠 `UnitKey` + `process_result` 唯一索引；⑨ task 模块包办两条产图路径的异步执行壳，imagegen 不自建异步；⑩ 14 类 Micrometer 指标按 10 组维度组织，不依赖 `harness/eval`。向 `design.md §5.4/§9.2/§9.4/§12/§13.1/§13.3`、`module-dependency-dag-plan.md`、`dag.md §二`、`imagegen.md §二` 提出连带修订建议。
