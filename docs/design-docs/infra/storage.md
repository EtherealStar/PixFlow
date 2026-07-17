# storage —— MinIO 对象存储抽象（Wave 1 基础设施）

> 本文是 PixFlow 完整重写阶段 `infra/storage` 模块的设计文档，对应 `design.md` 第十二章「业务模块划分」、第十三章 §13.4「MinIO（对象）」与 `module-dependency-dag-plan.md` 的 **Wave 1 基础设施**。
> 范围：对象存储的纯 I/O 抽象、桶布局、key 约定中心、流式读写、预签名 URL、生命周期清理、韧性与错误收口。
> 本文不涉及 MVP 既有实现，从新架构需求重新推导，按生产级标准设计，不做 MVP 式简化。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、模块结构与依赖位置](#二模块结构与依赖位置)
- [三、桶布局与保留策略](#三桶布局与保留策略)
- [四、Key 约定中心（StorageKeys）](#四key-约定中心storagekeys)
- [五、核心抽象](#五核心抽象)
- [六、关键能力详解](#六关键能力详解)
- [七、生命周期与 tmp 清理](#七生命周期与-tmp-清理)
- [八、桶初始化与开关](#八桶初始化与开关)
- [九、韧性与错误收口](#九韧性与错误收口)
- [十、配置](#十配置)
- [十一、安全与脱敏](#十一安全与脱敏)
- [十二、对其他模块的契约](#十二对其他模块的契约)
- [十三、测试策略](#十三测试策略)
- [十四、暂不考虑](#十四暂不考虑)

---

## 一、文档定位与设计原则

`infra/storage` 在依赖 DAG 中处于 **Wave 1**，只依赖 `common`，被 `module/file`、`module/dag`、`module/task`、`module/imagegen`、`harness/state`、`harness/context`、`harness/tools` 等多方消费。它承载对象存储的**纯 I/O 原语**，不含任何业务领域知识。

存储专属设计原则：

1. **纯 I/O，无业务语义**。storage 只认 `(桶, key)` 与字节流，不知道什么是「素材包」「支路结果」「生图」。所有业务路径模板归集到独立的 key 约定中心，storage 核心不依赖它。
2. **接口与实现分离，可无缝切换**。对上暴露 `ObjectStorage` 接口，MinIO 客户端封装在实现后面。本期实现采用 **MinIO 官方 Java SDK**；未来若切阿里云 OSS，只替换实现类与配置，消费方零改动。
3. **流式优先，拒绝全量入堆**。图片、zip 动辄数十 MB，读写一律以 `InputStream` 为主，大文件走分片上传，绝不强制把整个对象读成 `byte[]`。
4. **下载走预签名，不穿应用带宽**。结果图下载、前端预览通过预签名 URL 让客户端直连对象存储，应用只签发链接，不代理字节流。
5. **底层只懂 I/O，错误在边界归一化**。storage 抛独立的 `StorageException`（携带操作/桶/key/可重试上下文），由 `common` 的 `ErrorNormalizer` 在跨出 infra 边界时翻译为 `STORAGE` 分类（默认 `RETRY`）。见 `common.md` §10。
6. **多桶分治**。按内容类型分桶，使不同类别可独立配置生命周期、权限与配额；临时产物桶配 24 小时自动过期。
7. **生产级，不简化**。分片上传、预签名、桶生命周期、韧性重试、Testcontainers 集成测试齐备，不走应用代理下载等 MVP 捷径。

---

## 二、模块结构与依赖位置

源码包：`com.pixflow.infra.storage`

```
infra/storage/
├── ObjectStorage.java            # 核心接口：put/get/exists/delete/presign/stat（纯 I/O）
├── BucketType.java               # 逻辑桶枚举（PACKAGES/RESULTS/GENERATED/TOOL_RESULTS/TMP）
├── ObjectLocation.java           # record(BucketType bucket, String key)
├── ObjectRef.java                # record(BucketType, key, size, etag) —— put 后的句柄
├── StoredObjectMetadata.java     # record(size, contentType, etag, lastModified)
├── StorageKeys.java              # key 约定中心：业务路径模板 → ObjectLocation（纯静态，无 IO 依赖）
├── toolresult/
│   ├── ToolResultStorage.java    # 大 tool-result 共享外置抽象：write/read/ref/preview/miss
│   └── ObjectStorageToolResultStorage.java # 基于 ObjectStorage + StorageKeys.toolResult 的实现
├── StorageException.java         # 独立领域异常（携带 operation/bucket/key/retryable）
├── MinioObjectStorage.java       # ObjectStorage 的 MinIO 实现（封装 MinioClient + Resilience4j）
├── StorageProperties.java        # @ConfigurationProperties(pixflow.storage)
├── StorageBucketResolver.java    # BucketType → 物理桶名（读 StorageProperties）
├── StorageInitializer.java       # 启动期建桶 + 声明 tmp 生命周期（受开关控制）
└── config/
    └── StorageAutoConfiguration.java  # 装配 MinioClient、ObjectStorage Bean
```

依赖方向：`storage → common`（用 `Sanitizer` 脱敏、抛出由 `common` 归一化的异常），不反向依赖任何 `harness`/`module`/`agent`。

新增 Maven 依赖（`pom.xml`）：

    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>8.5.11</version>
    </dependency>

> MinIO SDK 自带 OkHttp 传输层；分片上传、预签名、桶生命周期等能力均由其原生 API 提供。

---

## 三、桶布局与保留策略

采用**多桶**模型。`design.md` §13.4 原本的顶层前缀（`packages/`、`results/`、`tool-results/`）在多桶模型下提升为独立桶，桶内 key 去掉顶层段。桶名由配置承载，下表为默认值。

| 逻辑桶（`BucketType`） | 默认物理桶名 | 内容 | 保留策略 |
|---|---|---|---|
| `PACKAGES` | `pixflow-packages` | 素材包原始 zip、解压后原图、文案文档 | 永久（随素材包显式删除） |
| `RESULTS` | `pixflow-results` | 已发布的确定性 DAG 输出 Asset Image | 随 generated asset 显式删除；不随 task/Activity 记录删除 |
| `GENERATED` | `pixflow-generated` | 已发布的 IMAGEGEN 输出 Asset Image | 随 generated asset 显式删除；不随 task/Activity 记录删除 |
| `TOOL_RESULTS` | `pixflow-tool-results` | 外置的大 tool-result（>阈值），供模型引用与回放 | 长期（默认不过期；可配 TTL） |
| `TMP` | `pixflow-tmp` | 中间产物、断点临时文件 | **24 小时自动过期** |

分桶理由：

- **生命周期分治**：`TMP` 是唯一需要激进回收的桶，单独成桶后用对象存储原生生命周期规则（24h 过期）自动清理，无需应用层定时扫描删除。其余桶各自永久/长期，互不干扰。
- **权限与配额分治**：未来可对 `RESULTS`/`GENERATED`（对外可下载）与 `TOOL_RESULTS`（内部回放）配不同访问策略。
- **`RESULTS` 与 `GENERATED` 分桶而非合并**：两条产图路径（确定性 vs 生成式）产物来源不同，分桶便于按路径统计、配额与未来差异化策略；二者保留策略当前一致。`process_result.output_minio_key` 存的是桶内 key，桶由结果类型推导。

> `design.md` §13.4 的 `tool-results/{id}.txt` 等路径在本模块落为「`TOOL_RESULTS` 桶 + key `{id}.txt`」，语义不变。

---

## 四、Key 约定中心（StorageKeys）

业务路径模板既不能散落到各业务模块各拼各的字符串，也不能污染 storage 核心 I/O。折中：独立的 `StorageKeys` 纯静态工具类，集中所有路径模板，返回 `ObjectLocation`；storage 核心（`ObjectStorage`）不依赖它，只认 `ObjectLocation`。

`StorageKeys` 只产出「逻辑桶 + key」，不解析物理桶名（物理名由 `StorageBucketResolver` 在实现层解析），因此它无需读配置，保持纯静态、可单测。

模板清单（与 `design.md` §13.4 对齐）：

| 方法 | 逻辑桶 | key 模板 | 来源 |
|---|---|---|---|
| `packageSource(packageId, archiveExt)` | PACKAGES | `{packageId}/source.{archiveExt}` | 原始 ZIP/RAR/7z 压缩包 |
| `packageImage(packageId, relPath)` | PACKAGES | `{packageId}/images/{relPath}` | 解压后原图 |
| `packageDoc(packageId, fileName)` | PACKAGES | `{packageId}/doc/{fileName}` | 文案文档 |
| `resultUnit(taskId, unitKeyHash, runEpoch, ext)` | TMP | `results/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.{ext}` | 普通/组 Work Unit 候选；发布前不可见 |
| `generatedUnit(taskId, unitKeyHash, runEpoch, ext)` | TMP | `generated/{taskId}/units/{unitKeyHash}/epochs/{runEpoch}/output.{ext}` | IMAGEGEN 候选；发布前不可见 |
| `resultAsset(packageId, imageId, ext)` | RESULTS | `{packageId}/images/{imageId}/output.{ext}` | File 注册后稳定的 generated asset 对象 |
| `generatedAsset(packageId, imageId, ext)` | GENERATED | `{packageId}/images/{imageId}/output.{ext}` | File 注册后稳定的 IMAGEGEN asset 对象 |
| `toolResult(id)` | TOOL_RESULTS | `{id}.txt` | 外置大 tool-result |
| `runtimeGroup(taskId, runEpoch, unitKeyHash, memberId, name)` | TMP | `{taskId}/{runEpoch}/{unitKeyHash}/{memberId}/{name}` | 当前 epoch 组成员临时对象；非 checkpoint，epoch 结束/TTL 清理 |

> key 中的业务标识（skuId、文件名等）来自外部输入，模板内对路径分隔与非法字符做规范化（去前导 `/`、禁止 `..`），防止路径穿越。规范化逻辑随 `StorageKeys` 一并单测。

---

## 五、核心抽象

### 5.1 `BucketType` / `ObjectLocation`

```java
public enum BucketType { PACKAGES, RESULTS, GENERATED, TOOL_RESULTS, TMP }

public record ObjectLocation(BucketType bucket, String key) {
    public static ObjectLocation of(BucketType bucket, String key) { ... }
}
```

逻辑桶与 key 解耦于物理桶名，物理名解析集中在 `StorageBucketResolver`（读 `StorageProperties`）。这样切换部署/改桶名只动配置，模板与调用方不变。

### 5.2 `ObjectStorage` —— 纯 I/O 接口

```java
public interface ObjectStorage {

    // 流式写入；size<0 表示未知大小，走自动分片。contentType 可空（默认 application/octet-stream）
    ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType);

    // 流式读取；调用方负责 close()，建议 try-with-resources
    InputStream getStream(ObjectLocation loc);

    // 小对象/文本便捷读（如外置 tool-result 预览），内部仍受最大读限保护
    byte[] getBytes(ObjectLocation loc);

    boolean exists(ObjectLocation loc);
    StoredObjectMetadata stat(ObjectLocation loc);

    void delete(ObjectLocation loc);
    void deleteByPrefix(BucketType bucket, String prefix);   // 任务/支路级批量清理
    ObjectRef copy(ObjectLocation source, ObjectLocation target); // 候选结果发布为稳定 Asset Image

    URL presignGet(ObjectLocation loc, Duration ttl);        // 下载/预览直连
    URL presignPut(ObjectLocation loc, Duration ttl);        // 前端直传（预留）
}
```

- **接口完全无业务知识**：参数只有 `ObjectLocation`、流、`Duration`，没有「素材包」「50KB」「tool-result」这类语义。
- `ObjectRef`、`StoredObjectMetadata` 均为不可变 record：

```java
public record ObjectRef(BucketType bucket, String key, long size, String etag) {}
public record StoredObjectMetadata(long size, String contentType, String etag, Instant lastModified) {}
```

---

## 六、关键能力详解

### 6.1 流式与分片上传

- `put` 接 `InputStream + size`。MinIO SDK 的 `PutObjectArgs.stream(in, objectSize, partSize)`：
  - `size ≥ 0`：传已知大小，`partSize` 取 `-1` 由 SDK 决定。
  - `size < 0`：未知大小（如边解压边上传），`partSize` 取配置值（默认 5 MiB，MinIO 下限）。
- 读用 `GetObjectArgs` 返回的 `GetObjectResponse`（即 `InputStream`），调用方流式消费、`try-with-resources` 关闭，避免大图整体入堆。

### 6.2 预签名 URL（生产级下载）

- `presignGet` 调 `getPresignedObjectUrl(method=GET, expiry=ttl秒)`，返回带签名的临时 URL。
- `module/task` 的下载入口、`module/file` 的结果预览、前端展示，都拿预签名 URL 让客户端直连 MinIO，**字节流不经过 Spring 应用**，省带宽、可被 CDN 前置。
- TTL 由配置控制（默认 15 分钟），过期自动失效。
- `presignPut` 预留前端直传能力，本期可不接业务，但接口先在。

### 6.3 批量删除（前缀）

- `deleteByPrefix(bucket, prefix)`：`listObjects(prefix, recursive=true)` 拿到 key 列表后 `removeObjects` 批量删。
- 用于任务级/支路级清理 `TMP` 桶残留，以及素材包删除时清 `PACKAGES` 桶对应前缀。禁止用 task prefix 删除 `RESULTS`/`GENERATED`，因为成功资产生命周期独立于执行记录。
- 批量删按页处理，避免单次列举过大；删除失败的 key 收集后随 `StorageException` 上报（含失败明细 details）。

### 6.4 大 tool-result 外置（共享 ToolResultStorage）

`design.md` §5.2 的「结果预算」：工具结果超阈值（默认 50KB）写 MinIO，模型只见引用+预览。阈值判定属于调用方策略，但 key 生成、内容 hash 去重、preview 生成、引用模型和缺失回读降级必须三处一致，因此在 `infra/storage` 中抽出共享 `ToolResultStorage`。它是 `ObjectStorage` 之上的薄适配，不让业务模块直接接触 MinIO，也不改变 storage 核心「纯 I/O」原则。

- storage 核心仍只提供原语：`put(StorageKeys.toolResult(id), ...)` 写入、`getBytes` 取回。
- `ToolResultStorage` 负责把 `(toolCallId, content, previewChars)` 转成稳定 `ToolResultReference`，底层写入 `TOOL_RESULTS` 逻辑桶；同 `toolCallId` 且内容相同复用同一 key，内容不同带 hash 后缀。
- 「是否超过 50KB」由 `harness/context`、`harness/tools`、`harness/session` 各自按配置决定；它们只调用共享 `ToolResultStorage` 做外置和回读，不各自拼 key 或定义引用 JSON。
- 回读时对象缺失不应让 transcript rehydrate 失败；`ToolResultStorage` 返回带 `missing=true` 的引用或等价结果，由调用方保留 preview 并标记 `missingExternalToolResult`。

---

## 七、生命周期与 tmp 清理

- **`TMP` 桶配 24 小时自动过期**：通过 MinIO 桶生命周期规则（`SetBucketLifecycleArgs` + `LifecycleConfiguration`，规则 `Expiration(days=1)` 作用于全桶）实现。所有临时产物落 `TMP` 桶，到期由对象存储自动回收，**应用层无需定时删除任务**。
- 规则在 `StorageInitializer` 启动期声明（幂等：重复声明覆盖同一规则），受建桶开关控制。
- 主动清理仍保留 `deleteByPrefix` 供「当前 epoch compose 成功后删 group runtime refs」「任务结束清 epoch/TMP 前缀」等即时回收场景；与 24h 兜底过期互补——正常路径即时删，异常残留靠生命周期兜底。
- 其余桶（`PACKAGES`/`RESULTS`/`GENERATED`）不配过期；`TOOL_RESULTS` 默认不过期，TTL 留作可配项（供回放/trace 长期留存）。

成功发布顺序为：候选写 TMP → fenced result SUCCESS → File 分配新 `imageId` → copy 到稳定 result/generated asset key → 事务登记 `asset_image` 与 lineage → 删除 TMP 候选。只有登记完成的稳定对象进入 Outputs。失败/取消只清理候选；删除成功 task/Activity 记录不触碰稳定资产对象。

> 设计取舍：Work Unit checkpoint 只在 MySQL `process_result.status=SUCCESS`；Redis 的 `runref:group:{taskId}:{runEpoch}:*` 只是当前 epoch 的运行态引用，MinIO 的 `TMP`/epoch 对象由生命周期兜底清理。对象写入与 fenced MySQL SUCCESS 分离，不能仅凭 MinIO 存在宣告完成。

---

## 八、桶初始化与开关

- `StorageInitializer`（`ApplicationRunner` 或 `@PostConstruct`）在启动期：
  1. 遍历 `BucketType`，对不存在的桶 `makeBucket`（`bucketExists` 判定，幂等）。
  2. 为 `TMP` 桶声明 24h 生命周期规则。
- 受开关 `pixflow.storage.auto-create-bucket` 控制：
  - **开发默认 `true`**：自动建桶 + 配生命周期，开箱即用。
  - **生产默认 `false`**：桶与策略由运维预建，应用启动仅校验桶存在（缺失则快速失败并明确报错，而非静默创建）。

---

## 九、韧性与错误收口

### 9.1 韧性（Resilience4j）

- `MinioObjectStorage` 对幂等操作（put/get/delete/stat/presign 均幂等）包裹 Resilience4j **重试 + 超时**，吸收瞬时网络抖动。
- 与 `common.md` 把 `STORAGE` 默认 `recovery` 定为 `RETRY` 一致——底层先做有限重试，仍失败则抛 `StorageException` 交上层按 `RETRY` 语义再决策。
- 重试仅针对网络/超时类，不针对「对象不存在」「桶不存在」这类确定性失败（直接抛，不浪费重试）。

### 9.2 错误收口

`StorageException` 为独立领域异常，**不依赖 `common` 的分类体系**（保持底层纯粹），携带定位上下文：

```java
public class StorageException extends RuntimeException {
    private final String operation;   // PUT / GET / DELETE / PRESIGN / LIST ...
    private final BucketType bucket;
    private final String key;
    private final boolean retryable;  // 供归一化与重试参考
    // + message + cause
}
```

- storage 内部 catch MinIO SDK 的 `ErrorResponseException`/`IOException`/`ServerException` 等，统一包成 `StorageException`，区分 `retryable`。
- 跨出 infra 边界由 `common` 的 `ErrorNormalizer` 翻译为 `PixFlowException(category=STORAGE)`（接线机制属 `common` 职责，见 `common.md` §10）；storage 侧只保证抛出携带完整上下文的 `StorageException`。
- 落日志/上报前，message 与 details 中的路径、凭证经 `common` 的 `Sanitizer` 脱敏。

---

## 十、配置

`@ConfigurationProperties(prefix = "pixflow.storage")`：

```yaml
pixflow:
  storage:
    endpoint: http://localhost:9000      # MinIO 地址；切 OSS 改此处
    access-key: ${MINIO_AK:minioadmin}
    secret-key: ${MINIO_SK:minioadmin}
    region: us-east-1                    # MinIO 可留默认；OSS 填对应 region
    auto-create-bucket: true             # 生产置 false
    presign-ttl: 15m                     # 预签名 URL 默认有效期
    upload-part-size: 5MiB               # 未知大小时的分片尺寸
    tmp-expiry-days: 1                   # TMP 桶生命周期天数
    buckets:                             # 逻辑桶 → 物理桶名（默认值可覆盖）
      packages: pixflow-packages
      results: pixflow-results
      generated: pixflow-generated
      tool-results: pixflow-tool-results
      tmp: pixflow-tmp
```

- `access-key`/`secret-key` 是敏感信息：从环境变量注入，**禁止打日志**，异常上下文经 `Sanitizer` 遮蔽（参考 `common.md` §5.6 对 AK/SK 的正则遮蔽）。
- `StorageBucketResolver` 读 `buckets` 映射，把 `BucketType` 解析为物理桶名。

---

## 十一、安全与脱敏

1. **桶非公开**：所有桶默认私有，对外访问一律走预签名 URL（有限期），不开放匿名读。
2. **预签名最小权限 + 短 TTL**：下载 URL 默认 15 分钟过期；直传 URL 仅在需要时签发。
3. **路径穿越防护**：`StorageKeys` 对外部输入（skuId、文件名、relPath）规范化，禁止 `..` 与绝对路径注入。
4. **凭证脱敏**：endpoint/AK/SK 不入日志；异常与 trace 落盘前过 `Sanitizer`。
5. **大小限制**：`getBytes` 设最大读上限（防止误用便捷读把超大对象拉进堆）；超限要求改用 `getStream`。

---

## 十二、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `module/file` | 用 `StorageKeys.package*` 写入原 zip/原图/文档；大文件走 `put` 流式分片 |
| `module/dag`/`task` | 候选结果写 TMP；fenced SUCCESS 后经 File publication port 提升到 RESULTS；任务清理只删未发布 TMP 对象 |
| `module/imagegen` | 单图候选写 TMP；成功后经 File publication port 提升到 GENERATED 并获得新 imageId |
| `harness/context` | cheap pipeline 判断单条 tool_result 超阈值后调用共享 `ToolResultStorage`，只把引用+preview 送入模型 |
| `harness/tools` | Tool handler 返回超阈值结果后调用共享 `ToolResultStorage`，trace/工具结果只保存稳定引用 |
| `harness/session` | transcript 落库前调用共享 `ToolResultStorage` 外置大 tool_result，`load` rehydrate 时通过同一抽象回读完整 content |
| `harness/state` | 中间产物引用的 put/get/delete 继续使用 `ObjectStorage` 原语 |
| `common` | storage 抛 `StorageException`，由 `ErrorNormalizer` 边界翻译为 `STORAGE`；脱敏用 `Sanitizer` |
| 调用方统一 | 只依赖 `ObjectStorage`、`StorageKeys` 与共享 `ToolResultStorage`，不直接接触 `MinioClient` |

---

## 十三、测试策略

- **集成测试（Testcontainers + 真实 MinIO 容器）**：put/get 流式往返、分片上传大对象、`presignGet` 签发后可下载、`exists`/`stat`/`delete`/`deleteByPrefix`、启动期建桶 + tmp 生命周期声明全链路。这是验证 S3 兼容行为的唯一可靠方式。
- **StorageKeys 单测**：每个模板的 key 形态、路径规范化、`..`/绝对路径注入被拦截。
- **BucketResolver 单测**：`BucketType` → 物理桶名映射、配置覆盖默认值。
- **韧性测试**：模拟瞬时失败触发重试、确定性失败（NoSuchKey）不重试直接抛。
- **错误收口测试**：MinIO 异常被包成 `StorageException` 且 `retryable` 标注正确、上下文（operation/bucket/key）齐全。
- **脱敏测试**：含 AK/SK 的异常上下文经 `Sanitizer` 后无凭证泄露。

> 若 CI 无 Docker，Testcontainers 用例标注按环境跳过（开发本地必跑），并补一组针对 `StorageKeys`/`BucketResolver`/错误映射的纯单测保证最低覆盖。

---

## 十四、暂不考虑

- 对象版本控制、跨区域复制、服务端加密（SSE）——本期单租户 + 本地 MinIO，不做，但接口不堵死。
- CDN 回源——前端预览先用预签名 URL，CDN 后续再加。
- 阿里云 OSS 真实切换——本期用 MinIO 官方 SDK，OSS 适配作为后续实现替换项；接口层已为替换预留。
- 前端直传业务接线——`presignPut` 接口先在，业务编排后续再做。

## Revision Notes

2026-07-12 / Codex: 对齐 task/dag 的结果写序：结果对象按 task/unit/run_epoch 确定性寻址；MinIO 对象存在不等于完成，只有 MySQL fenced `process_result.status=SUCCESS` 引用的对象对外可见，失效 epoch 对象由生命周期清理。
