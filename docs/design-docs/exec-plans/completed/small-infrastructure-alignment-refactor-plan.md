# 对齐 Cache、MQ 与 Storage 的小范围基础设施契约

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并作为 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 的基础设施子计划执行。执行者只依赖当前工作树和本文，就应能完成最新架构提交中变更行数较少的 Cache、MQ 与 Storage 基础设施切片。每个停止点都要更新本文；改变模块范围、接口、阶段顺序、兼容策略、测试命令或与后端总计划的交接条件时，必须同步更新四个 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

最新提交 `0faa1713204785303c49807430dc614d2ee5af6d` 只修改了架构与计划文档，没有同步修改生产实现。本计划把其中变更较小的三个基础设施模块落实为可验证的窄接口：Cache 回归为只依赖 Common 的 Redis/Redisson 通用原语，不再承载确认令牌业务存储；MQ 保持领域无关，并把默认 broker 重试收敛为三次和三档退避；Storage 明确区分当前 execution epoch 的 TMP 候选对象与 File 发布后的稳定 Asset Image 对象，并提供纯 I/O 的对象复制原语。

这些修改本身不新增页面功能。开发者可以通过依赖树、负向源码搜索、模块测试和真实 Redis/MinIO 容器测试观察结果：Cache 不再依赖 `pixflow-contracts` 或暴露 confirmation Bean；MQ 在第三次 broker 重投后进入终态失败路径；确定性和生成式 Work Unit 的候选 key 都位于 TMP 且包含 `taskId + unitKeyHash + runEpoch`；跨桶复制保留源候选并生成字节一致的稳定目标。最终用户可见的“成功图片获得新 `imageId`，清除任务后图片仍存在”由后端总计划 Milestone 3 的 Task/File publication 闭环验收，本计划提供并验证它所需的基础设施能力。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、设计文档索引、两个活动执行计划、总体设计相关章节、三个基础设施设计、Context Map 和 ADR 0001、0002、0006、0007。
- [x] (2026-07-17) 对比 `0faa171` 与父提交 `b620027`，按文档净变更量选择 Cache（12 增 / 15 删）、MQ（14 增 / 13 删）和 Storage（20 增 / 13 删）。
- [x] (2026-07-17) 审计三个模块的生产源码、POM、自动配置、直接调用方、测试与当前工作树，记录实现差距和文件所有权冲突。
- [x] (2026-07-17) 创建本中文 ExecPlan，固定机制边界、微提交顺序、快速定位关键词和与后端总计划的交接点。
- [x] (2026-07-17 13:07+08:00) Milestone 0：记录基线和文件所有权；确认两个未跟踪 Cache 故障注入测试及 Common/File/Lint 现有修改均为用户工作。
- [x] (2026-07-17 13:23+08:00) Milestone 1：删除 Cache confirmation 适配器、Bean、测试、指标、错误码、Contracts 依赖和精确 suppression，增加只允许 Common 的 Maven 依赖守护；20 项 Cache 测试（含真实 Redis 故障注入）通过。
- [x] (2026-07-17 13:15+08:00) Milestone 2：MQ 默认值与开发配置收敛为三次和 5s/30s/2m，新增配置绑定、retry boundary、AckDrop 与 schema 测试；17 项测试通过。
- [x] (2026-07-17 13:22+08:00) Milestone 3：Storage 新增严格 archive/stable asset keys 和 MinIO server-side `copy`；按用户要求删除一参数兼容入口并原子迁移 ZIP 调用方；19 项测试（含 4 项真实 MinIO 集成测试）通过。
- [x] (2026-07-18) Milestone 4：Task-owned `GeneratedAssetPublicationPort`、File 幂等 publication 和 App 接线已落地；DAG/Imagegen candidate 已原子切到 TMP，生成式与确定性前缀分离，旧稳定桶 producer/reader 合同已删除。
- [x] (2026-07-18 23:31+08:00) Milestone 5：三个 infra 模块测试、publication 直接消费者定向测试、17 模块触达链严格 verify、负向源码搜索和真实 Redis/MinIO/MySQL publication 故障矩阵全部通过；按用户明确要求不执行全仓 `mvn verify`，该命令不作为本次完成证据。

## Surprises & Discoveries

- Observation: “文档变更较小”不等于“运行风险相同”。Cache 与 MQ 的实现缺口局部且可独立提交；Storage 的 33 行文档差异改变了结果对象生命周期，若只把 bucket 改成 TMP 而没有 File publication seam，会直接破坏当前下载和结果查询。
  Evidence: `StorageKeys.resultUnit/generatedUnit` 当前返回 `RESULTS/GENERATED`；`WorkUnitResultRepository`、`DefaultImageGenExecutor` 和结果下载代码也按稳定桶读取，而源码中尚无 `GeneratedAssetPublicationPort`。

- Observation: Cache 的 task lock 机制已经完成新设计所需的关键部分，不应重写。
  Evidence: `LockTemplate.tryRunWithLock` 已向 owner-thread callback 传入只读 `LockGuard`；`TaskWorker` 在持锁线程建立 `ExecutionRun`；MySQL mapper 的结果和终态更新已经包含 `run_epoch` 条件。

- Observation: Cache 仍完整保留已被目标设计删除的确认令牌基础设施。
  Evidence: 模块仍有 `RedisConfirmationTokenStore`、自动配置 Bean、confirmation 指标、`CACHE_CONFIRMATION_TOKEN_FAILED`、两项 Redis 集成测试、`pixflow-contracts` Maven 依赖和两条精确 Checkstyle suppression。

- Observation: 当前工作树中有两个未跟踪的 Cache 锁故障注入测试，它们属于用户现有工作，不能删除、覆盖或擅自纳入提交。
  Evidence: `pixflow-infra-cache/src/test/java/com/pixflow/infra/cache/lock/RedisDisconnectFailureInjectionTest.java` 与 `RedisLockOwnershipFailureInjectionTest.java` 在 `git status --short` 中为 `??`。

- Observation: MQ 公共模型已经是领域无关的 RocketMQ `topic + tag + keys + consumerGroup`，最新文档中的视觉 tag 变化发生在 Vision 消费方，而不是 infra/mq。
  Evidence: infra/mq 没有预置业务 destination；旧 `COPY_ENRICH` 只存在于 `pixflow-module-vision/.../CopyEnrichmentDestination.java`。infra/mq 的实际缺口是 `MqProperties` 和 `application-dev.yml` 仍使用五次重试与五档退避，且没有 listener 重试边界测试。

- Observation: Storage 已经实现 epoch-aware `resultUnit`、`generatedUnit` 和 `runtimeGroup` 名称，但候选 bucket 与生成式前缀仍不符合最新设计。
  Evidence: 两个 unit 方法当前分别返回 `RESULTS` 和 `GENERATED`，并共用 `results/...` 前缀；设计要求二者都落 `TMP`，生成式路径使用 `generated/...`。

- Observation: 活动 Lint 计划已清空 MQ 和 Storage 的 Checkstyle suppression，但 Cache 仍有精确行号 suppression；删除确认令牌文件时必须在同一提交删除它的 suppression。
  Evidence: `config/checkstyle/suppressions.xml` 仍含 Cache 条目，其中两条精确指向 `RedisConfirmationTokenStore.java`。活动计划要求文件源码与该文件全部 suppression 原子处理，并禁止并行 Maven reactor。

- Observation: Docker 在首次基线运行时不可用，稍后 Docker Desktop 恢复后真实 Redis/MinIO 测试可以执行；最终不能沿用最初的 skipped 结果。
  Evidence: 首轮 Storage 15 项中 2 项跳过、Cache 因未跳过的断连测试报 Docker 错误；恢复后 Cache 20 项零跳过，Storage 19 项零跳过并包含跨桶 copy。

- Observation: Storage 直接消费者组合测试在到达目标模块前被既有 Eval 测试阻断，随后 30 模块 `-DskipTests verify` 又受 5 分钟命令时限中止；三个 infra 模块自身的统一严格门禁已独立成功，完整 App reactor 的生产与测试源码编译也成功。
  Evidence: `TraceRecorderTest.externalizesLargePayloadAfterSanitizingAndReplaysIt` 在 `TraceRecorderTest.java:89` 抛 `NoSuchElementException`；五模块严格 verify 为 BUILD SUCCESS、零 Checkstyle/SpotBugs；`mvn -pl pixflow-app -am -DskipTests test` 的 30 模块 reactor 为 BUILD SUCCESS。

- Observation: Milestone 4 原子切换后仍有两个直接消费者测试 fixture 停留在旧稳定桶合同，另一个 Task fixture 仍只 stub 已被 `stat` 取代的 `exists`。
  Evidence: `DefaultImageGenExecutorTest` 曾期待 `GENERATED/results/...`，实际正确结果为 `TMP/generated/...`；`WorkUnitResultRepositoryTest` 的 Mockito `stat` 默认返回 null。更新合同后 Imagegen 9 项和 Task candidate/publication 7 项均通过。

- Observation: Docker Desktop 已恢复，原先无法执行的 publication crash-window 可以用真实 MySQL 8.4 与 MinIO 验收。
  Evidence: `GeneratedImagePublicationIntegrationTest` 5 项零失败、零跳过，覆盖并发重放唯一 imageId、有序 lineage、reservation/copy/READY/cleanup 恢复窗口和双对象缺失诊断。

## Decision Log

- Decision: 以最新提交中文档的变更量选择 Cache、MQ、Storage；不把 Image、AI、Thirdparty、Vector、Auth 或 Permission 放入本计划。
  Rationale: 前三者的文档总变更分别为 27、27、33 行；其余模块从 67 行到 519 行不等，并已包含明显更大的领域或安全模型重写。
  Date/Author: 2026-07-17 / Codex

- Decision: 本计划是后端总计划的子计划，规范优先级依次为 accepted ADR、当前 `design.md` 与当前模块设计、后端总计划、Context 文档；completed ExecPlan 只提供既有实现和测试参考。
  Rationale: 旧 Cache completed plan 把确认令牌当作目标能力，已经被 ADR 0007 和当前设计明确否决，不能从历史计划恢复该路径。
  Date/Author: 2026-07-17 / Codex

- Decision: Cache 只删除 confirmation 业务适配，不改变 `CacheStore`、`ExpiringStateStore`、counter、watchdog lock、semaphore 或 token bucket 的公共语义。
  Rationale: 最新 Cache 变更是删除错误归属的业务状态，并澄清 Redis lock 与 MySQL epoch 的分工；现有通用原语不是重构目标。
  Date/Author: 2026-07-17 / Codex

- Decision: MQ 不定义 `PACKAGE_VISUAL_ANALYSIS_REQUESTED`、`VISION_ANALYZE_ITEM` 或任何其他业务 destination；本计划只调整领域无关的重试默认值和边界测试。
  Rationale: topic、tag、消息体、唯一键和幂等状态机由业务模块拥有。把 Vision 名称放进 infra/mq 会形成 infra 到 module 语义的反向依赖。
  Date/Author: 2026-07-17 / Codex

- Decision: Storage 先以加法提供 archive key、稳定资产 key 与 `copy`，候选 bucket 切换必须等待 Task-owned publication port 和 File 实现可用，然后与所有直接消费者原子切换。
  Rationale: 这样每个提交都保持系统可编译、当前路径可运行，同时避免 TMP 候选被当前下载代码误当成永久结果，或稳定资产还未登记就变成用户可见。
  Date/Author: 2026-07-17 / Codex

- Decision: Storage 的 `copy` 只做对象 I/O，不分配 `imageId`、不写 lineage、不判断 task epoch，也不删除源对象。
  Rationale: `imageId` 与 lineage 属于 File，fenced SUCCESS 属于 Task。Storage 只应把一个 `ObjectLocation` 复制到另一个 `ObjectLocation` 并返回目标 `ObjectRef`；上层事务成功后再显式删除候选。
  Date/Author: 2026-07-17 / Codex

- Decision: Maven reactor 串行执行；实施前保护当前未跟踪的两个 Cache 故障注入测试和 File/Common 等用户修改。
  Rationale: 活动 Lint 计划已证明并行 reactor 会争用 `target/spotbugsTemp.xml`；共享工作树中的现有修改不属于本计划。
  Date/Author: 2026-07-17 / Codex

- Decision: 按用户明确要求把 archive key 当作一次性重构，不保留一参数 `packageSource(packageId)` 兼容入口；当前只支持 ZIP 的 File 调用方显式传入 `"zip"`。
  Rationale: 用户要求不要为了兼容性保留旧代码；显式格式参数让现有能力真实可见，也避免双 API 长期漂移，同时不伪造 RAR/7z 解压已实现。
  Date/Author: 2026-07-17 / Codex

- Decision: Milestone 5 以本计划三个 infra 模块、publication 直接消费者、真实 publication 集成测试和触达链严格 verify 收口，不运行全仓 `mvn verify`。
  Rationale: 用户于 2026-07-18 明确要求“不用全仓验证”。App 定向测试仍编译了 30 模块生产与测试源码，但只执行指定组合测试；不能把它表述为全仓测试通过。
  Date/Author: 2026-07-18 / Codex

## Outcomes & Retrospective

本计划已经完成。Cache 不再注册或依赖 confirmation，Maven Enforcer 证明其 PixFlow 内部直接依赖只有 Common；20 项测试含断连 fail-closed 与所有权丢失故障注入，零跳过。MQ 以 17 项测试证明 retry count 0、1、2 返回重投、3 进入终态，AckDrop 与 unsupported schema 不重投。Storage 提供无旧重载的 archive keys、稳定 RESULTS/GENERATED asset keys 和纯 I/O server-side copy；19 项测试包含真实 MinIO 跨桶复制，证明字节和 content type 一致、源仍存在、缺失源确定性失败。

publication seam、TMP candidate 原子切换和直接消费者合同均已落地。File publication 单元/恢复测试 7 项、Imagegen 9 项、Task candidate/publication 7 项和 App adapter 1 项通过；真实 MySQL 8.4 + MinIO 集成测试 5 项证明同一 result 并发/重放只产生一个 READY imageId，copy 后数据库 finalize 失败保留 candidate，READY 后删除失败可恢复，双对象缺失保留同一 reservation 供诊断。17 模块触达链严格 verify 为零 Checkstyle、零 SpotBugs，三组负向源码搜索无输出。

按用户明确要求，本次没有运行全仓 `mvn verify`，因此不把全仓测试记作完成证据。App adapter 定向命令确实完成了 30 模块生产与测试源码编译，但只执行了指定测试。更大范围的 Task 删除独立性、Generated Image 再次作为新任务 source 等产品端到端验收继续由后端 publication 专项计划和后端总计划负责，不再阻塞本基础设施子计划完成。

## Context and Orientation

仓库根目录为 `D:\study\PixFlow`。`pixflow-infra-cache` 封装 Redis/Redisson 的通用协调原语；`pixflow-infra-mq` 封装 RocketMQ 的发布、监听、重试和 trace 传播；`pixflow-infra-storage` 封装 MinIO 的对象 I/O 与 key 工厂。三者都位于依赖图底层，只允许向 Common 和外部库依赖，不应知道 Proposal、Task、Vision 或 Asset Image 的业务状态机。

本计划使用以下术语。watchdog lock 是 Redisson 在持锁进程存活时自动续期、进程消失后自动过期的任务互斥锁。Execution Epoch 是 MySQL 中单调递增的 `run_epoch`，用条件更新阻止失去锁的旧 worker 提交；它不是 Redis fencing token。Runtime Reference 是 Redis 中只服务当前 epoch fan-in 的可丢 MinIO 引用，不是 Work Unit checkpoint。Candidate 是 worker 写入 TMP 的 epoch-scoped 候选对象；Published Asset 是 File 分配新 `imageId`、记录 lineage 并复制到稳定 RESULTS/GENERATED key 后的 Generated Image。至少一次投递表示 MQ 可能重复交付消息，业务必须用 MySQL 唯一约束、状态机或 checkpoint 吸收重复，MQ 自身不建立业务幂等表。

开始实施前必须阅读当时 `docs/design-docs/exec-plans/` 下所有活动计划，以及 `docs/design-docs/infra/cache.md`、`mq.md`、`storage.md`。涉及候选切换时还必须阅读 `docs/design-docs/module/task.md`、`file.md`、`imagegen.md`、`pixflow-module-task/CONTEXT.md`、`pixflow-module-file/CONTEXT.md`、`pixflow-module-imagegen/CONTEXT.md` 和 ADR 0001、0002、0006。若这些设计与本文冲突，先在 Decision Log 记录并按规范优先级处理，不恢复旧 confirmation 或直接可见的 epoch candidate。

## Scope and Non-Goals

范围包括三个 infra 模块的 POM、生产源码、自动配置、模块测试、必要的 `pixflow-app` 配置、精确 Checkstyle suppression，以及为保持编译和完成候选切换所需的直接调用方适配。范围内要删除 Cache confirmation 实现，收敛 MQ 默认重试，增加 Storage archive/stable/copy 能力，并在 publication seam 就绪后把 unit candidate 原子切换到 TMP。

范围不包括 Conversation 的 ephemeral Proposal store、Permission 的 deny-first 重构、Vision 的 Product Visual Facts job/item 状态机、Task/File publication 的完整业务实现、Asset Reference 解析、前端 Outputs、Rubrics、Memory/Vector、MQ outbox、Redis fencing token、对象存储事务或无关 lint/格式化。Vision 业务 destination 的改名由后端总计划 Milestone 4 实施；Task/File 的新 `imageId`、lineage、幂等 publication 和任务清理语义由总计划 Milestone 3 实施。

## Mechanism and Approach

Cache 采用“机制与语义分离”。`LockTemplate` 只负责取得 watchdog lock、在 owner 线程执行 callback、用 `LockGuard` 检查当前所有权并在 finally 安全释放；Task 自己定义 `lock:task:{taskId}:execution`，在 MySQL 取得 `run_epoch`，并让结果/终态写入按 epoch fenced。删除 confirmation adapter 后，Cache 仍能提供 fail-closed 的权威临时 KV、可降级的普通 KV、counter、semaphore 和 token bucket，但不再理解“用户确认”是什么。

MQ 采用“传输至少一次，幂等留给属主”。`ManagedMessageListener` 只根据 `ConsumerErrorHandler` 和 broker retry count 决定 `RECONSUME_LATER`、`ACK_DROP` 或终态失败。三次上限指 broker/显式延迟层的最大重投次数，不扩大业务内部 provider attempt 预算，也不替代 Task/Vision 的 MySQL 恢复扫描。`retryBackoff` 在 broker 模式是配置口径，在显式延迟模式才决定重新发布的 delay；本计划不借调整默认值补写一个新的 outbox 或调度器。

Storage 采用“两阶段可见性”。worker 先把字节写到包含 epoch 的 TMP candidate key；Task 只在锁所有权和 MySQL epoch 都有效时提交 SUCCESS。随后 File 以成功 result identity 做幂等 publication，分配新 `imageId`，调用 Storage 把 candidate 复制到稳定 asset key，在自己的事务中登记 Asset Image 与 lineage，最后删除 candidate。MinIO 对象存在本身不能证明成功；MySQL `process_result.status=SUCCESS` 是执行 checkpoint，File 的 asset row 是发布事实。Storage 的 `copy` 不伪装成跨 MinIO/MySQL 的原子事务。

## Plan of Work

### Milestone 0：冻结基线和文件所有权

先记录 `git status --short`、三个模块的 diff、当前精确 suppressions 和三个模块的测试结果。不得把两个未跟踪 Cache 故障注入测试加入、移动或删除；若用户在实施前已经提交它们，则只把它们当现有测试运行。确认活动 Lint 计划是否仍在修改 `config/checkstyle/suppressions.xml`；若是，删除 confirmation 文件和 suppression 的提交必须由同一执行者串行完成。

运行三个模块的 `-am test`，记录实际测试数与 Testcontainers skipped 数。历史计划记录的 Cache 22、MQ 12、Storage 15 只是 2026-07-16/17 的参考，不是本轮结果。再运行依赖搜索，保存 Cache 仍依赖 Contracts、Storage candidate 仍在稳定桶、MQ 默认仍为 5 的基线证据。

### Milestone 1：让 Cache 回归通用原语

提交 C1 删除 `pixflow-infra-cache/.../confirmation/RedisConfirmationTokenStore.java`、`CacheAutoConfiguration.confirmationTokenStore` Bean 和 `RedisIntegrationTest` 中两个 token 测试，并在同一提交删除 `config/checkstyle/suppressions.xml` 中精确指向该文件的条目。该提交保留尚未删除的 confirmation metrics/error 常量，因此改动集中在行为路径移除，模块必须编译和测试通过。

提交 C2 删除 `CacheMetrics.recordConfirmationToken` 及 Micrometer/Noop 实现、对应 metrics 测试、`CACHE_CONFIRMATION_TOKEN_FAILED`，并从 `pixflow-infra-cache/pom.xml` 删除 `pixflow-contracts`。清理 `pixflow-context/.../SessionMemoryPort.java` 中把 confirmation 当作现行契约的过期 Javadoc。运行 Maven dependency tree 或等价依赖检查，确认 Cache 只保留 Common 和外部技术依赖。

提交 C3 增加一个 Cache 架构/依赖守护测试或等价可维护检查，禁止生产包依赖 `com.pixflow.contracts.confirmation..`、permission、harness、module、agent，并保留现有 lock tests。不得为了“对齐”重命名 `LockTemplate`、删除 `LockGuard` 或增加 Redis fencing token。

### Milestone 2：收敛 MQ 重试而不引入业务语义

提交 Q1 在 `MqProperties` 把 `maxRetries` 默认值改为 3，把 `retryBackoff` 默认值改为 5s、30s、2m，并同步 `pixflow-app/src/main/resources/application-dev.yml`。同一提交新增属性测试，验证 Java 默认值和 Spring 配置绑定都得到三个 retry slots，显式覆盖仍有效。

提交 Q2 为 `ManagedMessageListener` 增加 retry boundary 测试：归一化错误被业务 handler 判为 Retry 时，retry count 0、1、2 返回 `RECONSUME_LATER`，count 3 不再重投并记录终态失败；`AckDrop` 始终成功确认；unsupported schema 不调用业务 handler。测试只断言领域无关行为，不使用 Vision 的 tag 或 payload。

完成时通过负向搜索确认 infra/mq 不出现 `COPY_ENRICH`、`PACKAGE_VISUAL_ANALYSIS_REQUESTED`、`VISION_ANALYZE_ITEM`、`TaskMessage` 或 Vision 状态类型。旧 `COPY_ENRICH` 在 Vision 中仍可暂时存在，直到后端总计划 Milestone 4 一次性切换，不能为了让本计划的搜索变绿而把业务常量搬进 MQ。

### Milestone 3：以加法建立 Storage 发布能力

提交 S1 扩展 `StorageKeys`。新增且只保留 `packageSource(packageId, archiveExt)`，只接受规范化的 `zip`、`rar`、`7z` 或由 File 设计明确允许的 archive format；新增 `resultAsset(packageId, imageId, ext)` 和 `generatedAsset(packageId, imageId, ext)`，分别返回 RESULTS/GENERATED 的稳定路径。按用户要求不提供一参数兼容入口；当前 File 仍只支持 ZIP，因此其调用方在同一提交显式传入 `"zip"`，不伪造 RAR/7z 已可解压。

提交 S2 在 `ObjectStorage` 增加 `copy(source, target)`，在 `MinioObjectStorage` 使用 MinIO server-side copy API 实现跨逻辑桶复制，并把异常归一为 operation=`COPY` 的 `StorageException`。更新仓库内所有测试 fake/anonymous `ObjectStorage` 实现以保持编译；测试替身可以显式抛 `UnsupportedOperationException`，但生产 Bean 不能。新增纯单测覆盖参数/错误映射，新增 MinIO Testcontainers 集成测试覆盖跨桶复制、目标字节与 metadata、源对象仍存在、源不存在时确定性失败。`copy` 不自动 delete source。

提交 S3 增加 StorageKeys 的候选目标测试，但暂不改变现有 `resultUnit/generatedUnit` 的 bucket，先用禁用或契约描述会制造红测试，因此不能单独提交。真正的 bucket 断言与实现必须放到 Milestone 4 的原子切换提交中。Milestone 3 的验收是新 archive/stable keys 和 copy 可独立使用，旧执行路径仍工作。

### Milestone 4：在 publication seam 就绪后原子切换 candidate

本里程碑的进入条件是后端总计划 Milestone 3 已提供 Task-owned `GeneratedAssetPublicationPort`、File 的幂等实现和 App 接线，或同一实施批次已准备好这些改动。若条件未满足，在 `Progress` 记录“基础设施加法已完成；candidate 切换等待 publication seam”，不得提前把稳定桶改为 TMP。

提交 S4 原子修改 `StorageKeys.resultUnit` 和 `generatedUnit`：两者 bucket 都为 TMP；确定性前缀为 `results/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.{ext}`，生成式前缀为 `generated/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.{ext}`。同一提交更新 `ProcessWorker`、DAG result writer、`DefaultImageGenExecutor`、`WorkUnitResultRepository` 的 candidate 验证、相关 unit tests 和任何按 task type 推导 candidate bucket 的代码。提交结束时不得存在“key 来自 TMP、reader 却去 RESULTS/GENERATED”的半迁移。

提交 S5 在 File archive format 已成为事实字段后，把 `FileService`、`UploadSessionService`、extractor 和测试中的显式 `"zip"` 替换为该事实字段。Storage API 已在 S1 一次性切换，不再有旧重载需要删除；若本期仍只支持 ZIP，则该动态格式接线继续留在总计划 Milestone 1，不能伪造 RAR/7z 已可解压。

提交 S6 运行 Task/File publication 合同测试，证明同一成功 result 重放只得到一个 generated image；candidate copy 成功且 asset 事务提交后才删除 TMP；copy 后数据库提交失败时 candidate 保留供重试；任务/Activity 清理不调用稳定 RESULTS/GENERATED 的 task-prefix delete。这里验证 Storage 原语被正确使用，但不把 publication 业务搬入 Storage。

### Milestone 5：组合验证和交接

先分别验证 Cache、MQ、Storage，再验证直接消费者，最后串行跑 reactor。更新后端总计划 Milestone 2/3/4 的 Progress 或交接记录：Cache confirmation 删除属于 Milestone 2；Storage candidate/copy 属于 Milestone 3；MQ 的 Vision destination 切换仍属于 Milestone 4。若只完成基础设施加法而 publication seam 未就绪，本计划保持进行中，不能把 Outcomes 写成端到端完成。

## Tiny Commit Sequence

建议提交顺序为 C1 删除 Cache confirmation 行为路径，C2 删除 Cache 死依赖与指标，C3 增加依赖守护，Q1 调整 MQ 默认配置，Q2 增加 MQ 重试边界测试，S1 一次性切换 archive/stable keys 及当前 ZIP 调用方，S2 增加 Storage copy，S4 在 publication seam 后原子切换 candidate，S5 接入未来 File archive format 事实字段，S6 增加 publication/cleanup 合同测试。每个提交结束时至少运行被触达模块的编译和定向测试；C1 必须与被删除文件的精确 Checkstyle suppression 同提交，S4 不得拆成 producer 与 consumer 两个不可运行提交。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点运行：

    git status --short
    git diff --check

复现最新提交的模块选择：

    git show -s --format=fuller HEAD
    git diff HEAD^ HEAD --numstat -- docs/design-docs/infra

Cache 基线与验收：

    rg -n "ConfirmationTokenStore|RedisConfirmationTokenStore|recordConfirmationToken|CACHE_CONFIRMATION_TOKEN_FAILED|pixflow.cache.confirmation" pixflow-infra-cache pixflow-context config/checkstyle/suppressions.xml
    mvn -pl pixflow-infra-cache -am test
    mvn -pl pixflow-infra-cache -am -DskipTests verify
    mvn -pl pixflow-infra-cache dependency:tree

完成后第一条只允许命中历史 completed 文档（若搜索范围包含 docs）；在上面给出的生产范围内预期无输出。dependency tree 不应包含 `com.pixflow:pixflow-contracts`。

MQ 基线与验收：

    rg -n "maxRetries|retryBackoff|RECONSUME_LATER" pixflow-infra-mq pixflow-app/src/main/resources
    rg -n "COPY_ENRICH|PACKAGE_VISUAL_ANALYSIS_REQUESTED|VISION_ANALYZE_ITEM|TaskMessage" pixflow-infra-mq/src
    mvn -pl pixflow-infra-mq -am test
    mvn -pl pixflow-infra-mq -am -DskipTests verify

第二条预期无输出。定向测试输出应明确显示第三次 retry count 不再返回 `RECONSUME_LATER`。

Storage 基线与验收：

    rg -n "StorageKeys\.(packageSource|resultUnit|generatedUnit|resultAsset|generatedAsset|runtimeGroup)|ObjectStorage\.copy|BucketType\.(TMP|RESULTS|GENERATED)" pixflow-infra-storage pixflow-module-dag pixflow-module-imagegen pixflow-module-task pixflow-module-file
    mvn -pl pixflow-infra-storage -am test
    mvn -pl pixflow-infra-storage,pixflow-state,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-module-file,pixflow-app -am test
    mvn -pl pixflow-infra-storage,pixflow-state,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-module-file,pixflow-app -am -DskipTests verify

最后串行运行：

    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

若 Docker 不可用，记录具体跳过的 Redis/MinIO 测试类和数量；仍需运行纯单元测试与 `-DskipTests verify`。不得删除 `disabledWithoutDocker`、把真实依赖测试改成纯 mock，或声称跳过的测试已经通过。

## Validation and Acceptance

Cache 验收要求生产源码、测试、自动配置、错误码、指标和 POM 不再出现 confirmation token；Spring context 不再注册 `ConfirmationTokenStore`；Cache 对 PixFlow 内部模块只依赖 Common。现有 KV、权威临时状态、counter、semaphore、token bucket 和 lock tests 继续通过；真实 Redis 可用时，断连必须 fail closed，owner 丢锁后 `LockGuard.assertHeld` 必须失败，模板不得解锁新 owner 的锁。

MQ 验收要求默认 `maxRetries=3` 且默认退避只有 5s、30s、2m；应用开发配置与 Java 默认一致；retry count 达到 3 后 listener 不再请求 broker 重投。infra/mq 不包含 Vision/Task/File 业务 destination、消息体或幂等表。Vision 旧 tag 尚未迁移不算本计划失败，但必须在总计划 Milestone 4 保持未完成。

Storage 基础验收要求 archive/stable key 对非法扩展名和路径穿越 fail fast；`copy` 可以在真实 MinIO 跨桶复制并保留 source；异常包含 source/target 可定位上下文但不泄露凭证。最终 candidate 切换验收要求 result 和 generated unit 都落 TMP、前缀区分、包含正数 epoch；Runtime Reference 仍只服务当前 epoch；稳定 RESULTS/GENERATED 只能由 File publication 使用。只有 MySQL fenced SUCCESS 和 File asset row 能使对象成为可见结果，单纯 MinIO 存在不算成功。

端到端交接验收要求同一 result publication 重放返回同一 `imageId`；失败、取消、fenced 或未登记 candidate 不进入 Outputs；清除 task/activity 不删除已发布资产。这些断言由总计划的 Task/File/App 测试实现，本计划在 Milestone 5 记录对应测试名和结果。

## Idempotence and Recovery

搜索、编译、测试和 dependency tree 命令可以重复运行。Storage copy 测试使用每次唯一的对象 key 或在测试结束显式清理；重复运行不能依赖上次容器数据。Cache confirmation 删除是不可逆目标，但 git 提交应分为行为路径、死代码/依赖和守护测试，便于定位失败；不得通过恢复旧 Bean 解决启动问题，应删除或迁移真实消费者。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。开始修改前检查工作树；遇到已有修改时在其上做最小补丁或延后。特别是两个未跟踪 Cache failure-injection 测试和当前 File/Common lint 修改必须保留。若删除 `RedisConfirmationTokenStore.java`，同步删除只指向该文件的 suppression；不要移动其他 Cache suppression 行号或顺手格式化整个模块。

如果 S4 中途停止，恢复单位是 `StorageKeys + 所有 candidate producer + WorkUnitResultRepository 验证 + tests`。不得留下 TMP 写入与稳定桶读取并存的工作树。若 publication 失败，稳定对象可能已 copy 但 asset 事务未提交；File 的幂等 publication 应按成功 result identity 重试并复用/覆盖确定性目标，不能把对象存在当作已登记，也不能让 Storage 自行补数据库。

## Quick Reference Search Keywords

查看本次提交为什么选择这三个模块，搜索 `git diff HEAD^ HEAD --numstat -- docs/design-docs/infra`。定位后端总计划交接点，搜索 `Milestone 2`、`Milestone 3`、`Milestone 4`、`GeneratedAssetPublicationPort`。

Cache 机制与旧路径：搜索 `只提供通用原语`、`LockTemplate`、`LockGuard`、`watchdog`、`run_epoch`、`runref:group`、`RedisConfirmationTokenStore`、`recordConfirmationToken`、`CACHE_CONFIRMATION_TOKEN_FAILED`、`pixflow-contracts`。`completed/cache-module-implementation-plan.md` 可用于找原有 Redis/Testcontainers 模式，但其中 `RedisConfirmationTokenStore` 是应删除的历史目标，不是参考实现。

MQ 机制与边界：搜索 `ManagedMessageListener`、`ConsumerErrorHandler`、`RetryDecision`、`RECONSUME_LATER`、`maxRetries`、`retryBackoff`、`至少一次投递`、`接管成功即确认`。搜索 `COPY_ENRICH` 可以找到旧 Vision 业务入口；搜索 `PACKAGE_VISUAL_ANALYSIS_REQUESTED` 和 `VISION_ANALYZE_ITEM` 可以定位最新目标文档，不能据此把常量放入 infra/mq。`completed/rabbitmq-to-rocketmq-refactor-plan.md` 用于复用 RocketMQ codec/listener 测试模式。

Storage 机制与交接：搜索 `StorageKeys.resultUnit`、`StorageKeys.generatedUnit`、`StorageKeys.runtimeGroup`、`BucketType.TMP`、`ObjectStorage.copy`、`MinioObjectStorage`、`GeneratedAssetPublicationPort`、`generated_image_id`、`source_result_id`、`candidate`、`stable asset`、`lineage`。`completed/execution-domain-refactor-plan.md` 中搜索 `Work Unit`、`runEpoch`、`Runtime Reference` 可找到已落地的执行恢复机制；`completed/storage-module-implementation-plan.md` 用于复用 MinIO Testcontainers 初始化和对象往返测试模式。

建议直接使用：

    rg -n "ConfirmationTokenStore|RedisConfirmationTokenStore|recordConfirmationToken|CACHE_CONFIRMATION_TOKEN_FAILED"
    rg -n "ManagedMessageListener|RetryDecision|RECONSUME_LATER|maxRetries|retryBackoff"
    rg -n "StorageKeys\.(resultUnit|generatedUnit|runtimeGroup|resultAsset|generatedAsset)|ObjectStorage\.copy"
    rg -n "GeneratedAssetPublicationPort|generated_image_id|source_result_id|lineage"

## Artifacts and Notes

对比基线：

    HEAD  0faa171 docs: align architecture, ADRs, and execution plans
    BASE  b620027 feat(app): expose task assets and progress events

    cache.md    12 insertions, 15 deletions
    mq.md       14 insertions, 13 deletions
    storage.md  20 insertions, 13 deletions

当前关键差距：

    Cache   confirmation adapter/bean/metric/error/tests/contracts dependency still present
    MQ      maxRetries=5; retryBackoff=[5s,30s,2m,10m,30m]
    Storage resultUnit -> RESULTS; generatedUnit -> GENERATED; both share results/ prefix
    Storage ObjectStorage.copy/resultAsset/generatedAsset absent

## Interfaces and Dependencies

Cache 最终 Maven 内部依赖为 `pixflow-common`，不再依赖 `pixflow-contracts`。公开锁接口保留语义等价形状：

    public interface LockTemplate {
        <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action);
        void runWithLock(CacheKey key, Duration waitTime, Runnable action);
        boolean tryRunWithLock(CacheKey key, Duration waitTime, Consumer<LockGuard> action);
    }

    public interface LockGuard {
        boolean isHeldByCurrentThread();
        void assertHeld();
    }

MQ 不增加业务接口。`MqProperties` 最终默认 `maxRetries=3`，`retryBackoff=[5s,30s,2m]`；`ManagedMessageListener` 继续只消费 `ConsumerBinding`、通用 envelope、业务提供的 handler/error handler 和 infra properties。

Storage 最终公开接口增加：

    ObjectRef copy(ObjectLocation source, ObjectLocation target);

`StorageKeys` 最终提供语义等价的方法：

    packageSource(long packageId, String archiveExt)
    resultUnit(String taskId, String unitKeyHash, long runEpoch, String ext)       // TMP/results/...
    generatedUnit(String taskId, String unitKeyHash, long runEpoch, String ext)  // TMP/generated/...
    resultAsset(long packageId, long imageId, String ext)                         // RESULTS stable
    generatedAsset(long packageId, long imageId, String ext)                      // GENERATED stable
    runtimeGroup(String taskId, long runEpoch, String unitKeyHash,
                 String memberId, String name)                                    // TMP current epoch

Storage 不公开 `publishAsset`，不依赖 Task 或 File。Task-owned `GeneratedAssetPublicationPort` 和 File 实现仍按后端总计划定义，App 只负责接线。

## Revision Notes

2026-07-17 / Codex: 创建本计划。依据提交 `0faa171` 相对 `b620027` 的 infra 文档差异，选择 Cache、MQ、Storage 三个变更量较小的模块；通过源码审计区分了可独立完成的 Cache/MQ 切片和必须等待 Task/File publication seam 的 Storage candidate 切换。计划明确保留现有 watchdog/epoch 机制、维持 MQ 领域无关、把 Storage copy 限定为纯 I/O，并提供了微提交、串行验证、工作树保护与快速搜索关键词。

2026-07-17 / Codex: 实施 Milestone 0-3 并完成三个 infra 模块统一 lint。Cache confirmation 全链路和 Contracts 依赖已删除；MQ 三次重试边界已由测试固定；Storage archive/stable keys 与跨桶 copy 已通过真实 MinIO。根据用户“不要为了兼容性保留旧代码”的要求删除一参数 archive key 入口并迁移 ZIP 调用方。由于 Task/File publication seam 尚不存在，Milestone 4 保持未完成，未制造 TMP producer 与稳定 reader 并存的半迁移。

2026-07-18 / Codex: 后端 publication 专项计划已满足 Milestone 4 的进入条件并完成原子切换：Task/File owner-defined seam、App adapter、TMP candidate 与稳定 File publication 同批存在，Imagegen 裸 object-key/default bucket 旧合同也已删除。真实 MinIO/MySQL publication 故障矩阵仍由专项计划负责，Milestone 5 端到端交接保持部分完成。

2026-07-18 / Codex: 完成 Milestone 5 和本计划。修正 Imagegen/Task 三处滞后的 candidate 测试合同，增加 File copy-finalize 与 READY-cleanup 两个恢复断言；三个 infra 模块 56 项测试、publication 直接消费者定向测试、17 模块严格 verify 和负向搜索通过。Docker 恢复后执行真实 MySQL 8.4 + MinIO publication 集成测试 5 项，覆盖并发与主要 crash window。依用户要求不执行全仓验证，并在 Progress、Decision Log 与 Outcomes 中明确限定证据范围。
