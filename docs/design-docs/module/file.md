# module/file —— 素材管理（上传 / 解压 / 绑定，Wave 2）

> 本文是 PixFlow 完整重写阶段 `module/file` 模块的设计文档，对应 `design.md` 第八章相关的素材接入、第九章 9.5「分组聚合（文件名驱动分组）」、第十二章「业务模块划分」、第十三章 §13.1 数据模型与 §13.4 MinIO 布局，以及 `module-dependency-dag-plan.md` 的 **Wave 2**。
> 范围：素材包上传、**MQ 驱动的异步解压入库**、文件名驱动的 SKU / 分组绑定、文案文档解析，以及素材包查询 / 删除。
> 配套阅读：`infra/storage.md`（对象 I/O 与 `StorageKeys`）、`infra/mq.md`（可靠消息设施与消费模型）、`common.md`（错误归一化、`Sanitizer`、跨切 SPI 模式）、`harness/state.md`（运行态读模型与「state 不推送」边界）。本文不涉及 MVP 既有实现，从新架构需求重新推导，按生产级标准设计。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、职责边界（入口侧 vs 出口侧）](#二职责边界入口侧-vs-出口侧)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、数据模型与状态机](#四数据模型与状态机)
- [五、上传与解压管线（MQ 驱动）](#五上传与解压管线mq-驱动)
- [六、Zip 安全（slip / bomb）](#六zip-安全slip--bomb)
- [七、文件名驱动解析（SKU / 分组）](#七文件名驱动解析sku--分组)
- [八、文案文档解析](#八文案文档解析)
- [九、进度推送（复用 WebSocket）](#九进度推送复用-websocket)
- [十、恢复、幂等与失败隔离](#十恢复幂等与失败隔离)
- [十一、错误与降级](#十一错误与降级)
- [十二、配置](#十二配置)
- [十三、对其他模块的契约](#十三对其他模块的契约)
- [十四、测试策略](#十四测试策略)
- [十五、对 design.md 的细化](#十五对-designmd-的细化)
- [十六、暂不考虑](#十六暂不考虑)

---

## 一、文档定位与设计原则

`module/file` 处于依赖 DAG 的 **Wave 2**，依赖 `infra/storage`（原 zip / 原图 / 文档的对象 I/O 与 key 约定）、`infra/mq`（解压作业的可靠异步分发）、`common`（错误归一化、脱敏、`ProgressNotifier` 跨切 SPI）+ MySQL/MyBatis-Plus + Apache POI / commons-csv（文案解析）。被 `module/dag`、`module/task`、`agent`、`module/conversation`（附件关联）按 `asset_*` 表与素材包查询接口消费。

模块专属设计原则：

1. **入口侧聚焦**。file 只负责「素材进系统」：上传、解压、绑定、文案解析。结果图与评分的展示是**出口侧**，依赖 `task`/`rubrics`（Wave 4/6）写入的表，本期不在 file 实现（见 [§二](#二职责边界入口侧-vs-出口侧)）。
2. **重活异步、入口轻**。HTTP 上传请求只做「流式落 zip + 建包记录 + 发解压消息」，秒级返回 `packageId`；耗时的解压 / N 次 MinIO 上传 / 批量入库交 RabbitMQ 作业异步处理（见 [§五](#五上传与解压管线mq-驱动)）。
3. **解压作业幂等可重投**。一个包 = 一条消息，`process-then-ack` + `(package_id, original_path)` 唯一约束让崩溃后的 MQ 重投天然续跑，**不需要自建恢复扫描与分布式锁**（见 [§十](#十恢复幂等与失败隔离)）。
4. **只准入、不解码**。file 对图片做轻量准入（扩展名白名单 + magic bytes 嗅探 + 大小限制），**不调 ImageIO 解码**——真正的解码 / 像素校验留给处理期的 `infra/image` + `module/dag`，保持 file 不依赖 `infra/image`。
5. **分组由文件名编号决定**。SKU / 分组 / 视图全部从文件名编号解析，不做视觉识别（对齐 `design.md §9.5`）。
6. **安全是硬约束**。zip slip、zip bomb、路径穿越在解压侧前置拦截，超阈值即拒（见 [§六](#六zip-安全slip--bomb)）。
7. **生产级、不简化**。流式 I/O、可靠投递、毒包 DLQ、断点续跑、Testcontainers 集成测试齐备。

---

## 二、职责边界（入口侧 vs 出口侧）

`design.md §12` 把 `module/file` 描述为「上传 / 解压 / SKU 绑定 / 结果管理 / 评分展示」。但依赖 DAG 中 file 只有 `storage → file` 一条入边且处于 **Wave 2**，而「结果管理 / 评分展示」要读 `process_result`（`module/task` 写，Wave 4）与 `rubrics_score`（`module/rubrics` 写，Wave 6）。若 file 在 Wave 2 就承担出口侧，会反向依赖后续波次模块，破坏拓扑分层。

因此沿数据流向切分职责：

| 侧 | 职责 | 波次 | 本文是否覆盖 |
|---|---|---|---|
| **入口侧** | 上传、解压、SKU/分组绑定、文案文档解析、素材包查询/删除 | Wave 2 | ✅ 是 |
| **出口侧** | 结果图聚合展示、评分展示、打包下载 | task/rubrics 就绪后（Wave 4+） | ❌ 否（预留） |

出口侧将来以**只读聚合视图**实现，可作为 file 的后续扩展或独立读模块，消费 `process_result` / `rubrics_score` + `storage.presignGet`。本文设计不堵死它，但不在 Wave 2 落地。

---

## 三、模块结构与依赖位置

Maven 模块：`pixflow-module-file`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.file`

```
module/file/
├── FileService.java                  # 对外门面：upload / 查询 / 删除（解压触发委托给 ingest）
├── pkg/
│   ├── AssetPackageService.java      # 素材包生命周期 + 状态机迁移
│   ├── PackageStatus.java            # enum UPLOADED/EXTRACTING/READY/PARTIAL/FAILED
│   ├── AssetPackage.java             # 实体
│   └── AssetPackageMapper.java       # MyBatis-Plus
├── ingest/
│   ├── ExtractionTopology.java       # 用 QueueTopologyBuilder 声明 pixflow.file 队列组
│   ├── ExtractionPublisher.java      # 上传后发解压消息（MessagePublisher 封装）
│   ├── ExtractionConsumer.java       # process-then-ack 消费：跑完整包解压才 ack
│   ├── ExtractionErrorHandler.java   # 实现 infra/mq 的 ConsumerErrorHandler（重试/DLQ 判定）
│   ├── ZipExtractor.java             # 中央目录预扫 + 流式逐 entry 上传 + 批量入库
│   ├── ImageAdmission.java           # 扩展名白名单 + magic bytes + 大小（不解码）
│   ├── ExtractionMessage.java        # 消息体（仅 packageId，zip 已在 MinIO）
│   └── PublishGapRescan.java         # @Scheduled 仅补「UPLOADED 但消息未投递」的缺口
├── naming/
│   ├── FileNameParser.java           # 文件名 → ParsedName（三段/两段/兜底）
│   ├── SkuExtractor.java             # 接口：兜底 SKU 提取（默认实现可替换）
│   └── ParsedName.java               # record(groupKey?, skuId, viewId?)
├── copydoc/
│   ├── CopyDocParser.java            # 接口：文档 → List<AssetCopy>
│   ├── CsvCopyDocParser.java         # commons-csv
│   ├── ExcelCopyDocParser.java       # Apache POI
│   ├── CopyDocColumnMapping.java     # 列名→字段映射（容忍中文表头）
│   └── AssetCopyMapper.java
├── image/
│   ├── AssetImage.java               # 实体（唯一约束 package_id + original_path）
│   └── AssetImageMapper.java
├── error/
│   ├── AssetIngestError.java         # 逐图/文档解析失败明细，供包详情与排障查询
│   └── AssetIngestErrorMapper.java
├── web/
│   └── FileController.java           # 上传 / 包详情(轮询进度) / 列表 / 删除
└── config/
    └── FileProperties.java           # @ConfigurationProperties(pixflow.file)
```

依赖方向：

```
module/file ──► infra/storage   （StorageKeys + ObjectStorage 流式读写、级联删除）
module/file ──► infra/mq        （QueueTopology 声明、MessagePublisher、ConsumerErrorHandler）
module/file ──► common          （ErrorNormalizer / Sanitizer / ProgressNotifier SPI / ApiResponse）
module/file ──► MySQL / MyBatis-Plus + POI + commons-csv
```

**不依赖** `infra/image`（不解码）、`infra/cache`（process-then-ack 免锁）、`harness/*`、`module/task` 等后续波次模块。WebSocket 推送经 `common` 的 `ProgressNotifier` SPI 解耦（见 [§九](#九进度推送复用-websocket)）。

---

## 四、数据模型与状态机

### 4.1 MySQL

沿用 `design.md §13.1` 的三张核心表，并对 `asset_package` 补充**状态枚举**、**进度列**与软删除列，同时新增一张轻量失败明细表 `asset_ingest_error`（细化见 [§十五](#十五对-designmd-的细化)）：

| 表 | 关键字段 | 说明 |
|---|---|---|
| `asset_package` | **id BIGINT**, name, minio_zip_key, doc_key, **status**, **image_count**, **extracted_count**, error_summary, **deleted_at**, created_at, updated_at | 素材包；`id` 与现有 `StorageKeys.package*(long packageId)` 保持一致；`image_count`=中央目录预扫得到的有效图总数；`extracted_count`=已入库进度；`deleted_at` 用于被历史任务/对话引用后的软删除 |
| `asset_image` | **id BIGINT**, package_id, sku_id, group_key, view_id, minio_key, original_path, created_at | 单图；**唯一约束 `(package_id, original_path)`** 保证重投幂等；`group_key` 非空=分组成员 |
| `asset_copy` | **id BIGINT**, package_id, sku_id, product_name, keywords, description | 文案条目；一文档多行，按 `sku_id` 关联 |
| `asset_ingest_error` | **id BIGINT**, package_id, original_path, stage, code, message, created_at | 解压/准入/上传/命名/文档解析的失败明细；`error_summary` 只存摘要，详情由此表分页查询 |

- `minio_zip_key` = `StorageKeys.packageSource(packageId)`；`doc_key` = `StorageKeys.packageDoc(packageId, fileName)`（可空）。
- `asset_image.minio_key` = `StorageKeys.packageImage(packageId, relPath)`；`original_path` = zip 内相对路径（=`relPath`）。
- `error_summary`：解压终态为 `FAILED`/`PARTIAL` 时的脱敏摘要（`Sanitizer` 截断 ≤1000 字），只保留分类统计与前 N 条样例；完整逐项失败进入 `asset_ingest_error`。
- `asset_package.id` 统一用 `BIGINT`（MySQL 自增或雪花 ID 均可），不使用 UUID 字符串。原因是 `infra/storage` 已落地的 `StorageKeys.packageSource(long packageId)`、`packageImage(long packageId, ...)`、`packageDoc(long packageId, ...)` 均以 `long` 为契约，file 模块继续沿用可避免额外映射与跨模块 API 返工。

### 4.2 素材包状态机

```
            上传请求落 zip + 建包
                    │
                    ▼
   ┌──────────┐  发解压消息   ┌────────────┐
   │ UPLOADED │ ───────────► │ EXTRACTING │
   └──────────┘   (消息确认)   └─────┬──────┘
        ▲                            │
        │ 消息投递失败                  ├─► READY    全部有效图入库成功
        │ （PublishGapRescan 补发）     ├─► PARTIAL  有成功也有逐图失败
        └────────────────────────────┴─► FAILED   zip 损坏 / 无有效图 / 不可恢复
```

- `UPLOADED`：zip 已落 MinIO、包记录已建，等待 / 重试投递解压消息。
- `EXTRACTING`：消费者正在解压（`process-then-ack`，未 ack）。
- `READY` / `PARTIAL` / `FAILED`：解压终态。`PARTIAL` 表示部分图准入/上传失败但至少一张成功；终态判定见 [§十](#十恢复幂等与失败隔离)。
- 状态迁移由 `AssetPackageService` 收口，乐观更新（带 `updated_at`），避免并发重投下的脏写。

---

## 五、上传与解压管线（MQ 驱动）

分两阶段：**同步上传（请求内）** 与 **异步解压（MQ 作业）**。决策依据见与设计讨论一致的结论——解压是 I/O 密集长耗时作业，MQ 提供跨节点削峰、背压、durable 恢复与毒包 DLQ，且 `infra/mq.md §二` 明确预留「批量导入」复用。

### 5.1 同步上传（HTTP 请求内）

```
POST /api/files/packages  (multipart: zip[, doc])
  → 生成 packageId
  → 流式存 zip：ObjectStorage.put(StorageKeys.packageSource(id), in, size, "application/zip")
  → [可选] 流式存 doc：put(StorageKeys.packageDoc(id, name), ...)
  → 本地事务提交 asset_package(status=UPLOADED)
  → ExtractionPublisher.publish(ExtractionMessage{packageId})，读 PublishResult：
        confirmed → 置 EXTRACTING（或留 UPLOADED 由消费者置）→ 返回 packageId
        failed    → 保留 UPLOADED，由 PublishGapRescan 补发（不阻断上传）
  → 返回 { packageId, status }
```

- **流式不入堆**：zip / doc 以 `InputStream` 直传 MinIO，`size` 已知（multipart 提供），不缓冲整个文件到堆。
- **消息体最小化**：`ExtractionMessage` 只含 `packageId`；zip 已在 MinIO，任意 worker 节点据 `packageId` 取回，呼应 `infra/mq.md §九`「消息体最小化」。
- **投递缺口闭合**：对齐 `infra/mq.md §5.2`——先提交 DB，再发布并读 `PublishResult`；失败不抛断请求，`PublishGapRescan`（`@Scheduled`）扫超龄 `UPLOADED` 补发。**这是唯一的定时补偿，且只覆盖投递缺口，不用于解压崩溃恢复。**

### 5.2 异步解压（ack 模式：process-then-ack）

与 `module/task` 的 `ack-then-process` **不同**：解压是「一个包一个独立、天然幂等的工作单元」，直接在消费回调内跑完整包、跑完才 ack，崩溃由 MQ 重投续跑，最省心且无需额外恢复设施。

```
ExtractionConsumer.onMessage(ExtractionMessage{packageId})   // 未 ack
  → AssetPackageService → status=EXTRACTING
  → 从 MinIO 取回 zip 到受限临时文件（大小受上传上限约束）
  → ZipExtractor：
        ① 读中央目录：拿到 entry 总数 + 声明大小 → 预 bomb 校验 → 回填 image_count
        ② 逐 entry（可选有界并发）：
             - 路径规范化 + slip 校验（§六）
             - ImageAdmission 准入（扩展名/magic bytes/大小）；非图片 entry 按规则忽略
             - 流式上传 MinIO：put(StorageKeys.packageImage(id, relPath), entryStream, size, ct)
             - FileNameParser 解析 → asset_image（INSERT IGNORE / ON DUPLICATE，幂等）
             - extracted_count++ → ProgressNotifier 推进度（§九）
        ③ 写终态（READY/PARTIAL/FAILED）+ error_summary
  → 删除临时文件
  → ack（整包处理完成）
  消费回调内异常 → 交 ExtractionErrorHandler 判定 Retry / DeadLetter（§十一）
```

- **为何取回临时文件而非纯流式**：读 **zip 中央目录**可一次性拿到 entry 总数与各 entry 声明大小——既给出准确的进度分母（`image_count`），又能在解压前做**声明大小级别的 bomb 预检**（更安全）。临时文件大小受上传上限约束、用完即删。这细化了 `storage.md`「不落盘」原则——其本意是「不把整个对象缓冲进堆」，而非禁止受限临时工作文件（见 [§十五](#十五对-designmd-的细化)）。跨节点消费本就要求 worker 从 MinIO 取回 zip，临时文件是其自然落点。
- **包内并发**：基于临时文件的随机访问可对多个 entry 并发上传 MinIO，但必须是有界并发而非无限 fan-out。`consumer-concurrency` 控制同时解压的包数，默认 1～2；`intra-package-parallelism` 控制单包内 entry 准入/上传/入库并发，默认 4。中央目录预扫保持单线程；入库阶段按批次提交，并通过 `(package_id, original_path)` 唯一约束消化重投重复。这个默认值偏保守，目的是保护 MinIO、MySQL 与 zip 随机读取吞吐，避免一个大包抢占整机资源。
- **consumer_timeout**：process-then-ack 下整包解压须在 RabbitMQ `consumer_timeout`（默认 30min）内 ack；为 `pixflow.file.q` 单独调大该超时或约束单包规模（本期数据集规模足够），作为部署配置记录。

---

## 六、Zip 安全（slip / bomb）

解压是不可信输入入口，前置三道硬防护，超阈值即拒、置 `FAILED` 并明确报错：

| 威胁 | 防护 |
|---|---|
| **Zip Slip（路径穿越）** | 每个 entry 名先规范化，拒绝绝对路径、`..`、盘符注入；再交 `StorageKeys.packageImage` 二次规范化（storage 侧亦有穿越防护，双保险） |
| **Zip Bomb（解压炸弹）** | 中央目录预检声明总解压大小；解压期累计实际解压字节、entry 数、单 entry 上限、压缩比上限，任一超 `pixflow.file.zip.*` 阈值即中止 |
| **资源滥用** | 单图大小上限、总图数上限；超限计入跳过清单或直接 `FAILED` |

阈值全部可配（见 [§十二](#十二配置)）。bomb / slip 命中归一化为 `VALIDATION` / `BUSINESS_RULE` 类错误码，消费侧据 `recovery=TERMINATE` 判为 `DeadLetter`（毒包不重试，见 [§十一](#十一错误与降级)）。

---

## 七、文件名驱动解析（SKU / 分组）

`FileNameParser` 落地 `design.md §9.5`：去扩展名后按 `_` 分段。

| 形态 | 示例 | group_key | sku_id | view_id | 说明 |
|---|---|---|---|---|---|
| 三段 `组_商品_图` | `A_B_C` | `A` | `B` | `C` | 多商品分组场景，一组可挂多个 sku |
| 两段 `商品_图` | `A_B` | `null` | `A` | `B` | 单商品无分组，首段即 sku |
| 其他 / 无下划线 | `ABC` | `null` | `SkuExtractor` 提取 | `null` | 兜底逻辑，无法从文件名解析 |

设计要点：

- 同 `(package_id, group_key)` 且 `group_key` 非空的图片构成一组；`view_id` 供组内排序（对齐 `compose_group` 的 `order`）。
- **`SkuExtractor` 接口化 + 默认实现**（同 memory 的 `InsightKeywordSearch` 可替换思路）：兜底规则（取主体 / 正则 / 归一化）可演化而不动调用方。
- 标识归一化（trim、大小写、非法字符过滤）与 `StorageKeys` 的 key 规范化保持一致，避免「文件名里能用、key 里被改写」造成的不一致。
- `FileNameParser` 为纯函数，单测覆盖三类分支与边界（多于三段截断为三段、空段、纯数字、中文名、重复下划线）。

---

## 八、文案文档解析

- `CopyDocParser` 接口 + `CsvCopyDocParser`（commons-csv）/ `ExcelCopyDocParser`（Apache POI），按 doc 扩展名分派。
- **列映射** `CopyDocColumnMapping`：`sku_id / product_name / keywords / description` 列名→字段，可配以容忍中文表头（如「商品编号 / 标题 / 关键词 / 描述」）。
- 按 `sku_id` 关联，一文档多行 → 多条 `asset_copy`；缺 `sku_id` 行跳过 + 告警，重复 `sku_id` 策略可配（默认覆盖）。
- **与图片解耦**：文档先落 MinIO（`packageDoc`），解析作为解压管线的独立步骤；文案解析失败**不阻断图片入库**，仅影响包终态判定（可记 `PARTIAL` + `error_summary`）。
- 文案分支在处理期由 `generate_copy` 以 `asset_copy` 为上下文（`design.md §9.1`），与本模块的解析职责衔接。

---

## 九、进度推送（复用 WebSocket）

需求：解压进度实时推送，**复用既有 WebSocket/STOMP 通道**，而非新建推送链路。

约束：`harness/state.md` 明确「state 不推送」，且 WebSocket 帧推送当前归在 `module/task` / `module/conversation`（Wave 4）。file 是 Wave 2，**不能反向依赖** task/conversation。同时 `SimpMessagingTemplate` 是 app 级单例 Bean——多个模块推帧本就共享同一 broker 与模板。

设计：引入跨切 **`ProgressNotifier` SPI**，与 `common` 既有的 `ErrorRecorder` SPI 同构（接口在 `common`，实现在上层注入）：

```java
// common（传输无关，纯接口；不引入 spring-messaging 依赖）
public interface ProgressNotifier {
    void publish(String channel, Object event);   // channel 为逻辑频道键
}
```

- **接口在 `common`**：跨切能力，人人可依赖，且 `common` 不引入 WebSocket 传输细节，保持地基纯净。
- **STOMP 实现在 app 级**：`pixflow-app`（WebSocket/STOMP broker 配置所在）提供 `StompProgressNotifier`，内部用 `SimpMessagingTemplate.convertAndSend("/topic/" + channel, event)`，作为 Bean 注入——与 `ErrorRecorder` 实现落在 `harness/eval` 同一依赖倒置手法。
- **file 只依赖 `common.ProgressNotifier`**：解压时 `notifier.publish("packages/" + packageId + "/progress", new ExtractionProgress(packageId, extracted, total, status))`。STOMP 目的地约定 `/topic/packages/{packageId}/progress`。
- **轮询兜底 + 单一事实源**：进度真值是 `asset_package`（`status` + `extracted_count` + `image_count`）。WS 推送是 **best-effort 实时层**；`GET /api/files/packages/{id}` 返回同一行供前端轮询兜底（`design.md §4`「轮询兜底」）。二者一致，因为都源自同一 DB 行。
- **traceId**：WS 帧内携带 `traceId`（`common.md §9.2` 约定的长连接透传边界）。

> 这是对架构的跨切细化：把「WebSocket 推送传输」从 task/conversation **专属**提升为 **app 级共享 + `ProgressNotifier` 解耦**，task/conversation 将来同样经此 SPI 推送，state 仍只提供数据源、不持连接。该口径已同步回 `common.md` 与 `state.md`（见 [§十五](#十五对-designmd-的细化)）。

---

## 十、恢复、幂等与失败隔离

### 10.1 崩溃恢复 = MQ 重投 + 幂等续跑

- **解压崩溃**：`process-then-ack` 下消息未 ack，worker 崩溃后 RabbitMQ 自动重投到另一 worker。**无需 `@Scheduled` 恢复扫描、无需分布式锁**——一个包=一条消息、`prefetch` 低、同一包同一时刻只被一个消费者处理。
- **幂等续跑**：重投后 `asset_image` 的 `(package_id, original_path)` 唯一约束让已入库的图被跳过（`INSERT IGNORE` / `ON DUPLICATE KEY`），已上传 MinIO 的对象按同 key 覆盖（幂等）。`extracted_count` 重算时按 `asset_image` 实际行数校准，避免重复计数。
- **投递缺口**：仅「DB 已建 `UPLOADED` 但消息没进 broker」这一缝由 `PublishGapRescan`（`@Scheduled` 扫超龄 `UPLOADED`）补发，对齐 `infra/mq.md §5.2`。

### 10.2 失败隔离与终态

- **逐图失败隔离**：单张图准入失败 / MinIO 上传失败 / 解析异常 → 写入 `asset_ingest_error`，并汇总进 `error_summary`，**不中断整包**，其余图继续。失败明细的 `stage` 建议取 `ZIP_SCAN`、`IMAGE_ADMISSION`、`STORAGE_UPLOAD`、`NAMING_PARSE`、`COPYDOC_PARSE` 等稳定枚举值，便于包详情页筛选与测试断言。
- **终态判定**：至少一张成功 → `READY`（无失败）或 `PARTIAL`（有失败，`error_summary` 记跳过数与原因）；无任何有效图或 zip 不可解 → `FAILED`。
- **毒包**：反复让解析器失败的损坏 zip，经 `ExtractionErrorHandler` 判 `DeadLetter` 进 `pixflow.file.dlq`，DLQ 深度告警人工介入（`infra/mq.md §十`），不无限重投。

### 10.3 素材包删除语义

删除采用「软删优先、物理删除受限」：

- 未被后续任务、对话或其他业务记录引用的素材包，可以执行物理删除：删除 `asset_package` / `asset_image` / `asset_copy` / `asset_ingest_error` 行，并调用 `ObjectStorage.deleteByPrefix(BucketType.PACKAGES, "{packageId}/")` 清理 MinIO 包前缀。
- 已被引用的素材包，不物理删除，只写 `asset_package.deleted_at`。列表接口默认过滤 `deleted_at is not null`，历史任务、历史对话和回放仍可通过显式 ID 读取到必要元数据，避免任务输入事实源丢失。
- Wave 2 中如果 `task` / `conversation` 尚未落表，删除服务仍应预留 `PackageReferenceChecker` 接口或等价内部检查点；当前实现可提供默认「无引用」实现，后续由 `module/task` / `module/conversation` 接入真实引用检查，避免删除语义写死。

---

## 十一、错误与降级

file 自有错误码 `FileErrorCode implements common.ErrorCode`（`common.md §11` 码自治约定），HTTP 状态由 `category` 推导：

| code | category | recovery | 场景 |
|---|---|---|---|
| `PACKAGE_NOT_FOUND` | NOT_FOUND | TERMINATE | 查询/删除不存在的包 |
| `INVALID_ZIP` | VALIDATION | TERMINATE | zip 无法解析 / 中央目录损坏 |
| `ZIP_BOMB_DETECTED` | BUSINESS_RULE | TERMINATE | 超解压大小 / 压缩比 / entry 数阈值 |
| `ZIP_PATH_TRAVERSAL` | BUSINESS_RULE | TERMINATE | entry 名含 `..` / 绝对路径 |
| `NO_VALID_IMAGE` | BUSINESS_RULE | TERMINATE | 包内无任何通过准入的图片 |
| `UNSUPPORTED_IMAGE_FORMAT` | VALIDATION | SKIP | 单图扩展名/magic bytes 不在白名单（逐图跳过） |
| `COPY_DOC_PARSE_FAILED` | VALIDATION | SKIP | 文案文档解析失败（不阻断图片） |
| `UPLOAD_TOO_LARGE` | VALIDATION | TERMINATE | 超上传上限（亦由 `MaxUploadSizeExceeded` 归一化） |
| `PACKAGE_ALREADY_REFERENCED` | BUSINESS_RULE | TERMINATE | 被任务/对话引用的素材包不允许物理删除，只能软删 |

`ExtractionErrorHandler`（实现 `infra/mq.ConsumerErrorHandler`）按归一化模型的 `recovery` 给 `RetryDecision`（`common.md §6.4` 映射）：

| 场景 | recovery | RetryDecision |
|---|---|---|
| MinIO 抖动 / `STORAGE` / `DEPENDENCY` | RETRY | `Retry(backoff)` → 超 `max-retries` 转 `DeadLetter` |
| 损坏 zip / bomb / slip / 无有效图 | TERMINATE | `DeadLetter`（毒包，进终态 DLQ） |
| 逐图 `SKIP` 类 | （已在包内隔离落库） | 不冒泡到消费判定，整包仍可 ack 为 `PARTIAL` |

降级：MinIO 暂不可用 → 重试耗尽进 DLQ，包留 `EXTRACTING`/置 `FAILED` 并告警；文案解析失败 → 图片仍入库、包记 `PARTIAL`。所有对外文案经 `Sanitizer` 脱敏。

---

## 十二、配置

`@ConfigurationProperties(prefix = "pixflow.file")`：

```yaml
pixflow:
  file:
    upload:
      max-zip-size: 2GiB              # 单 zip 上限（亦配 Spring multipart）
      max-doc-size: 50MiB
    extract:
      consumer-concurrency: 2         # 同时解压的包数（process-then-ack，少量即可）
      prefetch: 1                     # 公平分发，避免单 worker 囤积
      intra-package-parallelism: 4    # 单包内并发上传 entry 的线程数
      consumer-timeout: 30m           # pixflow.file.q 的 ack 超时（部署侧同步 RabbitMQ）
      temp-dir: ${java.io.tmpdir}/pixflow-extract
    zip:
      max-entries: 50000              # entry 数上限
      max-total-bytes: 5GiB           # 累计解压字节上限
      max-entry-bytes: 200MiB         # 单 entry 上限
      max-compression-ratio: 100      # 压缩比上限（bomb 防护）
    image:
      allowed-extensions: [jpg, jpeg, png, webp, bmp, gif, tiff]
      magic-bytes-check: true         # 嗅探文件头，防扩展名伪装
    copydoc:
      column-mapping:                 # 列名→字段（容忍中文表头）
        sku-id: [sku_id, 商品编号, SKU]
        product-name: [product_name, 标题, 商品名]
        keywords: [keywords, 关键词]
        description: [description, 描述, 详情]
      on-duplicate-sku: OVERWRITE     # OVERWRITE | SKIP
    publish-gap-rescan:
      interval: 1m                    # 扫超龄 UPLOADED 补发解压消息
      stale-after: 30s
```

- 队列 / 交换机 / 路由键名由 `ExtractionTopology` 用 `QueueTopologyBuilder("pixflow.file")` 声明，不在配置硬编码业务队列名（`infra/mq.md §四/§十`）。
- `max-zip-size` 需与 Spring `spring.servlet.multipart.max-*` 对齐。

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `infra/storage` | 用 `StorageKeys.package*` 写 zip/原图/文档；解压期 `getStream` 取回 zip、`put` 流式上传图；仅在素材包未被引用且执行物理删除时用 `deleteByPrefix(PACKAGES, "{packageId}/")` |
| `infra/mq` | `ExtractionTopology` 声明 `pixflow.file` 队列组；`ExtractionPublisher` 经 `MessagePublisher` 发布并据 `PublishResult` 补偿；`ExtractionConsumer` process-then-ack；`ExtractionErrorHandler` 实现 `ConsumerErrorHandler` |
| `common` | 错误归一化 / `Sanitizer` / `ApiResponse`；实现侧消费 `ProgressNotifier` SPI（接口在 common，STOMP 实现在 app 级） |
| `module/dag` | 读 `asset_image` 的 `sku_id`/`group_key`/`view_id` 做 `BranchExpander` 分组展开与 `compose_group` 成员归组 |
| `module/conversation` | `message.attached_package_id` 关联素材包；只读查询包详情；后续为 `PackageReferenceChecker` 提供引用检查，防止已被对话引用的包被物理删除 |
| `module/task` | 任务按 `package_id` 加载图片；下载/预览经 `storage.presignGet`（出口侧，后续波次）；后续为 `PackageReferenceChecker` 提供引用检查，防止已被任务引用的包被物理删除 |
| `agent` | `search` / `read` / 处理建议依赖已绑定的 `sku_id`；file 保证绑定在解压期完成 |
| `pixflow-app` | 提供 STOMP broker 配置 + `StompProgressNotifier` Bean；装配 file 的 controller / consumer |

**反向约束**：不依赖 `infra/image`、`infra/cache`、`harness/*`、`module/task`、`module/rubrics`。

---

## 十四、测试策略

- **`FileNameParser` 单测**：三段/两段/兜底分支 + 边界（>3 段、空段、纯数字、中文名、重复下划线、无扩展名）。
- **`SkuExtractor` 单测**：兜底提取规则；接口可替换性（fake 实现不影响 parser 契约）。
- **`ImageAdmission` 单测**：magic bytes 命中/伪装扩展名拦截、大小上限、白名单内外。
- **`ZipExtractor` 安全单测**：slip（`../`、绝对路径、盘符）拦截；bomb（超大小/比率/entry 数）中止；中央目录预扫得到正确总数。
- **`AssetIngestError` 明细测试**：逐图跳过、命名解析失败、文档行缺 `sku_id` 时写入稳定 `stage/code/message`；`error_summary` 只保留脱敏摘要且不超过 1000 字。
- **删除语义测试**：未引用包物理删除会清理 DB 与 MinIO 前缀；模拟已引用包时只写 `deleted_at`，列表默认不可见，按 ID 查询仍可用于历史回放。
- **解压管线集成测试（Testcontainers：MinIO + RabbitMQ）**：上传→发消息→消费→流式上传→批量入库→终态全链路；`READY`/`PARTIAL`/`FAILED` 三态。
- **幂等/重投测试**：消费中途模拟崩溃 → 重投后跳过已入库图、`extracted_count` 不重复计、最终一致。
- **`ConsumerErrorHandler` 测试**：MinIO 抖动→`Retry`；损坏 zip→`DeadLetter`；逐图 SKIP 不影响整包 ack 为 `PARTIAL`。
- **投递缺口测试**：`publish` 返回 `failed` 时包留 `UPLOADED`，`PublishGapRescan` 补发。
- **`CopyDocParser` 测试**：样例 CSV / XLSX（含中文表头映射）、缺/重复 sku_id 策略、解析失败不阻断图片。
- **`ProgressNotifier` 测试**：用 fake notifier 断言进度按 `extracted_count` 推进、channel/事件正确；轮询端点与 WS 事件同源一致。
- **错误码目录一致性**：`FileErrorCode` 并入 `common` 启动期聚合测试（code 唯一 + i18n 齐全 + category 非空）。

---

## 十五、对 design.md 的细化

本模块对 `design.md` 及相邻设计文档做如下**细化（非冲突）**，需同步记录：

1. **`asset_package` 主键、状态枚举、进度列与软删除列**：`design.md §13.1` 的 `id` 在 file 模块落为 `BIGINT`，与现有 `StorageKeys.package*(long packageId)` 契约一致；`status` 落为 `PackageStatus{UPLOADED/EXTRACTING/READY/PARTIAL/FAILED}`，新增 `extracted_count`、`error_summary`、`deleted_at`，支撑进度推送、失败隔离与被引用后的软删除。
2. **解压异步化走 RabbitMQ**：新增依赖边 `mq → file`（mq 在 Wave 1、file 在 Wave 2，无环），复用 `infra/mq.md §二` 预留的「批量导入」场景；file 自声明 `pixflow.file` 队列组。
3. **解压用 process-then-ack（区别于 task 的 ack-then-process）**：解压是天然幂等的整包工作单元，恢复靠 MQ 原生重投 + `(package_id, original_path)` 幂等续跑，**不引入 `@Scheduled` 解压恢复扫描与分布式锁**；仅保留覆盖「投递缺口」的 `UPLOADED` 重扫（对齐 `mq.md §5.2`）。
4. **跨切 `ProgressNotifier` SPI + WebSocket 传输上移到 app 级共享**：把 WS 推送从 task/conversation **专属**细化为 **app 级共享传输 + `common.ProgressNotifier` 解耦**，使 Wave 2 的 file 可复用同一 WS 通道而不反向依赖 Wave 4 模块。`common.md` 已新增 `ProgressNotifier` SPI（同 `ErrorRecorder` 模式），`state.md` 已把「推送在 task/conversation」细化为「推送传输 app 级共享、各模块经 `ProgressNotifier` 发布，state 仍只供数据源」。
5. **`asset_image` 唯一约束 + `asset_ingest_error` 失败明细**：`(package_id, original_path)` 唯一，保证重投/重跑幂等；新增 `asset_ingest_error` 存逐图/文档解析失败明细，`error_summary` 只保留脱敏摘要。
6. **临时工作文件的边界澄清**：解压期允许「将 zip 取回受限临时文件」以读取中央目录（准确进度分母 + 声明大小级 bomb 预检），细化 `storage.md`「不落盘」=「不把整个对象缓冲进堆」，非禁止受限临时文件。
7. **职责切分**：file 本期只做入口侧；`design.md §12` 所列「结果管理 / 评分展示」属出口侧，待 `task`/`rubrics` 就绪后实现（见 [§二](#二职责边界入口侧-vs-出口侧)）。

> 以上为对既有设计的补充落地，不改变 `design.md §9.5` 的文件名分组规则、§13.4 的 MinIO 布局与 §15 的「MySQL 事实源」原则。

---

## 十六、暂不考虑

- **出口侧（结果图聚合展示 / 评分展示 / 打包下载）**：依赖 `task`/`rubrics` 写入，后续波次实现。
- **视觉识别分组**：分组完全由文件名编号决定，不做图像内容识别（`design.md §9.5`）。
- **增量/断点续传上传**：本期整文件上传；超大 zip 的分片续传后续再评估。
- **前端直传（presignPut）接素材包**：`storage` 已预留 `presignPut`，业务编排后续再做。
- **组内参数联合推导**（如取组内最大尺寸回填各图）：`design.md §9.5` 本期不做。
- **多语言文案文档 analyzer / 复杂表结构解析**：本期按约定列映射解析 CSV/XLSX。
- **素材包版本管理 / 去重**：同一素材重复上传按新包处理，不做内容级去重。

## Revision Notes

2026-06-28 / Codex: 根据实现前决策讨论，固化 file 模块的生产级落地口径：`asset_package.id` 统一为 `BIGINT` 以匹配 `StorageKeys.package*(long)`；删除采用软删优先、物理删除受限；新增 `asset_ingest_error` 保存逐图/文档解析失败明细；包内并发采用 `consumer-concurrency` 与 `intra-package-parallelism` 两级有界并发且默认保守；`ProgressNotifier` 作为 common SPI，app 级实现 WebSocket/STOMP 传输，state 只提供数据源不持连接。

2026-06-29 / Codex: 同步 `harness/tools.md` 的新工具口径，将 agent 侧旧 `query_commerce_data` 依赖表述改为 `search` / `read`。
