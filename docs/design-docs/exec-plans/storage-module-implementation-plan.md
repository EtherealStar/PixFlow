# 实现 infra/storage 对象存储抽象

本 ExecPlan 必须按照仓库根目录的 `PLANS.md` 维护。它是活文档，后续任何实现者推进、修正或完成本计划时，都必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`，并在文末记录修改原因。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一层生产级对象存储基础设施。素材包原始 zip、解压后的原图、文案文档、确定性 DAG 处理结果、生图结果、大 tool-result 和临时中间产物都会通过统一的 `infra/storage` 接口写入对象存储。调用方只保存稳定的对象 key，不保存本地路径、不保存预签名 URL，也不直接接触 MinIO 客户端。

从使用者角度看，这会带来三个可观察结果：上传素材包后，业务表里能看到明确的 `*_object_key` 字段；任务结果预览和下载使用短期预签名 URL，而不是应用服务代理文件流；删除素材包或任务临时产物时，系统能按对象 key 或前缀清理对应对象。

本计划只描述设计与实施方案，不编写 Java 代码。后续实现者可以从本文件直接开始落地，无需知道前面对话内容。

## Progress

- [x] (2026-06-27 16:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`.kiro/specs/pixflow/design.md`、`docs/design-docs/design.md`、`docs/design-docs/module/storage.md`、`docs/design-docs/module/common.md`、`docs/design-docs/module/cache.md`、`docs/design-docs/module/permission.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`。
- [x] (2026-06-27 16:30+08:00) 确认 `.kiro/specs/pixflow/design.md` 是 MVP 参考文档，其中“本地磁盘”存储方案不作为本轮 storage 实现依据。
- [x] (2026-06-27 16:30+08:00) 确认本轮 `infra/storage` 以 `docs/design-docs/module/storage.md` 的 MinIO 对象存储抽象为主设计依据。
- [x] (2026-06-27 16:30+08:00) 与用户统一字段语义方向：数据库字段使用 `*_object_key` 表达“对象存储桶内 key”，避免继续使用 `path` 或 `minio_key` 这类容易混淆或绑定实现的名字。
- [x] (2026-06-27 16:30+08:00) 新增本中文 ExecPlan，明确架构思路、对外 API 形状、调用方存储的 key、字段命名调整、验证方式和设计文档快速定位关键词。
- [x] (2026-06-27 16:45+08:00) 参考 `docs/design-docs/exec-plans/completed/permission-module-implementation-plan.md` 的写法，补充 storage 对外接口、record、配置、初始化器和调用方契约的签名级说明。
- [x] (2026-06-27 15:38+08:00) 实现前再次核对当前源码包结构，确认实际根包仍为 `com.pixflow`，且源码中尚无既有 `infra/storage` 实现，本轮为新增模块。
- [x] (2026-06-27 15:38+08:00) 在 `pom.xml` 中加入 `io.minio:minio:8.5.11` 与 `org.testcontainers:testcontainers:1.20.4`。
- [x] (2026-06-27 15:38+08:00) 实现 `infra/storage` 纯模型、配置、接口、MinIO 实现、初始化器、异常与测试。
- [x] (2026-06-27 15:38+08:00) 运行 `mvn test`，结果为 BUILD SUCCESS；默认环境下 MinIO 集成测试因未设置 `PIXFLOW_MINIO_INTEGRATION=true` 被条件跳过。
- [ ] 接入后续 `module/file`、`module/task`、`module/dag`、`module/imagegen`、`harness/tools` 等调用方。

## Surprises & Discoveries

- Observation: 仓库同时存在 `.kiro/specs/pixflow/design.md` 和 `docs/design-docs/design.md`，二者定位不同。
  Evidence: `.kiro/specs/pixflow/design.md` 描述 MVP 阶段，存储方案是“本地磁盘”；`docs/design-docs/design.md` 明确当前阶段为完整重写，技术栈选择 MinIO 对象存储。

- Observation: 新版总设计中仍有部分字段名带 `minio_key` 或 `output_path`，这会把业务数据库绑定到底层实现或本地路径语义。
  Evidence: `docs/design-docs/design.md` 的数据模型中出现 `minio_zip_key`、`minio_key`、`output_minio_key`，旧 MVP 设计中出现 `zip_path`、`doc_path`、`output_path`。storage 模块设计又要求未来可切换阿里云 OSS，因此字段名应改为更通用的 `object_key`。

- Observation: storage 模块必须只依赖 `common`，不能依赖 `file/task/dag` 这类调用方。
  Evidence: `docs/design-docs/module/storage.md` 的“模块结构与依赖位置”写明 `storage → common`，同时 `module-dependency-dag-plan.md` 把 storage 放在 Wave 1，作为后续模块的基础设施。

- Observation: 当前源码中还存在其他未提交/未跟踪的 `infra/mq` 和文档改动，本轮 storage 实现需要避免误改或回滚它们。
  Evidence: `git status --short` 显示 `src/main/java/com/pixflow/infra/`、`src/test/java/com/pixflow/infra/` 下同时有 storage 与 mq 相关新增内容，且 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 等文档已有改动。

- Observation: MinIO 集成测试不应在普通 `mvn test` 中隐式拉 Docker。
  Evidence: 默认 `mvn test` 报告 `MinioObjectStorageIntegrationTest` 为 2 个测试按条件跳过；需要真实容器验证时显式设置 `PIXFLOW_MINIO_INTEGRATION=true`。

## Decision Log

- Decision: 数据库和跨模块 DTO 中统一使用 `*_object_key` 表达对象存储 key，不再新增 `*_path`、`*_minio_key` 或持久化 URL 字段。
  Rationale: `path` 容易被理解成本地文件系统路径，`minio_key` 绑定具体实现，URL 会过期且不适合入库。`object_key` 能准确表达“对象存储桶内的稳定对象名”，未来切换 OSS 不需要改业务字段语义。
  Date/Author: 2026-06-27 / Codex

- Decision: 业务表默认不存物理桶名；除非单表同时承载多个桶的结果，否则桶由业务类型或结果类型推导。
  Rationale: 物理桶名是部署配置，不是业务事实。把桶名写入业务表会让配置变更影响历史数据。对于 `process_result` 这种可能同时承载确定性结果和生图结果的表，优先用 `result_kind` 推导逻辑桶，而不是直接存物理桶名。
  Date/Author: 2026-06-27 / Codex

- Decision: 对外 API 只暴露 `ObjectLocation`、`ObjectRef`、`StoredObjectMetadata`、`ObjectStorage`、`StorageKeys` 等稳定抽象，调用方不直接使用 MinIO SDK。
  Rationale: 这样可以把 MinIO、分片上传、预签名、桶初始化、异常收口和重试都封装在 infra 内，后续替换 OSS 只换实现，不改调用方。
  Date/Author: 2026-06-27 / Codex

- Decision: `StorageKeys` 是业务 key 约定中心，但它必须是纯静态、无 I/O、无配置读取的工具。
  Rationale: 业务路径模板不能散落在调用方各自拼字符串；但 storage 核心 I/O 也不应知道素材包、任务、SKU 等业务语义。把模板集中在 `StorageKeys`，返回 `BucketType + key`，可以兼顾统一和解耦。
  Date/Author: 2026-06-27 / Codex

- Decision: 预签名 URL 只在请求时生成，不入库、不进长期状态。
  Rationale: 预签名 URL 带有效期和签名参数，是临时访问凭证，不是对象身份。持久身份只能是对象 key。
  Date/Author: 2026-06-27 / Codex

- Decision: `deleteByPrefix` 的接口参数保持为 `BucketType + prefix`，不新增 `ObjectPrefix` 类型。
  Rationale: 前缀删除只在少数清理场景使用，核心身份仍是 `ObjectLocation`。保留简单签名更直接，但必须在计划和测试中约束它只能用于明确边界的 package、task、tmp 支路前缀。
  Date/Author: 2026-06-27 / Codex

- Decision: `StorageAutoConfiguration` 仅在配置 `pixflow.storage.endpoint` 时启用真实 MinIO Bean。
  Rationale: 当前项目还处于基础设施分阶段落地阶段，默认测试和未接入 storage 的启动路径不应强制连接外部 MinIO；后续开发或部署环境显式配置 endpoint 后再启用真实对象存储。
  Date/Author: 2026-06-27 / Codex

- Decision: MinIO 集成测试使用 JUnit 环境变量条件 `PIXFLOW_MINIO_INTEGRATION=true` 显式启用。
  Rationale: 保证普通单元测试稳定、无 Docker 环境也可跑；同时保留真实 MinIO 容器测试入口，避免把未执行的集成测试表述为通过。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

本轮已完成 `infra/storage` 的第一版源码实现。新增的核心类包括 `BucketType`、`ObjectLocation`、`ObjectRef`、`StoredObjectMetadata`、`ObjectStorage`、`StorageKeys`、`StorageException`、`StorageProperties`、`DefaultStorageBucketResolver`、`MinioObjectStorage`、`StorageInitializer` 和 `StorageAutoConfiguration`。

已实现能力包括：逻辑桶与对象 key 模型、业务 key 模板和路径规范化、逻辑桶到物理桶名解析、MinIO 流式上传下载、`getBytes` 小对象读取上限、`exists/stat/delete/deleteByPrefix`、GET/PUT 预签名 URL、启动期建桶与 TMP 生命周期声明、底层异常收口为 `StorageException`。

已新增测试覆盖 `StorageKeys`、`ObjectLocation`、桶解析、`getBytes` 上限保护，并新增可选 MinIO Testcontainers 集成测试验证上传下载、元数据、预签名 GET、删除和前缀删除。2026-06-27 15:38+08:00 执行 `mvn test` 成功：41 个测试运行，0 失败，0 错误，2 个 MinIO 集成测试因未设置 `PIXFLOW_MINIO_INTEGRATION=true` 被条件跳过。

后续尚未完成调用方接入：`module/file`、`module/task`、`module/dag`、`module/imagegen`、`harness/tools` 仍需在各自模块实现时改为依赖 `ObjectStorage` 与 `StorageKeys`，并把长期状态字段统一为 `*_object_key`。

## Context and Orientation

本仓库是 PixFlow 电商运营 Agent。当前完整重写阶段的总体设计位于 `docs/design-docs/design.md`，模块实施顺序位于 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，storage 模块设计位于 `docs/design-docs/module/storage.md`。`.kiro/specs/pixflow/design.md` 是 MVP 阶段参考材料，里面的本地磁盘存储方案不能作为本轮实现依据。

`infra/storage` 是 Wave 1 基础设施模块。它只依赖 `common`，被 `module/file`、`module/dag`、`module/task`、`module/imagegen`、`harness/state`、`harness/context`、`harness/tools` 等模块消费。它的职责是提供对象存储的纯 I/O 能力。纯 I/O 的意思是：storage 只知道逻辑桶、对象 key、字节流、元数据、预签名和删除，不知道什么是素材包、任务、SKU、支路或生图。

“对象 key”指对象存储桶内的对象名，例如 `42/source.zip` 或 `1001/SKU123_88_b1.webp`。它不是本地文件路径，不带盘符，不以对象存储服务地址开头，也不是 URL。

“逻辑桶”指代码中的枚举概念，例如 `PACKAGES`、`RESULTS`、`GENERATED`、`TOOL_RESULTS`、`TMP`。逻辑桶会通过配置解析成物理桶名，例如 `pixflow-packages`。业务模块只接触逻辑桶，不接触物理桶名。

“预签名 URL”指对象存储服务临时签发的可访问链接。它有过期时间，适合给前端预览或下载使用，但不能入库当长期引用。

“流式读写”指上传和下载时使用输入流或输出流逐段处理字节，不把完整 zip 或大图一次性读进 JVM 堆内存。

## 关联设计文档快速定位关键词

在 `docs/design-docs/module/storage.md` 中搜索 `纯 I/O，无业务语义`，可以定位本模块的第一设计原则。搜索 `接口与实现分离`，可以定位为什么调用方只依赖 `ObjectStorage`。搜索 `流式优先`，可以定位为什么不能强制把大对象读成 `byte[]`。搜索 `下载走预签名`，可以定位为什么结果预览和下载不穿应用带宽。搜索 `多桶分治`，可以定位五类逻辑桶的设计理由。

在 `docs/design-docs/module/storage.md` 中搜索 `Key 约定中心` 或 `StorageKeys`，可以定位所有业务 key 模板。搜索 `ObjectStorage`，可以定位对外核心接口。搜索 `deleteByPrefix`，可以定位前缀删除的用途和约束。搜索 `TMP 桶配 24 小时自动过期`，可以定位临时对象生命周期。搜索 `StorageInitializer`，可以定位启动建桶和生命周期声明机制。搜索 `StorageException`，可以定位错误收口方式。搜索 `getBytes`，可以定位小对象便捷读取的上限约束。

在 `docs/design-docs/design.md` 中搜索 `MinIO`，可以定位对象存储技术选型。搜索 `对象存储`，可以定位总体架构和数据模型中的存储位置。搜索 `MinIO（对象）` 或 `13.4 MinIO`，可以定位原始对象路径约定。搜索 `process_result`，可以定位结果表当前字段，需要在实现时把 `output_minio_key` 或 `output_path` 语义更新为 `output_object_key`。搜索 `Redis 只放轻量引用`，可以定位 Redis 与 MinIO 的中间产物边界。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `Wave 1 基础设施`，可以定位 storage 的实施波次。搜索 `infra/storage`，可以定位 storage 被哪些后续模块依赖。搜索 `关键路径`，可以理解为什么 storage 要先于 file、dag、task、imagegen 等业务模块落地。

在 `docs/design-docs/module/common.md` 中搜索 `StorageException`，可以定位 infra/storage 异常跨边界后如何归一化为 `STORAGE` 分类。搜索 `Sanitizer`，可以定位凭证、路径和超长 details 的脱敏要求。搜索 `ErrorNormalizer`，可以定位异常归一化入口。

在 `docs/design-docs/module/cache.md` 中搜索 `Redis 不存大字节`，可以定位中间产物边界：Redis 存轻量引用，图片字节落 MinIO。搜索 `中间产物边界`，可以定位 `branch:cache` 和 `group:cache` 只保存对象 key 引用的原因。

## Architecture and Mechanism

storage 的架构采用“三层边界”。

第一层是纯模型和接口层。这里包含 `BucketType`、`ObjectLocation`、`ObjectRef`、`StoredObjectMetadata`、`ObjectStorage` 和 `StorageException`。调用方只通过这些对象表达“我要把这些字节写到哪个逻辑桶的哪个 key”，或者“我要读取、删除、签发哪个对象”。这一层不读取配置，不连接 MinIO，不知道物理桶名。

第二层是 key 约定中心。`StorageKeys` 集中维护所有业务 key 模板。它把业务参数转换为 `ObjectLocation`，例如素材包原始 zip、解压图片、文案文档、普通结果图、组结果图、生图结果、大 tool-result 和临时中间产物。它必须做路径规范化，去掉前导斜杠，拒绝 `..`，避免把用户传入的文件名或 SKU 变成路径穿越。

第三层是 MinIO 实现和 Spring 装配层。这里包含 `StorageProperties`、`StorageBucketResolver`、`MinioObjectStorage`、`StorageInitializer` 和 `StorageAutoConfiguration`。这层负责读取配置、创建 MinIO 客户端、把逻辑桶解析成物理桶名、执行真实上传下载、生成预签名 URL、启动时创建桶和声明 TMP 生命周期。

调用链应该是：业务模块调用 `StorageKeys` 得到 `ObjectLocation`，再把 `ObjectLocation` 和字节流交给 `ObjectStorage`。`ObjectStorage` 的 MinIO 实现解析物理桶名并执行 I/O。返回给业务模块的是 `ObjectRef` 或预签名 URL；业务模块入库时保存 `ObjectRef.key`，不保存 URL。

错误机制是：MinIO SDK、网络、I/O、对象不存在、桶不存在等底层异常在 `MinioObjectStorage` 内被收口成 `StorageException`。`StorageException` 携带 operation、逻辑桶、key、retryable 和 cause。它不直接变成 HTTP 响应。跨出 infra 边界或进入 HTTP、Tool、MQ、Stream 出口时，由 `common` 的 `ErrorNormalizer` 翻译成 `PixFlowException(category=STORAGE)`。

生命周期机制是：`TMP` 逻辑桶对应的物理桶配置 24 小时自动过期，用于异常残留兜底。正常路径仍应主动调用 `deleteByPrefix` 删除支路或任务临时产物。`PACKAGES`、`RESULTS`、`GENERATED` 默认永久保留，由业务显式删除或生命周期策略后续扩展。`TOOL_RESULTS` 默认长期保留，因为它用于 trace、回放和大结果引用。

## External API Shape

对外 API 形状应保持小而稳定。这里的 API 指 Java 模块内部接口，不是 HTTP API。

`ObjectLocation` 表达“逻辑桶 + 对象 key”。调用方在上传、读取、删除和签名时都传这个对象，而不是传物理桶名和裸字符串。

`ObjectStorage` 应提供这些能力：流式写入、流式读取、小对象读取、存在性检查、元数据查询、单对象删除、按前缀删除、生成 GET 预签名 URL、生成 PUT 预签名 URL。最终接口应保持下面的形状：

    public interface ObjectStorage {
        ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType);
        InputStream getStream(ObjectLocation loc);
        byte[] getBytes(ObjectLocation loc);
        boolean exists(ObjectLocation loc);
        StoredObjectMetadata stat(ObjectLocation loc);
        void delete(ObjectLocation loc);
        void deleteByPrefix(BucketType bucket, String prefix);
        URL presignGet(ObjectLocation loc, Duration ttl);
        URL presignPut(ObjectLocation loc, Duration ttl);
    }

`put` 的输入必须包含 `ObjectLocation`、输入流、对象大小和 content type。对象大小未知时允许传负数，由实现走分片上传。返回值应是 `ObjectRef`，包含逻辑桶、key、size 和 etag。

`getStream` 返回可关闭的输入流，调用方必须在使用后关闭。`getBytes` 只允许用于小对象或文本预览，并受最大读取限制保护；大图片、大 zip、下载任务不能使用 `getBytes`。

`presignGet` 和 `presignPut` 返回临时 URL。URL 只传给前端或短期调用方，不入库。预签名 TTL 默认取配置，调用方可以在需要时传明确 TTL，但不得签发无限期 URL。

`deleteByPrefix` 只用于已经明确边界的前缀，例如某个 package、task 或 tmp 支路。不要把它当通配扫描工具使用。实现应分页列举和批量删除，收集失败对象并在异常 details 中报告。

这些接口语义有几条硬约束。`put` 不负责创建业务数据库记录，只负责对象写入。`presignGet` 不检查业务权限，权限检查必须在调用它之前由业务模块完成。`getBytes` 必须有配置上限，超过上限应抛出明确异常并提示调用方改用 `getStream`。`delete` 和 `deleteByPrefix` 是对象存储层能力，不保证业务级级联一致性；级联顺序由 `module/file`、`module/task` 等调用方负责。

`StorageKeys` 是调用方创建 `ObjectLocation` 的唯一入口。最终方法形状应至少包含下面这些静态方法：

    public final class StorageKeys {
        public static ObjectLocation packageSource(long packageId);
        public static ObjectLocation packageImage(long packageId, String relPath);
        public static ObjectLocation packageDoc(long packageId, String fileName);
        public static ObjectLocation result(long taskId, String skuId, long imageId, String branchId, String ext);
        public static ObjectLocation groupResult(long taskId, String skuId, String groupKey, String branchId, String ext);
        public static ObjectLocation generated(long taskId, String skuId, long imageId, String ext);
        public static ObjectLocation toolResult(String id);
        public static ObjectLocation tmpBranch(long taskId, long imageId, String branchId, String name);
        public static ObjectLocation tmpGroup(long taskId, String groupKey, String branchId, long imageId, String name);
    }

`StorageKeys` 不接收物理桶名，不读取配置，不连接对象存储。它只返回逻辑桶和桶内 key。所有外部输入段都必须经过规范化。`relPath` 可以保留子目录结构，但必须拒绝绝对路径、盘符、空段和 `..`。`fileName`、`skuId`、`branchId`、`groupKey`、`ext`、`name` 这类单段输入不能包含路径分隔符。

## Stored Keys and Field Naming

本计划把字段语义更改为更明确的 `*_object_key`。凡是持久化对象存储身份的字段，都使用这个后缀。它表示“桶内 key”，不是 URL，不是本地文件路径，不是物理桶名。

建议将数据模型中的字段统一为以下语义。

`asset_package.source_zip_object_key` 保存原始上传 zip 在 `PACKAGES` 逻辑桶内的 key。推荐 key 形态是 `{packageId}/source.zip`。

`asset_package.doc_object_key` 保存文案文档在 `PACKAGES` 逻辑桶内的 key，可为空。推荐 key 形态是 `{packageId}/doc/{fileName}`。

`asset_image.source_image_object_key` 保存解压后原图在 `PACKAGES` 逻辑桶内的 key。推荐 key 形态是 `{packageId}/images/{relPath}`。

`asset_image.original_path` 保留为 zip 包内相对路径。这个字段不是对象存储 key，而是用户上传 zip 内部的原始相对路径，用于展示、SKU 提取和排查。

`process_result.output_object_key` 保存处理后结果图在结果桶内的 key。普通确定性 DAG 结果使用 `RESULTS` 逻辑桶，推荐 key 形态是 `{taskId}/{skuId}_{imageId}_{branchId}.{ext}`。组支路结果使用 `RESULTS` 逻辑桶，推荐 key 形态是 `{taskId}/{skuId}_g{groupKey}_{branchId}.{ext}`。

`process_result.result_kind` 保存结果类型，用来推导逻辑桶。建议值至少区分 `DAG_RESULT`、`GENERATED_IMAGE`、`COPY_ONLY`。当 `result_kind=DAG_RESULT` 时，`output_object_key` 属于 `RESULTS` 桶；当 `result_kind=GENERATED_IMAGE` 时，`output_object_key` 属于 `GENERATED` 桶；当 `result_kind=COPY_ONLY` 时，可能没有图片输出，`output_object_key` 可为空，只写 `generated_copy`。

`process_result.generated_copy` 只保存生成文案文本，不保存对象 key。若未来生成文案过大并外置，应新增明确字段如 `generated_copy_object_key`，不能复用 `output_object_key`。

`tool_result.external_object_key` 或等价字段保存大 tool-result 在 `TOOL_RESULTS` 逻辑桶内的 key。推荐 key 形态是 `{id}.txt`。如果当前没有独立表，也可以由 harness/tools 的 trace details 保存这个 key。

临时中间产物通常不入 MySQL 事实表。Redis 缓存中只保存轻量引用，引用字段也应叫 `object_key`，例如支路节点缓存值中保存 `{ bucket: TMP, object_key: "{taskId}/{imageId}/{branchId}/{name}" }` 这样的语义。Redis 值的具体结构由 `module/dag` 或 `module/task` 后续设计，但不能保存图片字节。

不要再新增这些字段名：`zip_path`、`doc_path`、`output_path`、`source_path`、`minio_key`、`output_minio_key`。如果旧字段已存在，迁移时应在同一变更中说明旧字段语义和新字段映射。新代码中应只使用 `*_object_key`。

## Key Templates

`StorageKeys` 应集中提供以下模板。下面写的是语义和 key 形态，不是代码。

素材包原始 zip 位于 `PACKAGES` 桶，key 是 `{packageId}/source.zip`。

素材包解压原图位于 `PACKAGES` 桶，key 是 `{packageId}/images/{relPath}`。其中 `relPath` 是 zip 根目录下的相对路径，必须经过规范化，禁止绝对路径和 `..`。

文案文档位于 `PACKAGES` 桶，key 是 `{packageId}/doc/{fileName}`。其中 `fileName` 只允许文件名语义，不允许路径穿越。

普通结果图位于 `RESULTS` 桶，key 是 `{taskId}/{skuId}_{imageId}_{branchId}.{ext}`。

组支路合成图位于 `RESULTS` 桶，key 是 `{taskId}/{skuId}_g{groupKey}_{branchId}.{ext}`。

生图结果位于 `GENERATED` 桶，key 是 `{taskId}/{skuId}_{imageId}.{ext}`。

大 tool-result 位于 `TOOL_RESULTS` 桶，key 是 `{id}.txt`。

普通支路临时产物位于 `TMP` 桶，key 是 `{taskId}/{imageId}/{branchId}/{name}`。

组支路临时产物位于 `TMP` 桶，key 是 `{taskId}/{groupKey}/{branchId}/{imageId}/{name}`。

这些模板应保证相同输入总是得到相同 key，且所有外部输入段都被规范化。规范化不是为了美观，而是为了防止路径穿越和不同调用方拼出不一致对象名。

## Plan of Work

第一阶段先落字段和 key 语义，不碰 MinIO 客户端。实现者应在设计文档和后续实体设计中把 `path/minio_key` 语义统一改成 `object_key`。如果某个表确实需要区分多个逻辑桶，优先增加 `result_kind` 这类业务类型字段来推导桶，而不是保存物理桶名。

第二阶段建立 `infra/storage` 的纯模型。新增逻辑桶枚举、对象位置、上传返回引用、对象元数据和 storage 异常。这个阶段可以完全不启动 Spring，也不连接 MinIO。验收方式是纯单元测试能证明 key 模板和路径规范化正确。

第三阶段建立配置和桶解析。新增 storage 配置属性，包含 endpoint、access key、secret key、region、auto-create-bucket、presign-ttl、upload-part-size、tmp-expiry-days 和逻辑桶到物理桶名的映射。新增桶解析器，把 `BucketType` 转换成物理桶名。验收方式是配置覆盖默认桶名后，解析结果符合预期。

第四阶段实现 MinIO 适配。使用 MinIO 官方 Java SDK，实现 `ObjectStorage` 的流式上传、流式读取、存在性检查、元数据查询、删除、前缀删除和预签名。大对象必须走流式和分片，不允许用 `byte[]` 作为常规路径。

第五阶段实现启动初始化。开发环境默认自动创建所有桶，并为 `TMP` 桶设置 24 小时生命周期。生产环境默认不自动建桶，只校验桶存在，缺失时快速失败并输出明确错误。

第六阶段补韧性和错误收口。MinIO 的网络、超时和服务端 5xx 可以按 retryable 包成 `StorageException`；对象不存在、桶不存在、权限错误等确定性失败不做无意义重试。异常 message 和 details 中的凭证、绝对路径和超长内容应通过 `common` 的 `Sanitizer` 处理。

第七阶段补测试。纯单元测试覆盖 `StorageKeys`、路径规范化、桶解析和异常映射。集成测试使用 Testcontainers 或 Docker Compose 中的真实 MinIO 验证上传下载、分片、预签名、删除、前缀删除、建桶和 TMP 生命周期配置。若 CI 没有 Docker，集成测试应可按环境跳过，但本地开发必须能跑。

第八阶段接调用方。`module/file` 写原 zip、原图和文案文档；`module/dag` 和 `module/task` 写结果图和临时产物；`module/imagegen` 写生图结果；`harness/tools` 写大 tool-result。所有调用方只依赖 `ObjectStorage` 和 `StorageKeys`，不得直接注入 `MinioClient`。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

如果工作区有用户已有改动，不要回滚。只在本计划相关文件和后续 storage 模块范围内工作。

实现前重新阅读关键文档：

    Get-Content AGENTS.md
    Get-Content PLANS.md
    Get-Content docs\design-docs\exec-plans\module-dependency-dag-plan.md
    Get-Content docs\design-docs\design.md
    Get-Content docs\design-docs\module\common.md
    Get-Content docs\design-docs\module\storage.md
    Get-Content docs\design-docs\module\cache.md

确认当前没有已存在的 storage 源码：

    rg --files src infra | rg "storage|Storage"

如果没有输出，说明 storage 尚未实现，应按本计划新建模块。如果已有输出，先读现有文件并更新本计划，说明是增量改造还是替换旧实现。

实施依赖时，检查 `pom.xml` 是否已有 MinIO 依赖：

    rg "minio|testcontainers" pom.xml

若没有，应在后续实现计划中新增 MinIO SDK 和测试容器依赖。完成依赖调整后运行：

    mvn test

成功时应看到 Maven 的 BUILD SUCCESS。若因本地没有 Docker 跳过集成测试，输出中应明确显示 MinIO 集成测试被按条件跳过，而不是悄悄不执行。

实现 `StorageKeys` 后，先只运行纯单元测试，验证 key 形态和路径规范化。预期测试覆盖这些输入：

    packageId=42 -> PACKAGES + 42/source.zip
    relPath=folder/a.png -> PACKAGES + 42/images/folder/a.png
    relPath=../evil.png -> rejected
    skuId=SKU/../A -> rejected or normalized to safe segment
    taskId=1001, skuId=SKU123, imageId=88, branchId=b1, ext=webp -> RESULTS + 1001/SKU123_88_b1.webp

实现 MinIO 集成后，用真实容器验证：

    put then getStream returns the same bytes
    stat returns size, etag, content type and last modified time
    presignGet returns a URL that downloads the object before expiry
    delete removes the object
    deleteByPrefix removes only objects under the requested prefix
    initializer creates buckets in development mode
    initializer fails fast when auto-create is false and a required bucket is missing

这些英文短句是测试行为说明，不是要求测试名必须完全一致。

## Validation and Acceptance

本计划的验收标准是可观察行为，而不是类名存在。

第一层验收：纯单元测试通过。`StorageKeys` 对每个模板产出正确的逻辑桶和 key；非法路径段被拒绝；桶解析器能使用默认值和配置覆盖值；`getBytes` 的最大读取限制有测试保护。

第二层验收：MinIO 集成测试通过。测试应启动真实 MinIO 或 S3 兼容容器，验证对象可流式写入和读回，预签名 URL 可下载，单对象删除和前缀删除生效，TMP 生命周期配置被声明。

第三层验收：调用方不保存错误语义。扫描代码时，不应在业务表或 DTO 中新增 `*_path`、`*_minio_key`、`*_url` 作为持久对象引用。应看到 `source_zip_object_key`、`doc_object_key`、`source_image_object_key`、`output_object_key` 这类字段。

第四层验收：没有业务模块直接使用 MinIO SDK。扫描代码时，`MinioClient` 只应出现在 `infra/storage` 实现和测试中；`module/file`、`module/task`、`module/dag`、`module/imagegen`、`harness/tools` 只能使用 `ObjectStorage` 和 `StorageKeys`。

第五层验收：预览和下载不穿应用带宽。业务接口返回给前端的应是短期预签名 URL 或包含该 URL 的响应，不应由 Spring Controller 长期代理对象字节流作为默认路径。确需应用代理的特殊接口必须单独说明理由。

## Idempotence and Recovery

本计划推荐的实现是可重复执行的。建桶操作必须幂等，桶已存在时不报错。TMP 生命周期声明可以重复执行，重复声明应覆盖同名规则或保持同一配置，不产生多条冲突规则。

上传对象的 key 由业务事实决定，相同输入会得到相同 key。若同一 key 重复上传，调用方必须明确这是覆盖还是不允许覆盖。storage 层只提供 put 原语，不替业务决定覆盖策略。

删除操作应尽量容错。单对象删除遇到对象不存在，可以按幂等成功处理或返回明确的 not found 语义，具体实现应在测试中固定。`deleteByPrefix` 删除多个对象时，应收集失败项并报告，但不能因为其中一个失败就隐藏其他失败细节。

如果 MinIO 集成测试因 Docker 不可用无法运行，计划执行者应在 `Progress` 和 `Outcomes & Retrospective` 中记录“纯单元测试已通过，集成测试因环境缺失未执行”。不要把未执行说成通过。

## Artifacts and Notes

本计划最重要的设计产物是字段语义统一。后续所有实现和数据模型讨论都应以这组名字为准：

    source_zip_object_key
    doc_object_key
    source_image_object_key
    original_path
    output_object_key
    result_kind
    generated_copy
    external_object_key

其中 `original_path` 是唯一保留 `path` 后缀的字段，因为它描述的是 zip 内部原始相对路径，不是对象存储身份。其他持久对象引用必须使用 `object_key`。

如果后续需要兼容旧字段，可以通过迁移脚本或 DTO 适配处理，但新模块内部不应继续扩散旧名。

## Interfaces and Dependencies

本模块依赖 `common`，使用其脱敏和错误归一化约定。storage 内部保留独立的 `StorageException`，跨出 infra 边界后由 `common` 翻译为 `STORAGE` 分类。

本模块使用 MinIO 官方 Java SDK 作为第一实现。依赖版本应与 `docs/design-docs/module/storage.md` 保持一致，当前建议是 `io.minio:minio:8.5.11`。如果实现时选择更新版本，必须在 `Decision Log` 记录原因，例如安全修复或兼容 Spring Boot 3。

本模块可使用 Resilience4j 做有限重试和超时治理，因为 `pom.xml` 已有 `resilience4j-spring-boot3`。重试只针对网络、超时和服务端临时错误，不针对对象不存在或权限错误。

最终应存在这些稳定概念和文件位置。实际包名应以当前仓库 `com.pixflow` 为准。

`src/main/java/com/pixflow/infra/storage/BucketType.java` 定义逻辑桶枚举，至少包含 `PACKAGES`、`RESULTS`、`GENERATED`、`TOOL_RESULTS`、`TMP`：

    public enum BucketType {
        PACKAGES,
        RESULTS,
        GENERATED,
        TOOL_RESULTS,
        TMP
    }

`PACKAGES` 存素材包原始 zip、解压原图和文案文档。`RESULTS` 存确定性 DAG 结果。`GENERATED` 存生图结果。`TOOL_RESULTS` 存超阈值外置 tool-result。`TMP` 存临时中间产物。

`src/main/java/com/pixflow/infra/storage/ObjectLocation.java` 表达逻辑桶和对象 key：

    public record ObjectLocation(BucketType bucket, String key) {
        public static ObjectLocation of(BucketType bucket, String key);
    }

`ObjectLocation.of` 应校验 bucket 非空、key 非空、key 不是绝对路径、key 不包含 `..`。更细的业务段规范化由 `StorageKeys` 完成。

`src/main/java/com/pixflow/infra/storage/ObjectRef.java` 表达上传后返回的逻辑桶、key、size 和 etag：

    public record ObjectRef(
        BucketType bucket,
        String key,
        long size,
        String etag
    ) {}

`src/main/java/com/pixflow/infra/storage/StoredObjectMetadata.java` 表达对象元数据：

    public record StoredObjectMetadata(
        long size,
        String contentType,
        String etag,
        Instant lastModified
    ) {}

`src/main/java/com/pixflow/infra/storage/ObjectStorage.java` 定义所有 storage 原语：

    public interface ObjectStorage {
        ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType);
        InputStream getStream(ObjectLocation loc);
        byte[] getBytes(ObjectLocation loc);
        boolean exists(ObjectLocation loc);
        StoredObjectMetadata stat(ObjectLocation loc);
        void delete(ObjectLocation loc);
        void deleteByPrefix(BucketType bucket, String prefix);
        URL presignGet(ObjectLocation loc, Duration ttl);
        URL presignPut(ObjectLocation loc, Duration ttl);
    }

这个接口是上层唯一应该注入的 storage 能力。上层不能注入 `MinioClient`，也不能绕过 `ObjectStorage` 自己创建预签名 URL。

`src/main/java/com/pixflow/infra/storage/StorageKeys.java` 集中 key 模板和路径规范化：

    public final class StorageKeys {
        public static ObjectLocation packageSource(long packageId);
        public static ObjectLocation packageImage(long packageId, String relPath);
        public static ObjectLocation packageDoc(long packageId, String fileName);
        public static ObjectLocation result(long taskId, String skuId, long imageId, String branchId, String ext);
        public static ObjectLocation groupResult(long taskId, String skuId, String groupKey, String branchId, String ext);
        public static ObjectLocation generated(long taskId, String skuId, long imageId, String ext);
        public static ObjectLocation toolResult(String id);
        public static ObjectLocation tmpBranch(long taskId, long imageId, String branchId, String name);
        public static ObjectLocation tmpGroup(long taskId, String groupKey, String branchId, long imageId, String name);
    }

`StorageKeys` 是纯工具类，不能注入配置，不能读取 Spring 环境，不能调用 `ObjectStorage`。它的单元测试应覆盖每个方法的 key 形态和非法输入拒绝。

`src/main/java/com/pixflow/infra/storage/StorageException.java` 收口底层 I/O 异常：

    public class StorageException extends RuntimeException {
        private final String operation;
        private final BucketType bucket;
        private final String key;
        private final boolean retryable;
    }

`operation` 使用稳定字符串，例如 `PUT`、`GET`、`STAT`、`DELETE`、`DELETE_PREFIX`、`PRESIGN_GET`、`PRESIGN_PUT`、`MAKE_BUCKET`、`SET_LIFECYCLE`。`retryable` 只表示底层异常是否适合重试，不代表业务操作一定会被重试。

`src/main/java/com/pixflow/infra/storage/StorageProperties.java` 绑定 `pixflow.storage` 配置。它应表达下面这些字段：

    public class StorageProperties {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String region;
        private boolean autoCreateBucket;
        private Duration presignTtl;
        private DataSize uploadPartSize;
        private DataSize maxBytesReadSize;
        private int tmpExpiryDays;
        private Buckets buckets;
    }

`maxBytesReadSize` 是对 `getBytes` 的保护上限，避免误把大图或 zip 拉进堆。`Buckets` 应包含 `packages`、`results`、`generated`、`toolResults`、`tmp` 五个物理桶名配置项。

`src/main/java/com/pixflow/infra/storage/StorageBucketResolver.java` 把逻辑桶映射为物理桶名：

    public interface StorageBucketResolver {
        String resolve(BucketType bucket);
    }

如果配置缺失某个桶名，启动时应快速失败，而不是等第一次请求才失败。

`src/main/java/com/pixflow/infra/storage/MinioObjectStorage.java` 封装 MinIO SDK，实现 `ObjectStorage`。它的构造依赖应是 MinIO 客户端、`StorageBucketResolver`、`StorageProperties` 和必要的韧性组件。它不能依赖任何 `module/*` 或 `harness/*` 类型。

`src/main/java/com/pixflow/infra/storage/StorageInitializer.java` 启动期建桶并声明 TMP 生命周期。它的行为应区分开发和生产：

    autoCreateBucket=true  -> 缺桶则创建，并给 TMP 桶声明生命周期。
    autoCreateBucket=false -> 只校验桶存在，缺失则启动失败。

`src/main/java/com/pixflow/infra/storage/config/StorageAutoConfiguration.java` 装配 MinIO 客户端和 storage bean。它应注册 `StorageProperties`、`StorageBucketResolver`、`ObjectStorage`、`StorageInitializer`，并避免在测试中强制连接外部 MinIO，集成测试应通过测试配置显式启用真实容器。

调用方契约如下。

`module/file` 上传原始 zip 时调用 `StorageKeys.packageSource(packageId)`，保存返回 `ObjectRef.key` 到 `asset_package.source_zip_object_key`。解压并识别图片后调用 `StorageKeys.packageImage(packageId, relPath)`，保存 `ObjectRef.key` 到 `asset_image.source_image_object_key`，同时把 zip 内相对路径保存到 `asset_image.original_path`。文案文档调用 `StorageKeys.packageDoc(packageId, fileName)`，保存到 `asset_package.doc_object_key`。

`module/dag` 或 `module/task` 写普通结果图时调用 `StorageKeys.result(taskId, skuId, imageId, branchId, ext)`，保存 `ObjectRef.key` 到 `process_result.output_object_key`，并把 `process_result.result_kind` 设为 `DAG_RESULT`。写组支路合成图时调用 `StorageKeys.groupResult(taskId, skuId, groupKey, branchId, ext)`，同样保存到 `process_result.output_object_key`，并保留 `group_key` 和成员明细。

`module/imagegen` 写生图结果时调用 `StorageKeys.generated(taskId, skuId, imageId, ext)`，保存 `ObjectRef.key` 到 `process_result.output_object_key`，并把 `process_result.result_kind` 设为 `GENERATED_IMAGE`。

`harness/tools` 外置大 tool-result 时调用 `StorageKeys.toolResult(id)`，保存返回 key 到 trace 或工具结果引用中，字段名使用 `external_object_key` 或 `object_key`，不要使用 URL。

`module/dag` 和 `module/task` 写临时中间产物时调用 `StorageKeys.tmpBranch(...)` 或 `StorageKeys.tmpGroup(...)`。这些 key 默认不进 MySQL 事实表，只作为 Redis 轻量引用值的一部分；字节本体写入 `TMP` 桶。

任何调用方都不能直接拼物理桶名，不能把预签名 URL 入库，不能直接使用 `MinioClient`。

## Change Notes

2026-06-27 / Codex: 创建本计划。原因是用户要求按照 `PLANS.md` 的格式撰写中文计划文档，并明确讨论 storage 模块的对外 API 形状、调用方应该保存什么 key、字段名语义如何改得更清楚，以及如何通过关键词快速定位参考设计文本。

2026-06-27 / Codex: 按用户要求参考 permission 实现计划，补充 API 接口、record、配置、初始化器和调用方契约的签名级说明。原因是原计划只说明了能力范围和方法名，仍不足以让后续实现者像 permission 计划那样直接照着创建稳定接口。

2026-06-27 / Codex: 按执行计划实现 `infra/storage` 源码、测试和 Maven 依赖，并同步更新 Progress、Surprises & Discoveries、Decision Log、Outcomes & Retrospective。原因是用户要求执行计划并编写 storage 模块代码，计划文档必须作为活文档记录真实执行结果。
