# 以 Canonical DAG、Work Unit 与 Execution Epoch 重构图片任务执行链

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能完成图片处理、DAG 执行、任务执行与执行状态四个领域上下文的一次性重构。实施期间每个停止点都必须更新本文；改变接口、表结构、里程碑顺序或验收方式时，必须同步更新 `Progress`、`Decision Log` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，Agent 只负责产生用户可确认的 Image Plan，不再产生可直接执行的 Java 对象或松散参数 Map。服务端在确认边界把 Image Plan 校验并规范化成 Canonical DAG，把它作为唯一持久事实，再通过纯函数 `DagCompiler` 编译成 Typed Execution Plan。worker 启动或恢复时只从 Canonical DAG 确定性重建 Typed Execution Plan，任何像素工具缺少类型映射或真实执行绑定都会在确认期或启动期失败，而不是运行到一半才遇到 stub。

异步执行统一以 Work Unit 为最小调度和 checkpoint 粒度。普通图片支路、组支路和单次生成式图片操作各是一个 Work Unit；节点调用只是 Work Unit 内部的 Node Attempt，允许由拥有外部边界的模块做有限重试，但不形成节点 checkpoint。只有 MySQL 中当前 task 的 `process_result.status=SUCCESS` 才是 Work Unit Checkpoint。worker 崩溃后，新 Execution Epoch 跳过 SUCCESS，其余选中 Work Unit 整体重算。

同一 task 的执行互斥由 Redisson watchdog `RLock` 保证，MySQL `run_epoch` 只用来 fence 已失去所有权的 worker 写入。恢复扫描器只根据 MySQL heartbeat 发现 stale task 并重新投递消息，不读取锁状态、不修改 epoch、绝不 `forceUnlock`。全部所选 Work Unit SUCCESS 才是 `COMPLETED`；成功与失败或跳过混合为 `PARTIAL`；终态任务和结果不可重新打开。`PARTIAL` 或 `FAILED` 的失败项重试会创建 Derived Retry Task，来源 task 保持只读历史。

图片内存不再靠线程数间接限制。`infra/image` 提供 JVM 全局 Pixel Budget，在实际 decode 之前根据 probe 结果申请加权许可，许可覆盖 raster 全生命周期；线程池只负责 CPU/I/O 排队。实施完成后，可以通过单元测试、真实 MySQL/Redis/MinIO 故障注入和前端操作观察上述行为，而不是只看到代码类型改名。

本次是开发期一次性切换。不得为迁移安全保留旧 Java 类型、双写、旧 StorageKeys、旧 controller、兼容开关或 runtime fallback。允许保留的只有与本计划无关的历史文档；task/state/dag/image 的旧生产路径必须删除。开发数据库按新基线重建，不为旧 `process_task` / `process_result` 行编写数据迁移适配器。

## Progress

- [x] (2026-07-12) 阅读 `PLANS.md`、当前执行计划、四个领域 `CONTEXT.md`、三个 ADR、相关权威设计和现有实现，完成差距分析。
- [x] (2026-07-12) 创建本 ExecPlan，固定一次性替换策略、模块边界、实施顺序、设计检索关键词和验收场景。
- [x] (2026-07-12) 完成 Milestone 1 已落地部分的独立验证：storage 15、state 17、imagegen 83、task 8 项测试通过，相关模块主源码 reactor 编译通过，旧 state 类型与旧 StorageKeys 生产调用零命中。
- [x] (2026-07-13) Milestone 1：统一领域类型、数据库基线和对象存储 key，删除旧身份与路径模型。创建 normal task 时按冻结 `WorkUnitSelection` 在同一事务预建唯一 `unit_key` 的 PENDING 结果行；derived retry 将在 Milestone 6 复用该投影路径。
- [x] (2026-07-12) Milestone 2：实现 JVM 全局 Pixel Budget、无隐式全帧复制的 Raster 生命周期和 probe-before-decode 流水线。全局预算、受控 probe/decode、compose/resize 上界、显式 raster ownership、中间资源释放、跨 worker pool 共享和 encode 异常释放均已实现并通过定向测试。
- [x] (2026-07-12) Milestone 3：完成 Canonical DAG → Typed Execution Plan → Typed Branch 的唯一执行链。worker/conversation 只从 canonical JSON 重建计划，watermark/background 使用显式 typed binding，生产适配真实 storage/thirdparty/image pipeline；旧 raw-node 类型、dispatcher、mapper 与 stub 已删除。普通图片任务同时冻结 `WorkUnitSelection` 并预建 PENDING 结果行。
- [x] (2026-07-13) Milestone 4：完成 epoch-aware Runtime Reference 与只认 MySQL SUCCESS 的恢复读模型。Runtime Reference 强制同 epoch 命中、错 epoch 主动删 ref、24 小时 TTL 兜底；task adapter 仅投影显式 `unit_key` 的 SUCCESS，覆盖 BRANCH/GROUP/GENERATIVE，并正确区分 task 不存在与空 checkpoint。
- [ ] (2026-07-13) Milestone 5：LockGuard、Execution Epoch claim、epoch heartbeat、completion queue、owner-thread fenced result/terminal commit、stale heartbeat 重投和生成式 selection/PENDING 投影已落地；真实 Redis/MySQL 故障注入因当前 Docker engine 不可用尚未执行，里程碑保持未完成。
- [ ] Milestone 6：实现严格终态派生、Derived Retry Task、结构化 failure 与 retry-failed API。
- [ ] Milestone 7：对齐生成式图片路径和前端任务体验。
- [ ] Milestone 8：完成真实依赖故障注入、架构守护、旧代码清除和全仓验证。

## Surprises & Discoveries

- Observation: Milestone 1 的 PENDING 结果预建依赖完整 `WorkUnitSelection`，但该 selection 的唯一合法生产者 `Typed Execution Plan + task planning` 安排在 Milestone 3；当前 `CreateTaskCommand` 只有 payload、expectedCount 和 hash，确认边界没有成员快照或稳定 unit identity。
  Evidence: `CreateTaskCommand` 无 selection 字段，`CreateTaskServiceImpl` 固定写入 `[]`；本计划 Milestone 3 才要求创建 planning 组件并生成完整 selection。为避免引入随后删除的临时 JSON 格式，先实施独立的 Milestone 2 切片，Milestone 3 建立 planning 后立即回补 Milestone 1 的预建行。

- Observation: `module/vision` 直接持有一次性 `InputStream`，无法满足 image pipeline 的 probe 与 decode 分流；仅修改 image 接口会在干净 reactor 编译时暴露类型错误。
  Evidence: `mvn -pl pixflow-module-vision,pixflow-module-dag -am -DskipTests clean compile` 首次在 `VisionImagePreprocessor` 报 `InputStream` 无法转换为 `ReopenableImageSource`；改为 resolver 每次重开 ObjectStorage 流后，image 18 项、vision 37 项及其依赖测试通过。

- Observation: 初始实现的 `DefaultImageCodec.probe/decode` 曾通过 `readAllBytes` 无界物化输入，且 decode 会在内存字节上再次调用 probe；该缺口已在本轮切片修复。
  Evidence: 现 `probe` 直接使用 ImageIO `ImageInputStream`，`decode` 只使用受 `max-source-bytes` 限制的缓存，并在同一 reader 中读取元数据后解码。

- Observation: 将 `RasterImage` 从防御复制改为显式 ownership 后，DAG 旧测试仍假设 close 后可继续访问 buffer；该断言与新生命周期契约冲突。
  Evidence: `BranchExecutionContextTest` 原先在 `onDispose` 后调用 `raster.width()`，改为断言 `IllegalStateException`；image 22 项与 DAG 20 项定向测试通过。

- Observation: 全仓回归在进入 image 模块前被既有 eval 测试阻断，与本次改动无关。
  Evidence: `mvn test` 在 `pixflow-eval` 的 `TraceRecorderTest.externalizesLargePayloadAfterSanitizingAndReplaysIt` 抛出 `NoSuchElementException`，其余后续模块未执行；image 22 项、DAG 20 项和 vision 37 项定向/依赖测试均通过。

- Observation: 审查发现 codec 的 EXIF/sRGB 转换和 resize FILL 的临时栅格不属于最终 `RasterImage`，必须显式 flush；probe 的受控流也必须覆盖读取上限，而不是只限制 decode 缓存。
  Evidence: 已在 `DefaultImageCodec` 增加 `BoundedInputStream` 并释放 decoded/oriented 中间图，在 `ResizeOp.FILL` finally 中 flush scaled；image 22 项测试继续通过。

- Observation: 计划原文使用 `WeightedPixelBudget` 名称，而实现使用 `DefaultPixelBudget`；两者语义相同，当前实现按 weighted pixel 数量提供全局单例许可。
  Evidence: `DefaultPixelBudget` 的 API 和自动配置均以 `PixelBudget.Permit.weightedPixels()` 为边界；未引入第二个并行实现，避免仅为命名增加兼容层。后续可在统一命名时直接重命名，不保留 deprecated wrapper。

- Observation: 单图预算此前只按源像素乘 headroom，无法反映显式 resize 的目标栅格；现已从 `ResizeOp` typed spec 读取 width/height，并与源 raster 上界取最大值。两个独立 executor pool 共享同一 `PixelBudget` 的测试确认许可不会超发。
  Evidence: `DefaultPixelBudgetTest.twoWorkerPoolsShareOneGlobalCapacity` 与 image 模块 23 项测试通过。

- Observation: 当前 `SpecMapper` 已能为多数本地工具生成 typed spec，可先作为 `DefaultDagCompiler` 的映射依赖建立纵向切片；但 watermark 仍返回 null，不能据此删除旧路径或宣称 binding 完整。
  Evidence: `DefaultDagCompilerTest` 证明 RESIZE 编译为携带 `ResizeSpec` 的 `LocalImageStep`、REMOVE_BG 编译为 `ExternalStep`；测试 1 项通过。

- Observation: Canonical DAG 必须消除节点/边输入顺序和参数 key 顺序差异，否则相同提案会得到不同 hash；factory 统一按 node id、edge from/to 与参数 key 排序。
  Evidence: `CanonicalDagFactoryTest.equivalentDagOrderProducesSameCanonicalHash`、binding/compiler/facade 共 7 项测试通过。

- Observation: 首版 factory 只对顶层 params 使用 `TreeMap`，无法覆盖嵌套 map 与数字规范化；仓库已有 `CanonicalJson` 才是共享的唯一规范化函数。
  Evidence: 审查后 `CanonicalDagFactory` 改为调用 `CanonicalJson.canonicalize`；watermark 改为非空 `WatermarkBindingSpec`，非法图片格式不再静默回退 JPEG。7 项定向测试继续通过。

- Observation: Milestone 1 触达模块的独立测试全部通过，但包含 task 全依赖的聚合测试在进入 task 前被 DAG 中两个与本次身份切换无关的旧测试阻断；新增 `CONFIRMING` 状态后，枚举数量断言和确认期 CAS mock 尚未同步。
  Evidence: `PendingPlanMapperTest.pendingPlanStatus_enumValues` 仍期望 4 个状态，实际为 5 个；`PendingPlanServiceTest.confirm_marksPending_asConfirmed` 未模拟 `startConfirmation` 的条件更新，抛出 `pending_plan 状态竞争: id=1`。同次运行中 imagegen 83 项测试已通过，随后单独执行 task 8 项测试通过。

- Observation: Maven 增量编译曾因工作区时间戳判断所有模块 “Nothing to compile”，不能作为本次类型替换的有效证据；执行 `clean compile` 后才确认 16 个相关 reactor 模块真实重编译通过。
  Evidence: `mvn -pl pixflow-infra-storage,pixflow-state,pixflow-module-task -am -DskipTests clean compile` 显示各模块 `Recompiling`，最终 `BUILD SUCCESS`。

- Observation: 生成式执行原先把字符串 task/image identity 折算为 long 后写旧扁平 key，无法携带 execution epoch。为满足 Milestone 1 删除旧 StorageKeys 的硬约束，`GenerativeUnitSpec` 已提前接收 `unitKeyHash/runEpoch`；后续 Milestone 7 只需继续收敛 owner-thread fenced commit，不再迁移对象路径。
  Evidence: `GenerativeUnitSpec`、`DefaultImageGenExecutor` 与 `DefaultImageGenExecutorTest` 已使用 `results/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.ext`。

- Observation: Milestone 3 开始时 DAG 仍以 raw node 驱动执行，dispatcher 和自动配置中存在运行期 stub；这些路径已在本里程碑一次性删除。
  Evidence: 当前生产源码旧类型/stub 搜索零命中，执行器由 typed step 和真实 infra adapter 驱动。

- Observation: raw-node 切换后，`remove_bg` 与 `generate_copy` 最初仍把参数保存为 `Map`，而 `set_background.backgroundImage` 会在普通 `SetBackgroundSpec` 中丢失对象引用；只改容器类型并不能形成强类型执行边界。
  Evidence: 现分别编译为 `BackgroundRemovalBindingSpec`、`CopyBindingSpec`、`SetBackgroundBindingSpec` 与 `WatermarkBindingSpec`；生产源码搜索旧类型、stub 和 typed-step Map 零命中。

- Observation: 单模块 Maven 测试会从本地仓库解析旧版 `pixflow-infra-image`，导致 `RasterImage.takeOwnership` 的 `NoSuchMethodError`；reactor `-am` 构建使用当前源码，不存在该错误。
  Evidence: `mvn -pl pixflow-module-dag -am -Dtest=PipelineUnitExecutorTest '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过 7 项，app 30 模块 reactor 编译成功。

- Observation: Group Unit 的纯本地像素链在一次 `ImagePipeline.runComposed` 调用内完成，不应为缓存额外 encode；只有外部字节型成员预处理天然产生可复用的中间 bytes。
  Evidence: `GroupUnitExecutor` 对纯本地成员继续传递 `ReopenableImageSource`；包含 external step 时经 `GroupRuntimeArtifactStore` 按当前 unit/epoch/member 写 `StorageKeys.runtimeGroup`、再发布 Runtime Reference，并在 compose 或失败退出时主动删除 ref/TMP。

- Observation: `CheckpointReadPortImpl.loadSkippableWorkUnits` 原先不先查父 task，导致不存在的 task 与“存在但尚无 SUCCESS”的 task 都返回空集合。
  Evidence: adapter 现先用 `ProcessTaskMapper.selectById` 区分 missing，新增测试覆盖 missing task 以及 BRANCH/GROUP/GENERATIVE 三类 SUCCESS；state reactor 与 task 定向测试通过。

- Observation: 当前 task 表没有 `unit_key`、`unit_selection_json`、`retry_of_task_id` 或 `run_epoch`；worker 只要查到任意同成员/branch 结果就跳过，因此 FAILED/SKIPPED 也会被误当成 checkpoint。
  Evidence: `pixflow-module-task/src/main/resources/db/migration/V1__create_process_task_tables.sql` 与 `internal/worker/TaskWorker.java#runLocked`。

- Observation: 当前 Work Unit 线程直接插入 `process_result`，而 Redisson `RLock` 属于 owner thread。若照设计在这些线程里调用 `isHeldByCurrentThread()`，结果必然为 false；必须让线程池只返回计算结果，由持锁 owner thread 完成数据库提交和事件发布。
  Evidence: `ProcessWorker.execute`、`ImageGenWorker.execute` 在 scheduler 线程调用 mapper；`TaskLockManager` 的锁在 `TaskWorker.handle` 调用线程获取。

- Observation: 当前 heartbeat、结果写和终态写均无 epoch 条件；`markTerminal` 甚至没有 status 条件。旧 worker 可以覆盖新 owner 或终态。
  Evidence: `ProcessTaskMapper.markRunning/heartbeat/markTerminal` 与 `ProcessResultMapper`。

- Observation: 当前终态判定把 `success > 0 && failed > 0` 判为 `COMPLETED`，与权威设计相反。
  Evidence: `TerminalStateJudge.judge` 的 failed/success 分支。

- Observation: state 仍使用 `CompletedUnits` 和带 `completed` 布尔值的 `ArtifactRef`；Runtime Reference 没有 epoch，checkpoint adapter 还过滤掉 GENERATIVE SUCCESS。
  Evidence: `pixflow-state/model/CompletedUnits.java`、`ArtifactRef.java`、`runtime/RunStateRefStore.java` 和 task 的 `CheckpointReadPortImpl`。

- Observation: 当前 `RasterImage` 在构造和 `buffer()` 时都复制整帧，pipeline 没有 Pixel Budget，`probe`/`decode` 又会把输入完整读入 byte array。
  Evidence: `pixflow-infra-image/RasterImage.java`、`pipeline/DefaultImagePipeline.java`、`impl/DefaultImageCodec.java`。

- Observation: 当前 StorageKeys 使用 sku/image/branch 旧路径，生成式 key 不含 Work Unit hash 或 epoch；对象存在无法和当前 epoch SUCCESS 对齐。
  Evidence: `pixflow-infra-storage/StorageKeys.java` 与 `DefaultImageGenExecutor.redraw`。

- Observation: 前端已经有 `PARTIAL`、`skipped` 和 `occurredAt` 的部分类型，但没有 `retryFailedTask`，任务详情仍显示“即将上线”，且页面调用 `cancelTask` 时漏传 conversationId，`CANCELLED` 也被错误允许重试。
  Evidence: `pixflow-web/src/api/tasks.ts`、`pages/TaskDetailPage.vue`、`components/tasks/TaskDetailHeader.vue`。

- Observation: `docs/design-docs/infra/cache.md` §五仍写“本期不引入 fencing token”，与已接受的 ADR 0002 以及 task/state 权威设计冲突。
  Evidence: 搜索 `本期不引入 fencing token`、`run_epoch`；本计划以 `docs/adr/0002-redisson-lock-with-execution-fencing.md` 为裁决，并要求实施时删除该旧表述。这里的 epoch 是 MySQL ownership generation，不是 Redis lock token。

- Observation: 首版 Milestone 5 切片按 submission order 等待 future，且结果 SQL 只检查 `status <> SUCCESS`，会让慢首项阻塞已完成项 checkpoint，并允许同一 epoch 重写 FAILED/SKIPPED。
  Evidence: 双轴审查后 `TaskWorker` 改为 completion queue + 整体 deadline，`ProcessResultMapper.commitForEpoch` 增加 `r.run_epoch < incoming epoch`；新增测试覆盖同 epoch no-op。

- Observation: SUCCESS fenced commit 若只信任 capability 返回的 key，可能公开错误 epoch 或尚不存在的对象；同时在 MinIO I/O 期间持有父 task 行锁会扩大数据库临界区。
  Evidence: `WorkUnitResultRepository` 现在先校验 key 的 task/unit hash/epoch 前缀和 `ObjectStorage.exists`，随后在事务内 `lockRunningEpoch(... FOR UPDATE)` 再执行条件更新。

- Observation: 当前环境无法连接 Docker engine，真实 Redis/MySQL 故障注入不能执行，Testcontainers 用例会明确 SKIPPED。
  Evidence: `docker info` 超时；`ExecutionFencingIntegrationTest` 启动时 Testcontainers 报 `Could not find a valid Docker environment`，1 项 skipped。测试源码已覆盖 MySQL 高 epoch fencing/SUCCESS 不可覆盖，cache 集成测试已增加重复 owner 与 Redis 不可用 fail-closed，但不能把 skipped 记为通过。

## Decision Log

- Decision: `UnitKeyCodec` 使用版本化的 `v1|kind|base64url(memberId)|base64url(branchId)` 格式，编码中不重复包含 taskId；对象路径摘要为该编码的 SHA-256 小写十六进制。
  Rationale: 分隔符不能与外部 member/branch 标识冲突，版本前缀允许未来显式拒绝未知格式；taskId 已由结果表复合唯一键隔离，排除它才能让 derived task 复制来源 selection 后仅替换新的父 taskId。
  Date/Author: 2026-07-12 / Codex

- Decision: 采用一次性 schema 和代码切换，不做兼容迁移。直接把 task 的 V1/V2 开发期 migration 合并为目标基线，删除被折叠的旧 migration，并重建本地开发数据库。
  Rationale: 项目仍在开发阶段，用户明确要求不要为迁移式安全保留旧代码。让新库先建旧表再 ALTER、让 Java 同时理解两套身份，只会把错误语义继续带入系统。
  Date/Author: 2026-07-12 / Codex

- Decision: `process_task.dag_json` 对 IMAGE_PROCESS 只保存 Canonical DAG；Typed Execution Plan 是可重建的派生值，不新增持久化表或 JSON 列。
  Rationale: 一个执行事实源可避免 Canonical DAG 与序列化 typed plan 漂移；编译器是纯函数，恢复时重建成本低。
  Date/Author: 2026-07-12 / Codex

- Decision: `unit_selection_json` 保存创建时冻结的 Work Unit Selection，包括稳定 identity 和执行所需的成员/源对象快照；worker 重建后必须与 selection 精确比对。Derived Retry Task 复制来源 FAILED selection 条目，不重新观察当前素材包后选择。
  Rationale: task 创建后的素材变化不能悄悄改变执行集合或重试内容；重试必须对来源失败事实负责。
  Date/Author: 2026-07-12 / Codex

- Decision: Work Unit worker pool 只执行纯能力并返回 `WorkUnitCompletion`；持有 RLock 的 task owner thread 逐个消费 completion，检查 lock ownership，然后执行带 `run_epoch` 条件的结果写、进度更新和事件发布。
  Rationale: Redisson RLock 有线程归属。把提交留在子线程无法满足 `isHeldByCurrentThread()`，而只依赖 epoch 又丢掉设计要求的第一层失锁拦截。
  Date/Author: 2026-07-12 / Codex

- Decision: `LockTemplate` 直接替换为向 callback 提供只读 `LockGuard` 的接口；guard 只暴露 `isHeldByCurrentThread/assertHeld`，不暴露 `RLock` 或 unlock。旧 overload 不保留。
  Rationale: task owner 需要在每次提交前验证锁仍属于当前线程，同时 infra/cache 仍应集中 try/finally 解锁，避免上层误解他人锁。
  Date/Author: 2026-07-12 / Codex

- Decision: `run_epoch` 是单调 ownership generation，不是 retry 次数、lock token 或业务状态。取得 RLock 后才能通过单条 SQL claim 新 epoch；所有 heartbeat、非 SUCCESS 结果变更和终态写都带 taskId/status/epoch 条件。
  Rationale: RLock 解决同时执行，epoch 解决失锁后旧 worker 的迟到写；二者缺一不可。
  Date/Author: 2026-07-12 / Codex

- Decision: Work Unit SUCCESS 永久不可覆盖。FAILED/SKIPPED/PENDING/RUNNING 仅在父 task 仍 RUNNING 且 incoming epoch 更高时可进入新 Execution Attempt。
  Rationale: SUCCESS 是唯一 checkpoint；允许覆盖会破坏恢复跳过和结果对象可见性。
  Date/Author: 2026-07-12 / Codex

- Decision: Pixel Budget 在 `infra/image` 内作为 JVM 单例加权许可池实现；task scheduler 不预占预算，image pipeline 在 probe 后、decode 前申请，并用 try-with-resources 覆盖所有 raster。
  Rationale: 只有 image 知道解码尺寸和目标画布上界。线程池并发与栅格内存占用不是同一个资源维度。
  Date/Author: 2026-07-12 / Codex

## Outcomes & Retrospective

Milestone 1 已完成领域身份、数据库目标基线、state checkpoint/runtime reference 类型和对象存储路径的主体切换。可观察证据为 storage 15、state 17、imagegen 83、task 8 项测试通过，且相关模块主源码 reactor 编译成功。旧 `CompletedUnits`、旧 `ArtifactRef` 和旧 StorageKeys 生产路径已删除；生成式输出现在按 task、Work Unit hash 和 execution epoch 寻址。

Milestone 1 尚未完成 normal/derived task 按冻结 `unit_selection_json` 预建 PENDING 结果行，因此 Progress 保持未完成；该项将在 Milestone 3 建立唯一 planning/selection 格式后回补。后续仍须以恢复、失锁、终态和内存准入行为测试作为验收，不能用当前编译与类型测试替代。

Milestone 2 已交付首个可运行切片：`PixelBudget` 是由 image auto-configuration 创建的 JVM 单例加权许可池，单图和组图在任何 decode 前完成 probe、尺寸校验和一次性预算申请，许可覆盖到 encode 并由 try-with-resources 在异常路径释放。vision 已直接迁移到可重开来源。当前仍未完成 `RasterImage` 的零复制引用生命周期，因此 Milestone 2 保持未完成。

Milestone 4 已完成恢复读模型收敛。state 不从对象或 Redis 引用推断 checkpoint；task adapter 只查询 SUCCESS 并从显式 `unit_key` 解码全部 Work Unit 类型。Runtime Reference 携带正数 run epoch，仅同 epoch 可见，错 epoch 主动删 ref；dag 的 Group Runtime Artifact Store 先写 TMP 再写 ref，调用方设置 24 小时兜底 TTL，并在单元退出时主动删 ref/TMP。state、DAG 与 task 定向 reactor 测试通过。

## Context and Orientation

PixFlow 有四个与本次重构直接相关的领域上下文。`pixflow-infra-image` 是图片处理上下文，只做本地确定性编解码和像素操作；它不知道 DAG、task 或 storage。`pixflow-module-dag` 是 DAG 执行上下文，把用户确认的 Image Plan 变成 Canonical DAG，再编译和展开成可独立执行的 branch。`pixflow-module-task` 是任务执行上下文，拥有 MQ、线程池、Work Unit、结果写、进度、终态和 Derived Retry Task。`pixflow-state` 是执行状态上下文，只提供 checkpoint、恢复和进度的读模型，不拥有 task 写入。

Image Plan 是 Agent 生成并由用户确认的 DAG 提案。Canonical DAG 是经过校验、规范化和 hash 后的唯一持久执行事实。Typed Execution Plan 是服务端从 Canonical DAG 编译出的强类型步骤和依赖边。Branch 是 DAG 中稳定的 source-to-sink 路径；普通 branch 作用于一张图片，Group Branch 先逐成员处理、再合成、再做后处理。

Work Unit 是最小独立调度和 checkpoint 单位，包含普通 branch、Group Branch 或一个生成式重绘。Work Unit Identity 在 task 内由 `kind + memberId + deterministicBranchId` 唯一确定，持久化为 `unit_key`；state 的 `UnitKey` 再带上 taskId。Execution Attempt 是某 Work Unit 在一个 Execution Epoch 下的一次执行。Node Attempt 是 Work Unit 内对 provider/外部边界的一次尝试，不是 checkpoint。

Execution Epoch 是 `process_task.run_epoch` 的单调所有权代数。Runtime Reference 是 Redis 中指向 MinIO 临时对象的当前 epoch 引用，可丢、可重算、不可证明完成。Work Unit Checkpoint 只表示 MySQL SUCCESS 这一持久事实。Pixel Budget 是 JVM 内所有 image pipeline 共享的加权像素许可池，与 task 线程池独立。

主要源码位置如下。DAG 代码在 `pixflow-module-dag/src/main/java/com/pixflow/module/dag/`；task 编排和持久化在 `pixflow-module-task/src/main/java/com/pixflow/module/task/`；state 读模型在 `pixflow-state/src/main/java/com/pixflow/harness/state/`；图片处理在 `pixflow-infra-image/src/main/java/com/pixflow/infra/image/`；对象 key 在 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/StorageKeys.java`；生成式执行在 `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/exec/`；确认入口在 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConfirmationService.java`；task REST 在 `pixflow-app/src/main/java/com/pixflow/app/task/`；前端在 `pixflow-web/src/`。

当前进行中的 `docs/design-docs/exec-plans/rubrics-criterion-verdict-refactor-plan.md` 会读取 task 的公开结果和 `TaskCompletedEvent`，但不应写 task 状态。本计划修改 `TaskResultView` 与事件语义时必须同步保持 Rubrics 只依赖 task public API；不得让 Rubrics 依赖 `infra.persistence`，也不得让 Rubrics 失败影响 task 终态。

## Design Reference Keywords

实施前先读根 `CONTEXT-MAP.md`，再用以下关键词在权威文档中定位，不要靠旧类名猜设计。

- `pixflow-module-dag/CONTEXT.md`：搜索 `Image Plan`、`Canonical DAG`、`Typed Execution Plan`、`Branch`、`Group Branch`、`Node Attempt`。
- `docs/design-docs/module/dag.md`：搜索 `CanonicalDag`、`DagCompiler`、`StepBindingRegistry`、`Canonical Form`、`Work Unit Identity`、`确定性单元执行器`、`Runtime Reference`。
- `pixflow-module-task/CONTEXT.md`：搜索 `Work Unit`、`Execution Attempt`、`Partial Task`、`Derived Retry Task`、`Execution Epoch`。
- `docs/design-docs/module/task.md`：搜索 `TaskWorker ownership wrapper`、`双重写前检查`、`Redisson 锁与 execution epoch`、`process_result.status 迁移表`、`Derived Retry Task`、`retryFailed`、`forceUnlock`、`TerminalStateJudge`。
- `pixflow-state/CONTEXT.md`：搜索 `Work Unit Checkpoint`、`Skippable Work Units`、`Runtime Reference`。
- `docs/design-docs/harness/state.md`：搜索 `MySQL SUCCESS 是唯一 Work Unit Checkpoint`、`CheckpointReadPort`、`SkippableWorkUnits`、`RuntimeArtifactRef`、`RecoveryCoordinator`、`结果对象后写 checkpoint`。
- `pixflow-infra-image/CONTEXT.md`：搜索 `Local Pixel Operation`、`Pixel Pipeline`、`Composed Pipeline`、`Pixel Budget`。
- `docs/design-docs/infra/image.md`：搜索 `JVM 全局 Pixel Budget`、`probe`、`ReopenableImageSource`、`无隐式全帧复制`、`retain/close`、`runComposed`。
- `docs/design-docs/infra/storage.md`：搜索 `resultUnit`、`generatedUnit`、`runtimeGroup`、`仅 fenced SUCCESS row 引用后可见`、`生命周期与 tmp 清理`。
- `docs/design-docs/infra/cache.md`：搜索 `分布式锁语义（看门狗）`、`安全释放优先`、`Redis 存引用，字节落 MinIO`、`run_epoch`。遇到“本期不引入 fencing token”按 ADR 0002 删除，不据此实现。
- `docs/design-docs/module/imagegen.md`：搜索 `生成式单元`、`StorageKeys.generatedUnit`、`runEpoch`、`断点恢复、幂等与失败隔离`。
- `docs/design-docs/frontend/tasks.md` 与 `docs/design-docs/frontend/api.md`：搜索 `PARTIAL`、`retry-failed`、`retryOfTaskId`、`selectedUnitCount`、`failure`、`occurredAt`。
- `docs/adr/0001-work-unit-checkpoints.md`：搜索标题 `Use work-unit checkpoints instead of node checkpoints`。
- `docs/adr/0002-redisson-lock-with-execution-fencing.md`：搜索 `RLock`、`execution epoch`、`fences`、`fails closed`。
- `docs/adr/0003-terminal-tasks-use-derived-retries.md`：搜索 `Derived Retry Task`、`terminal`、`immutable history`。

## Plan of Work

### Milestone 1：统一领域类型、目标 schema 与对象路径

先建立后续所有模块共享的身份和存储事实。直接重写 `pixflow-module-task/src/main/resources/db/migration/V1__create_process_task_tables.sql` 为目标基线，把现有 gallery 字段并入 V1 后删除 `V2__process_result_gallery_fields.sql`。`process_task` 新增 `unit_selection_json MEDIUMTEXT NOT NULL`、`retry_of_task_id BIGINT NULL`、`run_epoch BIGINT NOT NULL DEFAULT 0`，保留 MySQL heartbeat、worker 和终态时间；删除把 attempt 当 ownership 的旧字段。`process_result` 新增 `unit_key VARCHAR(...) NOT NULL`、`run_epoch BIGINT NOT NULL` 和结构化 final failure 列，唯一键只保留 `UNIQUE(task_id, unit_key)`；`image_id/group_key/branch_id` 只是查询投影。正常 task 与 derived task 都预建 PENDING 结果行。

在 state 中把 `CompletedUnits` 直接替换为 `SkippableWorkUnits`，把 `ArtifactRef(boolean completed, ...)` 直接替换为 `RuntimeArtifactRef(UnitKey, runEpoch, location, meta)`，给 `UnitKey` 增加 `generative(...)`。task 的 Work Unit 直接持有同一个 `UnitKey`，删除 task 内重复且会漂移的 identity 拼接逻辑；以单一 `UnitKeyCodec` 生成持久化 `unit_key` 和 `sha256(unitKey)`。

直接替换 `StorageKeys.result/groupResult/generated/tmpBranch/tmpGroup` 为 `resultUnit(taskId, unitKeyHash, runEpoch, ext)`、`generatedUnit(...)` 和 `runtimeGroup(...)`。更新测试断言 RESULTS/GENERATED 都按 task/unit/epoch 寻址，TMP 只保存当前 epoch 组成员对象。删掉所有旧方法和调用点，不留 deprecated overload。

本里程碑结束时，领域类型、MySQL identity 和 MinIO identity 使用同一 Work Unit 口径。运行 state/storage/task 的纯类型与 migration 测试；用 `rg` 确认旧类型和旧 key 方法零命中。

### Milestone 2：实现 Pixel Budget 与 Raster 生命周期

在 `pixflow-infra-image` 新增 `PixelBudget`、`WeightedPixelBudget` 和 `PixelBudget.Permit`。配置 `pixflow.image.pixel-budget.max-in-flight-pixels/acquire-timeout/target-headroom-factor`，由 `ImageAutoConfiguration` 装配为 JVM 单例并注入唯一 `ImagePipeline`。单图先 probe，计算源 raster 加目标上界后 acquire；组链先 probe 全部成员，计算成员总 raster、compose 画布和临时 buffer 上界，一次 acquire 成功后才 decode 任一成员。超过总预算抛 `PIXEL_BUDGET_EXCEEDED`，等待超时抛 `PIXEL_BUDGET_TIMEOUT`，image 内不重试。

把 `ImagePipeline` 入参直接替换为 `ReopenableImageSource`；`probe` 和 `decode` 各自打开受控流。重构 `DefaultImageCodec`，probe 不再无上限 `readAllBytes`，decode 不再为调用 probe 再复制整份输入。EXIF 读取如确需有限字节，必须受 source bytes 上限约束。

把 `RasterImage` 直接替换为 `AutoCloseable` 独占/显式 retain 句柄。`takeOwnership` 和 `borrowBuffer` 不复制整帧；每个 ImageOp 产出新句柄后立即 close 上游，异常、取消和 encode 路径均释放。删除 `RasterImage.of` 和复制式 `buffer()`，同步修改所有 op 使用显式借用。`DefaultImagePipeline.run/runComposed` 用 try-with-resources 管 permit 和所有 raster。

本里程碑验收必须证明两个不同 task worker pool 共用同一个预算、等待 permit 时 decode 调用数为零、成功/异常/取消都释放许可、组链在任何 decode 前完成整体准入，以及多步 pipeline 只 decode/encode 一次。

### Milestone 3：建立服务端 Typed Execution Plan

在 `pixflow-module-dag` 新增 `CanonicalDag`、`DagCompiler`、`TypedExecutionPlan`、sealed `ExecutionStep` 与 `StepBindingRegistry`。`DagValidator` 成功后由 `CanonicalJson` 生成唯一 bytes/hash/schemaVersion；`pending_plan.dag_json` 和 `process_task.dag_json` 保存 Canonical DAG，不保存 Agent 原始 JSON 或 Java record 序列化结果。确认边界 `ConfirmationService` 再校验、canonicalize、compile 和 group preflight 后才能创建 task。

`DagCompiler` 在编译期把所有 raw params 映射成 `ExternalStep`、`LocalImageStep`、`GroupStep` 或 `CopyStep` 的强类型 spec，并填充默认值。`StepBindingRegistry` 对 `PixelTool` 全集验证恰好一个 mapper 和真实 executor binding；缺失、重复或 stub 使应用启动或确认失败。`BranchExpander` 和 `GroupPreflight` 改为只接收 Typed Execution Plan，`ExecutableBranch` 只包含 typed steps，不再包含 `DagNode` 或 Map。

删除 `ValidatedDag`、`NodeDispatcher`、`SpecMapper` 和 `DagAutoConfiguration` 中全部 `UnsupportedOperationException` stub。`PipelineUnitExecutor` / `GroupUnitExecutor` 直接组合真实 `ObjectStorage`、thirdparty client、`ImagePipeline` 和 `StorageKeys.resultUnit/runtimeGroup`；`CopyUnitExecutor` 消费 typed CopyStep。AutoConfiguration 缺少必需依赖时 fail-fast，测试显式注入 fake，不通过生产 stub 让上下文假成功。

创建 task 前由 task planning 组件用 Typed Execution Plan 和素材成员快照产生完整 `WorkUnitSelection`；worker 只从 Canonical DAG 重建、展开并与冻结 selection 比对，不再反序列化 `ValidatedDag`。相同 canonical input 多次编译/展开必须得到相同 steps、branchId 和 unit identity。

### Milestone 4：收敛 checkpoint 与 Runtime Reference

重写 state 的 `CheckpointReadPort` 为 `loadSkippableWorkUnits`、`loadCounts`、`loadTaskStatus`，task adapter 只查询所有 `process_result.status=SUCCESS` 的显式 `unit_key`，包括 BRANCH、GROUP 和 GENERATIVE。`RecoveryCoordinator.resolveSkippable` 只返回该集合；不存在根据对象、Redis ref、FAILED 行或 `completed` 布尔值推断完成的分支。

`RunStateRefStore.getRef(key, expectedRunEpoch)` 只返回 epoch 完全相同的 `RuntimeArtifactRef`；不同 epoch 视为 miss 并安排清理。Redis 只保存 ObjectLocation 和轻量 metadata，字节先写 `StorageKeys.runtimeGroup` 的 TMP 对象。Group Branch 完成或 epoch 结束主动删 ref/TMP，24 小时生命周期兜底。普通 branch 不写 Runtime Reference。

同步修正 `docs/design-docs/infra/cache.md` 中拒绝 fencing 的旧句子，明确 cache 只提供 watchdog lock/guard 机制，task 拥有 lock key，MySQL 拥有 epoch。state 的架构测试继续禁止 MyBatis、Redisson、MinioClient 和 task persistence 类型进入 state。

### Milestone 5：实现 task ownership、fenced 提交与恢复

直接替换 `LockTemplate` callback 形状，使其传入只读 `LockGuard`。`TaskLockManager.tryRunWithTaskLock` 把 guard 交给 `TaskWorker` 的 owner scope。取得 RLock 后，`ProcessTaskMapper.claimExecution` 用单条 SQL 将 `QUEUED` 或 stale `RUNNING` claim 为 RUNNING、`run_epoch=run_epoch+1`、写 worker/heartbeat 并返回新的 `ExecutionRun(taskId, epoch, guard)`。Redis 不可用或拿不到锁时 fail-closed，不 claim epoch。

启动一个使用 task 专属 scheduler 的 heartbeat loop，更新条件固定为 `id=? AND status=RUNNING AND run_epoch=?`。更新 0 行时通知 owner 停止提交新 Work Unit，并放弃终态。finally 先停 heartbeat，再由 cache 模板仅在当前线程仍持锁时 unlock。

重构 `WorkUnitScheduler`、`ProcessWorker`、`ImageGenWorker` 和 `FailureIsolator`。scheduler 子线程只执行 capability 并返回 `WorkUnitCompletion`，其中是 SUCCESS artifact、FAILED final failure 或 SKIPPED；它们不调用 mapper、进度或 event publisher。owner thread 用 completion queue 持续消费结果，每次先 `guard.assertHeld()`，再调用专用 repository transaction：锁父 task 当前 epoch；SUCCESS 先已有 epoch 对象后 fenced 提交；FAILED/SKIPPED 写同一 `unit_key/run_epoch`；现有 SUCCESS 永远 no-op；提交成功后才更新 Redis progress 和发 `ProgressEvent`。

恢复扫描器只执行 `SELECT RUNNING WHERE heartbeat_at < staleBefore` 并幂等重新投递 TaskMessage。扫描器不改 status/epoch、不获取或释放 RLock、不 `forceUnlock`。新 owner 重建全部 selection，调用 `RecoveryCoordinator` 跳过 SUCCESS，对 PENDING/RUNNING/FAILED/SKIPPED 以新 epoch 整 Work Unit 重算。对象先写 epoch key，再提交 SUCCESS；stale DB 写失败留下的孤儿对象只能由生命周期清理，绝不对外公开。

本里程碑必须用真实 Redis/MySQL 故障注入验证：重复消息只一个 owner；旧 owner 失锁后不能提交；新 owner claim 后旧 epoch SQL 更新 0 行；heartbeat stale 只重投；Redis 不可用不会绕过锁；恢复保留 SUCCESS 并重算其他单元。

### Milestone 6：实现终态、Derived Retry Task 与公开 API

把 `TerminalStateJudge` 改成唯一纯聚合规则：`success == total` 为 COMPLETED；`success == 0 && failed > 0` 为 FAILED；`success > 0 && failed + skipped > 0` 为 PARTIAL；`success == 0 && skipped == total` 为 CANCELLED。终态 transaction 按当前 epoch 聚合并执行 `UPDATE ... WHERE status=RUNNING AND run_epoch=?`；只有更新 1 行才发布 `TaskCompletedEvent`。所有 task 终态、所有终态 task 的结果行和所有 SUCCESS 行不可再迁移。

扩展 `TaskCommandService.retryFailed(sourceTaskId, idempotencyKey)`。在一个 MySQL transaction 中锁定 PARTIAL/FAILED 来源 task，读取其 FAILED `unit_key`，从来源 `unit_selection_json` 复制对应 selection，创建 `retry_of_task_id=source` 的新 task 和 PENDING 结果行，然后提交并入队。请求不接受客户端 result/node 列表；没有 FAILED 返回 `TASK_NO_FAILED_UNITS`，非 PARTIAL/FAILED 返回 `TASK_RETRY_SOURCE_NOT_TERMINAL`。重复 Idempotency-Key 返回同一 derived task，来源 task 和对象永不修改。

在 `pixflow-app` 新增 `POST /api/tasks/{taskId}/retry-failed` controller，要求 `Idempotency-Key` header，返回 `taskId/retryOfTaskId/selectedUnitCount/status`。扩展 `TaskStatusView.retryOfTaskId`；扩展 `TaskResultView.failure` 为结构化 final failure，字段包含 code/category/recovery/failedNodeId/failedTool/attemptCount/safeMessage/details。它只描述 owning boundary retry 耗尽后的最终故障，不提供节点 history/checkpoint。

### Milestone 7：对齐 imagegen 与前端

把 `GenerativeUnitSpec` 直接替换为包含 `unitKeyHash` 和 `runEpoch` 的目标 record，`DefaultImageGenExecutor` 只写 `StorageKeys.generatedUnit`。ImageGen worker 与 DAG worker 一样只返回 completion，由 task owner fenced 提交；恢复只跳过 GENERATIVE SUCCESS。生成式任务同样遵守 COMPLETED/PARTIAL/FAILED/CANCELLED 与 Derived Retry Task 规则。

在 `pixflow-web/src/api/tasks.ts` 增加 `retryFailedTask`、`RetryTaskResponse`、`retryOfTaskId` 和结构化 failure 类型。`TaskDetailPage.vue` 真正加载 status/results，retry 时生成新的 idempotency key、调用 API、把 derived task 加入 store 并跳转；来源状态保持终态。按钮只对 PARTIAL/FAILED 且 failed>0 可用，CANCELLED 不显示。修复 cancel 调用传 conversationId、结果预览、refresh 和错误展示的占位实现。

`useTask` 和 WS 合同继续使用平铺 `occurredAt`，保留 `PARTIAL` phase 与 skipped 计数。增加 contract test，证明 COMPLETED 不显示失败项重试，PARTIAL 显示 warning 和失败数，derived response 跳转新 taskId，来源不回到 queued/running。

### Milestone 8：清理边界并完成全仓验证

新增或强化 ArchUnit：image 不依赖 storage/dag/task；dag 不依赖 task/file/cache/agent 且不含线程池/MQ/process_result/stub；state 不直连 MySQL/Redisson/MinIO client；task 是 `process_*` 唯一写侧，Rubrics 只依赖 task public outcome API。AutoConfiguration 必需 bean 缺失时启动失败，测试不能依靠生产 fake。

用 `rg` 删除旧生产路径和词汇：`ValidatedDag`、`NodeDispatcher`、`SpecMapper`、`CompletedUnits`、`ArtifactRef.completed`、`workerRunId`、旧 StorageKeys、任何 `forceUnlock`、worker 子线程直接 mapper 写入。Node Attempt 只允许出现在结构化 final failure/外部边界 metrics，不得出现节点 checkpoint 表或恢复逻辑。

最后运行触达模块、前端和全仓测试，执行 `git diff --check`。更新本计划的 living sections 和 Revision Notes，并只同步实现中发现的必要设计偏差；不得为了让旧测试继续通过恢复已删除接口。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点先记录工作树，保护用户现有的 Rubrics 与设计文档修改：

    git status --short
    git diff -- docs/design-docs pixflow-module-rubrics pixflow-infra-ai

Milestone 1 完成后运行：

    mvn -pl pixflow-infra-storage,pixflow-state,pixflow-module-task -am -DskipTests compile
    mvn -pl pixflow-infra-storage -Dtest=StorageKeysTest test
    mvn -pl pixflow-state test
    mvn -pl pixflow-module-task -Dtest=TaskStateMachineTest,ResultStateMachineTest test
    rg -n "CompletedUnits|record ArtifactRef|StorageKeys\.(result|groupResult|generated|tmpBranch|tmpGroup)|uq_process_result_task_image_branch|worker_run_id" pixflow-state pixflow-infra-storage pixflow-module-task pixflow-module-imagegen pixflow-module-dag

最后一条预期无输出。若命中历史设计说明，人工确认不在生产源码；不得恢复 deprecated wrapper。

Milestone 2 完成后运行：

    mvn -pl pixflow-infra-image test
    rg -n "RasterImage\.of|BufferedImage buffer\(\)|readAllBytes\(\).*probe|new DefaultImagePipeline\(.*ImageCodec" pixflow-infra-image/src

测试必须包含 `PixelBudgetCrossPoolTest`、异常/取消许可释放、组链整体准入和 decode/encode 计数。搜索预期不命中旧复制式 API。

Milestone 3 和 4 完成后运行：

    mvn -pl pixflow-infra-storage,pixflow-state,pixflow-module-dag -am test
    rg -n "ValidatedDag|NodeDispatcher|SpecMapper|UnsupportedOperationException|List<DagNode> perMemberOps|boolean completed" pixflow-module-dag/src pixflow-state/src

搜索预期无输出。增加 compile determinism、binding completeness、schema default、branch identity、epoch reference miss 和 GENERATIVE checkpoint 测试。

Milestone 5 和 6 完成后运行：

    mvn -pl pixflow-module-task,pixflow-module-imagegen,pixflow-app -am test
    rg -n "forceUnlock|findByUnit\(|resultMapper\.insert\(|markTerminal\(.*where id =|FAILED.*COMPLETED" pixflow-module-task/src/main pixflow-app/src/main

`resultMapper.insert` 若存在，只能在 task owner 的 fenced repository 内；worker/scheduler/failure 包不得命中。MySQL/Redis/MinIO 集成用例不能因 Docker 不可用而把 skipped 记为通过。

前端完成后运行：

    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web build
    rg -n "重试功能即将上线|canRetry.*cancelled|cancelTaskApi\(tid\.value\)" pixflow-web/src

搜索预期无输出。

最终运行：

    mvn -pl pixflow-infra-image,pixflow-infra-storage,pixflow-infra-cache,pixflow-state,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-conversation,pixflow-app -am test
    mvn test
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web build
    git diff --check
    git status --short

若全仓测试被无关的现有 Rubrics 实施中改动阻断，先确保本计划触达模块命令全部通过，再在 `Surprises & Discoveries` 记录完整失败测试名、首个相关 stack frame 和为何与本计划无关；不得只写“其他模块失败”。

## Validation and Acceptance

实现只有同时满足以下行为才算完成。

Agent 提交含 raw params 的合法 Image Plan 时，服务端校验并保存 Canonical DAG；数据库没有 Typed Execution Plan JSON。两次从同一 canonical bytes 编译得到完全相同的 typed steps、依赖边、branchId 和 Work Unit Identity。删除任一 PixelTool binding 后应用启动或确认失败，消息不得进入 task queue，生产代码中不存在 runtime stub。

一个两步本地图片 branch 只 decode 一次、encode 一次。两个不同 task pool 同时处理大图时，所有 pipeline 的加权像素总量不超过同一个 JVM Pixel Budget；拿不到 permit 前没有 decode。单个单元超过总预算得到 `PIXEL_BUDGET_EXCEEDED`，等待超时得到 `PIXEL_BUDGET_TIMEOUT`；成功、异常、取消后预算都恢复到零占用。

一个 task 收到两条重复 MQ 消息时只有一个 RLock owner claim epoch。模拟 owner A 失锁、owner B claim 更高 epoch 后，A 的 heartbeat、FAILED/SUCCESS 和终态 SQL 都更新 0 行且不发事件；B 可以提交。Redis 不可用时消息失败关闭，不允许只靠 `run_epoch` 执行。恢复扫描器从未调用 `forceUnlock`。

task 有三个 Work Unit，其中一个 SUCCESS、一个 FAILED、一个未提交时 worker 崩溃。新 epoch 的 `SkippableWorkUnits` 只含 SUCCESS；FAILED 和未提交单元整体重算。Redis Runtime Reference 或对应 TMP 对象存在都不能使它们被跳过；旧 epoch ref 必须 miss。已有 SUCCESS 永远不被更高 epoch 覆盖。

结果对象写在 `results/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.ext` 后，只有同 epoch fenced SUCCESS row 引用时 API 才返回下载。模拟对象写成功但 DB 提交失败，查询不可见该对象，后续由生命周期清理。

四个终态例子必须精确成立：2 SUCCESS 得 COMPLETED；1 SUCCESS + 1 FAILED 得 PARTIAL；0 SUCCESS + 2 FAILED 得 FAILED；0 SUCCESS + 2 SKIPPED 得 CANCELLED。终态 update 0 行不发布 `TaskCompletedEvent`。任一终态 task 都不能被 worker、cancel 或 retry 原地改回 RUNNING。

对 PARTIAL 来源调用 `POST /api/tasks/{id}/retry-failed` 时，新 task 只含来源 FAILED selection，`retryOfTaskId` 指向来源，来源 task/result/object 不变。重复 Idempotency-Key 返回同一 derived task。客户端提交 resultId/nodeId 列表没有对应字段。对 RUNNING、COMPLETED、CANCELLED 调用返回 `TASK_RETRY_SOURCE_NOT_TERMINAL`。

生成式任务与确定性任务使用相同 Work Unit checkpoint/epoch/终态规则，但对象落 GENERATED 桶。生成式 SUCCESS 可被恢复跳过；失败项 derived retry 使用新 taskId、新 epoch 路径。

前端 PARTIAL badge 显示“部分成功”和 failed/skipped 数；仅 PARTIAL/FAILED 且 failed>0 出现“重试失败项”。点击后跳转 derived task，来源卡片仍为终态。任务取消请求携 conversationId；WS 终态时间只读 `occurredAt`。

## Idempotence and Recovery

源码和文档修改使用小范围补丁，不执行 `git reset --hard`、`git checkout --` 或整体 restore。当前工作树已有 Rubrics 和设计文档改动，实施必须在其上演进，不覆盖、不回退。

本计划明确不兼容旧 task schema。重写 migration 后，本地开发 MySQL 需要删除并重建 PixFlow 的 disposable 开发库或对应 task 表，再由 Flyway 从零创建。执行者必须先确认目标是开发环境；若本地数据有保留价值，先导出备份，但不要因此在生产代码里添加 legacy reader、nullable fallback 或双写。共享/生产数据库若未来已经存在，必须另开计划评估迁移，不能临时偏离本文。

Canonical DAG、unit selection 和 terminal rows 都是不可变事实。实现失败可删除未提交源码改动后重跑测试，但不能通过修改历史 SUCCESS 或来源 terminal task 恢复。错误 Canonical DAG 必须重新提交提案；错误已发布 schema 必须升级 schema version。

结果和 Runtime Reference 遵守先对象后数据库。失败 epoch 的 RESULTS/GENERATED 孤儿对象与 TMP 对象由生命周期清理，不能通过扫描对象回填 SUCCESS。Runtime Reference 删除失败由 TTL 兜底；Redis 全丢只会导致当前 Work Unit 重算，不影响 checkpoint。

Derived Retry Task 创建事务失败时不发布 MQ；重试同一 Idempotency-Key。DB 提交成功但 MQ 发布失败时保持 PENDING 并由明确的 enqueue retry 重新发布，不修改来源 task。不得把 derived create 失败处理成来源 task 重新 RUNNING。

## Artifacts and Notes

目标提案与编译流：

    Agent Image Plan
        -> DagJsonReader shallow parse
        -> DagValidator strict validation
        -> CanonicalJson -> CanonicalDag (唯一持久事实)
        -> DagCompiler -> TypedExecutionPlan (纯派生值)
        -> BranchExpander + member snapshot -> WorkUnitSelection
        -> process_task + PENDING process_result rows

目标执行所有权流：

    MQ message
        -> Redisson RLock watchdog owner thread
        -> MySQL claim run_epoch
        -> heartbeat(epoch)
        -> worker pool computes WorkUnitCompletion only
        -> owner thread LockGuard.assertHeld
        -> fenced result transaction(epoch)
        -> progress/event
        -> fenced terminal aggregation(epoch)
        -> unlock only if held by current thread

目标恢复流：

    stale MySQL heartbeat
        -> RecoveryService requeues only
        -> new consumer acquires RLock
        -> claim higher Execution Epoch
        -> RecoveryCoordinator loads MySQL SUCCESS
        -> skip SUCCESS; recompute every other selected Work Unit

目标图片内存流：

    ReopenableImageSource
        -> probe all required sources
        -> PixelBudget.acquire(weighted pixels)
        -> decode
        -> local operations with explicit RasterImage ownership
        -> encode
        -> close raster(s) and permit

关键删除清单：

    pixflow-module-dag/.../ir/ValidatedDag.java
    pixflow-module-dag/.../exec/NodeDispatcher.java
    pixflow-module-dag/.../exec/SpecMapper.java
    DagAutoConfiguration 中所有 UnsupportedOperationException stub
    pixflow-state/.../model/CompletedUnits.java
    pixflow-state/.../model/ArtifactRef.java
    StorageKeys.result/groupResult/generated/tmpBranch/tmpGroup
    process_result.worker_run_id 与旧组合唯一键
    任何 worker/scheduler 子线程直接 mapper 写入
    前端 retry 占位和 CANCELLED retry 分支

## Interfaces and Dependencies

在 `pixflow-module-dag` 中最终存在以下核心类型，字段可按 Java 命名细化，但语义不得改变：

    public record CanonicalDag(
        byte[] canonicalJson,
        String canonicalHash,
        String schemaVersion,
        List<DagNode> nodes,
        List<DagEdge> edges) {}

    public sealed interface ExecutionStep
        permits ExternalStep, LocalImageStep, GroupStep, CopyStep {}

    public record TypedExecutionPlan(
        String canonicalHash,
        String schemaVersion,
        List<ExecutionStep> steps,
        List<ExecutionEdge> edges) {}

    public interface DagCompiler {
        TypedExecutionPlan compile(CanonicalDag dag);
    }

`StepBindingRegistry` 必须能在启动期枚举 `PixelTool.values()` 并验证 mapper/executor 完整性。执行器接口接收 typed branch、输入快照和由 task 提供的 output target，不接收原始 Map：

    public interface UnitExecutor {
        UnitOutcome execute(ExecutableBranch branch, UnitInput input, UnitOutputTarget target);
    }

    public record UnitOutputTarget(
        String taskId,
        String unitKeyHash,
        long runEpoch,
        String extension) {}

在 `pixflow-state` 中最终存在：

    public record SkippableWorkUnits(String taskId, Set<UnitKey> succeeded) {}

    public record RuntimeArtifactRef(
        UnitKey unit,
        long runEpoch,
        ObjectLocation location,
        Map<String, Object> meta) {}

    public interface CheckpointReadPort {
        SkippableWorkUnits loadSkippableWorkUnits(String taskId);
        PersistedCounts loadCounts(String taskId);
        TaskRunStatus loadTaskStatus(String taskId);
    }

    public interface RunStateRefStore {
        void putRef(CacheKey key, RuntimeArtifactRef ref, Duration ttl);
        Optional<RuntimeArtifactRef> getRef(CacheKey key, long expectedRunEpoch);
        void deleteRef(CacheKey key);
    }

在 `pixflow-infra-cache` 中直接替换锁 callback：

    public interface LockTemplate {
        boolean tryRunWithLock(CacheKey key, Duration waitTime, Consumer<LockGuard> action);
    }

    public interface LockGuard {
        boolean isHeldByCurrentThread();
        void assertHeld();
    }

`LockGuard` 不暴露 unlock。`RedissonLockTemplate` 独占 `RLock` 获取、watchdog 与 finally 释放。

在 task 中，线程池和 owner 的接口边界为：

    public record ExecutionRun(String taskId, long epoch, LockGuard lockGuard) {}

    public sealed interface WorkUnitCompletion
        permits WorkUnitSucceeded, WorkUnitFailed, WorkUnitSkipped {}

    public interface WorkUnitExecutor {
        WorkUnitCompletion execute(WorkUnit unit, ExecutionRun run);
    }

子线程可以使用 `run.epoch()` 生成 epoch 对象 key，但不能使用 mapper、progress 或 event publisher。只有 owner repository 接受 completion 并做 fenced commit。

在 `pixflow-infra-image` 中最终存在：

    public interface PixelBudget {
        Permit acquire(long weightedPixels, Duration timeout);
        interface Permit extends AutoCloseable {
            long weightedPixels();
            void close();
        }
    }

    public interface ReopenableImageSource {
        InputStream openStream();
    }

    public interface ImagePipeline {
        byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode);
        byte[] runComposed(List<ReopenableImageSource> members,
            List<ImageOp> perMemberOps, MultiImageOp compose,
            List<ImageOp> postOps, EncodeSpec encode);
    }

依赖方向保持：image 只依赖 common；storage/cache 是通用 infra；state 依赖 cache/storage 并定义只读 SPI；dag 依赖 image/thirdparty/ai/storage/state；task 依赖 dag/imagegen/state/cache/mq/storage 并实现 state SPI；conversation 只在确认边界调用 task/dag public API；app 承载 REST；web 只消费 API/WS。不得新增 image -> dag/task、state -> task persistence、dag -> task 或 Rubrics -> task persistence 依赖。

## Revision Notes

2026-07-13 / Codex: 开始 Milestone 5。`LockTemplate.tryRunWithLock` 改为只读 `LockGuard` callback，task 在持锁 owner 线程 claim 单调 `run_epoch` 并启动 epoch heartbeat；capability worker 只返回 sealed `WorkUnitCompletion`，owner 通过 completion queue 逐项校验 guard、epoch 对象和父 task 行锁后 fenced 更新预建结果行，再发布进度。恢复扫描只查询 stale heartbeat 并重投；SUCCESS 与同 epoch attempt 均不可覆盖。补齐生成式 Work Unit selection/PENDING 投影、fenced terminal update 与四态纯聚合。task 定向 17 项、file 锁契约相关 6 项通过；MySQL/Redis Testcontainers 因 Docker engine 不可用未实际执行，Milestone 5 保持进行中。

2026-07-13 / Codex: 完成 Milestone 4。Runtime Reference 使用正数 epoch 和 exact-epoch read，错 epoch 立即按 miss 处理并尝试删除；dag 为外部字节型 Group 成员预处理接入 TMP/ref 写读、24 小时兜底和单元退出主动清理。修复 checkpoint adapter 对 missing task 的错误空集合语义，验证 SUCCESS 的 BRANCH/GROUP/GENERATIVE 均可恢复跳过，并修正 cache 文档中拒绝 fencing 的旧表述。

2026-07-12 / Codex: 继续实施 Milestone 2。`DefaultImageCodec.probe` 改为 ImageIO 流式元数据读取，`decode` 使用 `max-source-bytes` 有界缓存并在同一 reader 内读取 probe；`RasterImage` 改为显式 `takeOwnership/borrowBuffer/retain/close`，所有 image op、pipeline 和 DAG cleanup 同步释放句柄。image 22 项、DAG 20 项定向测试及相关干净编译通过。Milestone 2 仍保持进行中，剩余 typed target 精准预算和跨 pool 并发验证。

2026-07-12 / Codex: 根据二次审查补充 probe 受控流、codec EXIF/sRGB 中间图释放和 resize 临时图释放；未改变模块边界。全仓 `mvn test` 仍被既有 `pixflow-eval` TraceRecorder 测试阻断，未将该无关失败归因于 image 改动。

2026-07-12 / Codex: 补强 codec 异常路径所有权转移前的中间图清理，以及 encode normalize 临时图释放；image 模块 22 项测试通过。Milestone 2 仍只剩 typed target 精准预算与跨 worker pool 并发验收。

2026-07-12 / Codex: 完成 typed resize target 预算上界和跨 worker pool 共享预算测试；image 模块 23 项测试通过。Milestone 2 仅剩 pipeline 操作/compose/encode 异常路径的专门释放断言，暂不提前标记完成。

2026-07-12 / Codex: 增加 encode 异常后 permit 可重新获取的回归测试；`DefaultImagePipelineTest` 4 项通过，Milestone 2 标记完成。后续进入 Milestone 3 Canonical DAG 到 Typed Execution Plan 编译链。

2026-07-12 / Codex: 开始 Milestone 3，新增 Canonical DAG、sealed execution steps、typed plan 和纯 `DefaultDagCompiler`；首个测试覆盖 resize typed spec 与 remove-bg external step。旧 raw execution 路径尚未删除，Milestone 3 保持进行中。

2026-07-12 / Codex: 新增 `CanonicalDagFactory` 稳定序列化与 SHA-256、覆盖全部 `PixelTool` 的 `StepBindingRegistry`，并把 validate→canonicalize→compile 接入 `DagFacade` 和自动配置。7 项定向测试通过；worker/expander 尚未切换，旧 raw 路径仍待删除。

2026-07-12 / Codex: 根据双轴审查让 factory 复用共享 `CanonicalJson`，增加 `WatermarkBindingSpec` 并拒绝非法格式的 JPEG fallback。真实 executor binding 验证和 raw 路径删除仍明确保留在 Milestone 3 剩余项。

2026-07-12 / Codex: 完成 Milestone 3 一次性切换。BranchExpander、preflight、三类 executor、worker 与 conversation 确认链全部消费 typed plan；pending/task 持久化 canonical JSON；删除 `ValidatedDag/NodeDispatcher/SpecMapper` 和生产 stub；补齐 background/remove-bg/copy typed binding、真实 infra adapters、冻结 selection 与 PENDING 行预建。app reactor 编译和 DAG 定向测试通过。

2026-07-12 / Codex: 根据 Milestone 3 双轴审查补齐执行闭环：新增 registry 驱动的唯一 `RoutingUnitExecutor`；task 以 `UnitKey + runEpoch` 生成 epoch-aware 输出目标并传给 executor；本地图像与组步骤去除 `Object typedSpec`；确认链执行 group preflight；task/selection/PENDING 投影在同一事务提交且 MQ 在 `afterCommit` 发布；image adapter 直接传递可重开来源，组源流显式关闭。worker completion 与 fenced result update 仍严格留在 Milestone 5。

2026-07-12 / Codex: 更新实施记录与验证证据。Milestone 1 已落地部分通过 storage 15、state 17、imagegen 83、task 8 项测试及相关 reactor 编译；聚合测试被 DAG 新增 `CONFIRMING` 后未同步的两个旧测试阻断，已在 Surprises 中记录具体测试与首个失败原因。由于冻结 selection 的 PENDING 结果预建尚未实现，Milestone 1 继续保持未完成；Milestone 2 仅完成设计对照，不提前标记进度。

2026-07-12 / Codex: 开始执行 Milestone 1。统一 state/task 的 Work Unit identity 与编码、替换 checkpoint/runtime reference 类型、重写 task V1 目标基线并删除 V2 gallery 迁移、替换全部旧 StorageKeys 及 imagegen 调用。因冻结 selection 与 PENDING 结果预建尚未落地，Milestone 1 保持未完成并在 Progress 明确剩余项。

2026-07-13 / Codex: 完成 Milestone 1 剩余切片。`CreateTaskServiceImpl` 在创建事务内调用 `WorkUnitPlanner` 固化 selection，将其写入 `process_task.unit_selection_json`，并为每个 Work Unit 预建 `process_result` 的 PENDING 行；`unit_key` 使用统一 `UnitKeyCodec`，task/selection/result 行提交后才发布 MQ，避免 worker 观察到半成品事实。相关 task reactor 编译通过。

2026-07-12 / Codex: 创建本计划。基于四个领域上下文和三个已接受 ADR，把现有 raw-node DAG、结果存在即跳过、无 epoch 写入、复制式 raster、旧对象 key 和前端 retry 占位直接替换为 Canonical DAG -> Typed Execution Plan、Work Unit SUCCESS checkpoint、RLock + MySQL Execution Epoch fencing、Derived Retry Task 和 JVM 全局 Pixel Budget。计划采用开发期一次性 schema/code 切换，不保留双写、旧接口、deprecated wrapper 或 runtime stub；同时用 owner-thread completion 提交解决 Redisson RLock 的线程归属约束。
