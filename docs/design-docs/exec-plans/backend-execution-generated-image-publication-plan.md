# 完成可恢复执行链与 Generated Image 原子发布闭环

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本文遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能彻底完成 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 的 Milestone 3。每次停止工作都必须更新本文；改变模块所有权、公开接口、数据库字段、对象 key、事务边界、恢复顺序、测试门禁或范围时，必须同步更新四个 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，确定性 DAG 和单图 Imagegen 产生的字节不再直接写入用户可见的 RESULTS/GENERATED 位置。执行器只把带 `taskId`、稳定 Work Unit identity 和 `runEpoch` 的候选对象写入 TMP；Task 在仍持有执行权时把 MySQL checkpoint 提交为 SUCCESS，再通过幂等发布端口请求 File 把该候选复制成一个独立 Generated Image。File 分配新的 `imageId`，保存来源 Task、result、全部源图、执行 epoch、媒体信息和 provider/model provenance，并返回 canonical IMAGE `referenceKey`。只有 Task 已绑定这个 Published Asset 后，图片结果才进入任务查询、下载和 Outputs，任务才允许进入终态。

用户可以观察到一个明确结果：执行成功后，`GET /api/tasks/{taskId}/results` 返回 `generatedImageId` 和 canonical `referenceKey`，不返回 TMP key 作为产品身份；同一图片可通过 File 的公开读取/内容边界预览或下载。删除 Task 或 Activity 后，已经发布的 Generated Image 仍可由 `GET /api/files/packages/{id}/images` 查询并再次 mention。进程分别在 SUCCESS 后、File reservation 后、对象 copy 后或 Task bind 前崩溃时，恢复 worker 重放同一 `sourceResultId`，仍只得到一个 READY `imageId`，不会产生重复资产或把失败、取消、失去 fencing 的 candidate 暴露给用户。

本计划同时收束三个活动计划中与该闭环不可分割的未完成工作：`docs/design-docs/exec-plans/small-infrastructure-alignment-refactor-plan.md` 的 Storage candidate 原子切换，`docs/design-docs/exec-plans/pixflow-file-development-plan.md` Milestone 4 的 Task/File publication seam，以及 `docs/design-docs/exec-plans/lint-baseline-remediation-plan.md` 中 Task 模块剩余 Checkstyle 基线。它不重写已经由 `docs/design-docs/exec-plans/completed/execution-domain-refactor-plan.md` 完成的 Canonical DAG、Typed Execution Plan、Work Unit identity、Execution Epoch、Derived Retry、heartbeat recovery、State checkpoint 或 Pixel Budget。

## Progress

- [x] (2026-07-18) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、全部活动 ExecPlan、Context Map、相关模块 `CONTEXT.md`、ADR 0001/0002/0003/0005/0006、执行/资产/基础设施设计和当前源码、schema、测试。
- [x] (2026-07-18) 完成 Milestone 3 差距审计，确认已有执行域能力和仍缺失的 TMP candidate、发布端口、File publication state、公开查询/下载边界及 Task lint 基线。
- [x] (2026-07-18) 创建本中文 ExecPlan，固定 Task SUCCESS 与 File publication 的两阶段恢复机制、多源 lineage、模块依赖方向和“先 lint/static analysis，后 test”的硬门禁；尚未修改生产代码或运行产品测试。
- [x] (2026-07-18) Milestone 0：重新确认活动计划、工作树所有权、数据库版本、现有 lint 数量和执行基线；确认工作树已有大量 Conversation/Context/Session/Web/docs 用户改动并全程保留。
- [x] (2026-07-18) Milestone 1 实施完成：Storage/DAG/Imagegen 的确定性与生成式产物统一写 TMP；候选携带 typed `ObjectLocation`、媒体信息、有序 source metadata 和真实 producer provenance；删除裸 key/默认 PACKAGES 的旧接口，没有保留兼容构造器或访问器。
- [ ] Milestone 1 验收待完成：运行 Storage/AI/Image/DAG/Imagegen/Task 的完整相关测试集，并补记 Pixel Budget 与 candidate key 的测试汇总。
- [x] (2026-07-18) Milestone 2 实施完成：新增 Task V2 publication persistence、`GeneratedAssetPublicationPort`、SUCCESS/PENDING checkpoint、owner-epoch fenced bind、publication backlog replay、失败诊断和 terminal PENDING gate。
- [x] (2026-07-18) Milestone 2 定向单测完成：backlog replay、bind fencing、发布失败保持 SUCCESS/PENDING、terminal PENDING gate 均已通过。
- [ ] Milestone 2 验收待完成：用真实 MySQL/Redis 验证 affected-row fencing、失锁与 higher epoch、stale recovery 和 publication failure 不会重复调用 provider。
- [x] (2026-07-18) Milestone 3 实施完成：新增 File V4 migration/fresh schema、PUBLISHING/READY reservation、`(source_task_id, source_result_id)` 幂等身份、稳定 key、完整 lineage、metadata fail-closed、READY 后 candidate cleanup 和 CAS bounded recovery。
- [x] (2026-07-18) Milestone 3 定向单测完成：File publisher/recovery 5 tests 通过，覆盖 canonical stable key/reference、多源 lineage、metadata conflict、缺失 MIME、MIME/extension/key 后缀 fail-closed、READY replay 和 cleanup 批次隔离。
- [ ] Milestone 3 验收待完成：真实 MySQL/MinIO crash windows、并发 publisher 唯一性、candidate/stable 双缺失诊断及单源/多源持久化集成测试尚未完成。
- [x] (2026-07-18) Milestone 4 实施完成：App adapter 接通 Task-owned port 与 File-owned publisher/read API；File→Imagegen 依赖和 App→File mapper/entity/internal 越层依赖已删除，生产源码守护搜索无命中。
- [ ] Milestone 4 验收待完成：补齐并运行组合根、ArchUnit/sentinel 和 Spring auto-configuration 的完整相关测试。
- [x] (2026-07-18) Milestone 4 组合根定向验收完成：新增 Task→File publication DTO 无损转换和 READY Generated Image→Imagegen source facts 测试；删除 Imagegen `SourceImageInfo.objectKey` 及由调用方猜 bucket 的旧合同，没有保留兼容构造器。
- [x] (2026-07-18) Milestone 5 主体实施完成：Task/Rubrics/download/File list-detail 改用 `generatedImageId`、canonical `referenceKey`、`sourceType` 和 READY stable content；Rubrics 经 `PublishedAssetReader` 读取；旧 download bucket 猜测入口已删除；SUCCESS/PENDING 已从公开 Task 查询 SQL 排除。
- [ ] Milestone 5 验收待完成：最新 public visibility 单测已编写但未运行；Task 删除后资产独立、File 删除后的诊断行为、Published Asset 再次作为 DAG/Imagegen source、Outputs/mention 的端到端验收尚未完成。
- [x] (2026-07-18) Milestone 5 的 public visibility 与 source reuse 定向验收完成：Task outcome/query 只返回 PUBLISHED identity 的 2 项测试、App typed source/publication 的 4 项测试及 Imagegen canonical IMAGE 校验的 5 项测试均通过；Rubrics 旧 `ObjectLocation` fixture 已切到 `generatedImageId/referenceKey`。
- [x] (2026-07-18) Milestone 6 的 Task lint 子目标完成：`config/checkstyle/suppressions.xml` 中 Task 条目从 57 条清到零，Task direct Checkstyle 为 0 violations，守护搜索无输出。
- [ ] Milestone 6 其余验收待完成：完整故障注入、并发重放、删除独立性、Derived Retry 和真实 MySQL/Redis/MinIO 回归尚未完成。
- [x] (2026-07-18) Milestone 7 的静态门禁子目标完成：`mvn -q -pl pixflow-app -am -DskipTests verify` 成功；后续可见性 DTO/SQL 收紧也通过 Task/File Checkstyle 和 App 依赖链 test-compile。
- [ ] Milestone 7 其余验收待完成：相关设计/Context/父计划同步、完整相关模块测试、用户可观察端到端验收和最终计划归档尚未完成；按用户要求不再执行全仓测试。
- [x] (2026-07-18) Milestone 7 文档与增量门禁更新：同步父计划和相邻活动计划；17 模块 Rubrics publication reactor 与 App 单模块严格 verify 成功，30 模块 App 定向 test reactor 编译全部生产/测试源码并通过 4 项组合测试。完整 30 模块 strict reactor 两次超过桌面命令上限，Docker engine pipe 不存在，真实 MySQL/Redis/MinIO 故障矩阵仍未执行，因此整体计划不归档。

## Surprises & Discoveries

- Observation: `StorageKeys.resultUnit` 和 `StorageKeys.generatedUnit` 名称看似候选 key，当前却分别返回 RESULTS 和 GENERATED；DAG `pipelineResultWriter` 还直接构造 `BucketType.RESULTS`。
  Evidence: `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/StorageKeys.java` 的两个方法调用 `unitOutputKey` 后选择稳定 bucket；`pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java` 的 writer 硬编码 RESULTS。因此 MySQL fenced commit 失败时，对象已经落在稳定 bucket，无法用可见性边界排除孤儿。

- Observation: Task 当前只有 `output_minio_key` 和 `generated_copy`，没有 Published Asset identity 或 publication state，查询和下载通过 `generated_copy != null` 猜测 GENERATED/RESULTS bucket。
  Evidence: `pixflow-module-task/src/main/resources/db/migration/V1__create_process_task_tables.sql`、`ProcessResultMapper`、`TaskOutcomeQueryImpl`、`DownloadService`、`DownloadBundleBuilder` 和 `pixflow-app/.../CustomDownloadService.java` 均使用这一推断。该推断在 candidate 全部进入 TMP 后不再成立，也会把执行对象 key 错当产品身份。

- Observation: File 的 `asset_image` 只表达包内 Original Image，且 File 为实现 `SourceImageReader` 反向编译依赖 Imagegen；App 又直接 import `AssetImage` 和 `AssetImageMapper`。
  Evidence: `pixflow-module-file/pom.xml` 依赖 `pixflow-module-imagegen`，`DefaultSourceImageReader` 实现 Imagegen-owned interface；`FileTaskAssetReader` 和 `CustomDownloadService` 直接读取 File persistence。若直接把 publication 塞进 Task 或这些 mapper adapter，会形成错误所有权并让 Generated Image 无法成为 File 的一等资产。

- Observation: 多数设计文档用单个 `sourceImageId` 描述生成图来源，但 GROUP Work Unit 可以包含多个源图。
  Evidence: Task 现有 `process_result_member` 和 DAG GROUP 语义保存多个成员，单列 `asset_image.source_image_id` 无法无损表达它们。本计划要求候选携带非空、有序、去重的 `sourceImages`，并用 `asset_image_lineage_source` 保存全部来源；只有恰好一个来源时才填 `asset_image.source_image_id` 投影。不得随意选择第一张图冒充唯一来源。

- Observation: infra-ai 的 `ImageGenResult` 当前只返回 bytes、content type 和 usage，Imagegen 因而无法把真实 provider/model provenance 传给 File。
  Evidence: `pixflow-module-imagegen/src/main/java/.../DefaultImageGenExecutor.java` 和相应测试构造的 `ImageGenResult` 没有 producer identity。provider/model 必须在 AI provider boundary 被确定并随结果向上传递，不能由 Task 根据配置猜测。

- Observation: 创建本文时 `config/checkstyle/suppressions.xml` 对 `pixflow-module-task` 仍有 57 个 `<suppress>` 条目，历史统计约 560 个 suppressed lines；这些文件与本计划核心 worker、query、download、persistence 高度重叠。
  Evidence: 条目覆盖 `TaskAutoConfiguration`、`ProcessTask`、`ProcessResult`、`TaskWorker`、`WorkUnitResultRepository`、`TaskQueryServiceImpl`、download/recovery/terminal 等路径。只删除本轮碰巧移动的行号会继续保留失真的基线，因此本计划接管 Task 模块的完整清零。

- Observation: 只把公开 Task snapshot 改为 published identity 仍不够；Rubrics 的 evidence builder 和视觉模型预签名路径也曾直接消费旧 bucket/key。
  Evidence: `ImageEvidencePackBuilder` 读取 `subject.output()`，`RubricsAutoConfiguration` 从 evidence `sourceRef` 反解 `BucketType/key`。现已让 subject 保存 `generatedImageId/referenceKey`，并经 Task-owned `PublishedAssetReader` 解析稳定位置和校验 image identity。

- Observation: 首次完整静态 reactor 的最后一个阻塞来自 App 测试仍 mock File persistence mapper，而非生产代码。
  Evidence: `FileTaskAssetReaderTest` 仍构造 `AssetImageMapper`。测试已改为只 mock `AssetImageQuery` 和 `AssetImageDescriptor`，随后 App 依赖链 test-compile 与完整 strict reactor 均成功。

- Observation: File recovery 的 cleanup 循环原先没有逐项异常隔离；单条损坏 reservation 会中断同批后续 READY asset 清理。
  Evidence: recovery 测试加入首条 `recoverCleanup` 抛错、第二条仍被调用的场景；实现现以中文注释固定“单项失败不阻塞批次”的恢复不变量。

- Observation: Imagegen 的旧 `SourceImageInfo` 仍携带裸 `objectKey`，并写明 bucket 由调用方决定；虽然 validator 没有读取该字段，它会保留 Generated Image 被误当 PACKAGES 对象的错误合同。
  Evidence: `SourceImageInfo` 已收窄为 `imageId/packageId/contentType`，`FileSourceImageReader` 不再透传对象 key；对象读取继续由 Task/File typed `ObjectLocation` 端口负责，5 项 Imagegen validator 测试和 1 项 Generated Image source adapter 测试通过。

- Observation: 完整 App 静态 reactor 首次在 Rubrics 测试编译处发现旧 outcome fixture 仍传 `ObjectLocation`。
  Evidence: `TaskCompletedEvaluationListenerTest` 已改为传 `generatedImageId/referenceKey`；随后 17 模块 Rubrics strict verify 成功，证明 Rubrics 测试合同不再依赖旧存储身份。

- Observation: 双轴 Spec 审查发现 File publisher 曾把对象存储返回的 null content type 当作可接受，并且 publication command 只要求 extension 非空，未约束 MIME、声明后缀和 candidate key 后缀一致。
  Evidence: 新增两个 fail-closed 测试先复现失败；`PublishGeneratedImage` 现只接受 jpg/jpeg/png/webp 的确定 MIME 映射并校验 key 后缀，`verifyMetadata` 对 null MIME 拒绝 READY。8 模块 strict verify 和 File publisher/recovery 5 项测试随后通过。

- Observation: 双轴 Standards 审查仍发现两个跨包可见性债务：`PublicationCoordinator` 作为 public internal type 被 Task config/worker 跨子包引用；App 的 custom bundle path 仍直接依赖 Task mapper、domain error、properties 和 internal builder。
  Evidence: 这两项不影响当前 publication 定向测试，但违反 Task 设计的“外部模块只依赖 api”约束。修复需要新增 Task-owned custom bundle public port并重排 coordinator 装配，属于尚未完成的 Milestone 4 架构验收，不能把当前组合根验收写成完整通过。

- Observation: 全仓 `mvn verify` 已实际启动并连接 Docker/MinIO，但在未修改的 `pixflow-eval` `TraceRecorderTest` 偶发失败处停止；该测试单独重跑通过。
  Evidence: 全仓运行在 `TraceRecorderTest.recordsOpenAndCommittedTurnWithoutLeakingSecrets` 得到 empty，随后 `mvn -q -pl pixflow-eval '-Dtest=TraceRecorderTest' test` 成功。用户明确要求不再运行全仓测试，因此不能把完整测试门禁记为通过。

## Decision Log

- Decision: 采用“Task fenced SUCCESS checkpoint → File idempotent publication → Task conditional bind → terminal aggregation”的两阶段流程，不引入跨 MySQL 与 MinIO 的伪分布式事务。
  Rationale: MySQL 事务无法原子包含对象 copy。持久 `resultId` 是跨阶段的稳定幂等身份；File 用 `(source_task_id, source_result_id)` 唯一约束消除重放重复，Task 用 publication state 使中间状态可恢复、可诊断。
  Date/Author: 2026-07-18 / Codex

- Decision: Task 拥有 `GeneratedAssetPublicationPort`，File 拥有 `GeneratedImagePublisher`，`pixflow-app` 提供 adapter；Task 不依赖 File，File 不依赖 Task 或 Imagegen。
  Rationale: Task 决定何时发布执行结果，File 独占稳定 Asset identity、key、lineage 和内容生命周期。App 是现有组合根，适合做两个 owner-defined public API 间的翻译。
  Date/Author: 2026-07-18 / Codex

- Decision: DAG 和 Imagegen 的所有新字节都先写 `BucketType.TMP`；确定性 candidate 使用 `results/...` 前缀，生成式 candidate 使用独立 `generated/...` 前缀，稳定对象仍由 File 选择 RESULTS 或 GENERATED。
  Rationale: bucket 决定生命周期与可见性，前缀保留产物类别并便于诊断。稳定 key 只能在 File 已分配 `imageId` 后计算。
  Date/Author: 2026-07-18 / Codex

- Decision: `process_result.status=SUCCESS` 表示执行 checkpoint 已持久化，不等于图片已对用户可见；`publication_status` 明确区分 `PENDING`、`PUBLISHED`、`NOT_APPLICABLE`。图片 SUCCESS 必须 PUBLISHED 才计入 terminal completion。
  Rationale: 保留 State 只认 MySQL SUCCESS 的既有 ADR，同时防止查询/终态把 publication 间隙误报成已完成。只有文案或没有 candidate bytes 的 copy-only SUCCESS 可以 NOT_APPLICABLE。
  Date/Author: 2026-07-18 / Codex

- Decision: publication 暂时失败不把执行结果改成 FAILED，也不把 Task 终结为 FAILED；当前 owner 记录安全诊断、停止 heartbeat 并退出，由 stale recovery 提升 epoch 后重放 backlog。
  Rationale: provider/DAG 已成功，失败发生在资产物化阶段。伪装成业务失败会触发错误的 Derived Retry 并重复外部生成；维持 RUNNING 可复用同一 result identity 和 candidate。
  Date/Author: 2026-07-18 / Codex

- Decision: File 的 PUBLISHING/READY 状态与 Task 的 PENDING/PUBLISHED 状态都持久化；不使用 Redis 保存 publication progress。
  Rationale: Redis Runtime Reference 可丢且受 epoch 限制，只适合运行期加速。资产身份、幂等 reservation 和恢复判断必须经进程重启仍存在。
  Date/Author: 2026-07-18 / Codex

- Decision: 所有源图以有序、去重列表传入 File；`asset_image_lineage_source` 保存完整列表，`asset_image.source_image_id` 只作为单源投影。
  Rationale: 这同时覆盖单图 Imagegen、BRANCH 和 GROUP，不丢信息，也不制造虚假唯一 lineage。
  Date/Author: 2026-07-18 / Codex

- Decision: 本计划完整清除 Task 模块 suppression；其他被触达 Java 文件也必须以“源码 + 该文件全部 suppression”为一个原子批次清理。每个批次都先运行 `-DskipTests verify`，通过 Checkstyle/SpotBugs 后才运行 test。
  Rationale: 业务改动会让旧行号基线漂移。把 lint 放在测试前既满足用户要求，也避免测试结果建立在静态门禁失败的源码上。
  Date/Author: 2026-07-18 / Codex

- Decision: `ImageDescriptor` 的输入位置是完整 `ObjectLocation`，不再保存裸 key，也不提供从 key 猜 PACKAGES 的兼容构造器或 `objectKey()` 访问器。
  Rationale: Original 与 READY Generated Image 都可以成为下一次 DAG/Imagegen 输入；bucket 是来源事实的一部分，丢失后无法安全重建。
  Date/Author: 2026-07-18 / Codex

- Decision: Rubrics evidence 的 `sourceRef` 使用 canonical image reference，而不是 `bucket/key`；可信内容位置统一通过 `PublishedAssetReader` 取得。
  Rationale: 评分证据应绑定产品资产身份，不绑定可迁移的对象存储布局；同时可在读取时校验 reference 对应的 imageId。
  Date/Author: 2026-07-18 / Codex

- Decision: File list/detail DTO 增加 canonical `referenceKey` 和 `sourceType`，且公开 require/list 只接受 READY、未删除图片。
  Rationale: Generated Image 必须成为 File 的一等可复用资产；PUBLISHING reservation 不能通过猜测 imageId 泄漏到公开接口。
  Date/Author: 2026-07-18 / Codex

## Outcomes & Retrospective

截至 2026-07-18，生产实现已完成 candidate → checkpoint → File reservation/copy/READY → Task bind 的主体闭环。实际新增 Task V2 和 File V4 migration；Task/File 通过 owner-defined public API 由 App adapter 组合，Task↛File、File↛Task/Imagegen、App↛File persistence/internal。Task suppression 从 57 条清到零，完整 `pixflow-app -am -DskipTests verify` 已成功。

已通过的行为证据包括 File 幂等 READY replay、稳定 key/reference、多源 lineage、metadata conflict fail-closed、bounded recovery/cleanup 隔离，以及 Task backlog replay、owner-epoch bind fencing、发布失败维持 SUCCESS/PENDING、terminal PENDING gate。公开读取已切到 generated image identity；Rubrics、single/bundle download 不再把 candidate key 当稳定结果。

尚未完成的验收包括真实 MySQL/Redis/MinIO publication crash-window 矩阵、并发 publisher、Task 删除后资产独立性的真实持久化验证、Generated Image 再次作为 source 的端到端流程和全量相关模块测试。Task public visibility、Rubrics published identity、App publication/source adapters 与 Imagegen canonical IMAGE 校验已通过定向测试；17 模块严格 verify 成功。当前 Docker engine pipe 不存在，所以容器验收不能执行或记为通过。本文不得移动到 completed，也不得把 Milestone 6/7 或整体计划标记完成。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-module-dag` 把确定性处理计划执行成 Work Unit；`pixflow-module-imagegen` 调用 AI 生图并返回一个生成式产物；`pixflow-module-task` 是唯一异步执行外壳和 `process_task`/`process_result` 写侧；`pixflow-state` 通过 Task read port 读取 SUCCESS checkpoint；`pixflow-infra-storage` 提供 typed bucket、对象位置、key helper、stat/copy/delete；`pixflow-module-file` 是 Asset Library，拥有 Original/Generated Image 的身份、lineage 和稳定对象；`pixflow-app` 是组合根和 HTTP adapter；`pixflow-module-rubrics` 通过 Task 的 immutable outcome 读取评估 subject。

Candidate 指执行器已经产生、但尚未成为产品资产的临时对象。它必须位于 TMP，key 包含 task、Work Unit hash 和 epoch，允许因失败、取消或 fencing 被清理。Checkpoint 是 MySQL 中 `process_result.status=SUCCESS` 的执行事实；State 只认 checkpoint，不认 Redis 或对象是否存在。Published Asset 是 File 中 READY 的 Generated Image，拥有新的 `imageId`、canonical reference、稳定对象位置和 lineage，其生命周期独立于 Task。Fencing 是所有 Task 写操作都带当前 `runEpoch` 条件，旧 owner 的更新行数为零。Owner thread 是取得 Redisson lock 并拥有 epoch 的线程；scheduler 子线程只做计算并返回 `WorkUnitCompletion`，不能写 `process_result`、heartbeat 或 terminal state。

当前 `process_result` 只有执行字段。`output_minio_key` 将保留为 candidate key，不再作为 public output；新增 publication 字段保存状态和 File 返回的稳定身份。当前 File `asset_image` 的 `package_id` 继续表明资产所属 package，Generated Image 与来源图片属于同一 package；`source_type` 区分 ORIGINAL/GENERATED，`publication_status` 区分 PUBLISHING/READY。File 的 canonical IMAGE reference 继续由现有 Asset Reference codec 生成，不能把 MinIO key、文件名或 taskId 塞进 reference。

## Scope and Non-Goals

范围包括 Storage candidate key、DAG/Imagegen output descriptor、infra-ai producer provenance、Task publication persistence/port/recovery/terminal/query/download/cleanup、File Generated Image schema/publisher/read/content API、App adapters、Rubrics subject identity、自动配置、Maven dependencies、ArchUnit/sentinel tests、必要的 HTTP DTO 对齐、相关设计文档和 Task 全模块 lint 清零。

范围不包括 File 归档上传的 RAR/7z、完整 Materials/Outputs 新产品 API、通用 tombstone/删除全生命周期、Vision Product Visual Facts、Memory、Rubrics criterion 算法、Agent tool catalog、前端页面重设计或无关重构。可以对现有 `/api/files/packages/{id}/images`、`/api/tasks/{taskId}/results`、`/api/conversations/{conversationId}/images` 和 download 合同做本闭环必需的字段/读取来源调整，但不得借机扩展分页、筛选或新业务能力。

已完成能力不得重写：Canonical DAG、Typed Execution Plan、Work Unit key/hash、Execution Epoch、lock watchdog、heartbeat recovery、owner-thread result commit、State MySQL checkpoint、Runtime Reference epoch 校验、Derived Retry、Pixel Budget、Storage `copy` 和 stable asset helper。若差距审计发现这些能力的实际行为与 accepted ADR 冲突，只做最小修复并把证据记录在本文；不能另起一套 scheduler、checkpoint 或 retry 模型。

## Reference Documents

开始实施前先阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`CONTEXT-MAP.md`、`docs/agents/domain.md` 和 `docs/design-docs/exec-plans/` 下当时所有活动计划。本文接管的父/相邻计划是：

- `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md`，尤其 Milestone 3、总体依赖顺序、旧路径清单和全仓验收。
- `docs/design-docs/exec-plans/small-infrastructure-alignment-refactor-plan.md`，尤其 Storage Milestone 4 的 candidate 原子切换、Cache lock/runtime reference、Image Pixel Budget 和 MQ delivery/retry。
- `docs/design-docs/exec-plans/pixflow-file-development-plan.md`，尤其 Milestone 4 的 Generated Image publication、Asset identity 与删除所有权；归档范围不接管。
- `docs/design-docs/exec-plans/lint-baseline-remediation-plan.md`，确认当前批次所有权、逐文件 suppression 原子清理、Maven reactor 串行要求和最终空基线目标。
- `docs/design-docs/exec-plans/completed/execution-domain-refactor-plan.md`，只用来确认已经实施的执行域事实和历史验收，不把 completed 计划当作新目标规范。

必须阅读的 accepted ADR 是 `docs/adr/0001-work-unit-checkpoints.md`、`docs/adr/0002-redisson-lock-with-execution-fencing.md`、`docs/adr/0003-terminal-tasks-use-derived-retries.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md` 和 `docs/adr/0006-publish-successful-image-results-as-assets.md`。若 ADR 与较旧模块文档冲突，accepted ADR 优先，并在本文 Decision Log 记录处理。

必须阅读的设计文档是 `docs/design-docs/base/asset-references.md`、`docs/design-docs/module/task.md`、`docs/design-docs/module/dag.md`、`docs/design-docs/module/imagegen.md`、`docs/design-docs/module/file.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/image.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/infra/mq.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/infra/thirdparty.md`、`docs/development/linting.md` 和 `config/checkstyle/README.md`。按 `CONTEXT-MAP.md` 再阅读 `pixflow-module-task/CONTEXT.md`、`pixflow-module-dag/CONTEXT.md`、`pixflow-module-imagegen/CONTEXT.md`、`pixflow-module-file/CONTEXT.md`、`pixflow-state/CONTEXT.md`、`pixflow-infra-image/CONTEXT.md` 及实施时存在的 Storage/AI/App context。

阅读源码时至少覆盖 `StorageKeys`/`ObjectStorage`、`DagAutoConfiguration.pipelineResultWriter`、`DefaultImageGenExecutor`/`GeneratedArtifact`、`WorkUnitCompletion`、`WorkUnitResultRepository`、`TaskWorker`/`ExecutionRun`/`RecoveryService`/`TerminalStateJudge`、`ProcessResultMapper`/`TaskOutcomeQueryImpl`、Task download/query/cleanup、`asset_image` schema/entity/mapper/reference service、`DefaultSourceImageReader`、`FileTaskAssetReader`、`CustomDownloadService` 和相关自动配置/测试。先搜索再修改，不依赖本文列出的行号，因为活动计划可能已经改变文件。

## Plan of Work

### Milestone 0：冻结真实基线和跨模块合同

先保护用户工作树并确认谁正在修改 File、Task、App、lint baseline。列出活动 ExecPlan、Task/File migration、模块依赖、所有 RESULTS/GENERATED/TMP 构造点、`output_minio_key` 消费方、File persistence 越层 import 和 Task suppression。把数量与意外记录到本文，若文件已被别的活动批次修改，按其 Progress 协调，不能 restore 或覆盖。

用现有测试风格建立但暂不执行以下合同测试：Storage candidate/stable key；Task publication transition/fencing/terminal backlog；File publisher idempotency/recovery/lineage；App adapter boundary；query/download 不暴露 TMP；Task cleanup 不删除 READY asset。测试可以先因接口不存在而不能编译，但根据用户要求，写完生产代码和测试后必须先通过 lint/static analysis，不能为了看红灯先运行 Maven test。

本里程碑只做审计和测试骨架，不改变运行行为。完成标准是本文记录了准确 migration 次序、Task suppression 数、当前 Docker 状态和所有旧路径；接口命名、字段语义、状态机及 module owner 已无待实现者选择的空白。

### Milestone 1：统一 TMP candidate 和完整产物描述

修改 `pixflow-infra-storage/.../StorageKeys.java`：`resultUnit(taskId, unitKeyHash, runEpoch, ext)` 返回 `BucketType.TMP` 和 `results/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.{ext}`；`generatedUnit` 返回 TMP 和独立 `generated/{taskId}/units/...`；`resultAsset`、`generatedAsset` 继续是 File publication 使用的稳定 RESULTS/GENERATED key。扩充 `StorageKeysTest`，证明两种 candidate 都是 TMP、前缀不同、epoch 不同 key 不同、路径穿越被拒绝。

把 `DagAutoConfiguration.pipelineResultWriter` 改为调用 `StorageKeys.resultUnit`，删除硬编码 RESULTS。`DefaultImageGenExecutor` 继续调用 `StorageKeys.generatedUnit`，但修正 `generatedBucket()`、Javadoc 和测试，不再宣称 executor 已发布稳定 Generated Asset。`WorkUnitResultRepository.verifyArtifact` 按 completion 携带的 typed `ObjectLocation` 验证 TMP candidate，不再根据 `generated_copy` 猜 bucket。候选存在性、size 和 content type 在 fenced SUCCESS 前验证；candidate 写入成功但 DB commit 失败时只留下可清理 TMP，不进入任何 public query。

先在 `com.pixflow.module.task.api.publication` 建立 `CandidateKind`、`SourceImageIdentity` 和 `ProducerIdentity` 三个 Task-owned value type。`ProducerIdentity` 固定含 `kind/provider/model/tool/nodeId`：GENERATIVE 要求 provider/model non-blank，DETERMINISTIC 要求 tool/nodeId non-blank，另一组字段为 NULL。再在 `pixflow-module-task/src/main/java/com/pixflow/module/task/internal/worker/CandidateArtifact.java` 建立不可变 record，字段为 `ObjectLocation location`、`long size`、`String contentType`、`String extension`、`CandidateKind DETERMINISTIC|GENERATIVE`、有序 `List<SourceImageIdentity> sourceImages` 和 `ProducerIdentity producer`。扩展 `WorkUnitCompletion.Succeeded`，使 scheduler 子线程只返回这个 descriptor 和 copy 文案，不传裸 key。source 列表必须非空、有序、按 image identity 去重；文案或真正无新字节的 copy-only completion 不含 candidate。

在 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/imagegen/ImageProducer.java` 新增不可变 `record ImageProducer(String provider, String model)`，两个值都要求 non-blank；给同 package 的 `ImageGenResult` 增加必填 `ImageProducer producer` component。provider adapter 在发起请求时就知道这两个实际值，必须把它们放入 result；Imagegen 把它映射为 Candidate producer。确定性 DAG 使用稳定的 producer kind/tool/node identity，model 为空，不能伪造 AI provider。保持 probe-before-decode、全 JVM weighted Pixel Budget、异常释放和 raster ownership 不变。

这个批次修改生产代码和测试后，先删除所有被触达 Java 文件的全部 Checkstyle suppression，运行 Storage/AI/Image/DAG/Imagegen/Task 的 `-DskipTests verify`；只有静态门禁成功才运行对应测试。完成标准是新执行只在 TMP 写 candidate，RESULTS/GENERATED 只能由 stable asset helper 命中，旧 epoch 和恶意 key 测试通过。

### Milestone 2：Task 持久化 publication backlog 并控制终态

新增当前 Task migration 序列的下一版本；创建本文时只有 V1，因此目标文件是 `pixflow-module-task/src/main/resources/db/migration/V2__add_result_publication_state.sql`。如果实施前已有 V2，使用下一个未占用版本并更新本文。给 `process_result` 增加：`candidate_bucket VARCHAR(32)`，保留 `output_minio_key` 作为 candidate key；`candidate_content_type VARCHAR(128)`、`candidate_extension VARCHAR(32)`、`producer_kind VARCHAR(32)`、`producer_provider VARCHAR(128)`、`producer_model VARCHAR(128)`、`producer_tool VARCHAR(128)`、`producer_node_id VARCHAR(128)`；`publication_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE'`；`generated_image_id BIGINT`、`published_reference_key VARCHAR(512)`、`published_at DATETIME(3)`、`publication_attempt_count INT NOT NULL DEFAULT 0`、`publication_last_error VARCHAR(1000)`。为 `status, publication_status, task_id` 建 backlog index。新图片 result 在创建/提交 SUCCESS 时设 PENDING；文案/copy-only 设 NOT_APPLICABLE。开发期既有行必须通过明确 migration policy 处理，不能把未知稳定 key 静默当新 candidate 重发；若没有需保留的数据，在测试 fresh schema 中明确这一事实。

在 `com.pixflow.module.task.api.publication` 新增 Task-owned API；`CandidateKind`、`SourceImageIdentity` 和 `ProducerIdentity` 也放在该 package，都是校验后不可变 record/enum：

    public interface GeneratedAssetPublicationPort {
        PublishedGeneratedAsset publish(GeneratedAssetCandidate candidate);
    }

    public record GeneratedAssetCandidate(
            long taskId,
            long resultId,
            String unitKey,
            long resultRunEpoch,
            long packageId,
            ObjectLocation candidate,
            long size,
            String contentType,
            String extension,
            CandidateKind kind,
            List<SourceImageIdentity> sourceImages,
            ProducerIdentity producer) {
    }

    public record PublishedGeneratedAsset(
            long imageId,
            String referenceKey) {
    }

这些 DTO 只能引用 JDK、Task-owned types、common、Asset Reference contract 和 infra-storage 的公开 value type，不能 import File entity/mapper/service。Task internal `CandidateArtifact` 在调用 port 前显式映射为这个 DTO，不能从 public signature 泄漏 internal type。Task 的 Maven dependency 不得新增 File。

重构 result commit，使真正持 Redisson lock 的 owner thread按以下固定顺序处理每个 `WorkUnitCompletion`：验证 `LockGuard` 仍由当前线程持有；验证 `process_task.status=RUNNING AND run_epoch=:ownerEpoch`；stat 并校验 TMP candidate；在一个短 MySQL 事务内以 task owner epoch 条件把对应 result 从非 SUCCESS 更新为 SUCCESS/PENDING、把 completion epoch 固化为 `resultRunEpoch` 并取得持久 `resultId`；提交后再次检查 ownership，调用 publication port；File 返回后按 `task_id/result_id/status=SUCCESS/publication_status=PENDING/result run epoch` 锁定不可变 checkpoint，并通过 `process_task.status=RUNNING AND run_epoch=:ownerEpoch` fence 当前发布 owner，绑定 imageId/reference 并置 PUBLISHED。每个 update 影响行数必须精确检查，零行表示 fenced 或已由恢复者处理，不能继续写 heartbeat、progress 或 terminal。

如果 File 已 READY 但 Task bind 失败，旧 owner 不删除 File asset，也不创建第二个；新 owner 用同一 taskId/resultId 和原 `resultRunEpoch` 重放 port，File 返回同一 imageId，再以自己的 higher `ownerEpoch` 补绑定。result epoch 是候选与执行 checkpoint 的不可变 provenance，owner epoch 是本次 Task 写权限，两者不能混为一个条件。若 publication 抛出可重试错误，Task 只递增 attempt 和保存安全诊断，保持 result SUCCESS/PENDING 与 task RUNNING，停止 heartbeat 并退出；RecoveryService 将其视为 stale work。不可重试的“candidate 和 stable 都不存在”保持 RUNNING 且暴露 operator diagnostic，不改写为 provider/DAG failure；在产品策略另有 ADR 前不得自动制造新生成调用。

恢复 owner 获得新 epoch 后，必须先查询该 task 的 SUCCESS/PENDING backlog，并逐项重放 publication；不能像当前逻辑一样见到 SUCCESS 就跳过。backlog 全部成为 PUBLISHED/NOT_APPLICABLE 后才规划未完成 Work Unit，最后才做 terminal aggregation。终态判定把“SUCCESS 且需要 publication 但未 PUBLISHED”视为未完成；heartbeat、progress 和 terminal update 均继续带 epoch fencing。State checkpoint 仍可读取 SUCCESS 执行事实，但 public Task outcome 直到 PUBLISHED 才返回图片。

本批次先清理触达 Task 文件 suppression，运行 Task/State/Cache/MQ 的严格 verify，再运行 unit/integration tests。完成标准是 owner 失锁后旧 heartbeat/result/bind/terminal update 都为零；SUCCESS 后 publication 前崩溃能由 higher epoch owner 继续；publication 失败不会触发 Derived Retry 或把来源 Task 改成 FAILED。

### Milestone 3：File 幂等发布 Generated Image

新增 `pixflow-module-file/src/main/resources/db/migration/V4__generated_image_publication.sql`，并同步 `pixflow-module-file/src/main/resources/schema.sql` 的 fresh-schema 定义。不要修改已存在的 V2/V3 migration。扩展 `asset_image`：`source_type VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL'`、`publication_status VARCHAR(16) NOT NULL DEFAULT 'READY'`，并允许 GENERATED 的 `original_path` 为空；增加 `candidate_bucket VARCHAR(32)`、`candidate_key VARCHAR(512)`、`stable_bucket VARCHAR(32)`、`content_type VARCHAR(128)`、`byte_size BIGINT`、`source_task_id BIGINT`、`source_result_id BIGINT`、`source_unit_key VARCHAR(512)`、`source_run_epoch BIGINT`、`source_image_id VARCHAR(64)`、`producer_kind VARCHAR(32)`、`producer_provider VARCHAR(128)`、`producer_model VARCHAR(128)`、`producer_tool VARCHAR(128)`、`producer_node_id VARCHAR(128)`、`publication_error VARCHAR(1000)`、`publication_updated_at DATETIME(3)`、`ready_at DATETIME(3)`、`cleanup_status VARCHAR(32)`、`cleanup_attempt_count INT NOT NULL DEFAULT 0` 和 `cleanup_last_error VARCHAR(1000)`。`minio_key` 只保存 READY stable key，不在 PUBLISHING reservation 时冒充稳定位置。建立唯一键 `uq_asset_image_source_result(source_task_id, source_result_id)`，以及覆盖 `publication_status, publication_updated_at` 和 `cleanup_status, publication_updated_at` 的 recovery index。

新增 `asset_image_lineage_source`，至少含 `id`、`asset_image_id`、`ordinal`、`source_image_id VARCHAR(64)`、`created_at`，并分别以 `(asset_image_id, ordinal)` 和 `(asset_image_id, source_image_id)` 唯一。每次 reservation 在同一事务写完整有序 lineage；输入有重复时在进入 File 前已稳定去重，File 仍防御验证。一个 source 时同时填 `asset_image.source_image_id`，多个 source 时该列为 NULL，不能保存第一项投影。

File owner-defined public API 位于 `com.pixflow.module.file.api.publication`。其中 `GeneratedImageKind`、`SourceImageRef` 和 `GeneratedImageProducer` 是 File-owned value type，不复用或 import Task DTO：

    public interface GeneratedImagePublisher {
        PublishedImage publish(PublishGeneratedImage command);
    }

    public record PublishGeneratedImage(
            long sourceTaskId,
            long sourceResultId,
            String sourceUnitKey,
            long sourceRunEpoch,
            long packageId,
            ObjectLocation candidate,
            long size,
            String contentType,
            String extension,
            GeneratedImageKind kind,
            List<SourceImageRef> sourceImages,
            GeneratedImageProducer producer) {
    }

    public record PublishedImage(
            long imageId,
            String referenceKey,
            ObjectLocation stableObject) {
    }

接口放在 File `api` 边界，内部 entity、mapper 和 recovery service 放在 `internal`。File 不 import Task 的 port DTO；App adapter 负责字段翻译。命令构造时校验正 ID、非空 source list、TMP candidate、允许的 content type/extension、size 和 producer。stable key 在 reservation 取得新 `imageId` 后由 `StorageKeys.resultAsset` 或 `generatedAsset` 计算；确定性结果落 RESULTS，AI 生成结果落 GENERATED，但两者在 File 都是 `source_type=GENERATED`。

实现固定状态机。第一步短事务按 `(sourceTaskId, sourceResultId)` 创建或读取 PUBLISHING reservation，分配 imageId、写 candidate/stable location 和 lineage。第二步事务外 `ObjectStorage.stat(candidate)`，校验 size/content type，再执行 TMP→stable `copy`。第三步 stat stable，至少校验 size、content type，并在能力存在时校验 etag/checksum。第四步短事务以 `id AND publication_status=PUBLISHING` finalize READY，写 `minio_key`、ready time 和 cleanup pending。第五步在 commit 后删除 candidate；删除成功标 CLEANED，失败保留 CLEANUP_PENDING 由 bounded scanner 重试。不得先删除 candidate 再提交 READY。

重放 PUBLISHING 时先 stat stable：匹配 reservation metadata 就直接 finalize；stable 不存在而 candidate 存在则重新 copy；stable 存在但 metadata 不匹配必须 fail closed 并记录冲突；两者都不存在则保持 PUBLISHING 并写 `publication_error` 供诊断，不分配新 imageId。重放 READY 直接返回同一 imageId/reference。并发请求由唯一约束和“insert-or-read”处理，不依赖 JVM 锁；重复 lineage insert 必须幂等。File startup/scheduled recovery 只扫描 bounded PUBLISHING 和 CLEANUP_PENDING，使用行级 claim/CAS 防止多实例重复工作。

File 的 reference resolver、image list/detail、permission proof、content reader 和删除逻辑只把 READY 当可见/可用；PUBLISHING 不出现在 canonical resolve、Materials、Outputs 或 mention。Original Image migration 默认 source_type ORIGINAL/status READY，既有行为不变。Task/Activity 删除无权删除 READY Generated Image；只有 File 删除 API 可以 tombstone 资产并按 File 计划管理 stable object。

先清理触达 File/Storage 文件 suppression并运行严格 verify，再运行 File+Storage 的 unit、MySQL/MinIO Testcontainers 和并发测试。完成标准是四个崩溃点均可恢复：reservation 后、copy 后 READY 前、READY 后 candidate delete 前、File READY 后 Task bind 前；同一 result 并发/重放始终一个 READY imageId。

### Milestone 4：在 App 组合根建立正确依赖方向

在 `pixflow-app` 新增 adapter，例如 `TaskGeneratedAssetPublicationAdapter implements GeneratedAssetPublicationPort`，构造参数只接收 File 的 `GeneratedImagePublisher`，完成 Task DTO→File DTO 映射并丢弃 File stable object 等 Task 不应持久化的细节。自动配置显式装配 adapter；Task auto-configuration 对 missing port 必须 fail closed 或提供仅在无图片执行场景可用的拒绝实现，不能用“发布成功”的 no-op。

为 File 提供 owner-defined `AssetImageQuery`/`AssetContentReader`（名称可与当前工作树已经新增的 public API 合并，但职责必须等价）：按 package/image 或 canonical reference 读取 READY `AssetImageDescriptor` 和 typed stable `ObjectLocation`，列出可作为 Task source 的 READY Original/Generated images，读取下载 metadata。App 的 `FileTaskAssetReader`、`CustomDownloadService` 和 Imagegen source adapter 只调用这些 public API，不 import `AssetImage`、`AssetImageMapper` 或 File internal package。

删除 `pixflow-module-file/pom.xml` 对 `pixflow-module-imagegen` 的依赖和 File 内 `DefaultSourceImageReader implements Imagegen.SourceImageReader`。若 Imagegen 仍拥有 `SourceImageReader` port，由 App adapter 使用 File public query/content API 实现；File 自身不知道 Imagegen。增加 ArchUnit或 Maven/sentinel 守护：Task 不依赖 File；File 不依赖 Task/Imagegen；App 可以依赖各 owner public API但不能 import File persistence/internal；DAG/Imagegen 不写 Task tables；scheduler child 不得到 Task mapper。

`FileTaskAssetReader` 不再把所有 source 硬编码为 PACKAGES；它使用 File descriptor 的 typed object location，所以 Generated Image 可以成为下一次 Imagegen 或 DAG 的输入。resolver 仍验证 canonical reference 所属 package、READY、未删除和 use policy。先运行 File/Imagegen/Task/App strict verify，成功后运行 adapter、context、ArchUnit 和 Spring auto-configuration tests。

### Milestone 5：公开结果只使用 Published Asset identity

修改 `TaskOutcomeQuery.SuccessfulResultSnapshot`：保留 resultId、taskId、unit/sku/group/view/branch、bytesOut、completedAt 等不可变执行事实；图片 subject 改为 `generatedImageId` 和 `publishedReferenceKey`，不返回 candidate `ObjectLocation`。如果 Rubrics 需要读取 bytes，由 App/Rubrics 通过 File `AssetContentReader` 解析 published identity；不能把 TMP key重新塞进 snapshot。只查询 SUCCESS 且 publication_status 为 PUBLISHED 的图片或 NOT_APPLICABLE 的非图片。

修改 `TaskQueryServiceImpl`、`TaskQueryController` DTO、`/api/tasks/{taskId}/results`、`/api/conversations/{conversationId}/images`、`DownloadService`、`DownloadBundleBuilder`、`CustomDownloadService` 和前端最小 DTO 映射：图片结果返回/消费 imageId/referenceKey；预览和下载经 File public content API 得到 stable location。删除所有用 `generated_copy != null` 在 RESULTS/GENERATED 间猜 bucket、把 `output_minio_key` 放进 public view、或直接读 File mapper 的路径。文案结果可以继续返回 copy 内容，但不能被误判为图片。

Task result soft delete/Task cleanup 只隐藏/删除 execution row、runtime key 和未发布 TMP candidate。删除代码不得按 task prefix 删除 RESULTS/GENERATED，因为 stable key 由 File/imageId 拥有且可能被后续对话、Vision 或 Rubrics 引用。Failed、Cancelled、Skipped、fenced 和 SUCCESS/PENDING candidate 都不能进入 Outputs；对 PENDING 的手工清理必须先确认没有 File PUBLISHING/READY reservation，避免删掉 recovery source。

补行为测试：deterministic 与 Imagegen 两条路径都返回 published identity；下载读取 stable object；candidate 单独存在时 API 无结果；Task 删除后 File image仍可查询、下载、resolve和 mention；File 删除后 Task execution fact仍可诊断但内容读取按 File tombstone policy失败；Rubrics snapshot只引用 Published Asset。按固定顺序先 lint/static verify，后运行 Task/File/Rubrics/App/Web tests。若本批不改前端，不运行无关 Web formatter；若改前端，必须先 `pnpm --dir pixflow-web lint`、`typecheck`，再 `test`、`build`。

### Milestone 6：故障矩阵、Task lint 清零和回归

用真实 MySQL、Redis 和 MinIO/Testcontainers 覆盖执行到发布全链。至少证明：Redis 断连时 lock acquisition/ownership check fail closed；owner 失锁或 higher epoch 被 claim 后，旧 heartbeat/result/bind/progress/terminal 写均影响零行；Redis Runtime Reference miss/epoch mismatch 只导致重算，不冒充 checkpoint；Pixel Budget 在 decode 前准入且成功/异常都释放；candidate 写入但 fenced DB commit 失败时不发布、不查询；SUCCESS 后 publication 前崩溃可恢复。

File 测试至少证明：reservation 后崩溃重放；copy 后 READY commit 前崩溃从 stable stat finalize；READY commit 后 candidate delete 前崩溃由 cleanup scanner 删除；File 已发布但 Task bind 失败时重放返回同 imageId；同一 result 的两个并发 publisher 只有一个 reservation/READY image；stable metadata 冲突 fail closed；candidate/stable 都缺失保留诊断；单源和 GROUP 多源 lineage 的顺序、去重和投影正确。

端到端还要覆盖 deterministic 和 Imagegen、copy-only NOT_APPLICABLE、failed/cancelled/fenced candidate 不可见、task 清理后 asset 独立存在、Published Asset 可作为新任务 source、Derived Retry 创建新 taskId 且来源终态不变、重试任务产生自己的 result/publication identity。publication 暂时失败不得再次调用 AI provider；可以通过 provider invocation counter 证明。

在运行任何上述 test 之前，完整清理 `config/checkstyle/suppressions.xml` 中所有 `pixflow-module-task` 条目，不只清理触达文件。修复限于真实 Checkstyle 问题和本计划业务重构；纯 lint 文件保持行为等价。每个文件删除全部对应 suppression，运行 Task reactor strict verify，直到 `rg -n 'pixflow-module-task' config/checkstyle/suppressions.xml` 无输出。其他触达文件同样不能留下漂移行号。不得新增 suppression、降低规则、关闭 SpotBugs、并行 Maven reactor 或把 `-DskipTests verify` 记为测试通过。

静态门禁全绿后才运行完整故障 tests。任何 Docker/Testcontainers skipped 都是未通过：先检查 Docker；环境不可用时把本里程碑记录为 blocked evidence，不删除、禁用或条件跳过测试，也不勾选完成。

### Milestone 7：文档、全仓门禁和用户验收

同步 `docs/design-docs/module/task.md`、`docs/design-docs/module/file.md`、`docs/design-docs/module/dag.md`、`docs/design-docs/module/imagegen.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/infra/storage.md` 和相关模块的 repository-relative `CONTEXT.md`，明确 candidate/checkpoint/published asset、两组 publication states、owner-thread 与恢复顺序、多源 lineage、query/download/cleanup 所有权。更新三个被接管活动计划的 Progress/Decision/Revision Notes：只把确实交付的 Storage candidate、File publication、Task lint 项标完成；不误标 File 归档、总计划其他 Milestone 或全仓 lint。

从仓库根串行执行全仓后端 strict verify；成功后才运行完整 `mvn verify`。若触达 Web，先 lint/typecheck 再 test/build。检查零 skipped、旧 path 搜索、依赖守护、migration fresh-schema 和 `git diff --check`。启动应用或用集成测试走一条真实任务，观察 task result 只有 published reference；删除 task 后 image 仍从 File 返回；再次以该 IMAGE reference 创建 redraw，证明 Generated Image 是独立可复用资产。

全部证据写回本文 Progress、Discoveries、Outcomes 和 Revision Notes，然后把父计划 Milestone 3 标记完成，并将本文移动到 `docs/design-docs/exec-plans/completed/`（如果仓库惯例要求 completed 计划只在所有门禁完成后移动）。只要容器测试有 skipped、Task suppression 有残留、任一 public path 暴露 TMP、或 crash window 未覆盖，就不得宣布完成。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行，Maven 命令必须串行。每次开始或恢复先审计：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs/exec-plans config/checkstyle/suppressions.xml
    rg --files pixflow-module-task/src/main/resources/db/migration pixflow-module-file/src/main/resources/db/migration
    rg -n "pixflow-module-task" config/checkstyle/suppressions.xml
    docker info

查找旧存储和越层路径：

    rg -n "BucketType\.(RESULTS|GENERATED)|resultUnit|generatedUnit|output_minio_key|outputMinioKey|generated_copy" pixflow-infra-storage pixflow-module-dag pixflow-module-imagegen pixflow-module-task pixflow-app pixflow-module-rubrics
    rg -n "AssetImageMapper|com\.pixflow\.module\.file\.(image|internal|persistence)" pixflow-app pixflow-module-task pixflow-module-imagegen
    rg -n "pixflow-module-(file|task|imagegen)" pixflow-module-task/pom.xml pixflow-module-file/pom.xml pixflow-module-imagegen/pom.xml

Milestone 1 修改和测试写完后，先运行 lint/static analysis：

    mvn -pl pixflow-infra-storage,pixflow-infra-ai,pixflow-infra-image,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task -am -DskipTests verify

只有上一条 BUILD SUCCESS 后才运行测试：

    mvn -pl pixflow-infra-storage,pixflow-infra-ai,pixflow-infra-image,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task -am test

Milestone 2 每个 Task 批次先静态后测试：

    mvn -pl pixflow-module-task,pixflow-state,pixflow-infra-cache,pixflow-infra-mq -am -DskipTests verify
    mvn -pl pixflow-module-task,pixflow-state,pixflow-infra-cache,pixflow-infra-mq -am test

第二条只能在第一条成功后执行。先用 unit tests 验证 transition，再用真实 MySQL/Redis integration tests 验证 affected-row fencing 和 stale recovery；mock 返回值不能作为唯一 fencing 证据。

Milestone 3/4/5 使用完整 publication reactor，同样先静态后测试：

    mvn -pl pixflow-infra-storage,pixflow-module-file,pixflow-module-task,pixflow-module-imagegen,pixflow-module-dag,pixflow-state,pixflow-module-rubrics,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-infra-storage,pixflow-module-file,pixflow-module-task,pixflow-module-imagegen,pixflow-module-dag,pixflow-state,pixflow-module-rubrics,pixflow-app -am test

验证 Task suppression 和旧路径清零：

    rg -n "pixflow-module-task" config/checkstyle/suppressions.xml
    rg -n "BucketType\.(RESULTS|GENERATED)" pixflow-module-dag/src/main pixflow-module-imagegen/src/main pixflow-module-task/src/main
    rg -n "generated_copy.*BucketType|outputMinioKey.*ObjectLocation|output_minio_key.*(RESULTS|GENERATED)" pixflow-module-task pixflow-app pixflow-module-rubrics
    rg -n "AssetImageMapper|com\.pixflow\.module\.file\.(image|internal|persistence)" pixflow-app/src/main pixflow-module-task/src/main pixflow-module-imagegen/src/main

第一条最终必须无输出。第二条允许 File publication stable helper 和明确的非 candidate 读取，不允许 DAG/Imagegen executor 或 Task query/download 直接命中稳定 bucket；每个保留命中都在 Artifacts 中解释。第三、四条生产源码预期无输出。

若触达前端，严格顺序是：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

`test` 和 `build` 只能在 lint/typecheck 都成功后运行。后端最终顺序是：

    mvn -DskipTests verify
    mvn verify
    rg -n "Tests run:.*Skipped: [1-9]" -g "*.txt" .
    git diff --check
    git status --short

`mvn -DskipTests verify` 必须是 30 项 reactor 的零 Checkstyle/SpotBugs BUILD SUCCESS；之后 `mvn verify` 才是测试证据。skipped 搜索必须无输出。若报告格式不同，检查所有 `target/surefire-reports` 和 `target/failsafe-reports` 的 XML `skipped` 属性。Docker 不可用是未满足验收，不是通过。

## Validation and Acceptance

静态验收要求所有触达文件在无新增 suppression 下通过 Checkstyle/SpotBugs，Task 模块 suppression 为零，模块依赖守护证明 Task↛File、File↛Task/Imagegen、App↛File persistence/internal。`StorageKeys` 的 candidate 测试显示两个执行路径都使用 TMP；生产搜索找不到 DAG/Imagegen/Task 把 candidate直接写稳定 RESULTS/GENERATED 的路径。

执行验收要求 scheduler child 只返回 completion。真实 Redis 断连时 lock fail closed；旧 epoch 的 heartbeat、SUCCESS、publication bind、progress 和 terminal update 均为零；Runtime Reference miss 只重算；Pixel Budget decode 前拒绝超限并在所有出口释放。candidate 已写但 fenced SUCCESS 失败时，Task API、Outputs、File resolver 都看不到该对象。

publication 验收要求 `process_result` 先形成 SUCCESS/PENDING checkpoint，File 以 sourceResultId reservation。正常路径只有一个 PUBLISHING→READY asset 和一个 Task PENDING→PUBLISHED bind。分别在 SUCCESS 后、reservation 后、copy 后、READY 后和 bind 前注入崩溃，恢复后都得到相同 imageId/reference，candidate 最终被清理。并发重放不产生第二行或第二个 stable key；metadata 冲突 fail closed。

lineage 验收要求单图 redraw 的 source projection 与 lineage row 相同；GROUP 的全部有序去重 source 都在 lineage table，`asset_image.source_image_id` 为 NULL。File 返回真实 provider/model；Task 不从配置推断。确定性输出也有 producer kind/tool provenance但 model 为空。

公开行为验收要求 `/api/tasks/{taskId}/results` 和 `/api/conversations/{conversationId}/images` 只返回 PUBLISHED 图片的 generatedImageId/referenceKey，不暴露 TMP/object key。preview、single download 和 bundle download 经 File 读取 stable object。Failed、Cancelled、Skipped、fenced、PENDING 都不进入 Outputs。Rubrics outcome 指向 published identity。

生命周期验收要求删除 Task/result/Activity 后，READY Generated Image 仍在 File list/detail/content/resolve 中，并可作为新 Proposal/Task 的 IMAGE source；Task cleanup 不调用 stable task prefix delete。只有 File delete 能按 File policy隐藏/删除资产。Derived Retry 新建 taskId，来源 task 终态和 Published Asset 不变。

完整 Maven verify、Web（若触达）四门禁和真实容器测试必须零 failure/error/skipped。一次 `-DskipTests verify`、mock 单测或 Docker 不可用时的 skip 都不能代替该标准。

## Idempotence and Recovery

所有 migration 只新增或放宽字段，已存在 V2/V3 不修改；fresh `schema.sql` 与 Flyway 最终形态一致。开始实施时若版本被占用，只改新 migration 的版本号并在本文记录，不重命名已经执行过的 migration。唯一约束和 CAS 使 publisher/recovery 可重复运行。

File publication 的恢复身份永远是 `(sourceTaskId, sourceResultId)`。READY 重放只返回；PUBLISHING 根据 stable/candidate stat 继续；两者都缺不新建身份。Task bind 以 result/epoch/status 条件重复执行；若新 owner 已绑定，旧 owner 零行退出。candidate cleanup 发生在 READY commit 后且可重试，删除不存在对象视为幂等成功，但 metadata mismatch 不能被吞掉。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。工作树已有 File/Conversation/Context 等改动时，在它们之上做最小补丁；有语义冲突就先更新本文并说明，不用旧版本覆盖。自动格式化不得跨越显式文件清单。

lint 的恢复单位是“一个 Java 文件的业务/格式修改 + 该文件全部 suppression 删除”。中途不能提交只删 suppression 但源码不通过，或只移动行号。Maven reactor 串行运行；共享 target/SpotBugs 临时文件不允许并行竞争。

publication 失败时不能删除 SUCCESS checkpoint或再次调用 AI。先恢复资产发布；只有源 Work Unit 自身 FAILED 才适用 Derived Retry。operator 修复丢失对象等不可自动恢复问题后，应重放同一 result identity或显式执行有审计的重新计算策略；本文不授权后台静默生成新业务结果。

## Artifacts and Notes

创建计划时的关键基线：

    StorageKeys.resultUnit       -> RESULTS/results/{task}/units/{hash}/epochs/{epoch}/output.ext
    StorageKeys.generatedUnit    -> GENERATED/results/{task}/units/{hash}/epochs/{epoch}/output.ext
    process_result               -> no publication status/image/reference
    asset_image                  -> Original-only, package_id + minio_key + original_path
    File -> Imagegen             -> compile dependency through DefaultSourceImageReader
    App -> File persistence      -> FileTaskAssetReader/CustomDownloadService mapper imports
    Task Checkstyle suppression  -> 57 entries, about 560 historical lines

2026-07-18 已取得的 concise evidence：

    mvn -q -pl pixflow-app -am -DskipTests verify
      -> BUILD SUCCESS（完整 strict static reactor）
    mvn -q -pl pixflow-module-task -DskipTests checkstyle:check
      -> BUILD SUCCESS，0 violations
    rg -n "pixflow-module-task" config/checkstyle/suppressions.xml
      -> no matches
    App/Task/Imagegen production imports of File mapper/entity/internal
      -> no matches
    DefaultGeneratedImagePublisherTest + GeneratedImagePublicationRecoveryTest
      -> 3 tests passed
    PublicationCoordinatorTest + TerminalStateJudgeTest
      -> passed
    docker info --format '{{.ServerVersion}}'
      -> 29.5.3
    mvn -q verify
      -> NOT PASSED；停止于未修改 pixflow-eval 的一次 TraceRecorderTest 偶发失败

目标对象流：

    scheduler child
      -> WorkUnitCompletion(CandidateArtifact in TMP)
      -> owner validates lock + epoch + candidate
      -> MySQL process_result SUCCESS/PENDING checkpoint
      -> Task GeneratedAssetPublicationPort
      -> App adapter
      -> File GeneratedImagePublisher
      -> asset_image PUBLISHING reservation + lineage
      -> TMP copy to stable RESULTS/GENERATED
      -> asset_image READY
      -> Task conditional bind PUBLISHED
      -> terminal aggregation and public query

实施时在此追加 concise evidence：migration diff、dependency tree、state transition SQL affected rows、MinIO before/after object listing、并发 publisher row count、crash injection logs、Task suppression search、test summary 和用户端 JSON 样例。不要粘贴海量日志。

## Interfaces and Dependencies

Task 的 persistence transition 必须表达下列不变量，具体 mapper 名可融入现有 `ProcessResultMapper`，但 SQL 条件不能弱化：

    UPDATE process_result r
    SET r.status = 'SUCCESS',
        r.run_epoch = :resultRunEpoch,
        r.publication_status = :publicationStatus,
        r.output_minio_key = :candidateKey,
        ...
    WHERE r.task_id = :taskId
      AND r.unit_key = :unitKey
      AND r.status <> 'SUCCESS'
      AND EXISTS (
          SELECT 1 FROM process_task t
          WHERE t.id = r.task_id
            AND t.status = 'RUNNING'
            AND t.run_epoch = :ownerEpoch)

    UPDATE process_result r
    SET r.publication_status = 'PUBLISHED',
        r.generated_image_id = :imageId,
        r.published_reference_key = :referenceKey,
        r.published_at = :now
    WHERE r.id = :resultId
      AND r.task_id = :taskId
      AND r.status = 'SUCCESS'
      AND r.publication_status = 'PENDING'
      AND r.run_epoch = :resultRunEpoch
      AND EXISTS (... current RUNNING task at :ownerEpoch ...)

SUCCESS transition 必须返回或随后在同事务按 `(task_id, unit_key)` 读取持久 resultId。不能把 unit hash、candidate key 或 auto-increment guess 当 sourceResultId。PUBLISHED bind 保存 File canonical reference 的原样值并可在边界重新 parse 校验。恢复发布时 `resultRunEpoch` 保持 SUCCESS checkpoint 首次提交的值，只有 `ownerEpoch` 随 stale recovery 增长；SQL 必须同时表达这两个角色。

Task terminal predicate 在既有 failure isolation/partial outcome 规则上增加 publication gate：所有 selected Work Unit 已达到既有执行终态；每个有 candidate bytes 的 SUCCESS 都 PUBLISHED；非图片 copy-only 是 NOT_APPLICABLE；没有 PENDING publication；当前 task/owner epoch 仍 RUNNING。既有 FAILED、SKIPPED、取消和部分成功的聚合语义不变，publication error 不参与 FAILED 计数。

File reservation SQL 依赖 `(source_task_id, source_result_id)` unique key。并发 insert duplicate 后读取现有行并核对 package、unit、candidate、size、content type、kind、source list 和 producer；任一不一致都是 idempotency conflict。READY reference 由 `AssetReferenceKey`/codec owner生成，File public response可包含 stable `ObjectLocation` 供可信 adapter 使用，但 Task public HTTP DTO不得包含它。

模块依赖目标是：

    infra-storage <- DAG, Imagegen, Task, File
    infra-ai      <- Imagegen
    Task public publication port <- App adapter -> File public publisher
    Imagegen SourceImageReader   <- App adapter -> File public read/content API
    Rubrics -> Task public outcome; App/Rubrics adapter -> File public content API

箭头表达编译依赖方向。Task 和 File 互不编译依赖；File 与 Imagegen 互不依赖；App 是唯一知道两侧 DTO 的位置。common/Asset Reference contract 可以被双方依赖，但不得把 publication 业务 DTO搬进 shared contracts 以规避正确所有权。

## Revision Notes

2026-07-18 / Codex: 创建本计划。基于父计划 Milestone 3、三个相邻活动计划、accepted ADR、模块 Context/设计和当前源码，确认执行域主体已完成，剩余实质是 TMP candidate、Task publication backlog、File Generated Image state machine、App owner-defined adapters、public query/download/cleanup 切换和 Task lint 清零。固定两阶段可恢复机制、resultId 幂等身份、GROUP 多源 lineage、真实 provider/model provenance、故障矩阵及所有测试前先 lint/static analysis；本轮只写计划，不把任何生产里程碑标记完成。

2026-07-18 / Codex: 记录本轮实施状态。已完成 TMP candidate、producer/source provenance、Task V2 publication backlog/fencing/terminal gate、File V4 Generated Image reservation/READY/lineage/recovery、App owner-defined adapters、Rubrics/download published identity、File READY public DTO，以及 Task suppression 全清；完整 strict static reactor 和定向 publication tests 已通过。Progress 已按每个 Milestone 拆分“实施/定向验证完成”和“完整验收待完成”，避免把已交付代码误读为未实施，也不把尚缺真实容器、端到端生命周期、完整相关测试和文档同步的里程碑误报为全部完成；全仓 verify 的 Eval 偶发失败及用户停止全仓测试决定已明确记录。

2026-07-18 / Codex: 继续收尾 publication 计划。按 TDD 删除 Imagegen `SourceImageInfo` 的裸 `objectKey`/默认 bucket 旧合同并迁移 App adapter；新增 Task→File DTO 无损转换和 READY Generated Image source 测试；修复 Rubrics 仍构造旧 `ObjectLocation` outcome fixture。双轴审查后又补齐 null MIME 与 MIME/extension/candidate-key 一致性的 fail-closed 校验。File publication 5 项、Task publication/visibility 8 项、Imagegen validator 5 项、App adapter 4 项均零失败/跳过；8 模块 File 与 17 模块 Rubrics strict verify 成功。Docker pipe 不存在，真实 crash-window、并发和生命周期测试仍是硬阻断，计划保持活动状态且不归档。
