# 完整实现 module/file 素材管理模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 file 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。

## Purpose / Big Picture

完成本计划后，PixFlow 将具备生产级素材包入口能力：用户可以通过 HTTP 上传一个 zip 素材包和可选文案文档，系统立即返回 `packageId`，后台通过 RabbitMQ 异步解压、做 zip 安全校验、把图片流式写入 MinIO、按文件名绑定 SKU/分组、解析文案表格，并通过包详情接口与 WebSocket/STOMP 进度事件观察处理进度。这个能力是后续 DAG 批处理、任务调度、对话附件和 Agent 决策的数据入口。

本计划不是 MVP 方案。实现必须覆盖可靠投递、重投幂等、zip slip / zip bomb 防护、逐图失败隔离、失败明细查询、软删除、Testcontainers 集成测试和 app 级进度推送接缝。

## Progress

- [x] (2026-06-28 22:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、当前 active exec plan、`module/file.md`、`infra/storage.md`、`infra/mq.md`、`base/common.md`、`harness/state.md`，确认 file 模块处于 Wave 2，依赖 storage/mq/common，不依赖 task/dag/state。
- [x] (2026-06-28 22:10+08:00) 与用户确认 5 个实现前决策：`asset_package.id` 用 `BIGINT`；删除软删优先；增加 `asset_ingest_error`；包内并发采用两级有界并发；`ProgressNotifier` 先同步 common/state 文档。
- [x] (2026-06-28 22:25+08:00) 同步更新 `docs/design-docs/module/file.md`、`docs/design-docs/base/common.md`、`docs/design-docs/harness/state.md`，固化上述决策。
- [x] (2026-06-28 22:35+08:00) 创建本执行计划文档，明确架构思路、机制、文件改动顺序、验证方法和设计文档快速定位关键词。
- [x] (2026-06-28 18:06+08:00) 实现 `pixflow-common` 的 `ProgressNotifier` SPI，并用 fake notifier 单测验证 common 不依赖传输层。
- [x] (2026-06-28 18:06+08:00) 创建 `pixflow-module-file` Maven 模块，加入根 `pom.xml` 与 `pixflow-app` 依赖；app 增加 STOMP `ProgressNotifier` 实现。
- [x] (2026-06-28 18:06+08:00) 初版实现 file 模块数据模型、Mapper、包生命周期服务、上传接口、列表/详情/失败明细/删除接口。
- [x] (2026-06-28 18:06+08:00) 初版实现 RabbitMQ 解压拓扑、发布器、消费者和错误处理器；投递失败保留 `UPLOADED`，补偿扫描尚未落地。
- [x] (2026-06-28 18:06+08:00) 初版实现 zip 路径安全校验、解压入库、图片 magic bytes 准入、文件名解析、CSV/XLSX 文案解析骨架、失败明细写入和进度推送。
- [ ] 补齐数据库 schema/migration、投递缺口补偿扫描、文案解析入库、幂等 upsert、包内有界并发、Testcontainers 全链路集成测试。

## Surprises & Discoveries

- Observation: 当前 `pixflow-infra-storage` 已经落地 `StorageKeys.packageSource(long packageId)`、`packageImage(long packageId, ...)`、`packageDoc(long packageId, ...)`，因此 file 模块的 package id 必须采用 `long` / `BIGINT`，否则会迫使 storage API 返工或引入无意义映射。
  Evidence: 在 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/StorageKeys.java` 中可以搜索 `packageSource(long packageId)`。
- Observation: 当前 `pixflow-infra-mq` 的 `ManagedMessageListener` 在业务 handler 返回后才 ack。虽然 mq 设计文档以 task 的 ack-then-process 为主，但 file 可以让 handler 同步跑完整解压，从而实现 process-then-ack。
  Evidence: 在 `pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedMessageListener.java` 中可以搜索 `handler.handle(envelope)` 与 `basicAck`。

## Decision Log

- Decision: `asset_package.id`、`asset_image.id`、`asset_copy.id`、`asset_ingest_error.id` 统一用 `BIGINT`，业务消息里的 `packageId` 用 Java `long`。
  Rationale: 与已经实现的 `StorageKeys.package*(long packageId)` 保持契约一致，避免 UUID 字符串带来的映射表、索引膨胀和跨模块 API 返工。
  Date/Author: 2026-06-28 / Codex

- Decision: 素材包删除采用“软删优先、物理删除受限”。未被引用的包可以物理删除 DB 行和 MinIO 前缀；已被任务或对话引用的包只写 `deleted_at`。
  Rationale: 素材包是任务输入事实源。任务和对话落地后会引用 `package_id`，硬删会破坏历史回放和审计。
  Date/Author: 2026-06-28 / Codex

- Decision: 新增 `asset_ingest_error` 保存逐图和文档解析失败明细，`asset_package.error_summary` 只保存脱敏摘要。
  Rationale: 生产排障需要回答“哪些文件失败、为什么失败、是否需要重传”。单个摘要字段无法支持分页查询、筛选和稳定测试断言。
  Date/Author: 2026-06-28 / Codex

- Decision: 解压并发采用两级有界并发：`consumer-concurrency` 控制同时处理的包数，`intra-package-parallelism` 控制单包内 entry 上传并发，默认分别保守设置为 1～2 和 4。
  Rationale: 解压导入同时压 MinIO、MySQL、临时文件和 RabbitMQ ack 时间。保守有界并发比无限 fan-out 更符合生产稳定性。
  Date/Author: 2026-06-28 / Codex

- Decision: `ProgressNotifier` 放在 `pixflow-common`，具体 WebSocket/STOMP 实现放在 `pixflow-app`，file 只发布逻辑进度事件。
  Rationale: file 是 Wave 2 模块，不能反向依赖 Wave 4 的 task/conversation；common 中的纯 SPI 可以让多个模块共享 app 级传输，同时保持 state 不持连接、不推帧。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

当前计划已经完成第一轮 Java 落地：`ProgressNotifier` SPI、`pixflow-module-file` 骨架、实体/Mapper、上传/查询/删除 HTTP 接口、RabbitMQ 解压入口、Zip 路径安全、图片准入、文件名解析、CSV/XLSX 文案解析骨架、app 级 STOMP 进度推送均已实现。当前实现仍不是最终验收版：缺正式 schema/migration、`PublishGapRescan`、文案解析入库、`asset_image` 幂等 upsert SQL、包内有界并发、中央目录双阶段预扫与 MinIO/RabbitMQ/MySQL 全链路集成测试。

已运行验证命令：

    mvn -pl pixflow-common -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-app -am test

结果：三条命令均通过。`pixflow-module-file` 当前覆盖 `FileNameParser`、`ImageAdmission`、`ZipPathValidator`、`CsvCopyDocParser` 纯单测；`pixflow-app` 聚合验证通过，说明新增 file 依赖和 `StompProgressNotifier` 编译装配无冲突。运行 `pixflow-module-file` 与 `pixflow-app` 聚合测试时，既有 infra Testcontainers 测试被执行；`pixflow-infra-storage` 的 MinIO 集成测试跳过 2 个，既有 RabbitMQ/Redis/Qdrant 集成测试通过。

## Context and Orientation

仓库是 Maven 多模块 Spring Boot 项目，根目录是 `D:\study\PixFlow`。根 `pom.xml` 目前已有 `pixflow-common`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-state`、`pixflow-app` 等模块，但还没有 `pixflow-module-file`。

file 模块是“素材进系统”的入口侧模块。入口侧是指上传 zip、保存原始包、解压图片、解析文件名、绑定 SKU 和分组、解析文案文档、查询和删除素材包。出口侧是指展示处理结果、评分、打包下载；这些依赖后续 `module/task` 和 `module/rubrics`，不在本计划实现。

需要理解的术语如下。

RabbitMQ 是消息队列。这里用一条消息代表一个素材包解压作业，消息体只放 `packageId`，zip 本体已经在 MinIO。MinIO 是对象存储，存 zip、原图和文档。MySQL 是事实源，保存 `asset_package`、`asset_image`、`asset_copy` 和 `asset_ingest_error`。process-then-ack 是指 RabbitMQ 消费者先完成整个解压作业，再确认消息；如果进程崩溃，未 ack 的消息会被 RabbitMQ 重投。幂等是指重复执行不会造成重复数据或错误计数，本计划靠 `(package_id, original_path)` 唯一约束实现。

设计文档快速定位关键词如下。执行者可以用这些关键词在对应设计文档中搜索，迅速定位依据文本。

在 `docs/design-docs/module/file.md` 中搜索：

    asset_package 主键
    asset_ingest_error
    素材包删除语义
    process-then-ack
    ProgressNotifier
    包内并发
    Zip 安全
    文件名驱动解析
    文案文档解析

在 `docs/design-docs/base/common.md` 中搜索：

    跨切进度通知 SPI
    ProgressNotifier
    ErrorRecorder
    SSE / WebSocket
    traceId

在 `docs/design-docs/harness/state.md` 中搜索：

    state 不推送
    ProgressNotifier
    统一状态快照
    只提供数据源

在 `docs/design-docs/infra/storage.md` 中搜索：

    StorageKeys
    packageSource
    packageImage
    deleteByPrefix
    流式与分片上传
    路径穿越防护

在 `docs/design-docs/infra/mq.md` 中搜索：

    MessagePublisher
    PublishResult
    QueueTopologyBuilder
    ConsumerErrorHandler
    ManagedMessageListener
    Publisher Confirms
    DLQ
    消息体最小化

## Plan of Work

先补 `pixflow-common` 的 `ProgressNotifier` 接缝。创建 `pixflow-common/src/main/java/com/pixflow/common/progress/ProgressNotifier.java`，接口只有 `void publish(String channel, Object event)`。提供 no-op 或测试 fake 实现时，不要把 Spring WebSocket 类型放进 common。common 的测试只需要证明接口可被实现和调用，具体 STOMP 映射放到 app 测。

然后创建 `pixflow-module-file`。在根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>` 加入该模块，在 `pixflow-module-file/pom.xml` 中依赖 `pixflow-common`、`pixflow-infra-storage`、`pixflow-infra-mq`、`mybatis-plus-spring-boot3-starter`、`spring-boot-starter-web`、`spring-boot-starter-validation`、Apache POI、commons-csv 和测试依赖。源码根包使用 `com.pixflow.module.file`。

接着实现数据库实体和 Mapper。新增 `AssetPackage`、`AssetImage`、`AssetCopy`、`AssetIngestError`。所有主键用 `Long`。`asset_package` 包含 `deletedAt`。`AssetImage` 必须对应唯一约束 `(package_id, original_path)`。如果项目已有迁移工具，新增正式 migration；如果暂无迁移工具，至少在模块测试资源中提供 schema SQL，并在后续 app 集成时再接入统一迁移。

再实现包生命周期服务。`AssetPackageService` 负责创建包、状态迁移、更新 `image_count` 和 `extracted_count`、写终态、软删除/物理删除。状态只能通过服务迁移，不要让 controller 或 extractor 直接改状态字段。删除服务需要 `PackageReferenceChecker`，当前默认实现返回“未被引用”，后续 task/conversation 接入真实检查。

实现上传入口。`FileController` 暴露 `POST /api/files/packages`，multipart 字段为 `zip` 和可选 `doc`。controller 只做参数校验和调用 `FileService`。`FileService` 生成 `packageId`，用 `ObjectStorage.put(StorageKeys.packageSource(id), ...)` 流式保存 zip，用 `StorageKeys.packageDoc(id, fileName)` 保存 doc，提交 `asset_package(status=UPLOADED)`，然后通过 `ExtractionPublisher` 发布 `ExtractionMessage{packageId}`。如果 `PublishResult` 失败，不抛断请求，保留 `UPLOADED`，等待补偿扫描。

实现 RabbitMQ 拓扑和消费。`ExtractionTopology` 用 `QueueTopologyBuilder("pixflow.file")` 声明 file 队列组。`ExtractionPublisher` 使用 infra/mq 的 `MessagePublisher`。`ExtractionConsumer` 使用 infra/mq 的 `ManagedMessageHandler<ExtractionMessage>`，handler 内同步执行完整解压，返回后 infra listener 才 ack，从而实现 process-then-ack。`ExtractionErrorHandler` 根据 `PixFlowException.recovery()` 返回 `RetryDecision.Retry` 或 `RetryDecision.DeadLetter`。`PublishGapRescan` 只扫描超龄 `UPLOADED` 包并补发消息，不做解压恢复扫描。

实现 zip 解压和安全。`ZipExtractor` 从 MinIO 把 source.zip 取回受限临时文件，读取中央目录，检查 entry 数、声明总解压大小、单 entry 大小和压缩比。每个 entry 名要拒绝绝对路径、`..`、盘符和空段，然后再交 `StorageKeys.packageImage(packageId, relPath)` 二次规范化。解压期间累计实际字节，防止中央目录声明不可信。非图片 entry 按规则忽略或记录明细，图片 entry 进入 `ImageAdmission`。

实现图片准入。`ImageAdmission` 只检查扩展名、magic bytes 和大小，不调用 ImageIO，不依赖 `infra/image`。白名单来自 `pixflow.file.image.allowed-extensions`。magic bytes 要覆盖 jpg/jpeg、png、webp、bmp、gif、tiff。单图超限和格式不匹配写入 `asset_ingest_error`，不终止整包。

实现文件名解析。`FileNameParser` 是纯函数，去扩展名后按 `_` 分段。三段代表 `group_key`、`sku_id`、`view_id`；两段代表无分组单图，首段是 `sku_id`，第二段是 `view_id`；其他情况调用 `SkuExtractor` 兜底。结果用 `ParsedName` record 表示。空段、重复下划线、中文名、纯数字、多于三段都要有单测。

实现文案解析。`CopyDocParser` 是接口，`CsvCopyDocParser` 用 commons-csv，`ExcelCopyDocParser` 用 Apache POI。列映射来自 `FileProperties.copydoc.column-mapping`，支持中文表头。缺 `sku_id` 行写入 `asset_ingest_error(stage=COPYDOC_PARSE)` 并跳过。重复 `sku_id` 默认覆盖，行为由配置 `on-duplicate-sku` 控制。文案解析失败不阻断图片入库，只让包进入 `PARTIAL` 并写摘要。

实现查询、失败明细和删除接口。`GET /api/files/packages/{id}` 返回包状态、总数、已解压数、错误摘要和必要元数据。`GET /api/files/packages` 分页列出未软删包。`GET /api/files/packages/{id}/errors` 分页返回 `asset_ingest_error`。`DELETE /api/files/packages/{id}` 根据引用检查决定物理删除或软删。

最后接入 app 级进度推送。`pixflow-app` 提供 `StompProgressNotifier`，把 `channel` 映射为 `/topic/{channel}`。file 解压每次更新 `extracted_count` 后发布 `packages/{packageId}/progress`，事件包含 `packageId`、`extracted`、`total`、`status`、`traceId`。没有 WebSocket 环境时 no-op 实现不影响轮询，因为事实源是 `asset_package`。

## Concrete Steps

所有命令默认在仓库根目录 `D:\study\PixFlow` 执行。

第一步查看工作树，避免覆盖用户改动：

    git status --short

预期：如果有与 file/common/state/app/pom 相关的未提交改动，先阅读差异并与其共存；不要回滚用户改动。

第二步补 common SPI 并运行 common 测试：

    mvn -pl pixflow-common -am test

预期：构建成功，新增 `ProgressNotifier` 相关测试通过。

第三步创建 file 模块骨架并确认 Maven 可识别：

    mvn -pl pixflow-module-file -am test

预期：模块能被 Maven 找到；早期可只有空测试，后续每个里程碑都必须保持该命令通过。

第四步实现纯函数和轻量单元测试，包括 `FileNameParser`、`SkuExtractor`、`ImageAdmission`、`CopyDocColumnMapping`：

    mvn -pl pixflow-module-file -am test

预期：纯单测全部通过，不需要 Docker。

第五步实现上传、查询、删除服务和 Mapper，用 H2 或项目既有 MyBatis 测试方式验证 DB 行为。如果项目没有 H2 约定，使用 Testcontainers MySQL 或模块内 schema 初始化。

    mvn -pl pixflow-module-file -am test

预期：创建包、状态迁移、软删除/物理删除、失败明细分页均有测试覆盖。

第六步实现 RabbitMQ + MinIO 全链路集成测试。Windows 本地如果 Docker Desktop 29.4.x 遇到 Testcontainers 管道问题，使用仓库父 POM 已提供的 profile：

    mvn -pl pixflow-module-file -am test -Pwindows-docker-npipe

预期：上传 zip 后发布消息，消费者解压，MinIO 出现 `PACKAGES/{packageId}/images/...` 对象，MySQL 出现 `asset_image` 行，包状态最终为 `READY` 或 `PARTIAL`。

第七步在 app 模块接入 `ProgressNotifier` 和 file controller/consumer 装配：

    mvn -pl pixflow-app -am test

预期：app context 能启动，`ProgressNotifier` Bean 可注入，file 模块 controller 与 consumer 不产生循环依赖。

第八步运行完整相关模块测试：

    mvn -pl pixflow-module-file,pixflow-app -am test

预期：所有相关模块测试通过。若 Docker 不可用，必须记录哪些 Testcontainers 测试被跳过，以及跳过条件。

## Validation and Acceptance

验收必须证明可观察行为，而不只是代码编译。

上传验收：启动 app 后，向 `POST /api/files/packages` 上传一个包含两张有效图片和一个 CSV 文案文档的 multipart 请求。响应应是成功的 `ApiResponse`，`data.packageId` 是数字，`data.status` 初始为 `UPLOADED` 或 `EXTRACTING`。

进度验收：轮询 `GET /api/files/packages/{packageId}`，应看到 `imageCount` 从预扫结果出现，`extractedCount` 增长，最终状态变为 `READY`。如果 WebSocket/STOMP 测试可用，订阅 `/topic/packages/{packageId}/progress` 应收到包含同一 `packageId`、`extracted`、`total` 和 `status` 的事件。

失败隔离验收：上传一个包含一张有效 png、一张伪装成 jpg 的文本文件、一个路径为 `../evil.png` 的 zip entry 的包。路径穿越应导致包进入 `FAILED` 或 poison DLQ，伪装格式应写入 `asset_ingest_error`。如果包内至少有成功图片且失败不是整包级安全错误，终态应为 `PARTIAL`，有效图片仍落库。

幂等验收：模拟同一 `ExtractionMessage{packageId}` 被消费两次。第二次不应插入重复 `asset_image`，`extracted_count` 应按实际行数校准，不应翻倍。

删除验收：对未被引用的包调用 DELETE，应删除 DB 行并清理 MinIO `PACKAGES` 桶对应前缀。对模拟已引用的包调用 DELETE，应只写 `deleted_at`，列表接口不再展示，但按 ID 查询仍能返回历史元数据。

测试验收：至少运行以下命令并通过：

    mvn -pl pixflow-common -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-app -am test

如果运行集成测试需要 Docker，在 Windows 本地优先使用：

    mvn -pl pixflow-module-file -am test -Pwindows-docker-npipe

## Idempotence and Recovery

本计划的实现应可重复运行测试和重复消费消息。上传同一个 zip 两次应创建两个不同 `packageId`，不做内容级去重。消费同一个 `packageId` 的解压消息多次应通过 `(package_id, original_path)` 唯一约束和 MinIO 同 key 覆盖保持幂等。

如果上传请求在 DB 提交后发布 MQ 失败，包保持 `UPLOADED`，`PublishGapRescan` 会补发消息。这个补偿只覆盖投递缺口，不负责解压崩溃恢复。解压崩溃恢复由 RabbitMQ 未 ack 重投处理。

如果物理删除 MinIO 前缀时部分失败，服务应保留足够错误信息并返回可重试错误，不应先删除 DB 后静默丢失对象清理失败。推荐顺序是先确认引用状态，再在事务中标记删除意图或软删，物理删除和硬删要设计成可重试。若第一版未引入删除意图字段，至少要保证失败抛出后不会把包误报为完全删除。

临时 zip 工作文件必须写入 `pixflow.file.extract.temp-dir` 下，文件名带 `packageId` 和随机后缀，处理完成或异常退出时尽力删除。测试要覆盖异常路径的清理；生产残留可由操作系统临时目录策略兜底。

## Artifacts and Notes

关键路径示意：

    POST /api/files/packages
      -> ObjectStorage.put(PACKAGES, "{packageId}/source.zip")
      -> INSERT asset_package(status=UPLOADED)
      -> MessagePublisher.publish(ExtractionMessage{packageId})
      -> response { packageId, status }
      -> RabbitMQ consumer receives packageId
      -> get source.zip from MinIO to bounded temp file
      -> central directory scan and zip safety checks
      -> per image admission, StorageKeys.packageImage, ObjectStorage.put
      -> INSERT asset_image idempotently
      -> UPDATE asset_package extracted_count
      -> ProgressNotifier.publish("packages/{packageId}/progress", event)
      -> final status READY / PARTIAL / FAILED

建议的 `asset_ingest_error.stage` 稳定枚举值：

    ZIP_SCAN
    IMAGE_ADMISSION
    STORAGE_UPLOAD
    NAMING_PARSE
    COPYDOC_PARSE

建议的 HTTP 端点：

    POST   /api/files/packages
    GET    /api/files/packages
    GET    /api/files/packages/{id}
    GET    /api/files/packages/{id}/errors
    DELETE /api/files/packages/{id}

## Interfaces and Dependencies

在 `pixflow-common/src/main/java/com/pixflow/common/progress/ProgressNotifier.java` 定义：

    package com.pixflow.common.progress;

    public interface ProgressNotifier {
        void publish(String channel, Object event);
    }

在 `pixflow-module-file/src/main/java/com/pixflow/module/file/pkg/PackageStatus.java` 定义：

    public enum PackageStatus {
        UPLOADED,
        EXTRACTING,
        READY,
        PARTIAL,
        FAILED
    }

在 `pixflow-module-file/src/main/java/com/pixflow/module/file/ingest/ExtractionMessage.java` 定义只含最小消息体：

    public record ExtractionMessage(long packageId) {}

在 `pixflow-module-file/src/main/java/com/pixflow/module/file/naming/ParsedName.java` 定义：

    public record ParsedName(String groupKey, String skuId, String viewId) {}

在 `pixflow-module-file/src/main/java/com/pixflow/module/file/naming/SkuExtractor.java` 定义：

    public interface SkuExtractor {
        String extract(String baseName);
    }

在 `pixflow-module-file/src/main/java/com/pixflow/module/file/pkg/PackageReferenceChecker.java` 定义：

    public interface PackageReferenceChecker {
        boolean isReferenced(long packageId);
    }

默认实现可以返回 false，但接口必须存在，以便后续 task/conversation 接入真实引用检查。

file 模块必须使用现有依赖接口，不得直接使用 MinIO SDK、RabbitTemplate 或 WebSocket 模板：

    com.pixflow.infra.storage.ObjectStorage
    com.pixflow.infra.storage.StorageKeys
    com.pixflow.infra.mq.MessagePublisher
    com.pixflow.infra.mq.consumer.ConsumerErrorHandler
    com.pixflow.infra.mq.consumer.ManagedMessageHandler
    com.pixflow.infra.mq.topology.QueueTopologyBuilder
    com.pixflow.common.progress.ProgressNotifier
    com.pixflow.common.error.ErrorCode
    com.pixflow.common.error.PixFlowException
    com.pixflow.common.sanitize.Sanitizer

## Change Note

2026-06-28 / Codex: 初次创建计划。本文把已确认的 5 个生产级决策写入可执行计划，并同步给后续实现者足够的上下文、命令、验收标准和设计文档搜索关键词。
