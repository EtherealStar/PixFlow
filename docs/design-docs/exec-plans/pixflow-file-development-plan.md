# 将 pixflow-file 建成 canonical Asset Library 并闭合 Generated Image 生命周期

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，也应能完成 `pixflow-module-file` 的目标实现。每次停止工作都必须更新本文；若实现中改变接口、数据库迁移或验证方式，必须同步更新所有受影响章节，并在文末追加 Revision Notes。

## Purpose / Big Picture

完成后，PixFlow 的素材不再由包 ID、图片 ID、显示名或 MinIO key 等多套身份表示。用户上传 ZIP、RAR 或 7z 归档后，后端通过一个可恢复的分片协议创建 Asset Package，安全提取 Original Image，并为 Package、SKU 和 Image 生成唯一的 canonical Asset Reference。Conversation、Agent、Permission、DAG、Imagegen、Task 和 Web 只传这个引用；PACKAGE/SKU 展开、资源可用性检查、去重和删除判断都由后端 File 边界完成。

成功的处理结果不再只是会随 Task 清理而消失的 `process_result` 对象。Task 在 fenced SUCCESS 后通过端口请求发布，File 为结果分配新的 `imageId`，把 TMP candidate 复制到稳定对象位置，持久化 lineage，并返回可再次 mention 的 IMAGE reference。清除任务历史不会删除 Generated Image；只有用户删除该 Generated Image 时才删除稳定字节并留下最小 Reference Tombstone。可以通过 `/api/asset-references` 浏览 Materials 和 Outputs、通过 canonical key 解析或展开素材、通过定向测试重放同一 publication 请求，以及在清理 Task 后仍读取 Generated Image 来观察这些结果。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、活动执行计划索引、后端总重构计划、Lint 基线计划、设计文档索引与领域文档使用规则。
- [x] (2026-07-17) 阅读 File、Asset Reference、Storage、Task、Conversation 和前端 Files/API 目标设计，以及 ADR 0005、0006 和 `pixflow-module-file/CONTEXT.md`。
- [x] (2026-07-17) 审计 `pixflow-module-file` 的 POM、schema、迁移、自动配置、上传/解压、查询、删除、Permission proof、Imagegen adapter 与测试，记录目标差距和现有工作树边界。
- [x] (2026-07-17) 创建本中文 ExecPlan，固定机制、实施顺序、必读参考文档和“每轮测试前先 linting”的门禁。
- [x] (2026-07-17 23:55+08:00) Milestone 0：冻结工作树、数据库迁移策略、现有 API/测试基线和跨计划文件所有权。工作树中的 DAG/Imagegen/Task Proposal 改动已确认属于现有工作；File strict verify 在当前环境两次超过 124 秒无输出，改以成功的 compile、定向 Checkstyle 和测试记录该门禁阻断。
- [ ] Milestone 1（已完成 File 内首个切片）：新增 File `AssetReferenceResolver`、`AssetReferenceInspector`、`AssetReferenceExpander` 与不含存储位置的安全视图，默认实现位于 internal 包；Permission proof 改为先复用共享 codec 再调用 resolver。codec、resolver expansion 和 Permission proof 定向测试通过。按用户 2026-07-18 明确的 File-only 范围，Conversation 消费者迁移和旧 `PackageReference` 系列删除未执行；Generated Image source type、tombstone、`AssetContentReader` 和完整 owner fact 验证仍待完成，因此本里程碑保持未勾选。
- [ ] Milestone 2：把归档上传和提取对齐为 ZIP/RAR/7z 分片协议、格式分派、安全限制与可恢复取消。
- [ ] Milestone 3：完成 Materials/Outputs 的后端分页、搜索、层级浏览、预签名访问和 exact-key exclusion。
- [ ] Milestone 4（主体实现与定向测试已完成）：Generated Image reservation、幂等 publication、完整 lineage、bounded candidate 恢复和 Task/App 端口已落地；File publisher/recovery 3 项与 App adapters 4 项通过。真实 MySQL/MinIO 并发和四个 crash point 因 Docker engine 不可用尚未验收，故里程碑保持未勾选。
- [ ] Milestone 5：用 Reference Tombstone 替换软删除，完成 Package、Original Image、Generated Image 的真实字节清理语义。
- [ ] Milestone 6：删除旧 multipart、object-key 泄漏、旧 package/image 身份和 File -> Imagegen 反向适配，完成组合与端到端验收。

## Surprises & Discoveries

- Observation: 当前 File 已有 Redis 分片会话，但仍公开旧 `POST /api/files/packages` multipart 入口，并允许独立 `doc`；目标设计明确只允许归档分片协议，metadata 文档只能来自归档内部。
  Evidence: `pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 同时包含 session routes 和 multipart route；`docs/design-docs/frontend/api.md` 明确声明不存在 multipart endpoint。

- Observation: 当前归档事实和提取器都被写死为 ZIP；Storage 已接受 `zip|rar|7z` 的 source key，但 File 尚未持久化或分派 archive format。
  Evidence: `FileService.upload` 调用 `StorageKeys.packageSource(packageId, "zip")`，`ExtractionConsumer` 只依赖 `ZipExtractor`，POM 中没有 RAR/7z extractor dependency。

- Observation: 当前 `PackageReference` / `ImageReference` 不是 canonical Asset Reference；它们把 `objectPrefix`、`objectKey` 暴露到模块边界，并且只支持 package -> image 的旧模型。
  Evidence: `pixflow-module-file/src/main/java/com/pixflow/module/file/pkg/PackageReference.java` 和 `ImageReference.java` 公开 storage location；`DefaultPackageReferenceResolver` 接收裸 `packageId`。

- Observation: canonical key parser 已在 Permission proof 内部出现一个私有副本，但它没有验证 URL 编码是否 canonical，也不能作为全系统唯一 codec。
  Evidence: `AssetPermissionProof.Reference.parse` 使用 `URLDecoder` 后直接接受 SKU；`pixflow-contracts` 尚无 `com.pixflow.contracts.asset` 类型。

- Observation: contracts 切片已建立唯一 canonical grammar，并证明旧 File parser 会错误接受 leading-zero package id；这只替换了 permission proof 的解析，不等同于完成 File resolver/inspection/expansion。
  Evidence: `CanonicalAssetReferenceCodecTest` 38 项通过；`AssetPermissionProofTest.nonCanonicalReferenceIsDeniedBeforeReadingOwnerFacts` 证明 `package:01/image:2` 在读取 owner facts 前返回 `DENIED`。

- Observation: 当前 `asset_image` 只能表达 Original Image，缺少 source type、publication state、lineage、producer 和稳定发布幂等键；仓库中也不存在 `GeneratedAssetPublicationPort`。
  Evidence: `AssetImage` 只有 package/SKU/path/minio/display/deleted/created 字段；全仓生产源码搜索没有 Generated Asset publication 类型。

- Observation: 当前删除是旧的软删除折中。单图删除只设置 `deleted_at` 且故意保留对象；包被引用时也只设置 `deleted_at`。这与最新“删除字节和 processable row，只留无删除时间的 Reference Tombstone”冲突。
  Evidence: `FileService.deleteImage` 与 `AssetPackageService.delete`；`docs/design-docs/base/asset-references.md` 和 `docs/design-docs/module/file.md` 的 deletion 规则。

- Observation: File 已经完成 Checkstyle 基线清理，新的业务开发不应恢复 suppression；共享 Maven reactor 不能并行运行，因为 SpotBugs 临时文件会互相争用。
  Evidence: `docs/design-docs/exec-plans/lint-baseline-remediation-plan.md` 记录 File 33 个 suppression 已删除、模块 32 项测试通过，并要求 reactor 串行验证。

- Observation: File 的 `verify` 在当前环境连续两次超过 124 秒且没有 Maven 输出，但单独 `compile` 与 `checkstyle:check` 可以完成。
  Evidence: `mvn -pl pixflow-module-file -am -DskipTests verify` 和同命令在 canonical 切片后均以 timeout 结束；`mvn -pl pixflow-module-file -DskipTests checkstyle:check` 返回 0 violations，定向 resolver 测试返回 1 passed。

- Observation: Permission proof 不能只依赖可替换 resolver 负责语法准入，否则 mock 或错误实现返回空事实时会把非法 key 判为 PROVED。
  Evidence: 首次迁移测试中 non-canonical `package:01/image:2` 得到 PROVED；加入共享 codec 预解析并对 null resolver view deny 后，`AssetPermissionProofTest` 2 项全部通过。

- Observation: 双轴 Spec 审查发现首个切片曾错误勾选整个 Milestone 1，并指出 PACKAGE/SKU 伪造 source type、INSPECT expansion 放过非终态 package 和实现类位于 public api 包；另指出 Conversation 仍是旧 attachment 契约。
  Evidence: 审查后 Milestone 1 恢复未勾选；PACKAGE/SKU source type 改为 null，所有用途统一要求 READY/PARTIAL，并把默认实现移动到 `internal/reference`。Conversation 问题因用户随后明确 File-only 范围而只记录为后续跨模块迁移项。

- Observation: Standards 审查发现 `DefaultAssetReferenceService` 加三个接口转发 Bean 会产生相同接口的重复候选。
  Evidence: 删除三个转发 Bean，只保留实现三个接口的单一 service Bean；`FileAutoConfigurationTest` 断言三个接口各只有一个 Bean并通过。

## Decision Log

- Decision: canonical Asset Reference 的纯值对象与 codec 放在 `pixflow-contracts` 的 `com.pixflow.contracts.asset`；资源存在性、readiness、ownership、展示路径、展开和 storage lookup 留在 File。
  Rationale: key grammar 跨多个模块且不应重复实现，但只有 File 拥有资产当前事实。codec 不访问数据库，也不暴露对象存储位置。
  Date/Author: 2026-07-17 / Codex

- Decision: 使用 sealed value hierarchy 表达 PACKAGE、SKU、IMAGE，而不是允许任意 nullable 字段组合的通用 record。
  Rationale: 非法 kind/字段组合在构造阶段即不可表示；codec 仍可对外返回统一 `AssetReferenceKey` 接口。
  Date/Author: 2026-07-17 / Codex

- Decision: publication 采用“持久 reservation -> 可重试 copy -> READY commit -> after-commit candidate cleanup”，而不宣称 MinIO 与 MySQL 能原子提交。
  Rationale: 先按 `(sourceTaskId, sourceResultId)` 唯一键保留同一个 `imageId` 和 stable target，重试才不会产生第二张 Generated Image。只有 READY 行可见；PUBLISHING 行由相同请求或恢复扫描继续。candidate 删除发生在 READY 事务提交后，避免数据库回滚时丢失唯一源字节。
  Date/Author: 2026-07-17 / Codex

- Decision: Task 拥有 `GeneratedAssetPublicationPort`，File 公开与 Task 无关的 `GeneratedImagePublisher`；`pixflow-app` adapter 在两者之间转换。
  Rationale: Task 决定 fenced SUCCESS 和调用时机，File 决定 image identity、stable key、lineage 和 tombstone。这样不增加 `pixflow-module-file -> pixflow-module-task` 或 `pixflow-module-task -> pixflow-module-file` 的内部实现依赖。
  Date/Author: 2026-07-17 / Codex

- Decision: 删除不继续使用 `deleted_at` 作为资产状态，改为删除 processable row并写 `asset_reference_tombstone`；tombstone 不保存 storage key、字节、删除时间或业务状态。
  Rationale: “仍在表中但标记删除”会把 Reference Tombstone 与可处理资产混为一谈，也容易误保留字节。独立 tombstone 只服务历史引用渲染，符合最新设计。
  Date/Author: 2026-07-17 / Codex

- Decision: 新数据库结构使用新增 Flyway migration，不修改已存在的 V2/V3；只有在 Milestone 0 证明所有目标数据库均可重建时，才可在 Decision Log 改为重建 baseline。
  Rationale: 当前无法证明 V2/V3 未在共享环境执行。默认保留 checksum 是安全、可恢复的选择。
  Date/Author: 2026-07-17 / Codex

- Decision: 每个里程碑先运行严格 lint/static analysis，再运行任何测试；Maven 命令串行执行。
  Rationale: 用户明确要求测试前先 linting，仓库 lint 规则也把 `-DskipTests verify` 作为后端严格门禁。编译、Checkstyle 或 SpotBugs 未通过时不得用测试结果掩盖。
  Date/Author: 2026-07-17 / Codex

- Decision: 本轮实施范围收窄为 `pixflow-module-file`；不修改 Conversation 或 loop，也不在 File-only 改动中删除仍被外部模块编译依赖的旧 reference 类型。
  Rationale: 用户在实施过程中明确只需要 File 模块。跨模块消费者迁移必须在另一次获得授权的原子切片中完成，不能把当前工作树留在无法编译的半迁移状态。
  Date/Author: 2026-07-18 / Codex

- Decision: Permission proof 在 resolver 前显式执行共享 `CanonicalAssetReferenceCodec` 的 parse/serialize round-trip，并拒绝 null resolved view。
  Rationale: codec 是唯一 grammar owner，Permission 仍需在访问任何 owner fact 前 fail closed；resolver 负责数据库事实，不承担调用方可替换实现的语法安全假设。
  Date/Author: 2026-07-18 / Codex

## Outcomes & Retrospective

已完成 File canonical 解析的首个可运行切片。`pixflow-module-file` 新增三个 public 能力边界和安全 reference view，默认实现隐藏在 internal 包；Permission 通过共享 codec + resolver 复核权限，READ/INSPECT/PROCESS/GENERATE 映射已穷尽。resolver/permission 6 项与自动配置 1 项定向测试通过，File Checkstyle 为 0 violation，File `-am compile` 通过。按 File-only 范围，Conversation 迁移和旧跨模块类型删除未实施；完整上传格式分派、Materials/Outputs 查询、Generated Image publication、真实删除和全 reactor 验收仍未完成；strict `verify` 与 File 全测试因 124 秒超时不能记为通过。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-module-file` 的领域名称是 Asset Library。Asset Package 是一个上传归档形成的命名空间；Original Image 是从这个归档中通过准入的可处理图片；Generated Image 是由成功任务结果发布、拥有新 `imageId` 和来源 lineage 的可处理图片。Asset Reference 是后端生成的 canonical key，只能是 `package:{packageId}`、`package:{packageId}/sku:{urlEncodedSkuId}` 或 `package:{packageId}/image:{imageId}`。Reference Expansion 是把 PACKAGE 或 SKU 在后端解析成 Original Image；Generated Image 不进入 PACKAGE/SKU expansion。Reference Tombstone 是删除字节和可处理行后仅为历史消息保留的最小身份与原展示名，不是软删除资产。

当前 `pixflow-module-file/src/main/java/com/pixflow/module/file/FileService.java` 同时承担上传、查询、图片操作和文案导入。`upload/` 已有 Redis session、分块写入、complete 和 cancel；`ingest/ZipExtractor.java` 只处理 ZIP；`pkg/` 与 `image/` 是 MyBatis 实体和 mapper；`permission/AssetPermissionProof.java` 用 File 当前事实实现 Permission-owned proof port；`config/FileAutoConfiguration.java` 装配所有 bean。`schema.sql` 和 V2/V3 migration 只有 Original Image 模型。`pixflow-infra-storage` 已提供 `packageSource(packageId, archiveExt)`、stable `resultAsset/generatedAsset`、epoch-scoped unit key 和跨桶 `ObjectStorage.copy`，但 publication 业务尚未实现。

目标依赖关系是：Contracts 提供纯 Asset Reference 类型；File 依赖 Common、Storage、Cache、MQ 和 Permission 的窄 proof SPI；File 不依赖 Imagegen、Task 或 Conversation 内部实现。Imagegen、Task、Permission、Conversation 等消费者通过 File public API 或 App adapter 使用资产事实。File 可保留对 Permission SPI 的依赖来提供 concrete proof adapter，但 parser 必须复用共享 codec，不能再维护私有语法。当前 `pixflow-module-file -> pixflow-module-imagegen` 的 `SourceImageReader` 实现必须迁到 `pixflow-app` adapter 或由 Imagegen 改为消费中立 File read API。

## Reference Documents and Required Reading

执行任何里程碑前，先重新阅读当时 `docs/design-docs/exec-plans/` 下全部活动计划，因为总计划、Lint 计划或 Task/Storage 子计划可能已经推进并改变文件所有权。随后按以下顺序阅读；这里的摘要帮助定位，但不能替代原文。

先读 `docs/design-docs/index.md` 和 `docs/design-docs/design.md`，理解系统层次、模块依赖、`asset_package` / `asset_image` 目标数据和对象存储布局。再读 `CONTEXT-MAP.md`、`docs/agents/domain.md` 和 `pixflow-module-file/CONTEXT.md`，使用 Asset Package、Original Image、Generated Image、Asset Reference、Reference Expansion、Reference Tombstone 这些规范词汇。

身份与生命周期的权威来源是 `docs/design-docs/base/asset-references.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md`、`docs/adr/0006-publish-successful-image-results-as-assets.md` 和 `docs/design-docs/module/file.md`。实施 publication 前还必须读 `docs/design-docs/module/task.md`、`docs/design-docs/module/imagegen.md`、`pixflow-module-task/CONTEXT.md`、`pixflow-module-imagegen/CONTEXT.md`、ADR 0001、0002、0003，确认 fenced SUCCESS、Execution Epoch、derived retry 和 candidate 生命周期。实施 storage 操作前读 `docs/design-docs/infra/storage.md` 及 `small-infrastructure-alignment-refactor-plan.md` 的 Storage 交接，复用现有 stable keys 与 copy 原语，不在 File 重造 MinIO client。

实施上传、浏览和 HTTP API 前读 `docs/design-docs/frontend/upload.md`、`docs/design-docs/frontend/files.md`、`docs/design-docs/frontend/api.md` 和 `docs/design-docs/frontend/chat.md`，特别核对 1-based pagination、`excludeReferenceKey`、20 条上限、Materials/Outputs 分层、真实删除和 mention snapshot。实施 Permission proof 与 Conversation 接缝前读 `docs/design-docs/infra/permission.md`、`permission-authorization-boundary-refactor-plan.md` 和 `docs/design-docs/module/conversation.md`，确认 proof 的 `PROVED/DENIED/UNAVAILABLE` 语义和每个执行边界重新授权。

`docs/design-docs/exec-plans/completed/file-module-implementation-plan.md` 与 `completed/files-page-backend-api-plan.md` 只能用于理解历史实现，不能覆盖最新设计。后者建议的软删除、task-result 作为 Outputs 身份和旧 API 文档路径已经被 canonical Asset Reference 与 Generated Image 设计取代。发现当前实现或 completed plan 与上述权威文档冲突时，以当前活动总计划、accepted ADR 和当前设计文档为准，并在本文 Decision Log 记录处理。

## Plan of Work

### Milestone 0：冻结基线、迁移策略与文件所有权

先从仓库根记录 `git status --short` 和本计划相关 diff。当前工作树已有后端总计划、Permission 计划和 Conversation/File proof 测试修改，执行者不得覆盖、移动或格式化这些用户工作。检查活动 Lint 计划是否仍在修改 `config/checkstyle/suppressions.xml`；File suppression 当前应为零，本计划不得新增。记录 `pixflow-module-file` 当前 strict verify 与测试结果，但严格按“先 lint、后测试”运行。

盘点 V2/V3 migration 是否已在共享数据库执行。无法证明可重建时，选择 V4 及后续新 migration，并在本节记录版本号。检查 MySQL/Flyway 测试方式、RocketMQ/Redis/MinIO 可用性和 Docker 状态。列出当前 route、公开 Java 类型、模块依赖和全仓消费者，特别是 multipart upload、`PackageReferenceResolver`、`DefaultSourceImageReader`、`AssetPermissionProof.Reference`、裸 package/image IDs 和 object keys。结束时得到一份可复现基线，不改生产行为。

### Milestone 1：建立 canonical Asset Reference 与 File 深边界

在 `pixflow-contracts/src/main/java/com/pixflow/contracts/asset/` 增加 `AssetReferenceKind`、sealed `AssetReferenceKey` 及 PACKAGE/SKU/IMAGE 三种不可变实现，并增加 `AssetReferenceCodec`。codec 必须拒绝未知 kind、额外或缺失 segment、非正数 package/image ID、空 SKU、非法 percent escape、解码后包含路径分隔语义的输入，以及“解码再编码后不等于原字符串”的非 canonical SKU 编码。序列化只接受合法 typed key；任何 display name、object key 或 URL 都不能进入这些值对象。

在 `pixflow-module-file` 建立明确的 public `api` 包和 internal implementation。公共能力至少分成 `AssetReferenceResolver`、`AssetReferenceInspector`、`AssetReferenceExpander` 与 `AssetContentReader`，返回不含 storage key 的 immutable views。解析先用 codec 得到 kind，再查询 package/image 当前事实并验证 package 归属、source type、readiness、tombstone 和访问用途。PACKAGE/SKU expansion 只返回 processable Original Image；IMAGE 返回一个 Original 或 Generated Image；多 key expansion 按输入顺序稳定处理并用 `(packageId,imageId)` 去重。exact duplicate key 可在请求边界拒绝或去重，但父子 key 可共存，不能因为展开结果重合而删除 prompt 中的不同引用意图。

把 `AssetPermissionProof` 改为调用 resolver/proof query 并复用共享 codec，保留依赖故障到 `UNAVAILABLE`、业务拒绝到 `DENIED` 的 fail-closed 区分。删除私有 `Reference` parser。用新的 File read API 替代 `PackageReference`、`ImageReference` 和 object-key-bearing resolver；消费者必须迁移后再删除旧类，不留 deprecated wrapper。Milestone 末尾用 codec 单测、resolver 数据测试和负向搜索证明全仓没有第二个 canonical parser，也没有 File public DTO 暴露 `ObjectLocation`、bucket、MinIO key 或 object prefix。

### Milestone 2：完成归档上传、格式分派、安全提取与取消

保留并收紧 `UploadSessionService` 作为唯一上传入口。Init 校验 `.zip/.rar/.7z`、2 GiB 上限、固定 5 MiB chunk、完整 SHA-256 和 filename；把 `archiveFormat` 作为 Asset Package 持久事实。相同 file hash 在 lock 内只得到一个权威决定：READY/PARTIAL 返回 DEDUP，活动 session 返回 RESUME 和已上传 index，其余创建 UPLOAD。完整边界重新计算 archive hash、验证扩展名/魔数/选择的 extractor 一致，把 package 从 UPLOADED 推进并可靠发布 extraction。12 小时 sliding TTL 只在合法 resume 与成功 chunk write 时刷新；cancel、complete 和 expiry 清除 temp chunks；新增 orphan cleanup 扫描无 session 的过期 TMP 前缀。

把 `ZipExtractor` 上提为 `ArchiveExtractor` 策略边界，按 `archiveFormat` 分派 ZIP、RAR、7z 实现。优先使用维护中的纯 Java 库；选定依赖、版本、许可证、encrypted-entry 检测和资源关闭行为后，在 Decision Log 写明依据，不能只依据扩展名调用同一个 ZIP parser。三种实现共享一套 `ArchiveSafetyPolicy` 和 admission pipeline，统一限制 path traversal、绝对路径/盘符、entry 数、声明与实际总解压大小、单 entry 大小、compression ratio、扩展名、magic bytes 和 encrypted/password-protected entries。metadata 文档在归档内部按 File 规则解析，不再接受独立 multipart `doc`。

为 extraction 增加 durable cancellation marker 和终态 CAS。worker 在每次 entry 读取、上传和数据库写入之间检查 marker；取消获胜时停止新增 image，删除 archive、已提取图片、临时对象、browse rows、errors/copy data 与 activity bridge，并删除 package；READY/PARTIAL/FAILED 已先获胜则 cancel 返回 conflict。移除 `FileService.upload(MultipartFile, MultipartFile)`、`UploadPackageResponse` 和 `POST /api/files/packages`，负向 MockMvc 测试证明旧 route 返回 404。

### Milestone 3：完成 Materials、Outputs 和 mention candidate 查询

按 `frontend/api.md` 实现 `GET /api/asset-references`。`source=MATERIALS` 返回 package -> SKU -> Original Image 的 1-based lazy pages；`source=OUTPUTS` 返回供 App/Task read side 按 conversation -> task 组织的 Generated Image pages；`parentKey` 只展开合法层级；非空 `query` 在 package name、SKU、original name 和 generated name 上做后端全局搜索。`excludeReferenceKey` 是最多 20 个 repeatable 参数，必须先按 exact key 排除再分页。非法 source、page/size、parent kind 或超过上限返回稳定 validation error。

File 自己只查询 Asset Package、Original/Generated Image 和 sourceTaskId lineage，不读取 Conversation 或 Task 内部表。需要 conversation/task 顶层分组时，由 `pixflow-app` 组合 Task public read API 与 File 的 `listGeneratedByTaskIds`；File 仍是每个 Generated Image 的 identity、display、bytes 和 reference producer。Materials 的普通包/SKU/图片分页、全局 original-image filters/sort 和 Outputs 的 generated-image filters 都在数据库侧完成，不允许前端先拉全量再过滤。

候选 DTO 固定包含 `referenceKey`、`kind`、IMAGE 的 `sourceType` 和当前 `displayPath`；PACKAGE/SKU 不伪造 source type。预览/下载 DTO 可包含短期 URL，但 URL 不是候选身份且不持久化。READY/PARTIAL 才贡献 Materials；UPLOADED/EXTRACTING/FAILED、PUBLISHING 和 tombstone 都不进入候选。rename 只改变当前 display name，新候选使用新路径，Conversation 已保存的 `displayPathSnapshot` 不回写。

### Milestone 4：发布 Generated Image 并形成可恢复闭环

新增 migration 扩展 `asset_image`：至少增加 `source_type`、`storage_bucket`、`publication_status`、`source_task_id`、`source_result_id`、`source_image_id`、`unit_key`、`run_epoch`、media type/extension/size、producer kind/provider/model 和必要时间字段；为 `(source_task_id,source_result_id)` 建唯一约束。Original Image 回填 `source_type=ORIGINAL`、`publication_status=READY` 和 PACKAGES bucket。lineage 字段对 ORIGINAL 为空、对 GENERATED 必填；使用数据库 check constraint或 service invariant测试证明非法组合不能成为 READY。

File public API 定义 `GeneratedImagePublisher.publish(GeneratedImageCandidate)`。candidate 包含 taskId、resultId、unitKey、runEpoch、candidate `ObjectLocation`、source IMAGE identity、media metadata 和 producer identity，但不接受调用方指定新 imageId、stable key 或 canonical reference。第一次调用在短事务中按唯一结果身份插入 PUBLISHING reservation并取得 imageId，File 用 source type 和新 imageId 计算 `StorageKeys.resultAsset` 或 `generatedAsset`。随后检查 candidate 或已存在 stable target，调用 `ObjectStorage.copy`，校验目标 size/content type，再在第二个短事务中把同一 reservation 更新为 READY。提交后删除 candidate；删除失败只产生 orphan-cleanup 候选，不回滚 READY。

重放同一 result identity 必须返回同一 imageId/reference。若进程在 reservation 后崩溃，重试复用 PUBLISHING 行；若 stable target 已存在且 metadata 符合则直接 finalize；若 candidate 仍在则重新 copy；两者都不存在则保持可诊断的失败，不创建新 identity。增加恢复扫描处理超龄 PUBLISHING reservation，但扫描只重复上述状态机，不把对象存在误判为成功。不同 result identity 即使字节相同也产生不同 Generated Image。

在 `pixflow-module-task` public API 定义 Task-owned `GeneratedAssetPublicationPort` 和不含 File entity 的 command/result。在 `pixflow-app` 新增 adapter 把 Task command 转为 `GeneratedImageCandidate` 并调用 File。Task 只在 owner thread 仍持锁、MySQL epoch 相同、candidate 存在且 result 已 fenced 到 SUCCESS 后调用；这部分调用时机由 Task 计划/测试拥有，本计划只实现端口接缝和组合测试。删除当前 File 对 `pixflow-module-imagegen` 的 POM 依赖与 `DefaultSourceImageReader`，在 App 用 File `AssetContentReader` 实现 Imagegen/Task 所需端口。

### Milestone 5：真实删除、Reference Tombstone 与幂等恢复

新增 `asset_reference_tombstone`，只保存 kind、packageId、可选 skuId/imageId、原展示名和渲染历史引用所需的最小 namespace facts；不保存 referenceKey 字符串、storage location、bytes、deletedAt、task 状态或业务 payload。key 仍由 typed identity 动态序列化。为现有 `deleted_at` 行编写一次性数据迁移：先生成 tombstone，再确认对象清理策略，最后删除或净化 processable row；若共享环境含有价值数据，先做 dry-run count 与备份，不在 migration 中静默丢字节。

删除 Original Image 时，先创建/确认 image tombstone，再删除 PACKAGES 对象，最后删除 `asset_image` processable row。删除 Generated Image 时同样先保留 tombstone，再删除 RESULTS/GENERATED stable object和 processable row；不删除 source package、source image、siblings 或 task facts。删除 Package 时，从 Materials 立即消失，删除 archive、全部 Original Image bytes/rows、copy/errors/browse data；Generated Image 继续存在。如果 generated lineage 或历史引用需要 namespace，则保留 package/original-image tombstone。删除操作以 typed reference 和当前事实执行，重复删除返回同一 no-op 结果或 tombstone view，不返回 500。

由于 MySQL 与对象存储没有跨资源事务，删除状态机必须可恢复。先落 tombstone/cleanup intent，再删对象，再删 processable row；列表和 resolver 在 cleanup intent 后立即拒绝 PROCESS，避免“用户已删除但仍能执行”。对象删除失败保留 intent 并由 cleanup scanner 重试；不能先删唯一数据库定位事实再失去对象 key。完成后移除 `asset_package.deleted_at`、`asset_image.deleted_at` 的生产语义；若物理列因迁移兼容暂留，也不得再由生产查询或写路径使用，并在后续 schema cleanup migration 删除。

### Milestone 6：删除旧边界并完成组合验收

迁移 Conversation、DAG、Imagegen、Task、Vision、Memory、Permission 和 Web 的直接消费者，但只修改它们使用 File public API 的 adapter/DTO，不在本计划重写其内部状态机。删除裸 `packageId + imageIds/source_image_ids` 工具输入、旧 `PackageReferenceResolver`、object-key DTO、multipart upload、前端 key 构造与 package client-side expansion。保留内部数据库 ID 和 storage key，但它们不能穿过公开 HTTP、tool、message 或 module API。

在 `FileAutoConfiguration` 中让核心生产能力缺失依赖时 fail fast；测试 fake 通过显式 `@TestConfiguration` 提供。不要用新的 `@ConditionalOnBean` 让 resolver、publication、upload 或 cleanup 在生产中静默消失。增加架构测试守护 File 不依赖 Task/Imagegen/Conversation 内部 package，Contracts asset package 不依赖 Spring/MyBatis/Storage，所有 public asset DTO 不含 storage location。

最终完成 Module、App 和前端接缝验证，运行全 reactor。手工场景必须证明：上传 ZIP/RAR/7z 均按真实格式提取；PACKAGE/SKU/IMAGE 可浏览和 mention；同一 publication 重放只产生一张 Generated Image；Task/Activity 清理后 Outputs 仍可访问；删除 Generated Image 后字节消失但历史 message token 仍显示原 snapshot；删除 Package 后 Originals 消失而 Generated Images 保留。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 串行执行。开始前记录工作树，不清理用户修改：

    git status --short
    git diff -- docs/design-docs/exec-plans config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml pixflow-module-file
    rg -n "pixflow-module-file" config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml

第三条预期无输出。Milestone 0 的顺序必须是 lint/static analysis 在前、测试在后：

    mvn -pl pixflow-module-file -am -DskipTests verify
    mvn -pl pixflow-module-file -am test

以后每个里程碑均使用相同门禁。先运行：

    mvn -pl <touched-modules> -am -DskipTests verify

只有该命令成功、Checkstyle 为零 violation 且 SpotBugs 为零 High warning 后，才运行：

    mvn -pl <touched-modules> -am test

`<touched-modules>` 必须包含本轮所有直接修改模块。例如 Milestone 1 使用：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-permission -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-permission -am test
    rg -n "objectKey|objectPrefix|ObjectLocation|BucketType|minioKey" pixflow-module-file/src/main/java/com/pixflow/module/file/api
    rg -n "URLDecoder|package:.*sku:|package:.*image:" pixflow-module-file pixflow-permission --glob "*.java"

第一条搜索预期无输出。第二条只允许共享 codec 的调用或测试 fixture，不允许第二套 parser。Milestone 2 使用：

    mvn -pl pixflow-module-file,pixflow-infra-cache,pixflow-infra-mq,pixflow-infra-storage -am -DskipTests verify
    mvn -pl pixflow-module-file,pixflow-infra-cache,pixflow-infra-mq,pixflow-infra-storage -am test
    rg -n "PostMapping\(.*api/files/packages|RequestPart|MultipartFile|FileService\.upload|UploadPackageResponse" pixflow-module-file/src/main

最后搜索预期无输出。ZIP/RAR/7z 的 fixture 必须分别包含正常归档、path traversal、encrypted/password、伪扩展名、entry count、超大 entry、总大小和高压缩比用例。不能用同一个 ZIP 改扩展名伪装三格式通过。

Milestone 3 使用：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-task,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-task,pixflow-app -am test

MockMvc/HTTP 测试覆盖四种 `/api/asset-references` 查询、1-based page、exclude-before-page、20 条上限、非法 parent 和 PUBLISHING/tombstone 排除。Milestone 4 使用：

    mvn -pl pixflow-contracts,pixflow-infra-storage,pixflow-module-file,pixflow-module-task,pixflow-module-imagegen,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-infra-storage,pixflow-module-file,pixflow-module-task,pixflow-module-imagegen,pixflow-app -am test
    rg -n "DefaultSourceImageReader|com\.pixflow\.module\.imagegen" pixflow-module-file
    rg -n "GeneratedAssetPublicationPort|GeneratedImagePublisher|sourceTaskId|sourceResultId" pixflow-module-task pixflow-module-file pixflow-app

第一条搜索预期无输出。publication 的真实 MinIO 集成测试必须零 skipped；至少注入 reservation 后、copy 后、READY commit 后和 candidate delete 前四个 crash point，重跑后仍只有一个 READY imageId 和一个 stable object。

Milestone 5 使用：

    mvn -pl pixflow-module-file,pixflow-module-task,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-module-file,pixflow-module-task,pixflow-app -am test
    rg -n "deletedAt|deleted_at|setDeletedAt|getDeletedAt" pixflow-module-file/src/main pixflow-module-file/src/main/resources

若迁移阶段暂留物理列，搜索命中只能在 migration/schema 注释或明确的迁移 reader 中；最终生产 Java 预期无输出。删除集成测试使用真实 MinIO 证明字节不存在，并证明 sibling、source 和 Generated Image 保留规则。

Milestone 6 先 lint 后 test，最后才做前端与全仓验证：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-permission,pixflow-conversation,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-module-vision,pixflow-module-memory,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-permission,pixflow-conversation,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-module-vision,pixflow-module-memory,pixflow-app -am test
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

这里前端也严格遵守 lint 在 test 前：`lint` 和 `typecheck` 都成功后才运行 `pnpm test`。完整 `mvn verify` 若被 Docker 环境阻断，记录 `docker info`、首个失败测试和栈帧；同时记录 strict verify 和非 Docker 测试的真实结果。环境阻断不能写成通过，也不能通过删除、禁用或 skip 测试解决。

## Validation and Acceptance

Asset Reference 验收要求 codec 对三个 kind round-trip 稳定，并拒绝非 canonical SKU 编码、wrong-kind、额外 segment、零/负 ID、object key 和 URL。后端是唯一 serializer；Web 只原样保存和回传。File resolver 对 deleted、PUBLISHING、cross-package image 和不存在资源拒绝；Permission proof 在业务拒绝时 DENIED、依赖异常时 UNAVAILABLE。PACKAGE/SKU expansion 只含 Original Image，IMAGE 可指向 Original 或 Generated，多引用按 `(packageId,imageId)` 稳定去重。

上传验收要求 ZIP、RAR、7z 使用相应真实 extractor；扩展名、magic bytes 与选择器不一致时失败。2 GiB/5 MiB/SHA-256、UPLOAD/RESUME/DEDUP、12 小时 sliding TTL 和 temp cleanup 可测试。密码归档、zip slip、绝对路径、超限 entry、bomb ratio 和伪图片均不能写入 processable row。旧 multipart route 返回 404，独立 doc 不再存在。取消获胜后没有 package browse row和对象残留；终态先获胜时 cancel 明确冲突。

查询验收要求 Materials 只返回 READY/PARTIAL 的 Original Image，Outputs 只返回 READY Generated Image。所有 page 为 1-based；filter/search/sort/exclusion 在数据库分页前执行。parentKey 层级正确，超过 20 个 exclusions 被拒绝。display rename 不改变 key，历史 message snapshot 不改变，预签名 URL 不进入身份或持久化。

publication 验收要求同一 `(sourceTaskId,sourceResultId)` 在并发、event replay 和四个 crash point 后只得到一个 imageId、一个 READY row、一个 stable object和同一 canonical IMAGE reference。source image ID 不被复用；lineage、epoch、unit、producer 和 media metadata 完整。candidate 在 READY commit 前不会删除，READY 后 candidate cleanup 可重试。Task 清理不删除 Generated Image；different result identity 即使内容相同也产生不同 imageId。

删除验收要求删除操作立即让资产退出 resolve-for-process、browse 和 mention candidate；最终真实对象不存在、processable row不存在、tombstone 只含身份/原名且没有 deletion timestamp/storage key。Package 删除不删除 Generated Image，Generated Image 删除不删除 source/sibling/task，Original 删除不破坏已有 Generated Image lineage。历史 Conversation token继续用自己的 `displayPathSnapshot` 渲染 deleted reference。

架构验收要求 `pixflow-module-file` 不依赖 Task、Imagegen、Conversation 内部实现，不公开 mapper/entity/ObjectLocation。Contracts asset codec 不依赖 Spring、MyBatis、Storage 或 File。Task/File 通过 Task-owned port、File-owned publisher 和 App adapter 接线。生产必需 bean 缺失时 context 启动失败，不静默降级。

## Idempotence and Recovery

所有 lint、搜索、编译和测试命令可以重复运行。Upload init、chunk put、complete、cancel、reference reads、publication 和 deletion 必须分别用 fileHash/uploadId/chunk index/result identity/reference identity 吸收重放。任何扫描器只恢复已有事实，不创建第二份身份。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。每轮先检查工作树；遇到重叠修改时做最小补丁并保留原意，无法安全合并则在 Progress 记录并停在可编译边界。File 已无 Checkstyle suppression，不得新增；SpotBugs filter 也不得因本计划扩大。

数据库 migration 默认只新增版本。执行 destructive data cleanup 前先输出受影响 package/image/tombstone/object count，备份有价值数据库和对象；migration 或 cleanup 中断后从 reservation/cleanup intent 重试。不要在数据库已迁移后修改旧 migration checksum。若开发库可重建，仍需先在 Decision Log 记录证据与命令。

publication 的恢复点是唯一 result identity 对应的 reservation。PUBLISHING + candidate 存在时重 copy；PUBLISHING + stable target 存在且 metadata 正确时 finalize；两者都无时保持错误供人工/上游恢复，不分配新 ID。READY + candidate 残留时只清 candidate。删除的恢复点是 tombstone/cleanup intent；对象删除可重复，process row 最后删除。

## Artifacts and Notes

目标身份链：

    AssetReferenceCodec (pure grammar)
      -> File resolve / inspect / expand (current MySQL facts)
      -> Permission / Conversation / Agent tools / planners

目标发布链：

    Task fenced SUCCESS + TMP candidate
      -> GeneratedAssetPublicationPort (Task-owned)
      -> App adapter
      -> GeneratedImagePublisher (File-owned)
      -> PUBLISHING reservation
      -> Storage copy to stable asset key
      -> READY Asset Image + lineage + canonical IMAGE reference
      -> after-commit candidate cleanup

目标删除链：

    typed delete request
      -> tombstone / cleanup intent
      -> immediately hidden from process and browse
      -> object deletion
      -> processable row deletion
      -> historical snapshot remains renderable

当前必须移除或替换的旧入口包括：

    POST /api/files/packages multipart
    FileService.upload(MultipartFile, MultipartFile)
    UploadPackageResponse
    PackageReference / ImageReference object-key views
    DefaultPackageReferenceResolver(raw packageId)
    AssetPermissionProof private Reference parser
    DefaultSourceImageReader in pixflow-module-file
    asset_image.deleted_at soft-delete behavior
    AssetPackageService ConservativePackageReferenceChecker fallback

## Interfaces and Dependencies

`pixflow-contracts` 中的最低形状应等价于：

    public sealed interface AssetReferenceKey
            permits PackageAssetReference, SkuAssetReference, ImageAssetReference {
        AssetReferenceKind kind();
        long packageId();
    }

    public record PackageAssetReference(long packageId) implements AssetReferenceKey {}

    public record SkuAssetReference(long packageId, String skuId)
            implements AssetReferenceKey {}

    public record ImageAssetReference(long packageId, long imageId)
            implements AssetReferenceKey {}

    public interface AssetReferenceCodec {
        AssetReferenceKey parse(String referenceKey);
        String serialize(AssetReferenceKey reference);
    }

实际构造器必须验证正 ID、非空 SKU 和 defensive normalization；codec 是 canonical encoding 的唯一实现。不要在 entity 中持久化冗余 `reference_key` 列。

File public API 应至少表达以下能力，具体 view 名可按现有命名调整，但不能泄露 storage：

    public interface AssetReferenceResolver {
        ResolvedAssetReference resolve(String referenceKey, AssetUse use);
    }

    public interface AssetReferenceExpander {
        ExpandedAssetSet expand(List<String> referenceKeys, AssetUse use);
    }

    public interface AssetReferenceInspector {
        AssetInspection inspect(String referenceKey);
    }

    public interface AssetContentReader {
        ReadableAsset requireImage(String imageReferenceKey, AssetUse use);
    }

`AssetUse` 至少区分 BROWSE、INSPECT、PROCESS、DOWNLOAD，使 readiness 和 tombstone 规则不靠调用方布尔参数猜测。`ReadableAsset` 可以在可信 App adapter 内携带 `ObjectLocation`，但 HTTP/tool DTO 不得直接返回它；如果同一 Java public API 同时供内部 adapter 和外部 DTO 使用，应拆成 internal SPI 与 safe view 两层。

File publication seam 应等价于：

    public interface GeneratedImagePublisher {
        PublishedGeneratedImage publish(GeneratedImageCandidate candidate);
    }

    public record PublishedGeneratedImage(
        long imageId,
        String referenceKey,
        long packageId,
        String skuId,
        String sourceTaskId,
        String sourceResultId
    ) {}

`GeneratedImageCandidate` 必须包含 result identity、epoch/unit、candidate location、source IMAGE reference、media metadata 和 producer identity，并拒绝 PACKAGE/SKU source。Task-owned `GeneratedAssetPublicationPort` 位于 Task public API，参数不 import File 类型；App adapter是唯一跨 seam 转换位置。

`asset_image` 的最终可处理事实至少能表达 Original/Generated、READY/PUBLISHING、bucket/key、current display、lineage 和 producer。`asset_reference_tombstone` 只表达 derived key 所需 identity 与 display-name facts。索引至少覆盖 Materials 的 package/SKU/sourceType/status，Outputs 的 sourceTaskId/sourceType/status，全局 search 的规范化名称字段，以及 publication 的唯一 result identity。任何 schema 细化都要同步 `schema.sql`、Flyway migration、MyBatis entity/mapper 和真实 MySQL migration test。

依赖最终应满足：`pixflow-contracts` asset package只依赖 JDK；`pixflow-module-file` 依赖 Common、Contracts、Permission SPI、Storage、Cache、MQ、MyBatis 和归档解析库；不依赖 Task、Imagegen、Conversation、Agent、Vision 或 Web。`pixflow-app` 可以依赖两侧 public API做 adapter。Storage 只提供 I/O 和 key factory，不分配 imageId、判断 epoch、写 lineage 或决定 deletion。

## Revision Notes

2026-07-17 / Codex: 创建本计划。依据活动后端总计划的 Milestone 1/3、最新 File/Asset Reference 设计、ADR 0005/0006、当前源码和 Lint 计划，把 `pixflow-file` 拆成 canonical key、归档协议、查询、可恢复 publication、tombstone 删除和组合清理六个增量里程碑。明确使用 reservation 状态机弥合 MySQL/MinIO 非原子边界，使用 Task-owned port + App adapter 避免 Task/File 反向依赖，并按用户要求把每一轮验证固定为 strict lint/static analysis 成功后才运行测试。

2026-07-18 / Codex: 同步 Generated Image publication 专项实施。File V4、PUBLISHING/READY publisher、lineage、metadata fail-closed、bounded recovery、Task/App adapters 和 READY public query 已完成；同时删除 Imagegen source facts 中裸 object key 的旧合同。定向测试与 17 模块 strict verify 通过；Docker 不可用，真实 MySQL/MinIO 并发/crash-window 仍未完成，因此 Milestone 4 不提前勾选。
