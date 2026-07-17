# module/task —— 任务调度与异步执行外壳（Wave 4 主循环 + 编排模块）

> 本文是 PixFlow 完整重写阶段 `module/task` 模块的设计文档，对应 `design.md` 第九章「DAG 确定性执行引擎」（§9.2 异步任务分发、§9.3 并发保障、§9.4 断点恢复与失败隔离、§9.5 分组聚合）、第十三章数据模型、第十四章异步执行时序，以及 `module-dependency-dag-plan.md` 的 **Wave 4 主循环 + 编排模块**。
> 范围：消费 RocketMQ 任务消息、按 `[图片×支路]/[组×支路]`（确定性路径）与 `[1 源图→1 重绘]`（生成式路径）两种单元粒度 fan-out、`process_task` / `process_result` 落库、Redis 进度/取消/锁协调、下载分发、两条产图路径的共用执行壳、断点恢复扫描。
> 配套阅读：`module/dag.md`（dag 是无状态确定性单元执行器、被 task 逐单元调用）、`module/imagegen.md`（生图执行器 SPI、被 task 同款外壳消费）、`harness/state.md`（运行态读模型聚合、恢复协调权威、双源对账）、`infra/mq.md`（RocketMQ 封装、topic/tag/consumer group、DLQ/重试）、`infra/cache.md`（Redisson 锁/信号量/进度计数）、`infra/storage.md`（MinIO 桶与路径约定）、`base/asset-references.md`（素材引用统一契约）、`harness/hooks.md`（TaskCreated/TaskCompleted 事件订阅方）。本文不涉及 MVP 既有实现，从生产级需求重新推导。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、核心边界：task 是 dag/imagegen 的异步外壳](#二核心边界task-是-dagimagegen-的异步外壳)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、TaskService：对外暴露的唯一入口](#四taskservice对外暴露的唯一入口)
- [五、CreateTaskService：从稳定业务身份到 process_task 落库](#五createtaskservice从稳定业务身份到-process_task-落库)
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

`module/task` 在依赖 DAG 中处于 **Wave 4**，依赖 `infra/mq`（任务消息 publish/consume）、`infra/cache`（Redisson 锁/进度/信号量/取消）、`infra/storage`（结果落 MinIO）、`module/dag`（`BranchExpander` + `UnitExecutor`）、`harness/state`（checkpoint 读侧聚合 + 取消读协议 + 运行态引用）、`common`（错误归一化/脱敏）。被 `module/conversation`（确认 REST 端点调 `TaskCommandService.createAndEnqueue`）、`harness/hooks`（订阅 `TaskCreatedEvent` / `TaskCompletedEvent`）、`agent`（执行期查询）和应用层全局 Activity 投影消费；前端不直接订阅 task 专属通道。

模块专属设计原则：

1. **task 是异步外壳，不做像素语义**。`module/dag` 是无状态确定性单元执行器（dag.md §二），`module/imagegen` 是无状态单图重绘执行器（imagegen.md §二），task 是它们的**有状态异步执行壳**——把任务消息拆成工作单元、调线程池 fan-out、落 `process_result`、管进度/锁/取消/恢复。task 内**不出现**任何像素工具逻辑、任何 DAG 节点派发、任何生图模型调用。
2. **MySQL 是事实源，Redis 只做同次运行内的轻量优化**。持久断点以 `process_result` 为唯一权威（design.md §5.4/§9.4）；Redis 进度/取消/引用均为可丢可重建，**原始字节一律不进 Redis**。任何恢复判定、终态判定一律以 MySQL 为准。
3. **两条产图路径共用同一套异步壳**。确定性的 `[图片×支路]/[组×支路]` 与生成式的 `[1 源图→1 重绘]` 都跑在 task 的 worker 上，由 `WorkerRouter` 按 `process_task.task_type` 路由到 `ProcessWorker` / `ImageGenWorker`；调度壳（线程池/进度/锁/取消/恢复/下载）100% 复用。imagegen 不自建异步（imagegen.md §八），dag 也不自建异步（dag.md §二）。
4. **状态机迁移是显式纯函数**。`process_task.status` 与 `process_result.status` 的所有合法迁移集中在一个 `TaskStateMachine` / `ResultStateMachine` 类里（纯函数，**不在数据库触发器里**、**不在 worker 的 if-else 里**），可被属性测试覆盖。
5. **失败隔离是 task 的核心契约**。单个 Work Unit 最终失败 → 以当前 epoch 持久化结构化 failure + 兼容 `error_msg`，不发布半成品；兄弟单元继续。只有全部成功才 COMPLETED，混合结果是 PARTIAL。
6. **取消是协作式，绝不打断运行中的工作单元**。task worker 在工作单元**边界**检查 `cancel:task:*` 标志；像素工具调用、模型调用、MinIO I/O **不在中途打断**（避免数据损坏）。已开始的单元跑完自然结束。
7. **生产级、不简化**。Redisson RLock、MySQL epoch/heartbeat、boundary-owned retries、DLQ、稳定业务幂等键、presigned URL、metrics 全套齐备。task 没有正常工作单元 retry loop；崩溃恢复与用户创建 derived retry task 是两个显式入口。
8. **API 与 internal 物理隔离**。`api/` 包对其他模块可见，`internal/` 包封装类全部包内可见。agent / conversation 只 import `api`，**看不到 worker 内部**——这是防止业务模块乱调 task 内部方法的关键。
9. **执行记录与素材资产生命周期分离**。成功的图片处理或 IMAGEGEN 结果经发布端口注册为新的 `GENERATED` Asset Image，并获得新的 `imageId`；删除已成功的任务/Activity 记录不得删除该素材。失败记录可由用户立即清理，并有 24 小时兜底清理；取消任务则立即清理该任务的记录、候选结果与临时对象，已发布的独立素材不受影响。

---

## 二、核心边界：task 是 dag/imagegen 的异步外壳

`design.md §12` 把「任务调度」列在 `module/task`，但与 `module/dag`「执行引擎」字面上重叠。本模块据 `design.md` 设计原则一/二（两层循环分离、确定性底座不被污染）与 dag.md §二、imagegen.md §二的边界，把这条线**明确钉死**：

| 维度 | `module/dag` | `module/imagegen` | `module/task`（本模块） |
|---|---|---|---|
| 性质 | 无状态、确定性库 | 无状态、能力模块 | **有状态、并发、异步**的编排外壳 |
| 拥有 | 像素工具白名单、`DagValidator`、`BranchExpander`、确定性单元执行器 | `ImageGenExecutor` SPI、`submit_imagegen_plan` handler | 线程池、Redisson 锁/进度/取消/信号量、MQ 消费与发布、`@Scheduled` 恢复扫描、`process_task`/`process_result` 落库、presigned URL 生成 |
| 调用 task 的入口 | 无 | 无 | `WorkerRouter` → `ProcessWorker` 调 `UnitExecutor.execute`；`ImageGenWorker` 调 `ImageGenExecutor.redraw` |
| 被 task 调用 | `BranchExpander.expand`、`UnitExecutor.execute` | `ImageGenExecutor.redraw`、`GenerativeUnitSpec` 装配 | —— |
| 失败隔离 | 单元执行器返回含 `UnitFailure` 的 FAILED（不打断批次） | executor 抛最终归一化异常，由 task 转成同一 failure shape | fenced 落 FAILED + 结构化 final failure，继续兄弟；全成功→COMPLETED，混合→PARTIAL，全失败→FAILED |
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
│   │   ├── CancelTaskCommand.java
│   │   └── RetryFailedTaskCommand.java
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
│   ├── retry/
│   │   └── RetryFailedTaskService.java  # 终态来源 → derived task
│   ├── cancel/
│   │   └── CancellationService.java     # 取消标志设置 + 单元间检查
│   ├── worker/
│   │   ├── TaskWorker.java              # 入口：RocketMQ consumer 接管
│   │   ├── WorkerRouter.java            # 按 task_type 路由
│   │   ├── ProcessWorker.java           # 调 dag.UnitExecutor
│   │   ├── ImageGenWorker.java          # 调 imagegen.ImageGenExecutor
│   │   └── UnitExecutionContext.java    # RLock owner/run_epoch/cancel 上下文
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
│   │   └── HeartbeatWriter.java         # worker fenced 更新 MySQL heartbeat_at
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
│   │   └── TaskMessageConsumer.java     # ManagedMessageContainer / ConsumerBinding
│   ├── cache/
│   │   ├── TaskCacheKeys.java           # key 命名空间集中
│   │   ├── TaskProgressCounter.java     # AtomicLong incr / get
│   │   ├── TaskCancelFlag.java          # cancel:task:{id} 读写
│   │   ├── TaskIdempotencyStore.java    # task:idempotency:{key}
│   │   └── TaskLockKeys.java            # lock:task:{id}:execution
│   └── lock/
│       └── TaskLockManager.java         # Redisson 看门狗 + 释放
└── bootstrap/
    └── TaskAutoConfiguration.java      # @Configuration 装配
```

依赖方向：

```
module/task ──► infra/mq        （任务消息 publish/consume、ConsumerBinding、DLQ/重试策略）
module/task ──► infra/cache     （Redisson RLock/进度/取消/idempotency；不存 heartbeat）
module/task ──► infra/storage   （结果落 MinIO、presigned URL 生成）
module/task ──► module/dag      （DagCompiler / BranchExpander / UnitExecutor / 中立输入）
module/task ──► module/imagegen （ImageGenExecutor.redraw / GenerativeUnitSpec；Wave 4 真正消费）
module/task ──► harness/state   （CheckpointReadPort → 可跳过集合 / 取消读协议）
module/task ──► common          （PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer）

module/conversation ──► module/task（确认 REST 端点调 TaskCommandService.createAndEnqueue）
harness/hooks       ──► module/task（订阅 TaskCreatedEvent / TaskCompletedEvent）
agent               ──► module/task（执行期查询/订阅，Wave 5）
harness/state       ──► module/task（实现 CheckpointReadPort，倒置接入；state.md §六）
```

**反向约束**（ArchUnit 守护）：task 只依赖 dag 的 canonical/compile/expand/exec 公共边界，不依赖 validate/propose 内部；不依赖 `module/file`、`harness/loop`、`agent`、`harness/tools`，不出现 Spring AI 注解、不调用 LLM。

---

## 四、TaskService：对外暴露的唯一入口

task 模块对外只暴露两个接口，所有调用方（包括 conversation REST 端点、agent 内部、harness/hooks 订阅方、前端）只看到这两个接口，看不到 worker / scheduler / recovery 等内部细节。

### 4.1 TaskCommandService

```java
public interface TaskCommandService {
    /**
     * 创建并入队一个任务。
     * 由 Proposal 确认 REST 端点调用；permission、DAG 与 group preflight 均已在 Proposal 展示前校验通过。
     * @param cmd 包含 taskType、packageId、conversationId、idempotencyIdentity、payload（dag.json 或 imagegen plan）
     * @return taskId
     * @throws TaskIdentityConflictException 同一 identity 已绑定不同 canonical payload 时
     */
    TaskId createAndEnqueue(CreateTaskCommand cmd);

    /**
     * 取消任务。幂等：重复调用不报错。
     * 已完成/已失败任务返回 false；进行中任务返回 true 并设置 cancel 标志。
     */
    boolean cancel(CancelTaskCommand cmd);

    /**
     * 从终态 PARTIAL/FAILED 来源任务的 FAILED work units 创建派生任务。
     * 来源任务及其结果不可变；同一来源任务重复请求返回同一 direct derived taskId。
     */
    TaskId retryFailed(RetryFailedTaskCommand cmd);
}
```

### 4.2 TaskQueryService

```java
public interface TaskQueryService {
    /** 状态查询；返回统一快照融合 MySQL/Redis 数据，供应用层 Activity 聚合器读取。 */
    TaskStatusView getStatus(TaskId taskId);

    /** 列出某会话下所有任务，供内部查询与全局 Activity 快照投影。 */
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

## 五、CreateTaskService：从稳定业务身份到 process_task 落库

`createAndEnqueue` 是确认 REST 端点调用的入口，承担「已校验提案 → 持久化 → 入队」的窄职责。**它不做 DAG 校验、不做 group preflight、不做权限校验**——这些都已经在 conversation 的确认 REST 边界完成。

### 5.1 序列化时序

```
conversation.confirm REST 端点（Proposal 展示前已完成 permission、DagValidator、group preflight 校验）
  → TaskCommandService.createAndEnqueue(CreateTaskCommand{
        taskType, packageId, conversationId, idempotencyIdentity, payload  // payload: dag_json or imagegen plan
    })
  → IdempotencyGuard.checkAndReserve(idempotencyIdentity)       // 命中已绑定任务 → 直接返回原 taskId
  → 创建 process_task(status=PENDING, taskType=..., dagJson=payload) + 落库
  → 状态机迁移 PENDING → QUEUED
  → TaskMessagePublisher.publish(TaskMessage{taskId, taskType, ...})
  → 状态机迁移 QUEUED → PENDING（MQ 发布成功后保持 QUEUED；如发布失败 → 状态机迁移 PENDING → PENDING + 触发补偿或直接 FAILED）
  → publish TaskCreatedEvent（异步，hooks 订阅方各自处理）
  → return taskId
```

### 5.2 Idempotency 语义

任务创建只接受服务端已经掌握的稳定业务身份，不接受前端生成的随机 `Idempotency-Key`：

- Proposal 确认使用 `proposalId`；同一 Proposal 无论连点还是网络重放，都返回同一 `taskId`。
- `retry-failed` 使用 `retry-failed:{sourceTaskId}`；同一来源任务始终只对应一个 direct derived task。
- key 写入 Redis `task:idempotency:{key}` → taskId 映射，TTL 24h；Redis 仅作加速，不能决定业务唯一性。
- 同 key 重复请求无论原任务处于运行态还是终态，都返回已经绑定的 `taskId`，不创建第二个任务。
- MySQL `process_task.idempotency_identity` 加 `UNIQUE` 约束，作为最终事实源；不保留同时接受随机 HTTP key 的兼容字段。

### 5.3 payload 落库

- **确定性路径**：`process_task.dag_json` = 确认时二次校验并规范化后的 Canonical DAG；同时保存 `schema_version` 与由 Typed Execution Plan + 成员快照派生的 `unit_selection_json`。不存 Agent 原始 `DagDocument`，也不把 Java typed plan 序列化为第二事实源。
- **生成式路径**：`process_task.dag_json` 位置存「单个 `sourceReferenceKey` + 已解析 `sourceImageId` + prompt + params」载荷（`type=IMAGEGEN` 区分），与 dag 共用 `dag_json` JSON 列、靠 `task_type` 字段分流；具体载荷形状由 `module/imagegen` 的 `ImagegenPlan` 规范定义（imagegen.md §五）。一个 IMAGEGEN Proposal 只创建一个 task。
- **恢复时确定性重建**：worker 从 `process_task.dag_json + schema_version` 调 `DagCompiler` 重建 Typed Execution Plan，并校验展开出的 Work Unit Identity 与 `unit_selection_json` 一致；不调用 submit handler，不接收新提案参数。

### 5.4 Derived Retry Task

`retryFailed` 只接受 `PARTIAL/FAILED` 来源任务，并在一个 MySQL 事务中：以 `retry-failed:{sourceTaskId}` 查找或锁定唯一 direct child；若已存在则直接返回；否则锁定来源任务只读快照，查询其 `process_result.status=FAILED` 的 `unit_key`；无失败项则拒绝；创建新 `process_task(retry_of_task_id=source.id, dag_json/schema_version/package_id` 复制，`unit_selection_json` 固定为失败 key 集合)；为新任务预建对应 PENDING 结果行；提交后发布新 taskId。来源任务、来源结果和对象 key 均不修改，派生任务拥有新的 `run_epoch` 与结果路径。

### 5.5 状态机迁移（PENDING → QUEUED）

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

`ProcessWorker` 与 `ImageGenWorker` 都实现相同的 `TaskWorker` 接口（`handle(TaskMessage)`），由 `WorkerRouter` 在 RocketMQ consumer 接管入口处路由。

### 6.2 ProcessWorker（确定性路径）

两类 worker 都由同一个 `TaskWorker` ownership wrapper 调用：`try RLock → claim run_epoch → 启动 MySQL heartbeat → route(msg, ExecutionRun)`；wrapper 在 finally 中停止 heartbeat，并且只在 `isHeldByCurrentThread()` 时 unlock。下面的 `run` 已携带本次 epoch，所有 repository 写必须使用它。

```java
public class ProcessWorker {
    public void handle(TaskMessage msg, ExecutionRun run) {
        // 1. 加载 process_task + 校验 schema_version（与 dag schema 大版本对齐）
        ProcessTask task = taskMapper.findById(msg.taskId());
        CanonicalDag dag = dagJsonReader.readCanonical(task.getDagJson(), task.getSchemaVersion());
        TypedExecutionPlan plan = dagCompiler.compile(dag);          // 确定性重建

        // 2. 加载 asset_image 列表（packageId 下全部），装配为 ImageDescriptor
        List<ImageDescriptor> images = imageReader.findByPackage(task.getPackageId())
            .stream().map(ImageDescriptor::from).toList();

        // 3. 调 dag.BranchExpander.expand(dag, images) → List<ExecutableBranch>
        List<ExecutableBranch> branches = branchExpander.expand(plan, images);

        // 4. 装配 WorkUnit（每条支路 + 每张图/每个组 = 一个 WorkUnit）
        List<WorkUnit> units = assembleWorkUnits(branches, images, task.getId());

        // 5. 与持久化 unit_selection_json 对齐，只跳过 MySQL SUCCESS checkpoint
        List<WorkUnit> selected = unitSelection.requireExactMatch(task, units);
        Set<UnitKey> doneSet = state.recovery().resolveSkippable(task.getId()).succeeded();

        // 6. 把未跳过单元提交到 WorkUnitScheduler
        selected.stream()
             .filter(u -> !doneSet.contains(u.unitKey()))
             .forEach(scheduler::submit);

        // 7. 等所有单元完成（CompletableFuture.allOf）→ TerminalStateJudge 写终态
        scheduler.awaitAll(selected).thenRun(() -> terminalJudge.judge(task.getId(), run.epoch()));
    }
}
```

### 6.3 ImageGenWorker（生成式路径）

```java
public class ImageGenWorker {
    public void handle(TaskMessage msg, ExecutionRun run) {
        ProcessTask task = taskMapper.findById(msg.taskId());
        ImagegenPlan plan = imagegenPayloadReader.read(task.getDagJson());   // 反序列化生图提案

        // 一个 IMAGEGEN Proposal 只有一个「1 源图 → 1 重绘」单元，不需要 BranchExpander
        WorkUnit unit = WorkUnit.generative(
            task.getId(), plan.sourceReferenceKey(), plan.sourceImageId(), plan, ...);
        List<WorkUnit> units = List.of(unit);

        // 对齐 unit_selection_json，只跳过 SUCCESS checkpoint
        List<WorkUnit> selected = unitSelection.requireExactMatch(task, units);
        Set<UnitKey> doneSet = state.recovery().resolveSkippable(task.getId()).succeeded();

        selected.stream()
             .filter(u -> !doneSet.contains(u.unitKey()))
             .forEach(scheduler::submit);

        scheduler.awaitAll(selected).thenRun(() -> terminalJudge.judge(task.getId(), run.epoch()));
    }
}
```

### 6.4 关键差异点

| 维度 | ProcessWorker | ImageGenWorker |
|---|---|---|
| 工作单元数 | = ∑(支路 × 图) 或 ∑(组支路 × 组)（BranchExpander 展开结果） | 恒为 1 |
| 单元载荷 | `UnitInput`（含图片字节来源 + 可选 CopyContext） | `GenerativeUnitSpec`（含 prompt + params + 源图引用） |
| 执行器调用 | `unitExecutor.execute(branch, input)` → `UnitOutcome` | `imageGenExecutor.redraw(spec)` → `GeneratedArtifact` |
| 跳过集计算 | `resolveSkippable(taskId)` 只返回 SUCCESS unit_key | 同左 |
| 进度计数单位 | 每条支路（含 group）一条记录 | 当前单张源图的一次重绘 |
| 落 `process_result` 字段 | TMP 候选 key、`branch_id`、`group_key`、`source_image_id`；发布后补 `generated_image_id`/稳定资产 key | TMP 候选 key、`source_image_id`；发布后补新的 `generated_image_id`/GENERATED 稳定 key 与 lineage |

**共用的部分**（所有 worker 共享）：

- Redisson `RLock lock:task:{id}:execution`（watchdog 自动续期，Redis 故障 fail-closed）
- MySQL `process_task.run_epoch` claim + `heartbeat_at`（所有更新带 `WHERE status=RUNNING AND run_epoch=?`）
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
    UnitKey unitKey,                  // (kind, memberId, branchId)，任务内 canonical identity
    WorkUnitPayload payload           // 内部载荷（图片引用 / 文案 / 提示词）
) {}

public record WorkUnitPayload(
    ObjectLocation sourceLocation,    // 源图 MinIO 位置（确定性/生成式共用）
    CopyContext copyContext,          // 可空：仅 generate_copy 支路需要
    GenerativeUnitSpec generativeSpec,// 可空：仅生成式单元
    BranchSpec branchSpec             // 可空：仅确定性支路
) {}
```

`WorkUnit` **不是**对外 API（`api/` 包不可见），是 worker 内部的线程池调度单位。`UnitKeyCodec` 对 `kind/memberId/branchId` 生成稳定 `unit_key` 字符串；`process_result` 使用 `UNIQUE(task_id, unit_key)`，不再依赖含 nullable `image_id/group_key` 的组合唯一索引。

### 7.2 WorkUnitScheduler

- **两个线程池**，按 `taskType` 隔离：
  - `process-pool`：core=8, max=16, queue=1000（确定性处理，IO + 像素计算为主）
  - `imagegen-pool`：core=4, max=8, queue=200（生图，外部模型调用为主，**额外叠加** `infra/ai.GlobalConcurrencyLimiter` 封顶）
- **背压**：队列满 → 拒绝策略 = `CallerRunsPolicy`（提交方线程跑，防任务丢失但防止无界堆积），同时 `Metrics` 指标 `pixflow.task.scheduler.rejected` 自增。
- **像素内存准入**：process-pool 只决定线程/队列；每个图片 pipeline 仍须由 `infra/image` 在 probe 后取得 JVM 全局 Pixel Budget permit。所有 task 共享同一预算，CallerRunsPolicy 也不能绕过。
- **取消检查点**：每个 `WorkUnit` 提交后、线程池取出前 / 单元结束后，由 `WorkUnitSubmitter` 检查 `TaskCancelFlag.isCancelled(taskId)`；已取消则不入队 / 不开始下一个。
- **超时**：单工作单元无硬超时（像素处理可能很慢），由 `harness/state` 的"心跳超时 → @Scheduled 重扫"代替——单工作单元挂死不释放线程由 `CompletableFuture.get(超时)` 在 terminal judge 阶段兜底。
- **异常包装**：线程池抛出的任何异常都被 `WorkUnitSubmitter` 捕获 → 转 `ResultStatus.FAILED` + 归一化 `error_msg`，**不让异常逃逸到 worker 主流程**。

### 7.3 Redisson 锁与 execution epoch 的边界

- **互斥只靠 Redisson `RLock`**：worker 以 watchdog 模式持有 `lock:task:{id}:execution` 覆盖整个 execution run；未取得锁的重复消息直接结束接管。不得设置固定业务 lease，也不得由恢复扫描器 `forceUnlock` 另一个 owner。
- **fencing 只靠 MySQL `run_epoch`**：取得 RLock 后，worker 原子 claim `QUEUED/RUNNING → RUNNING, run_epoch=run_epoch+1, worker_id=?, heartbeat_at=?` 并取得 epoch。epoch 不是锁；它只让旧 worker 的 result/heartbeat/terminal UPDATE 返回 0。
- **双重写前检查**：每次发布结果前先确认 `RLock.isHeldByCurrentThread()`，数据库写再带 `run_epoch` 条件。锁刚丢失但尚无新 worker 时由第一层阻止；新 worker claim 后由 epoch 阻止旧写。
- **心跳在 MySQL**：周期更新 `heartbeat_at WHERE id=? AND status=RUNNING AND run_epoch=?`；更新 0 行立即停止调度并放弃终态提交。Redis 不保存 heartbeat 事实。
- **不做工作单元级锁**：同一 task 内的 Work Unit 由单一 task owner 并发调度；数据库 `unit_key` 唯一与 epoch fencing 处理重复写。

---

## 八、状态机：process_task / process_result 的迁移表

`process_task.status` 与 `process_result.status` 的所有合法迁移集中在 `TaskStateMachine` / `ResultStateMachine` 纯函数里（不依赖 Spring 上下文、不查数据库），可被属性测试覆盖。**所有写库代码都必须经过 `stateMachine.transit(status, newStatus)` 校验**，否则拒绝（`@Aspect` 或显式调用）。

### 8.1 process_task.status 迁移表

```
PENDING(0)    → QUEUED(1)            # createAndEnqueue 入队成功
PENDING(0)    → FAILED(4)            # 入队失败（如 MQ 不可用）
QUEUED(1)     → RUNNING(2)           # worker 拉起（RocketMQ consumer 入口）
RUNNING(2)    → COMPLETED(3)         # 所有所选 work units 均 SUCCESS
RUNNING(2)    → FAILED(4)            # 无 SUCCESS 且至少一条 FAILED
RUNNING(2)    → CANCELLED(5)         # 用户取消
RUNNING(2)    → PARTIAL(6)           # SUCCESS 与 FAILED/SKIPPED 混合（含或不含取消）
QUEUED(1)     → CANCELLED(5)         # 用户在入队后、消费前取消（罕见但可能）
FAILED(4)     → （终态）              # 显式 retry 创建 derived task，不原地复活
COMPLETED(3)  → （终态）              # 不允许从 COMPLETED 复活
CANCELLED(5)  → （终态）
PARTIAL(6)    → （终态）              # PARTIAL 是"部分完成"语义，不允许再迁移
```

### 8.2 process_result.status 迁移表

```
PENDING(0)    → RUNNING(1)           # 当前 run_epoch 开始执行
PENDING(0)    → SKIPPED(4)           # 取消后未开始
RUNNING(1)    → SUCCESS(2)           # 单元成功，形成不可覆盖 checkpoint
RUNNING(1)    → FAILED(3)            # 当前 epoch 最终失败（诊断事实，非 checkpoint）
PENDING/RUNNING/FAILED/SKIPPED → RUNNING  # 仅父 task 仍 RUNNING 且 incoming run_epoch 更高的崩溃恢复
SUCCESS(2)    → （永久不可变）
```

### 8.3 关键约束

- `RUNNING` 状态的写入与读取由 `TerminalStateJudge` 协调：所有工作单元完成后才判定终态（避免"还在跑就标 FAILED"的并发漏洞）。
- 终态判定时必须等所有 `RUNNING` 的工作单元 `CompletableFuture` 完成（`allOf().join()`），避免与 `WorkUnitSubmitter` 的取消检查点产生竞态。
- 任务状态机严格单调，所有终态不可变。结果状态只有一个受约束例外：父 task 仍为 RUNNING 且 worker 持有更高 epoch 时，可把任意非 SUCCESS 行重置为 RUNNING；SUCCESS 永远不可覆盖，父 task 终态后所有结果行冻结。
- `process_result` 写入条件统一包含：父 task `status=RUNNING AND run_epoch=:epoch`；现有结果不是 SUCCESS；incoming epoch 不小于 row epoch。任一条件不满足返回 stale/no-op，绝不通过普通 upsert 绕过。

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
- `ProgressEvent` 通过 `harness/state.ProgressEventPublisher` 发布；应用层把它投影为全局 Activity，而不是暴露 task 专属前端通道。
- 前端只订阅 STOMP 用户目的地 `/user/queue/activity`，并在重连或冷启动时通过 `GET /api/activities` 获取全量快照。
- IMAGEGEN 的用户可见状态只有“生图中 / 成功 / 失败”；内部 done/total、恢复 epoch 与 provider 重试细节不直接展示。

### 9.3 done / failed / total 的语义

- `total` = 工作单元总数（`workUnits.size()`，worker 拉起分支展开后确定）
- `done` = `SUCCESS + FAILED + SKIPPED`（终态即不再变）
- `failed` = `FAILED`（仅失败数，不含成功 / 跳过）
- 终态时：
  - `SUCCESS == total` → `COMPLETED`
  - `SUCCESS == 0` 且 `FAILED > 0` → `FAILED`
  - `SUCCESS > 0` 且存在 `FAILED/SKIPPED` → `PARTIAL`
  - `SUCCESS == 0` 且全部 `SKIPPED` → `CANCELLED`

---

## 十、FailureIsolator：失败隔离与终态判定

### 10.1 单工作单元失败

```
WorkUnit.run() throws Exception (or returns UnitOutcome.FAILED)
  → FailureIsolator.handle(unit, exception)
       1. 归一化异常 → DagErrorCode / ImagegenErrorCode / TaskErrorCode（透传已有 code，新增"task 自己抛"的）
       2. Sanitizer 脱敏 → error_msg（≤1000 字）
       3. 写 process_result(status=FAILED, run_epoch, failure_code/category/recovery,
                             failed_node_id/failed_tool/attempt_count/failure_details_json,
                             error_msg)
       4. 进度 incr（done++, failed++）
       5. publish ProgressEvent
       6. 写 process_result 后不抛任何异常 → 继续兄弟单元
```

### 10.2 任务级失败

`TerminalStateJudge` 在所有 `CompletableFuture` 完成后触发：

- `doneSet` = 所有工作单元的最终 status
- 若全部 `SUCCESS` → `RUNNING → COMPLETED`
- 若无 SUCCESS 且至少一条 `FAILED` → `RUNNING → FAILED`
- 若有 SUCCESS 且存在 `FAILED/SKIPPED` → `RUNNING → PARTIAL`
- 若全部 `SKIPPED` → `RUNNING → CANCELLED`
- 终态写入后 `publish TaskCompletedEvent`
- 上述聚合与 UPDATE 必须在同一 epoch 下完成：`UPDATE process_task ... WHERE id=? AND status=RUNNING AND run_epoch=?`；0 行表示 stale worker，不发布事件。

### 10.3 系统级失败（worker 崩溃）

- **不进入** FailureIsolator 的判定路径——worker 崩溃时无 `TaskCompletedEvent` 发布。
- 由 `RecoveryService`（§十一）的 `@Scheduled` 扫描发现心跳缺失 → 重新入队 + worker 重启后重跑未完成单元。

### 10.4 错误码

`TaskErrorCode implements common.ErrorCode`（码自治，并入 `common` 启动期聚合测试）：

| code | category | recovery | 场景 |
|---|---|---|---|
| `TASK_NOT_FOUND` | NOT_FOUND | TERMINATE | taskId 不存在 |
| `TASK_IDEMPOTENCY_IDENTITY_CONFLICT` | CONFLICT | TERMINATE | 同一业务身份被提交了不同规范载荷（服务端接线错误） |
| `TASK_INVALID_STATE_TRANSITION` | VALIDATION | TERMINATE | 状态机迁移非法（如 COMPLETED → RUNNING） |
| `TASK_DAG_PAYLOAD_INVALID` | VALIDATION | TERMINATE | 反序列化 `process_task.dag_json` 失败（恢复时 payload 损坏） |
| `TASK_IMAGEGEN_PAYLOAD_INVALID` | VALIDATION | TERMINATE | 反序列化生图 payload 失败 |
| `TASK_RESULT_WRITE_FAILED` | STORAGE | RETRY | 写 `process_result` 失败（数据库瞬时不可用） |
| `TASK_PROGRESS_PUBLISH_FAILED` | DEPENDENCY | SKIP | 进度事件发布失败（不影响主流程，仅指标告警） |
| `TASK_CANCEL_NOT_PERMITTED` | BUSINESS_RULE | TERMINATE | 终态任务尝试取消（已 SUCCESS/FAILED/CANCELLED） |
| `TASK_DOWNLOAD_RESULT_NOT_READY` | BUSINESS_RULE | SKIP | 结果未就绪时请求下载（前端在 done=0 时请求） |
| `TASK_DOWNLOAD_BUNDLE_TOO_LARGE` | VALIDATION | TERMINATE | 打包 zip 超过 `max-bundle-bytes`（防 OOM） |
| `TASK_RECOVERY_LOCK_CONTENTION` | DEPENDENCY | SKIP | 恢复扫描时锁被占用（下一轮重试） |
| `TASK_STALE_EXECUTION_EPOCH` | CONFLICT | TERMINATE | worker 已失去 RLock 或 result/terminal 写入的 run_epoch 过期 |
| `TASK_NO_FAILED_UNITS` | BUSINESS_RULE | TERMINATE | 来源 PARTIAL/FAILED 任务没有 FAILED work unit 可重试 |
| `TASK_RETRY_SOURCE_NOT_TERMINAL` | BUSINESS_RULE | TERMINATE | 对非 PARTIAL/FAILED 来源调用 retry-failed |

---

## 十一、断点恢复：RecoveryService + 单元级幂等

### 11.1 恢复粒度（design.md §9.4）

**task 级 + Work Unit 级**两级恢复：

- **task 级**：`@Scheduled` 只扫 `status=RUNNING AND heartbeat_at < now-staleAfter`，重新入队一条任务消息，不改状态、不碰锁。新 consumer 必须先拿 Redisson RLock，成功后才递增 MySQL `run_epoch`。
- **Work Unit 级**：worker 读取 `state.RecoveryCoordinator.resolveSkippable(taskId)`，只跳过 `process_result.status=SUCCESS` 的 `unit_key`；PENDING/RUNNING/FAILED/SKIPPED 都在新 epoch 下整 Work Unit 重算。

**禁止节点级续传**（与 design.md §9.4 一致）：支路本就是独立单元，节点级续传处理参数/中间态/分支共享复杂度爆炸。

### 11.2 单元幂等保证

- **Deterministic UnitKey**：canonical identity = `(kind, memberId, branchId)`，由 `UnitKeyCodec` 生成稳定字符串并在任务创建时写进 `unit_selection_json`。派生任务直接复用来源 FAILED unit_key，不重新选择“当前看起来失败”的集合。
- **唯一索引**：只使用 `UNIQUE(task_id, unit_key)`。`image_id/group_key` 仍为查询投影，但 nullable 列不承担唯一性，避免 MySQL 对 NULL 的组合唯一语义留下重复组结果。
- **确定性结果对象**：`results/{taskId}/units/{sha256(unitKey)}/epochs/{runEpoch}/output.{ext}`。先写对象，再以同 epoch fenced 提交 SUCCESS 行；DB 提交失败/worker stale 时对象不发布为结果，由生命周期清理。derived task 的新 taskId 天然隔离对象。
- **写入策略**：专用 repository SQL 在同一事务中锁定父 task epoch 并写 result；禁止无条件 `ON DUPLICATE KEY UPDATE`。SUCCESS 冲突永远保留原行；非 SUCCESS 只在父 task RUNNING + incoming epoch 更高时重置/覆盖。

### 11.3 RecoveryService 实现

```java
@Scheduled(cron = "${pixflow.task.recovery.cron:0 */1 * * * *}")
public void recover() {
    // MySQL heartbeat 是 stale discovery 的唯一依据；扫描器不读取/释放 RLock
    List<ProcessTask> stale = taskMapper.findRunningHeartbeatBefore(
        Instant.now().minus(taskProps.getRecoveryStaleAfter())
    );
    for (ProcessTask task : stale) {
        taskMessagePublisher.publish(TaskMessage.of(task));
        metrics.record("requeued", task.getId());
    }
}
```

### 11.4 关键约束

- 恢复扫描是**幂等**的：连续触发不会产生重复任务消息（`TaskMessagePublisher.publish` 按 `taskId` 去重）。
- 恢复不修改 `process_task.status/run_epoch`：只有实际取得 RLock 的 worker 才能 claim 新 epoch；扫描器绝不 `forceUnlock`，旧 owner 尚存时新消息只产生锁竞争并安全退出。
- Redis 不可用时新 worker 无法取得 RLock，必须 fail-closed 并让 MQ 后续重投；不得仅凭 MySQL epoch 绕过互斥。
- 扫描频率默认 1 分钟（`pixflow.task.recovery.cron: 0 */1 * * * *`），生产可调为 5 分钟。
- 恢复扫描器跑在 `@Scheduled` 线程，**不抢** worker 线程池。

---

## 十二、取消：协作式协议 + 工作单元边界检查

### 12.1 取消协议

```
用户点取消 → REST cancel → TaskCommandService.cancel(cmd)
  → 1. 查 process_task.status
       - 终态（COMPLETED/FAILED/CANCELLED/PARTIAL）→ return false（幂等）
       - 非终态 → 继续
  → 2. 设置 Redis cancel:task:{id} = 1（立即生效，让正在跑的 worker 在单元边界看到）
  → 3. 状态机迁移（QUEUED → CANCELLED / RUNNING → CANCELLED 或 PARTIAL）
       - QUEUED 状态：直接 CANCELLED（worker 拉起后看到 cancel 标志会立即退出，见 §12.3）
       - RUNNING 状态：只设置 cancel_requested/cancel flag，不预写 PARTIAL；当前 epoch 的 TerminalStateJudge 按真实结果聚合终态
```

### 12.2 取消标志的读侧

- task worker 在以下边界检查 `cancel:task:{id}`：
  1. RocketMQ consumer 入口（拉起任务时）
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
- 最终 task 终态仍用统一规则：有 SUCCESS 且存在 FAILED/SKIPPED → `PARTIAL`；无 SUCCESS 且有 FAILED → `FAILED`；全部 SKIPPED → `CANCELLED`。取消请求只决定尚未开始的单元变 SKIPPED，不覆盖真实 FAILED，也不单独改写聚合规则。

### 12.5 结果发布与清理生命周期

- 每个成功图片结果在 fenced SUCCESS 事务完成后，经应用层 `GeneratedAssetPublicationPort` 注册为独立 `GENERATED` Asset Image。File 模块分配新的 `imageId`，记录 `sourceTaskId/sourceResultId/sourceImageId` lineage，并返回 canonical IMAGE `referenceKey`；绝不复用源图 ID。
- `process_result.generated_image_id` 只保存发布后的资产身份。对象先写临时/epoch 路径，只有 publication 成功才进入 Outputs 与 mention 候选；stale worker、失败或未完成对象由生命周期任务清理。
- COMPLETED/PARTIAL 执行记录在保留期间不可变，但用户可从 Activity 清除记录；清除记录不删除已发布的 generated asset。
- FAILED 先由能力拥有方执行有界静默重试。最终仍失败时保留记录和诊断信息，用户点击删除立即清理；无人处理时 24 小时后自动清理。失败候选与临时对象随记录清理，不发布为素材。
- CANCELLED 在终态确定后立即删除该任务的 Activity 记录、未发布候选、临时对象和可安全删除的执行明细。取消不删除此前已成功发布为独立素材的资产。

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

- 下载请求必须通过已认证且仍符合配置管理员资格的会话。下载单个已发布产物时使用 canonical IMAGE `referenceKey`；任务打包下载使用 `taskId` 并在服务端校验其会话归属。下载授权不依赖创建任务时的幂等身份。
- 未来若加租户隔离：presigned URL 加 tenant 字段，MinIO policy 强制 bucket 隔离。

---

## 十四、错误与降级

### 14.1 业务错误（task 模块自身）

见 §10.4 `TaskErrorCode` 表。

### 14.2 基础设施错误

| 来源 | 错误归一化 | task 行为 |
|---|---|---|
| MySQL result/terminal 写失败 | `STORAGE/RETRY` | repository 边界有界 retry；每次都重验 RLock owner + run_epoch，耗尽则 worker run 退出并由 stale recovery 接管，不伪造 FAILED/SUCCESS |
| Redis 进度 incr 失败 | `DEPENDENCY/SKIP` | 跳过（不影响结果落库），指标告警 |
| Redis 取消标志读取失败 | `DEPENDENCY/SKIP` | 跳过（视为未取消，继续执行；下次边界再查） |
| MinIO 写失败（dag 单元执行器已归一化） | `STORAGE/RETRY` | 透传，由 `FailureIsolator` 标 FAILED |
| MQ 消费失败 | `DEPENDENCY/RETRY` | infra/mq 进程内重试 + RocketMQ broker 重投或显式延迟重投，耗尽后入 DLQ |
| MQ DLQ 消息 | —— | DLQ 处理器落 `task.last_error` + 状态机迁移 `RUNNING → FAILED` + publish TaskCompletedEvent（带 `lastError`） |

### 14.3 降级原则

- **进度推送失败** → 不影响主流程，仅指标告警。
- **取消标志读取失败** → 视为未取消（最坏情况是用户取消按钮失效，但不会让任务错乱）。
- **process_result 写失败** → 当前 worker run 失败退出，等待 MySQL heartbeat stale recovery；绝不把“数据库不可用”写成业务 FAILED，也绝不让只有对象、没有 fenced SUCCESS row 的产物对外可见。
- **DLQ 消息处理失败** → 记录脱敏告警与失败指标，不无限重试。

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
      heartbeat-interval: 30s         # worker fenced 更新 MySQL heartbeat_at
    lock:
      acquire-wait: 0s                # 重复消息不等待；未取得 RLock 即退出
      watchdog-timeout: 30s           # Redisson watchdog 配置；不设置固定业务 lease
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

- 线程池参数与 design.md §9.3「默认并发 8」对齐，可按部署规模调；图片内存上限由 `pixflow.image.pixel-budget.*` 的 JVM 全局预算独立控制。
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
| `pixflow.task.heartbeat` | counter | `result=ok\|stale\|error` | MySQL fenced 心跳结果；不使用 task_id label |
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
| `module/conversation` | 调 `createAndEnqueue`（确认边界）、`cancel`、`retryFailed`；retry-failed 保留来源 task 不变并返回新 taskId；订阅 `TaskCompletedEvent` |
| `harness/hooks` | 订阅 `TaskCreatedEvent` / `TaskCompletedEvent`；`harness/hooks` 不反向调 task 内部 |
| `agent`（Wave 5） | 必要时调 `TaskQueryService.getStatus`；不调 `TaskCommandService`（确认执行走 conversation REST 边界） |
| `module/dag` | 从 Canonical DAG 调 `DagCompiler`，再 `BranchExpander.expand(TypedExecutionPlan, images)` 与 `UnitExecutor.execute`；据 `UnitOutcome/UnitFailure` fenced 写结果 |
| `module/imagegen`（Wave 4 落地） | 调 `ImageGenExecutor.redraw(GenerativeUnitSpec)`；据 `GeneratedArtifact` 写临时结果并经 publication port 获得新的 generated image identity |
| `harness/state` | task 实现 `CheckpointReadPort`，只投影 SUCCESS Work Unit Checkpoint；消费 `resolveSkippable` 与取消读协议；将 ProgressEvent 桥接为全局 Activity |
| `infra/mq` | 任务消息 publish/consume；`TaskMessageDestination` 声明 `pixflow-task` topic、`TASK_EXECUTE` tag 与 `pixflow-task-worker` consumer group；DLQ 处理器调用 `task` 的 `markFailedByDlq(taskId, errorMsg)` |
| `infra/cache` | Redisson RLock/进度/取消/idempotency；heartbeat 与 run_epoch 在 MySQL，cache 不承担 fencing 事实 |
| `infra/storage` | dag/imagegen 调 `ObjectStorage.put(StorageKeys.resultUnit/generatedUnit(..., runEpoch), bytes)` 写 TMP 候选；publication copy 到稳定 RESULTS/GENERATED asset key 后删除候选；源图由 dag/imagegen 拉取 |
| `common` | 抛 `PixFlowException(TaskErrorCode)`；进度/错误文案经 `Sanitizer`；`TaskErrorCode` 并入 `common` 启动期聚合测试 |

**反向约束**（ArchUnit 守护）：
- task **不依赖** `harness/loop` / `harness/tools` / `harness/context` / `harness/hooks`（除订阅 SPI 外的反向调用）
- task **不直连** `module/file` 的表或内部类；结果资产注册只调用由应用层接线、File 实现的 `GeneratedAssetPublicationPort`
- task **不依赖** `agent`（执行期查询走 `TaskQueryService`，不导入 agent 包）
- task **不出现** Spring AI 注解 / LLM 调用

---

## 十八、测试策略

### 18.1 单元测试

- **`TaskStateMachine` / `ResultStateMachine`**：任务终态不可变；全部 SUCCESS 才 COMPLETED；混合结果 PARTIAL；result 的非 SUCCESS 只允许父 task RUNNING + higher epoch 重置，SUCCESS 永不覆盖。
- **`CreateTaskServiceImpl`**：`proposalId` 已绑定运行态或终态任务都返回原 taskId；不同 Proposal 创建不同任务；MQ 发布失败 → 状态机迁移 `PENDING → FAILED`。
- **`WorkUnitScheduler`**：线程池满时 `CallerRunsPolicy` 触发；线程池满时 `AbortPolicy` 触发；正常提交流程；异常包装（提交后抛异常不污染 worker）。
- **`FailureIsolator`**：异常归一化（各类异常 → 对应 `TaskErrorCode`）；脱敏（error_msg 不含凭证/绝对路径）；进度 incr；写 `process_result` 后不抛异常。
- **`TerminalStateJudge`**：全 SUCCESS → COMPLETED；全 FAILED → FAILED；SUCCESS+FAILED（无取消）→ PARTIAL；SUCCESS+SKIPPED → PARTIAL；全 SKIPPED → CANCELLED；stale epoch 不写终态/不发事件。
- **`RetryFailedTaskService`**：只接受 PARTIAL/FAILED；复制 canonical payload/schema、固定 FAILED unit_key selection、设置 retry_of_task_id；来源不可变；空失败集拒绝；`retry-failed:{sourceTaskId}` 重放返回同一 direct derived taskId。
- **`ExecutionClaim`**：未取得 RLock 不递增 epoch；取得后原子 claim；心跳/result/terminal 的 stale epoch 均更新 0 行并停止；unlock 只允许 current owner。
- **`IdempotencyGuard`**：Redis 命中 / miss；MySQL UNIQUE 兜底（Redis miss + MySQL 命中 → 仍走幂等）。
- **`GeneratedAssetPublication`**：成功结果获得新 imageId；源图 ID 不复用；重复 publication 返回同一 generated image；清除任务记录不删除已发布资产。
- **`TaskLifecycleCleanup`**：失败记录手动删除与 24h 超时清理；取消终态立即清理记录与未发布对象；成功记录清理不触碰资产。
- **`DownloadService`**：canonical IMAGE reference 授权；bundle 超 `max-bundle-bytes` → `TASK_DOWNLOAD_BUNDLE_TOO_LARGE`；bundle 临时文件清理。

### 18.2 集成测试（单元边界 fake + 真实依赖故障注入）

- **create → worker → terminal 端到端**：造 5 张图 + 简单 DAG（remove_bg→resize）→ 创建任务 → worker 拉起 → 全部 SUCCESS → 终态 COMPLETED。
- **失败隔离**：造 5 张图，其中 2 张损坏 → 2 FAILED + 3 SUCCESS → 终态 PARTIAL，并持久化结构化最终失败字段。
- **取消协作**：worker 跑到一半时调 cancel → 后续单元标 SKIPPED → 已开始单元自然完成 → 终态 PARTIAL。
- **断点恢复**：worker 跑到一半时强制 kill（不发 TaskCompletedEvent）→ @Scheduled 扫描 → heartbeat 缺失 → 重新入队 → 新 worker 拉起 → 跳过已成功单元 → 终态 COMPLETED。
- **显式失败重试**：对 PARTIAL 调 retry-failed → 来源仍 PARTIAL，生成新 taskId，只创建 2 个失败 unit rows；派生任务成功不改来源结果。
- **业务身份重放**：同一 `proposalId` 或 `retry-failed:{sourceTaskId}` 二次 createAndEnqueue → 返回原 taskId + 不创建新任务；同身份不同 payload → conflict。
- **生图路径**：造 3 张源图并发布 3 个独立 Proposal/task；每个 worker 只调一次 fake ImageGenExecutor、生成一个新 imageId，成功资产进入 GENERATED 稳定 key，三个任务分别终态 COMPLETED。
- **真实 MySQL/Redis/MinIO 故障注入是完成门槛**：Testcontainers 运行实际 schema、Redisson RLock 与对象存储；验证 worker A 失锁、worker B claim higher epoch 后 A 的 heartbeat/result/terminal 全部被 fence；恢复扫描不 force-unlock；MinIO put 后 MySQL 失败留下的 epoch 对象不对外可见；SUCCESS 重放不可覆盖；Redis 不可用时 fail-closed。测试被 skipped 不算验收通过。

### 18.3 边界守护（ArchUnit）

```java
@ArchTest
static final ArchRule task_module_boundaries = classes()
    .that().resideInAPackage("com.pixflow.module.task..")
    .should().onlyAccessClassesThat().resideInAnyPackage(
        "com.pixflow.module.task..",
        "com.pixflow.module.dag.expand..", "com.pixflow.module.dag.exec..",
        "com.pixflow.module.dag.ir..", "com.pixflow.module.dag.compile..",
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

- 与 dag / imagegen / state / mq / cache / storage 联合跑全链路（Testcontainers 拉起 MySQL/Redis/MinIO/RocketMQ）：
  - 真实 DAG 端到端：上传素材包 → Agent 提案 → 用户确认 → task 执行 → 终态 → 通知 → 下载
  - 真实生图端到端：同上传素材包 → Agent 提案 → 用户确认 → task 执行 → 终态 → 下载
  - 故障注入：MQ DLQ、worker 崩溃、RLock 丢失、stale epoch、MySQL/MinIO 提交间隙、Redis 不可用 → 行为符合设计预期

---

## 十九、对 design.md 与依赖计划的细化

本模块对 `design.md` 与相邻设计/计划文档做如下**细化（非冲突）**，需同步记录：

1. **status enum 重新对齐**（`design.md §13.1`）：task 使用 7 态、result 使用 5 态；`COMPLETED` 严格表示全部所选单元成功，任意 SUCCESS 与 FAILED/SKIPPED 混合均为 `PARTIAL`，不局限于取消场景。
2. **`process_task` 增量字段**：除创建/时间字段外，新增 `schema_version`、`unit_selection_json`、`retry_of_task_id`、`run_epoch`、`heartbeat_at`；终态 retry 创建派生任务。
3. **`process_result` 增量字段**：新增显式 `unit_key/unit_kind/run_epoch` 与结构化 final failure 字段；`UNIQUE(task_id,unit_key)` 是唯一性事实，SUCCESS 不可覆盖。
4. **锁与 heartbeat**：Redis 只保存 `lock:task:{taskId}:execution` RLock 与 idempotency/进度/取消；MySQL `heartbeat_at` 做 stale discovery，`run_epoch` 做 fencing。恢复不得 force-unlock。
5. **MySQL 索引补强**：`idx_process_task_status_heartbeat`、`uq_process_task_idempotency_identity`、`uq_process_result_task_unit_key`、`idx_process_task_retry_of`。
6. **新增 `imagegen → task` 依赖边**（`module-dependency-dag-plan.md`）：Wave 4 落地时由 task 引入，与 `dag → task` 边对称（dag.md / imagegen.md 已登记）。
7. **task 与 dag/imagegen 的「执行引擎」边界**（`design.md §9/§12`、`dag.md §二`、`imagegen.md §二`）：本模块钉死——dag 与 imagegen 是被 task 调用的**无状态确定性/能力单元**；task 是**有状态异步外壳**。失败隔离分两半——单元内归一化在 dag/imagegen（返回 FAILED outcome），批次内继续在 task（落 `process_result.status=FAILED` + 兄弟单元继续）。
8. **WebSocket 归属**（`design.md §5.4`）：task **不直连** WebSocket / STOMP / SSE；进度推送由 `harness/state.ProgressEventPublisher` 负责，task 只发 `ProgressEvent`（domain event）到 publisher。这细化 design.md §4 「WebSocket 推送」的归属（应改为 state 模块）。
9. **DLQ 语义**（`design.md §9.2`）：业务错误（dag 校验失败）不入 DLQ；系统错误（worker 抛 NPE / DB 挂）入 RocketMQ consumer group DLQ → DLQ 处理器落 `last_error` + 状态机迁移 RUNNING → FAILED；第三方错误（抠图/生图 5xx）→ 走支路级 Resilience4j 重试 + FailureIsolator，不入 DLQ。
10. **`process_task.dag_json` 字段含义**（`design.md §13.1`）：明确该字段存**已校验的不可变 DAG 快照**（确定性路径）或**生图提案规范化载荷**（生成式路径）；恢复时不需回查 dag/imagegen 模块。task 自包含、避免循环依赖。
11. **生成式路径 task 模块边界**（`imagegen.md §八`）：task 模块包办两条产图路径的异步执行壳，imagegen 只暴露 `ImageGenExecutor` SPI；由 `WorkerRouter` 按 `task_type` 路由到 `ProcessWorker` / `ImageGenWorker`。该边界在 `module-dependency-dag-plan.md` 已有（task 模块本就设计为承接两条路径），本文落实实现细节。
12. **可观测性 14 类指标**（与 dag 的 11 类、imagegen 的 6 类在 shape 上互补）：按 `create/worker/heartbeat/recovery/cancel/terminal/progress/download/lock/state.transition` 10 组维度组织；不依赖 `harness/eval`；不带业务字段；细分到 `TaskErrorCode` 的 label。

> 以上为对既有设计的补充落地，不改变 `design.md §5.4`「MySQL 是事实源」、§9.4「支路幂等重算」原则、§13.3「Redis 引用而非字节」口径。

---

## 二十、暂不考虑

- **节点级断点续传**：恢复以支路/单元为幂等单元整体重算（design.md §9.4），不做节点级续传。
- **终态任务原地复活**：PARTIAL/FAILED/CANCELLED/COMPLETED 都不可回到 RUNNING；失败重试只能创建 derived task。
- **任务优先级调度**：`process_task.priority` 字段已建但本期不实现优先级队列；生产可调线程池并发上限达到类似效果。
- **下载细粒度鉴权**：本期按 taskId 即可下载（前端已在会话内鉴权）；多租户隔离留待权限层细化。
- **任务级工作流编排**（DAG 任务的子任务 / 任务依赖）：本期每个 task 独立执行，无 task 间依赖；如需"批量完成后触发下游任务"留待后续工作流模块。
- **正常任务/Work Unit 自动重试循环**：task 不实现。provider/AI/storage 在 owning boundary 内有界 retry；worker 崩溃由新 epoch 恢复，终态失败由用户创建 derived retry task。
- **应用层代理下载**：本期用 MinIO presigned URL，**绝不**经应用层代理大文件（避免 OOM / 阻塞 worker 线程）。
- **跨 task 的资源复用**（如多 task 共用 watermark 缓存）：本期 watermark 缓存放 dag 进程内（dag.md §11.4），不跨 task；如需跨 task 共享由 task 引入分布式缓存（待成本瓶颈出现再评估）。

---

## Revision Notes

2026-07-12 / Codex: 固化 Work Unit checkpoint 与异常恢复协议。`unit_key` 成为显式唯一身份，SUCCESS 不可覆盖；非 SUCCESS 仅能在父任务仍 RUNNING 且 higher run_epoch 的崩溃恢复中重算。Redisson `RLock` 是唯一互斥，MySQL run_epoch 只做 fencing、heartbeat_at 只做 stale discovery，恢复扫描禁止 force-unlock。全部成功才 COMPLETED，混合结果为 PARTIAL；retry-failed 创建带固定失败单元 selection 的 derived task。完成验收要求真实 MySQL/Redis/MinIO 故障注入。

2026-06-29 / Kiro: 新增 `module/task` 设计文档。确立核心边界「task 是 dag/imagegen 的异步外壳」——dag 是无状态确定性单元执行器、imagegen 是无状态单图重绘能力模块、task 是有状态并发编排外壳。确定 12 项对既有设计的细化（§十九），核心包括：① 状态机 7 态 + 5 态扩展；② `process_task` / `process_result` 增量 13 个生产级字段；③ Redis 键补强 3 类；④ 两条产图路径共用同一执行壳（`WorkerRouter` + `ProcessWorker` + `ImageGenWorker`）；⑤ 失败隔离分两半（单元内归一化在 dag/imagegen，批次内继续在 task）；⑥ 取消是协作式协议、绝不打断运行中单元；⑦ WebSocket 归属改为 `harness/state`，task 只发 domain event；⑧ 恢复粒度 = task 级 + 单元级，单元级幂等靠 `UnitKey` + `process_result` 唯一索引；⑨ task 模块包办两条产图路径的异步执行壳，imagegen 不自建异步；⑩ 14 类 Micrometer 指标按 10 组维度组织，不依赖 `harness/eval`。向 `design.md §5.4/§9.2/§9.4/§12/§13.1/§13.3`、`module-dependency-dag-plan.md`、`dag.md §二`、`imagegen.md §二` 提出连带修订建议。

2026-07-02 / Codex: 同步 `infra/mq.md` 的 RocketMQ 目标设计，将任务消息消费从 RabbitMQ / `@RabbitListener` 口径改为 RocketMQ consumer / `ConsumerBinding` / `ManagedMessageContainer` 口径；`TaskMessageDestination` 声明 `pixflow-task` topic、`TASK_EXECUTE` tag 与 `pixflow-task-worker` consumer group。业务语义保持不变：消费成功只代表接管成功，长任务可靠性仍由 MySQL 状态机、Redisson 锁、`process_result` checkpoint 与恢复扫描兜底。

2026-07-09 / Codex: 同步 conversation 取消安全重构实现状态：`CancelTaskCommand` 改为 `long taskId + conversationId + requesterUserId + reason`，conversation 在入口先完成 owner proof 与 taskId 边界解析，task 内部通过 `process_task.id + conversation_id` 查询后才设置取消标志；非法 taskId 不再落到 task 内部 `Long.parseLong`，跨会话 task 取消按 missing 处理。
