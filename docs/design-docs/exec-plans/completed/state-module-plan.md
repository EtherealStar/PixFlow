# 实现 harness/state 执行运行态聚合与断点恢复协调

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/state` 模块本身及其与 `module/task`、`module/dag`、`infra/cache`、`infra/storage`、`common` 的接缝，不实现 `module/task` 的 MySQL 表读写，不实现 `module/dag` 的分支执行，不实现 WebSocket 推送，不按 MVP 裁剪功能。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级的执行运行态读侧核心。后续任务调度模块可以通过它查询任务快照、判断取消信号、计算崩溃恢复时哪些工作单元可以跳过，并统一处理 Redis 进度计数与 MySQL checkpoint 的对账。用户能间接看到的效果是：任务重启恢复不会重复处理已经成功的图片支路或组支路，状态查询在 Redis 抖动时仍能返回 MySQL 权威进度，取消信号不会被静默吞掉，轮询和 WebSocket 会使用同一份状态快照口径。

本计划的验收重点不是“类是否存在”，而是可观察行为：运行 `mvn -pl pixflow-state -am test` 后，应能看到恢复协调、进度双源对账、取消读侧协议、运行态引用降级、统一快照组装和依赖边界守护测试全部通过。state 自身不依赖真实 MySQL，使用内存版 `CheckpointReadPort` 验证核心机制；真实数据库接线留给后续 `module/task` 集成测试。

## Progress

- [x] (2026-06-28 18:20+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/design.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/base/common.md`，确认 state 属于 Wave 2 harness 基础。
- [x] (2026-06-28 18:20+08:00) 已核对现有代码结构，确认项目为 Java 21 + Maven 多模块，已有 `pixflow-common`、`pixflow-infra-cache`、`pixflow-infra-storage`、`pixflow-hooks` 等模块，state 应新增为独立模块 `pixflow-state`。
- [x] (2026-06-28 18:20+08:00) 已确认本计划按生产级完整实现设计，不按 MVP 简化，不把 state 做成临时缓存层或 task 的内部工具类。
- [x] (2026-06-28 18:20+08:00) 已确定新增 `TaskRuntimeKeyPort`，由后续 `module/task` 提供 `progress` 与 `cancel` 的 `CacheKey`，解决 state 不拼业务 Redis key 但仍能按 `taskId` 查询的问题。
- [x] (2026-06-28 18:20+08:00) 已确定 `ProgressView` 需要表达 Redis 实时值、MySQL 权威值、来源和 drift，而不是只暴露一个不带来源的计数。
- [x] (2026-06-28 18:20+08:00) 已确定 state 首版只把 `ObjectStorage` 作为可选依赖或类型边界保留，不主动做中间产物字节回读服务，避免把 state 变成 I/O 编排层。
- [x] (2026-06-28 17:28+08:00) 新增 `pixflow-state` Maven 模块，并加入根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>`。
- [x] (2026-06-28 17:28+08:00) 实现 state 的模型、端口、运行态读取、恢复协调、快照服务、错误码、配置与可观测指标。
- [x] (2026-06-28 17:28+08:00) 补齐单元测试、装配测试和依赖边界测试。
- [x] (2026-06-28 17:28+08:00) 运行 `mvn -pl pixflow-state -am test`，确认 state core 测试通过。
- [ ] 后续在 `module/task` 和 `module/dag` 实现时补齐 MySQL checkpoint、Redis key、REST/WS 和恢复重扫的集成测试。

## Surprises & Discoveries

- Observation: `state.md` 设计强调 state 不拼业务 key，但 `ProgressReader.read(taskId)` 与 `CancellationReader.isCancelRequested(taskId)` 又需要访问 `progress:task:{taskId}` 与 `cancel:task:{taskId}`。
  Evidence: `docs/design-docs/harness/state.md` 同时写明“state 不拼业务键、不知道 dag 业务含义”和“进度优先读 Redis `progress:task:{taskId}`、取消读 Redis `cancel:task:{taskId}`”。因此本计划新增 `TaskRuntimeKeyPort`，由 task 拥有 key 命名，state 只消费 `CacheKey`。

- Observation: 现有 `infra/cache` 公共接口已经把 Redis 原语收敛为 `CacheStore`、`AtomicCounter` 和 `CacheKey`，state 不需要、也不应该接触 Redisson。
  Evidence: `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/CacheStore.java` 暴露 `get/put/putIfAbsent/exists/delete`；`pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/counter/AtomicCounter.java` 暴露 `incrementBy/get/reset`；`CacheKey` 已携带完整 key 字符串、默认 TTL 和 namespace。

- Observation: 现有 `infra/storage` 的 `ObjectStorage` 是纯 I/O 接口，state 文档里的 `ArtifactRef.location` 只需要使用 `ObjectLocation` 表示引用即可。
  Evidence: `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/ObjectStorage.java` 暴露 `put/getStream/getBytes/exists/stat/delete/presign` 等 I/O 方法；state 的核心恢复口径只需要知道字节在哪里，不需要在首版读取字节。

- Observation: 当前 active exec plans 中还没有 state 计划，但已有 context/hooks/mq 计划可作为格式与模块拆分风格参考。
  Evidence: `docs/design-docs/exec-plans/` 下已有 `context-module-plan.md`、`hooks-module-plan.md`、`mq-module-plan.md`，没有 `state-module-plan.md`。

## Decision Log

- Decision: `harness/state` 新增为独立 Maven 模块 `pixflow-state`，包名使用 `com.pixflow.harness.state`。
  Rationale: state 是 Wave 2 harness 基础件，依赖 `common`、`infra/cache`、`infra/storage`，并被 Wave 4 的 `module/task` 消费。独立模块能清楚表达依赖方向，也便于用 `mvn -pl pixflow-state -am test` 做局部验证。
  Date/Author: 2026-06-28 / Codex

- Decision: state 是读侧聚合与恢复协调层，不收编 task/dag 的运行态写入。
  Rationale: 进度自增、加锁去重、设置取消标志、删除 branch/group 引用缓存、写 `process_task` 和 `process_result` 都带明确业务语义，属于 task/dag。state 如果接管这些写入，会破坏设计文档中“读侧聚合，不抢写侧”的边界。
  Date/Author: 2026-06-28 / Codex

- Decision: 新增 `TaskRuntimeKeyPort`，由 task 侧实现 `progressKey(taskId)` 与 `cancelKey(taskId)`。
  Rationale: state 的进度和取消 API 需要按 `taskId` 查询，但 Redis key 的命名权属于 task。用 SPI 倒置 key 生成，可以避免 state 偷拼 `progress:task:*` 和 `cancel:task:*`，同时保持 state API 对调用方友好。
  Date/Author: 2026-06-28 / Codex

- Decision: `ProgressView` 同时表达 Redis 实时进度、MySQL 权威计数、来源和 drift。
  Rationale: 只返回一个 `done` 会掩盖 Redis 与 MySQL 的漂移。生产排障需要知道展示值来自 Redis 还是 MySQL，以及 Redis done 与 MySQL succeeded 的差值。终态判定仍只信 MySQL。
  Date/Author: 2026-06-28 / Codex

- Decision: `CheckpointReadPort` 由 state 定义、由后续 task 实现；state 不直连 MySQL，不依赖 MyBatis、JDBC 或 `process_*` 实体类型。
  Rationale: `process_task` 和 `process_result` 是强业务表，属主是 task/dag。state 只需要完成单元、计数和任务状态的投影，不能拿到全字段业务实体。
  Date/Author: 2026-06-28 / Codex

- Decision: 快照查不到任务时由 state 抛 `STATE_TASK_NOT_FOUND`；恢复协调默认由 task 对已存在任务调用，若 checkpoint port 明确报告不存在，也应归一化为同一错误码。
  Rationale: 状态查询是用户可见读接口，taskId 不存在必须明确返回 NOT_FOUND。恢复协调通常来自 task 的运行中任务扫描，理论上不会传未知 taskId，但测试仍要覆盖该边界。
  Date/Author: 2026-06-28 / Codex

- Decision: state 首版不提供“按 `ArtifactRef` 回读中间产物字节”的高级服务，只保留 `ObjectLocation` 类型和未来扩展位置。
  Rationale: 首版 state 的核心价值是读模型聚合和恢复裁决，不是执行 I/O。过早加入字节回读会把 task/dag 的执行编排职责拉进 state，增加边界风险。
  Date/Author: 2026-06-28 / Codex

- Decision: state 的 Spring 自动装配应对 `CheckpointReadPort` 和 `TaskRuntimeKeyPort` 做条件装配，不提供假的生产实现。
  Rationale: Wave 2 可以先实现 state core，但 Wave 4 才会实现 task。没有端口实现时不应伪造数据，也不应让应用误以为状态查询可用。测试使用 test-scope 的内存替身。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

本计划当前已完成 state core 的首版实现。新增的 `pixflow-state` 模块把 state 的职责收束为“执行运行态读模型聚合、恢复可跳过单元计算、进度/取消读侧协议和运行态引用 facade”，并在代码层固定 Redis key 归属、MySQL checkpoint 倒置、进度漂移可观测、首版不做字节回读这些边界。`mvn -pl pixflow-state -am test` 已通过，state 模块 15 个测试覆盖恢复协调、进度双源对账、取消协议、运行态引用、快照组装、自动装配和依赖边界。后续 `module/task` 和 `module/dag` 实现时，仍需补齐真实 MySQL checkpoint、Redis key、REST/WS 和恢复重扫的集成测试。

## Context and Orientation

PixFlow 当前处于完整重写阶段。仓库根目录是 `D:\study\PixFlow`，父工程 `pom.xml` 是 Maven 多模块项目，Java 版本为 21。已有基础模块包括 `pixflow-common`、`pixflow-infra-cache`、`pixflow-infra-storage` 和 `pixflow-hooks`。本计划要新增的模块是 `pixflow-state`，源码路径应为 `pixflow-state/src/main/java/com/pixflow/harness/state/...`，测试路径应为 `pixflow-state/src/test/java/com/pixflow/harness/state/...`。

这里先定义几个术语，便于不了解本仓库的读者继续实施。运行态是指任务正在执行时产生的临时状态，例如进度计数、取消标志、中间产物引用。checkpoint 是指已经持久落库、可用于崩溃恢复的断点；在 PixFlow 中，MySQL `process_result` 成功记录就是工作单元的权威 checkpoint。读侧聚合是指 state 从 MySQL、Redis 和 MinIO 的引用信息中读取事实并组装成统一视图，但不负责写入这些事实。工作单元是任务恢复时的最小跳过单位，普通支路是“图片 × branch”，组支路是“group × branch”。drift 是 Redis 实时计数与 MySQL 权威计数之间的差值，用来发现进度计数漂移。

实现时应优先阅读这些设计文档，并可用括号中的关键词快速定位设计文本。

在 `docs/design-docs/harness/state.md` 中搜索：`读侧聚合，不抢写侧`、`CheckpointReadPort`、`运行态引用存取`、`进度的双源对账`、`取消的读侧协议`、`RecoveryCoordinator`、`ExecutionStateSnapshot`、`降级分级`、`边界守护`。这些位置解释 state 的职责边界、核心类型、恢复规则、Redis/MySQL 对账和测试要求。

在 `docs/design-docs/design.md` 中搜索：`State Store`、`断点恢复与失败隔离`、`分组聚合`、`Redis（键约定）`、`process_task`、`process_result`、`异步执行时序`。这些位置解释为什么 MySQL 是权威事实源，为什么 Redis 只做实时与避算优化，以及普通支路和组支路如何落库。

在 `docs/design-docs/infra/cache.md` 中搜索：`只提供通用原语`、`CacheStore`、`AtomicCounter`、`降级分级策略`、`中间产物边界`、`对其他模块的契约`。这些位置解释 state 为什么只能通过 cache 原语读写 Redis，为什么 Redis 只放引用而不放字节，以及不同 Redis 操作失败时应怎样降级。

在 `docs/design-docs/infra/storage.md` 中搜索：`ObjectLocation`、`ObjectStorage`、`纯 I/O`、`TMP 桶`、`对其他模块的契约`。这些位置解释中间产物字节为什么落对象存储，以及 state 首版为什么只需要引用位置而不需要直接编排字节读取。

在 `docs/design-docs/base/common.md` 中搜索：`ErrorCategory`、`RecoveryHint`、`PixFlowException`、`ErrorCode`、`Sanitizer`、`infra 异常收口策略`。这些位置解释 state 的错误码如何分类，取消命中如何抛业务错误，依赖故障如何上抛或降级。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：`Wave 2 harness基础`、`state`、`cache --> state`、`storage --> state`、`state --> task`。这些位置解释 state 的实现波次和依赖方向。

## Plan of Work

第一阶段建立模块边界。新增目录 `pixflow-state/`，创建 `pixflow-state/pom.xml`，父工程是根 `pom.xml`。根 `pom.xml` 的 `<modules>` 增加 `pixflow-state`，`dependencyManagement` 增加 `com.pixflow:pixflow-state:${project.version}`。该模块依赖 `pixflow-common`、`pixflow-infra-cache`、`pixflow-infra-storage`、`spring-boot-autoconfigure`、`spring-context` 和测试依赖。不要让 `pixflow-state` 依赖 `pixflow-app`、`module/task`、`module/dag`、MyBatis、Redisson 或 MinIO SDK。

第二阶段实现不可变模型。创建 `model/UnitKind.java`、`UnitStatus.java`、`TaskRunStatus.java`、`ProgressSource.java`、`UnitKey.java`、`CompletedUnits.java`、`ArtifactRef.java`、`ProgressView.java`、`ExecutionStateSnapshot.java`。所有 record 都必须在 compact constructor 中校验必填字段并防御性拷贝集合或 Map。`UnitKey` 提供 `branch(taskId, imageId, branchId)` 与 `group(taskId, groupKey, branchId)` 两个工厂方法，避免调用方手填错误的 `UnitKind`。`CompletedUnits` 只保存成功单元，提供 `isDone(UnitKey)` 与 `size()`。

第三阶段实现只读端口。创建 `port/CheckpointReadPort.java` 和 `port/TaskRuntimeKeyPort.java`。`CheckpointReadPort` 是 MySQL checkpoint 的倒置接缝，由后续 `module/task` 实现。它提供读取已成功单元、读取权威计数、读取任务状态和列出运行中任务的能力。`TaskRuntimeKeyPort` 是 Redis 运行态 key 的倒置接缝，由后续 `module/task` 实现，只返回进度和取消的 `CacheKey`。state 不拼 `progress:task:*` 或 `cancel:task:*` 字符串。

第四阶段实现运行态引用 facade。创建 `runtime/RunStateRefStore.java` 和默认实现。它只组合 `CacheStore`，提供 `putRef(CacheKey, ArtifactRef, Duration)`、`getRef(CacheKey)`、`deleteRef(CacheKey)`。branch/group 的 key 构造和生命周期仍归 dag/task。`getRef` 遇 Redis miss 或可降级读异常时返回 `Optional.empty()`；`putRef` 和 `deleteRef` 的失败只记录 warn 和指标，不阻断主流程。实现中不要读取或写入图片字节。

第五阶段实现进度读侧协议。创建 `runtime/ProgressReader.java` 和默认实现。读取流程是先通过 `CheckpointReadPort` 获取 MySQL 权威计数，再在配置允许时通过 `TaskRuntimeKeyPort.progressKey(taskId)` 和 `AtomicCounter.get` 读取 Redis 实时 done。Redis 命中时 `ProgressView` 的展示来源为 `REDIS`，同时保存 MySQL counts 和 drift；Redis miss 或 Redis 异常时返回 MySQL 来源，不上抛。终态判定不能使用 Redis。

第六阶段实现取消读侧协议。创建 `runtime/CancellationReader.java` 和默认实现。它通过 `TaskRuntimeKeyPort.cancelKey(taskId)` 获取 `CacheKey`，再用 `CacheStore` 读取布尔值。`isCancelRequested(taskId)` 返回实时取消标志。`throwIfCancelled(taskId)` 在命中时抛 `PixFlowException`，错误码为 `STATE_TASK_CANCELLED`。取消读失败必须上抛，因为取消没有 MySQL 权威副本可回退。

第七阶段实现恢复协调。创建 `recovery/RecoveryCoordinator.java` 和默认实现。`resolveSkippable(taskId)` 只调用 `CheckpointReadPort.loadCompletedUnits(taskId)`，返回 MySQL 成功单元集合。Redis 引用不能参与“可跳过”判定。失败单元不进入可跳过集合；组支路成功记录按 `UnitKind.GROUP` 整组跳过。

第八阶段实现统一状态快照。创建 `query/ExecutionStateService.java` 和默认实现。`snapshot(taskId)` 从 `CheckpointReadPort` 读取任务运行态，从 `ProgressReader` 读取进度，从 `CancellationReader` 读取取消标志，并组装 `ExecutionStateSnapshot`。任务不存在时抛 `STATE_TASK_NOT_FOUND`。如果取消读取失败，该失败应上抛；如果进度 Redis 读取失败，则快照仍返回 MySQL 进度。

第九阶段实现错误码、配置和指标。创建 `error/StateErrorCode.java`，至少包含 `STATE_TASK_NOT_FOUND` 与 `STATE_TASK_CANCELLED`。创建 `config/StateProperties.java`，绑定 `pixflow.state.progress.prefer-redis`、`pixflow.state.progress.drift-warn-threshold`、`pixflow.state.recovery.running-scan-limit`、`pixflow.state.snapshot.include-progress`。创建 `observability/StateMetrics.java` 及 noop/micrometer 实现，记录快照结果、进度来源、进度 drift 和恢复可跳过数量。指标 tag 不得包含 taskId。

第十阶段实现 Spring 装配。创建 `config/StateAutoConfiguration.java`。当容器存在 `CacheStore`、`AtomicCounter`、`CheckpointReadPort` 和 `TaskRuntimeKeyPort` 时装配 `ProgressReader`、`CancellationReader`、`RecoveryCoordinator`、`ExecutionStateService`。`RunStateRefStore` 只依赖 `CacheStore`，可以单独装配。不要提供生产用的内存 checkpoint port；内存替身只放在 test scope。

第十一阶段补齐测试。单元测试使用 fake `CacheStore`、fake `AtomicCounter`、test-scope `InMemoryCheckpointReadPort` 和 fake `TaskRuntimeKeyPort`。测试必须覆盖恢复只跳过成功单元、进度 Redis 命中和回退、取消命中和取消读失败、引用 miss 降级、快照组装、任务不存在、配置开关和指标记录。再补 ArchUnit 测试，断言 `com.pixflow.harness.state..` 不依赖 MyBatis、JDBC、Redisson、MinioClient、`com.pixflow.module.task` 或 `com.pixflow.module.dag`。

## Concrete Steps

从仓库根目录开始执行：

    cd D:\study\PixFlow
    git status --short

如果当前工作区已有与 state 无关的用户改动，不要回滚。继续只编辑 `docs/design-docs/exec-plans/state-module-plan.md` 和后续实现所需的 `pixflow-state` 模块文件、根 `pom.xml` 中的模块声明与依赖管理条目。

确认 state 设计文档中的核心边界：

    cd D:\study\PixFlow
    rg -n "读侧聚合|CheckpointReadPort|进度的双源对账|取消的读侧协议|RecoveryCoordinator|ExecutionStateSnapshot|降级分级" docs\design-docs\harness\state.md

确认依赖波次：

    cd D:\study\PixFlow
    rg -n "Wave 2|harness/state|cache --> state|storage --> state|state --> task" docs\design-docs\exec-plans\module-dependency-dag-plan.md

确认现有 cache 和 storage 接口：

    cd D:\study\PixFlow
    Get-Content pixflow-infra-cache\src\main\java\com\pixflow\infra\cache\store\CacheStore.java
    Get-Content pixflow-infra-cache\src\main\java\com\pixflow\infra\cache\counter\AtomicCounter.java
    Get-Content pixflow-infra-storage\src\main\java\com\pixflow\infra\storage\ObjectLocation.java

实现模块后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-state -am test

成功时应看到 Maven `BUILD SUCCESS`，并且 state 相关测试全部通过。测试数量以后续实现为准，但至少应包含恢复协调、进度、取消、引用、快照、配置和依赖边界测试。

如果后续 `module/task` 已实现 checkpoint 和 key 端口，再运行 task 侧集成测试。命令形态应类似：

    cd D:\study\PixFlow
    mvn -pl pixflow-task -am test

该命令应证明 `module/task` 能实现 `CheckpointReadPort` 和 `TaskRuntimeKeyPort`，并通过真实 MySQL 投影、真实 Redis key 和状态查询入口消费 state。

## Validation and Acceptance

state core 的验收以可观察测试行为为准。

第一类验收是恢复协调。构造一个任务 `task-1`，checkpoint 中包含两个成功单元、一个失败单元和一个组支路成功单元。调用 `RecoveryCoordinator.resolveSkippable("task-1")` 后，应看到返回集合只包含成功的普通支路和成功的组支路，失败单元不在集合中。这个测试证明“可跳过 = MySQL 成功记录”，而不是 Redis 引用或失败记录。

第二类验收是进度双源对账。构造 MySQL counts 为 total=10、succeeded=4、failed=1。Redis counter 返回 5 时，`ProgressReader.read("task-1")` 应返回来源 `REDIS`，展示 done 为 5，同时保留 MySQL succeeded 为 4，drift 为 1。Redis miss 或 fake counter 抛异常时，同一调用应返回来源 `MYSQL`，展示 done 为 4，不上抛。

第三类验收是取消协议。fake cache 中 `cancelKey(task-1)` 为 true 时，`CancellationReader.throwIfCancelled("task-1")` 应抛 `PixFlowException`，code 为 `STATE_TASK_CANCELLED`，category 为 `BUSINESS_RULE`。fake cache 读取取消 key 抛异常时，`isCancelRequested` 不应静默返回 false，而应把依赖失败上抛。

第四类验收是运行态引用。`RunStateRefStore.putRef` 写入一个只包含 `ObjectLocation` 与轻量 meta 的 `ArtifactRef`，随后 `getRef` 应返回相同引用。fake cache miss 或读异常时，`getRef` 应返回 `Optional.empty()`，调用方可以退化重算。测试不应把任何图片字节放进 Redis。

第五类验收是统一状态快照。构造 task status 为 RUNNING，进度可从 Redis 读到，取消标志为 false。调用 `ExecutionStateService.snapshot("task-1")` 后，应得到包含 taskId、RUNNING、progress、cancelRequested=false 和 snapshotAt 的 `ExecutionStateSnapshot`。当 checkpoint port 表示 task 不存在时，应抛 `STATE_TASK_NOT_FOUND`。

第六类验收是配置行为。将 `pixflow.state.progress.prefer-redis=false` 时，`ProgressReader` 应始终使用 MySQL counts，不调用 Redis counter。将 drift 阈值设为 1 且 Redis/MySQL 差值超过阈值时，应记录 drift 指标或 warn 事件，但不得修正 Redis，也不得影响快照返回。

第七类验收是依赖边界。ArchUnit 测试扫描 `com.pixflow.harness.state..`，断言它不依赖 `org.redisson`、`io.minio`、`com.baomidou.mybatisplus`、`java.sql`、`com.pixflow.module.task`、`com.pixflow.module.dag`。这个测试失败时，说明 state 越界。

## Idempotence and Recovery

本计划推荐的实现是 additive changes。新增 `pixflow-state` 模块、添加纯模型、添加端口、添加服务和测试都可以重复运行。根 `pom.xml` 只应增加一个 `<module>pixflow-state</module>` 和 dependencyManagement 中的 `pixflow-state` 条目，不应重排无关模块或修改版本号。

如果 Maven 编译失败，先运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-state -am test -DskipTests

如果这一步失败，说明是编译或依赖问题；优先检查 `pixflow-state/pom.xml` 是否缺少 `pixflow-common`、`pixflow-infra-cache`、`pixflow-infra-storage` 或 Spring autoconfigure/context 依赖。如果编译通过但测试失败，再运行单个测试类定位行为问题。

如果实现中发现 `CacheStore` 或 `AtomicCounter` 的现有 API 与本计划假设不一致，以当前 Java 源码为准，并更新本计划的 `Surprises & Discoveries` 与 `Decision Log`。不要通过在 state 中直接引入 Redisson 来绕过接口限制。

如果后续 task 实现 `CheckpointReadPort` 时发现 MySQL 表字段与设计文档有差异，task 应在自己的实现中完成投影适配，state 的模型和端口只接受 `UnitKey`、`CompletedUnits`、`PersistedCounts` 和 `TaskRunStatus` 这些中立类型。不要让 `process_result` 实体流入 state。

## Artifacts and Notes

本计划创建前已确认当前真实工程证据如下：

    根 pom.xml modules 当前包含:
        pixflow-contracts
        pixflow-common
        pixflow-permission
        pixflow-hooks
        pixflow-infra-storage
        pixflow-infra-cache
        pixflow-infra-mq
        pixflow-infra-vector
        pixflow-infra-ai
        pixflow-infra-image
        pixflow-infra-thirdparty
        pixflow-app

    本计划应新增:
        pixflow-state

    已确认的 cache 接口:
        CacheStore.get(CacheKey, Class<T>)
        CacheStore.put(CacheKey, T, Duration)
        CacheStore.putIfAbsent(CacheKey, T, Duration)
        CacheStore.exists(CacheKey)
        CacheStore.delete(CacheKey)

    已确认的 counter 接口:
        AtomicCounter.incrementBy(CacheKey, long, Duration)
        AtomicCounter.get(CacheKey)
        AtomicCounter.reset(CacheKey)

    已确认的 storage 类型:
        ObjectLocation(BucketType bucket, String key)
        ObjectStorage 只提供纯 I/O 原语

实现完成后，建议保留这些测试类作为证据：

    RecoveryCoordinatorTest
    ProgressReaderTest
    CancellationReaderTest
    RunStateRefStoreTest
    ExecutionStateServiceTest
    StateAutoConfigurationTest
    StateArchitectureTest

一个理想的测试输出应能说明下面这些事实：

    RecoveryCoordinatorTest > returns only mysql succeeded units PASSED
    ProgressReaderTest > falls back to mysql when redis fails PASSED
    CancellationReaderTest > throws state task cancelled when flag is true PASSED
    RunStateRefStoreTest > treats cache read failure as miss PASSED
    ExecutionStateServiceTest > assembles snapshot from status progress and cancel PASSED
    StateArchitectureTest > state does not depend on task dag redisson minio or mybatis PASSED

## Interfaces and Dependencies

最终应存在以下 Maven 模块、包和 Java 类型。实际文件路径应位于项目根目录下的 `pixflow-state/src/main/java/com/pixflow/harness/state/...`。

在根 `pom.xml` 中，新增模块和 dependencyManagement 条目：

    <module>pixflow-state</module>

    <dependency>
        <groupId>com.pixflow</groupId>
        <artifactId>pixflow-state</artifactId>
        <version>${project.version}</version>
    </dependency>

在 `pixflow-state/pom.xml` 中，依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-infra-cache
    com.pixflow:pixflow-infra-storage
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    org.springframework.boot:spring-boot-configuration-processor optional
    org.springframework.boot:spring-boot-starter-test test scope

如果使用 ArchUnit 做边界测试，测试依赖增加：

    com.tngtech.archunit:archunit-junit5 test scope

在 `model/UnitKind.java` 中定义：

    public enum UnitKind {
        BRANCH,
        GROUP
    }

在 `model/UnitStatus.java` 中定义：

    public enum UnitStatus {
        PENDING,
        SUCCEEDED,
        FAILED
    }

在 `model/TaskRunStatus.java` 中定义：

    public enum TaskRunStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

在 `model/ProgressSource.java` 中定义：

    public enum ProgressSource {
        REDIS,
        MYSQL
    }

在 `model/UnitKey.java` 中定义不可变 record：

    public record UnitKey(
            String taskId,
            UnitKind kind,
            String memberId,
            String branchId) {

        public static UnitKey branch(String taskId, String imageId, String branchId);
        public static UnitKey group(String taskId, String groupKey, String branchId);
    }

`memberId` 在 `BRANCH` 时表示 `imageId`，在 `GROUP` 时表示 `groupKey`。构造期必须校验 `taskId`、`kind`、`memberId`、`branchId` 非空。

在 `model/CompletedUnits.java` 中定义：

    public record CompletedUnits(String taskId, Set<UnitKey> succeeded) {
        public boolean isDone(UnitKey unit);
        public int size();
    }

它只包含成功单元，不包含失败单元。

在 `model/ArtifactRef.java` 中定义：

    public record ArtifactRef(
            UnitKey unit,
            boolean completed,
            ObjectLocation location,
            Map<String, Object> meta) {
    }

`location` 来自 `com.pixflow.infra.storage.ObjectLocation`。`meta` 只放尺寸、格式等轻量元信息，不放图片字节。

在 `model/ProgressView.java` 中定义：

    public record ProgressView(
            int total,
            int done,
            int failed,
            ProgressSource source,
            PersistedProgress persisted,
            Long redisDone,
            long drift) {
    }

    public record PersistedProgress(int total, int succeeded, int failed) {
    }

`done` 是展示值。`source=REDIS` 时 `done` 取 Redis counter，`persisted` 保留 MySQL 权威计数，`drift=redisDone - persisted.succeeded`。`source=MYSQL` 时 `done` 取 `persisted.succeeded`，`redisDone` 可为空，`drift` 为 0。

在 `model/ExecutionStateSnapshot.java` 中定义：

    public record ExecutionStateSnapshot(
            String taskId,
            TaskRunStatus status,
            ProgressView progress,
            boolean cancelRequested,
            Instant snapshotAt) {
    }

在 `port/CheckpointReadPort.java` 中定义：

    public interface CheckpointReadPort {
        Optional<CompletedUnits> loadCompletedUnits(String taskId);
        Optional<PersistedCounts> loadCounts(String taskId);
        Optional<TaskRunStatus> loadTaskStatus(String taskId);
        List<String> listRunningTaskIds(int limit);

        record PersistedCounts(int total, int succeeded, int failed) {
        }
    }

`Optional.empty()` 表示 taskId 不存在。task 侧实现负责把 `process_result` 和 `process_task` 投影为 state 的中立模型。

在 `port/TaskRuntimeKeyPort.java` 中定义：

    public interface TaskRuntimeKeyPort {
        CacheKey progressKey(String taskId);
        CacheKey cancelKey(String taskId);
    }

它由后续 `module/task` 实现，state 不拼 Redis 业务 key。

在 `runtime/RunStateRefStore.java` 中定义：

    public interface RunStateRefStore {
        void putRef(CacheKey key, ArtifactRef ref, Duration ttl);
        Optional<ArtifactRef> getRef(CacheKey key);
        void deleteRef(CacheKey key);
    }

在 `runtime/ProgressReader.java` 中定义：

    public interface ProgressReader {
        ProgressView read(String taskId);
    }

在 `runtime/CancellationReader.java` 中定义：

    public interface CancellationReader {
        boolean isCancelRequested(String taskId);
        void throwIfCancelled(String taskId);
    }

在 `recovery/RecoveryCoordinator.java` 中定义：

    public interface RecoveryCoordinator {
        CompletedUnits resolveSkippable(String taskId);
    }

在 `query/ExecutionStateService.java` 中定义：

    public interface ExecutionStateService {
        ExecutionStateSnapshot snapshot(String taskId);
    }

在 `error/StateErrorCode.java` 中定义：

    public enum StateErrorCode implements ErrorCode {
        STATE_TASK_NOT_FOUND,
        STATE_TASK_CANCELLED
    }

`STATE_TASK_NOT_FOUND` 的 category 为 `NOT_FOUND`。`STATE_TASK_CANCELLED` 的 category 为 `BUSINESS_RULE`。

在 `config/StateProperties.java` 中绑定：

    pixflow.state.progress.prefer-redis=true
    pixflow.state.progress.drift-warn-threshold=5
    pixflow.state.recovery.running-scan-limit=200
    pixflow.state.snapshot.include-progress=true

在 `config/StateAutoConfiguration.java` 中装配：

    RunStateRefStore
    ProgressReader
    CancellationReader
    RecoveryCoordinator
    ExecutionStateService
    StateMetrics

自动装配必须尊重依赖端口是否存在。没有 `CheckpointReadPort` 或 `TaskRuntimeKeyPort` 时，不应装配会误导调用方的状态查询服务。

## Change Notes

2026-06-28 / Codex: 创建 `harness/state` 中文 ExecPlan 初稿。原因是 state 设计文档已经明确总体职责，但具体实现还需要补足 `TaskRuntimeKeyPort`、进度 drift 视图、checkpoint not-found 语义、首版不做字节回读、装配条件和测试验收方式，确保后续实现按生产级完整模块推进而不是按 MVP 临时方案落地。

2026-06-28 / Codex: 完成 `pixflow-state` 模块首版实现并更新进度。新增模型、端口、运行态引用 facade、进度读取、取消读取、恢复协调、状态快照、配置、自动装配和指标接口；使用内存 fake 补齐 state core 测试；运行 `mvn -pl pixflow-state -am test` 通过。后续真实数据库和 Redis key 接线仍由 `module/task`/`module/dag` 承担。
