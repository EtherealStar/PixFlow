# 完整实现 module/commerce 电商数据接入模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 commerce 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。

## Purpose / Big Picture

完成本计划后，PixFlow 将具备生产级电商数据接入能力：运营人员可以导入 CSV 或 Excel 电商指标数据，系统可以按一批 SKU 查询曝光量、点击率、加购率、购买率，并返回类目均值、偏离百分比、趋势序列、缺失 SKU 和数据新鲜度。Agent 后续通过 `search` 发现候选 SKU，通过 `read(include=["data"])` 消费这些结构化事实，生成“点击率低于类目均值多少”这类有数据支撑的处理建议，但 commerce 模块本身不做建议、不做决策。

本计划不是 MVP 方案。实现必须覆盖 MySQL 事实源、幂等导入、行级容错、类目基准、时间窗口、趋势、可替换外部平台适配器、外部 API 异步导入、实时查询按需刷新、紧超时降级、错误码与测试。真实电商平台暂未确定，因此本计划只实现可替换平台端口和测试用 fake/provider 适配，不把代码写死到某一家平台。

可以通过两个行为观察模块完成：第一，导入一份包含多个类目和多个 SKU 的 CSV/XLSX 后，查询接口能返回每个 SKU 的窗口聚合指标、类目基准和 `missingSkus`。第二，触发外部 API 导入任务时，请求立即返回 import job id，RabbitMQ worker 后台调用可替换的 `PlatformApiClient` 拉取数据、write-through 落 `commerce_data`，再查询时聚合结果反映新数据。

## Progress

- [x] (2026-06-28 23:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、当前 active exec plans、`docs/design-docs/design.md` 和 `docs/design-docs/module/commerce.md`，确认 commerce 是 Wave 2 未完成模块，必须按生产级完整模块实现。
- [x] (2026-06-28 23:35+08:00) 阅读 `docs/design-docs/base/common.md`、`docs/design-docs/infra/thirdparty.md` 和 `docs/design-docs/module/file.md`，确认 commerce 的外部电商平台 API 归 commerce 自己适配，不进入 infra/thirdparty；错误、脱敏与进度通知沿 common 模式；异步作业可复用 infra/mq。
- [x] (2026-06-28 23:40+08:00) 与用户确认关键方向：先讨论再写计划；真实平台不写死，做可替换适配；外部 API 导入走 MQ 异步；查询默认采用 `sourceScope=ALL` 并保留 preferred source 扩展；类目冲突默认 WARN。
- [x] (2026-06-28 23:45+08:00) 创建本执行计划文档，固化完整架构思路、机制、文件改动顺序、验证命令和设计文档快速定位关键词。
- [x] (2026-06-28 19:18+08:00) 创建 `pixflow-module-commerce` Maven 模块并加入根 `pom.xml` 的 `<modules>` 与 dependencyManagement，同时让 `pixflow-app` 依赖 commerce。
- [x] (2026-06-28 19:22+08:00) 实现 commerce 核心 DTO、配置、错误码、AutoConfiguration 和 `CommerceService` 门面。
- [x] (2026-06-28 19:24+08:00) 实现 `commerce_data` 与 `commerce_import_job` 的 MySQL 实体、Mapper、schema 资源和聚合 SQL；聚合 SQL 覆盖 SKU 窗口聚合、类目基准和趋势读取。
- [x] (2026-06-28 19:25+08:00) 实现 CSV/XLSX 本地导入管线、行级校验、中文/英文字段映射、幂等 upsert、类目冲突 WARN/FAIL 和 `ImportReport`。
- [x] (2026-06-28 19:26+08:00) 实现外部 API 异步导入：MQ 拓扑、导入任务发布、worker 拉取、write-through 落库、任务状态查询和失败状态记录。
- [x] (2026-06-28 19:27+08:00) 实现批量查询、类目基准、趋势、`missingSkus`、per-SKU `FreshnessInfo`、`sourceScope` 和样本不足降级。
- [x] (2026-06-28 19:27+08:00) 实现实时查询按需刷新：`FreshnessPolicy` 判定、`ExternalPlatformSource` 拉取、紧超时、失败降级库存数据并标记 stale。
- [x] (2026-06-28 19:28+08:00) 实现 HTTP controller、app 装配和四个验证入口：本地导入、API 导入任务创建、任务查询、commerce 查询。
- [x] (2026-06-28 19:30+08:00) 运行 `mvn -pl pixflow-module-commerce -am test` 和 `mvn -pl pixflow-app -am test`，均通过；app reactor 中若干既有 Testcontainers 测试运行通过，storage 的 MinIO 集成测试按既有条件跳过 2 个。

## Surprises & Discoveries

- Observation: 仓库当前没有 `pixflow-module-commerce` Maven 模块，根 `pom.xml` 的 `<modules>` 和 dependencyManagement 中也没有 commerce 条目。
  Evidence: 仓库根目录已有 `pixflow-module-file`、`pixflow-infra-mq`、`pixflow-infra-thirdparty` 等模块，但没有 `pixflow-module-commerce`。

- Observation: `docs/design-docs/module/commerce.md` 原始依赖说明没有把 RabbitMQ 作为 commerce 依赖，但用户已明确要求“API 导入用 MQ 异步”。这不会破坏依赖 DAG，因为 `infra/mq` 是 Wave 1，commerce 是 Wave 2；只需要在实现或后续文档同步时把 `mq -> commerce` 依赖边说明清楚。
  Evidence: `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中 `infra/mq` 已在 Wave 1 完成，`module/commerce` 处于 Wave 2 未完成项。

- Observation: `infra/thirdparty` 文档已经明确“电商平台店铺数据 API 归 `module/commerce`”，因此 commerce 外部平台适配器不能偷用 `infra/thirdparty` 的 provider 路由，也不能把平台 SDK 类型泄漏给上层。
  Evidence: 在 `docs/design-docs/infra/thirdparty.md` 中搜索“电商平台店铺数据 API”可以看到其归属为 `module/commerce`。

- Observation: 当前实现为了快速验证模块行为，新增的是不依赖 Docker 的 commerce 单元测试；MySQL 聚合 SQL 已编译但尚未新增 commerce 自己的 MySQL Testcontainers 集成测试类。
  Evidence: `mvn -pl pixflow-module-commerce -am test` 中 commerce 新增 4 个测试通过，覆盖 CSV 中文表头、BenchmarkCalculator 和实时刷新失败降级；未出现 commerce MySQL container 测试。

- Observation: `mvn -pl pixflow-app -am test` 会运行 reactor 中其它模块的 Docker 集成测试，当前环境 Docker 可用；`pixflow-infra-storage` 的 `MinioObjectStorageIntegrationTest` 按既有条件跳过 2 个。
  Evidence: Maven 输出显示 app reactor BUILD SUCCESS，storage 模块 `Tests run: 13, Failures: 0, Errors: 0, Skipped: 2`。

## Decision Log

- Decision: 不写死任何真实电商平台，先实现可替换的 `CommerceDataSource` 和 `PlatformApiClient` 端口，测试中使用 fake/provider 与 MockWebServer。
  Rationale: 当前没有确定真实平台、鉴权方式、字段口径和限流规则。把供应商无关 record 作为 commerce 内部契约，可以在未来只新增 provider 实现和配置，不改查询、导入和 Agent 工具契约。
  Date/Author: 2026-06-28 / Codex

- Decision: 外部 API 批量导入必须走 RabbitMQ 异步作业，而不是在 HTTP 请求内同步拉完整窗口。
  Rationale: API 导入可能涉及大量 SKU、分页、限流和重试。同步请求会拖慢前端和 Agent 回合，也不利于失败恢复。MQ 作业可以立即返回 import job id，后台按任务粒度处理，失败进 DLQ 或记录任务失败，查询层仍只读 MySQL 事实源。
  Date/Author: 2026-06-28 / Codex

- Decision: 本地 CSV/XLSX 文件导入第一版保持同步分批处理并返回 `ImportReport`；外部 API 导入走 MQ 异步。如果后续本地文件规模证明需要异步，再复用同一 `CommerceImportJob` 状态模型扩展。
  Rationale: 本地数据文件已经在请求中携带，解析和 upsert 可以分批完成并即时返回行级报告。外部 API 导入受网络、限流和分页影响更大，必须异步。该切分让第一版保持简单，同时不堵住未来大文件异步化。
  Date/Author: 2026-06-28 / Codex

- Decision: 查询默认 `sourceScope=ALL`，允许本地导入数据与外部 API 数据在同一窗口聚合；同时在 DTO 和配置中保留 `preferredSource` / source 过滤扩展。
  Rationale: `commerce.md` 设计允许实时拉取 write-through 后与库存数据一起参与类目基准。生产上仍需要保留口径控制能力，避免未来平台数据和本地数据重复或口径不一致时无法切换。
  Date/Author: 2026-06-28 / Codex

- Decision: 同一 SKU 类目冲突默认 `WARN`，取最后一次导入或拉取值；配置 `category-conflict=FAIL` 时整行或整批按实现阶段的校验策略失败。
  Rationale: 类目是随数据进入的属性，早期没有独立 `sku_category` 维度表。WARN 模式能最大化导入成功率，同时通过 `ImportReport.warnings` 保持数据诚实。严格客户可改配置为 FAIL。
  Date/Author: 2026-06-28 / Codex

- Decision: 查询结果必须包含 `stale`、`freshness` 或等价字段，且至少能表达到 per-SKU 维度。
  Rationale: 实时刷新可能部分成功、部分失败。如果只在总结果标记 stale，Agent 可能误把过期数据当成新鲜事实。per-SKU 新鲜度能约束 Agent 不编造支撑。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

2026-06-28 / Codex: 已完成 commerce 模块的第一版端到端实现。新增 `pixflow-module-commerce`，提供 CSV/XLSX 本地导入、行级容错、自然键 upsert、`commerce_data` 与 `commerce_import_job` 存储模型、外部 API 异步导入 MQ worker、可替换 `PlatformApiClient` 端口、实时查询按需刷新与失败降级、类目基准、趋势、`missingSkus`、per-SKU 新鲜度和 HTTP 验证入口。`mvn -pl pixflow-module-commerce -am test` 与 `mvn -pl pixflow-app -am test` 均通过。剩余增强是为 commerce 聚合 SQL 增加 MySQL Testcontainers 集成测试，并视后续真实平台接入同步细化 provider 配置文档。

## Context and Orientation

仓库是 Maven 多模块 Spring Boot 项目，根目录是 `D:\study\PixFlow`。当前已有 `pixflow-common`、`pixflow-infra-mq`、`pixflow-module-file`、`pixflow-infra-thirdparty`、`pixflow-app` 等模块，但还没有 `pixflow-module-commerce`。commerce 是 Wave 2 基础业务数据模块，先于 Agent 决策层实现。它的工作是保存和查询电商指标事实，不负责决定图片要怎么处理。

“事实源”在本计划中指 MySQL 表 `commerce_data`。不管数据来自本地 CSV/XLSX，还是来自外部店铺 API，都必须先写入 MySQL，再由查询聚合读取。这样类目均值、趋势和偏离百分比都可重算，也不会因为某次外部 API 失败而让查询层进入半内存状态。

“write-through” 指实时或异步拉到的数据先落库，再给查询使用。它和“实时穿透”不同：实时穿透是查询时直接把外部 API 返回值拼进结果而不落库，本计划禁止这种做法。

“类目基准” 指同一个类目下的平均曝光量、点击率、加购率、购买率。它是描述性统计，不是处理建议。commerce 可以说“SKU123 的点击率比类目均值低 40%”，但不能说“应该换白底图”。这个判断属于后续 Agent。

“MQ” 是消息队列，本仓库用 RabbitMQ，由 `pixflow-infra-mq` 封装。外部 API 导入用 MQ 的意思是：HTTP 请求只创建导入任务并发一条消息，worker 后台消费消息、拉取平台数据、落库、更新任务状态。用户或前端通过任务查询接口看进度和结果。

“可替换平台适配器” 指 commerce 内部定义 `PlatformApiClient` 接口和自己的请求/响应 record。真实平台的 SDK、HTTP 字段、鉴权方式只存在 provider 实现里，不能泄漏到 `CommerceQueryService`、`CommerceImportService` 或 Agent 工具契约中。

实现时需要重点参考以下设计文档。为了快速定位，每个文档后面列出建议搜索关键词。

在 `docs/design-docs/module/commerce.md` 中搜索：

    MySQL 为唯一事实源
    数据诚实
    commerce_data
    幂等自然键
    时间维度语义
    导入管线
    类目基准
    聚合规则
    FreshnessPolicy
    ExternalPlatformSource
    search / read
    missingSkus
    stale
    category-conflict

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：

    Wave 2
    module/commerce
    基础数据
    search / read
    infra/mq

在 `docs/design-docs/base/common.md` 中搜索：

    ErrorCategory
    RecoveryHint
    PixFlowException
    Sanitizer
    错误码目录约定
    DEPENDENCY
    RATE_LIMIT
    NETWORK
    PROVIDER

在 `docs/design-docs/infra/mq.md` 中搜索：

    MessagePublisher
    PublishResult
    QueueTopologyBuilder
    ManagedMessageListener
    ConsumerErrorHandler
    DLQ
    消息体最小化
    Publisher Confirms

在 `docs/design-docs/infra/thirdparty.md` 中搜索：

    电商平台店铺数据 API
    什么进 thirdparty
    什么不进
    源头构造
    Resilience4j

在 `docs/design-docs/module/file.md` 中搜索：

    ColumnMapping
    commons-csv
    Apache POI
    ImportReport
    行级容错
    幂等

注意：`docs/design-docs/module/commerce.md` 当前强调 commerce 不依赖 `infra/cache`，本计划保持这一点。外部 API 的实时查询只使用进程内 Resilience4j 治理，不做集群级全局并发。若未来需要集群级平台 API 并发上限，再引入 `infra/cache` 的 DistributedSemaphore，并同步修改依赖 DAG。

## Plan of Work

先创建 Maven 模块 `pixflow-module-commerce`。在根 `pom.xml` 的 `<modules>` 中加入 `pixflow-module-commerce`，并在 dependencyManagement 中加入 `com.pixflow:pixflow-module-commerce:${project.version}`。新模块 `pixflow-module-commerce/pom.xml` 依赖 `pixflow-common`、`pixflow-infra-mq`、MyBatis-Plus Spring Boot 3 starter、Spring Boot validation、Spring Boot web、Spring Boot autoconfigure、commons-csv、Apache POI、Resilience4j Spring Boot 3、MockWebServer 测试依赖、Testcontainers MySQL/RabbitMQ 测试依赖。源码根包使用 `com.pixflow.module.commerce`。

然后实现配置、错误码和基础 DTO。创建 `CommerceProperties`，配置前缀为 `pixflow.commerce`，覆盖导入批大小、类目冲突策略、默认窗口、benchmark 最小样本、默认粒度、source scope、实时刷新开关、freshness ttl、平台标识、Resilience4j 超时和 API 导入队列参数。创建 `CommerceErrorCode implements ErrorCode`，至少包含导入格式不支持、缺列、文件损坏、指标非法、导入任务不存在、平台拉取失败、MySQL 依赖不可用等码。创建 `CommerceService` 门面，暴露本地导入、API 导入任务创建、导入任务查询和查询四类入口。

接着实现 MySQL 数据模型。创建 `CommerceData` 实体与 `CommerceDataMapper`，字段必须覆盖 `sku_id`、`category`、`impressions`、`ctr`、`add_cart_rate`、`purchase_rate`、`period_type`、`period_start`、`period_end`、`source`、`fetched_at`、`created_at`、`updated_at`。测试 schema 必须包含唯一键 `uk_commerce_natural = (sku_id, period_type, period_start, source)`，索引 `idx_category_period = (category, period_type, period_start)` 和 `idx_sku_period = (sku_id, period_start)`。同时新增 `CommerceImportJob` 与 `CommerceImportJobMapper`，用于外部 API 异步导入状态，字段至少包含 `id`、`source`、`platform`、`status`、`sku_count`、`succeeded_count`、`failed_count`、`request_json`、`report_json`、`error_summary`、`created_at`、`updated_at`、`finished_at`。

再实现本地 CSV/XLSX 导入。`CommerceFileParser` 是解析接口，`CsvCommerceParser` 使用 commons-csv，`ExcelCommerceParser` 使用 Apache POI。`ColumnMapping` 支持表头映射和列序映射，默认表头优先，支持中文表头如“曝光量”“点击率”“加购率”“购买率”“类目”。`RawCommerceRow` 保存未转换字符串，`RowValidator` 负责 sku 非空、曝光量非负、比率在 0 到 1 之间、日期可解析、`period_start <= period_end`。单行失败写入 `ImportReport.failures` 并跳过，文件级缺列或文件损坏抛 `PixFlowException`。通过行批量 upsert 到 `commerce_data`，重复导入同一自然键只更新指标和时间戳，不新增重复行。

实现外部 API 异步导入。定义 `ApiImportRequest`，入参包含 skuIds、window、periodType、sourceScope 或平台参数。HTTP controller 收到请求后创建 `CommerceImportJob(status=PENDING)`，通过 `CommerceApiImportPublisher` 发布 `CommerceApiImportMessage{jobId}`，然后立即返回 job id。`CommerceApiImportConsumer` 消费消息，把任务置为 `RUNNING`，调用 `CommerceDataSource.pull(PullSpec)` 拉取标准化 `CommerceData`，再走与本地导入相同的校验和批量 upsert 路径。成功后任务置为 `SUCCEEDED` 或 `PARTIAL`，失败后置为 `FAILED` 并写脱敏 `error_summary`。消息体只放 job id，符合 infra/mq 的“消息体最小化”原则。

实现可替换数据源端口。`CommerceDataSource` 定义 `List<CommerceData> pull(PullSpec spec)` 和 `boolean supportsLive()`。`LocalDatasetSource` 不访问网络，只表示数据已经在 MySQL 中。`ExternalPlatformSource` 依赖 `PlatformApiClient`，负责把平台响应转成 commerce 标准模型，并使用 Resilience4j 包裹调用。`PlatformApiClient` 的出入参必须是 commerce 自己的 record，例如 `PlatformPullRequest`、`PlatformMetricRow`、`PlatformPullResult`，不要暴露真实平台 SDK 类型。第一版提供 fake 或 mock provider，用于单测和集成测试；真实平台 provider 留给后续配置实现。

实现查询与分析层。`CommerceQuery` 应包含 `skuIds`、`TimeWindow`、`periodType`、`withBenchmark`、`withTrend`、`sourceScope` 和可选 `preferredSource`。`CommerceQueryResult` 应包含 `perSku`、`missingSkus`、`degraded` 和必要的统计元数据。`SkuMetrics` 应包含 `skuId`、`category`、聚合 `Metrics`、可空 `Benchmark`、可空趋势点列表、`freshness` 或 `stale` 信息。`Benchmark` 应包含类目均值、偏离百分比、样本数和 `insufficientSample`。单 SKU 窗口聚合中曝光量求和，ctr、add_cart_rate、purchase_rate 走曝光加权平均。类目均值在 SQL 层计算，不把全类目数据取回 Java 内存再算。`BenchmarkCalculator` 只做偏离百分比、除零保护和样本不足判断，作为纯算法类单测。

实现实时查询按需刷新。`CommerceQueryService` 在查询前调用 `FreshnessPolicy` 检查被查 SKU 的库存数据是否在 TTL 内。库存够新或 `live-enabled=false` 时直接查 MySQL。过期或缺失且 live 开启时，只刷新被查 SKU，调用 `ExternalPlatformSource.pull`，成功后 write-through upsert，再统一从 MySQL 聚合。失败、超时、限流或熔断时降级用库存数据并标记 stale；库存也没有时把该 SKU 放入 `missingSkus`。外部故障不应抛到 Agent 主循环，也不应让同步查询超过配置的紧超时，默认不超过 2 秒。

最后接入 app 与 HTTP 验证入口。`pixflow-app` 应依赖 `pixflow-module-commerce` 并装配 auto-configuration。新增 `CommerceController` 或等价 web 层，暴露本地导入、API 导入任务创建、导入任务查询和查询接口。返回体统一使用 `ApiResponse<T>`。后续 `harness/tools` 和 `agent` 就绪后，`search` / `read` 工具 handler 只需要把 tool 入参转换为 commerce 查询或候选检索请求；其中 `read(include=["data"])` 调用 `CommerceService.query(...)`。commerce 模块自身不依赖 tools 或 agent。

## Concrete Steps

所有命令默认在仓库根目录 `D:\study\PixFlow` 执行。开始任何代码改动前先查看工作树，避免覆盖用户或其他 agent 的未提交改动：

    git status --short

预期：如果有与 `pixflow-module-commerce`、根 `pom.xml`、`pixflow-app` 或 `docs/design-docs/module/commerce.md` 相关的未提交改动，先阅读差异并与其共存；不要回滚用户改动。当前计划创建时工作树已有其它文档和 memory 模块改动，这些不是 commerce 代码实现的一部分。

第一步创建 commerce 模块骨架。新增目录：

    pixflow-module-commerce/pom.xml
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/
    pixflow-module-commerce/src/test/java/com/pixflow/module/commerce/
    pixflow-module-commerce/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

同时修改根 `pom.xml`，在 `<modules>` 中加入：

    <module>pixflow-module-commerce</module>

如果根 `pom.xml` 的 dependencyManagement 管理内部模块，也加入：

    com.pixflow:pixflow-module-commerce:${project.version}

然后运行：

    mvn -pl pixflow-module-commerce -am test

预期：Maven 能识别模块并完成空模块或最小 context 测试。

第二步实现配置、DTO、错误码和自动装配。创建 `CommerceProperties`、`CommerceErrorCode`、`CommerceAutoConfiguration`、`CommerceService`、`CommerceQuery`、`CommerceQueryResult`、`SkuMetrics`、`Metrics`、`Benchmark`、`TrendPoint`、`TimeWindow`、`FreshnessInfo` 等类型。运行：

    mvn -pl pixflow-module-commerce -am test

预期：配置绑定测试、错误码 category 非空测试和 DTO 构造测试通过。

第三步实现 MySQL schema、实体和 mapper。测试资源中新增 `schema-commerce.sql`，包含 `commerce_data` 和 `commerce_import_job`。先用 mapper 集成测试或 MyBatis 测试验证插入、自然键 upsert、按 SKU 查询、按类目窗口聚合。运行：

    mvn -pl pixflow-module-commerce -am test

如果使用真实 MySQL Testcontainers，在 Windows 本地 Docker 需要 profile 时运行：

    mvn -pl pixflow-module-commerce -am test -Pwindows-docker-npipe

预期：重复插入同一自然键不会增加行数，更新后的指标可被查询到。

第四步实现本地导入管线。先完成 `ColumnMapping`、`RawCommerceRow`、`RowValidator`、`CsvCommerceParser`、`ExcelCommerceParser` 和 `CommerceImportService`，用小样本 CSV/XLSX 测试中英文表头、缺列、非法比率、重复导入、类目冲突 WARN/FAIL。运行：

    mvn -pl pixflow-module-commerce -am test

预期：有效行落库，坏行进入 `ImportReport.failures`，重复导入不增加行数，类目冲突 WARN 模式能记录 warning。

第五步实现外部 API 异步导入。新增 `CommerceImportJobService`、`CommerceApiImportPublisher`、`CommerceApiImportConsumer`、`CommerceApiImportMessage`、`CommerceImportTopology` 和 `CommerceImportErrorHandler`。使用 fake `PlatformApiClient` 验证 job 从 `PENDING` 到 `RUNNING` 到 `SUCCEEDED`，失败时进入 `FAILED` 或 DLQ 判定。运行：

    mvn -pl pixflow-module-commerce -am test

在 Docker 可用时运行 RabbitMQ 集成测试：

    mvn -pl pixflow-module-commerce -am test -Pwindows-docker-npipe

预期：创建 API 导入任务后发布消息，consumer 消费 job id，fake platform 返回数据，MySQL 出现对应 `commerce_data` 行，`commerce_import_job` 终态为 `SUCCEEDED`。

第六步实现查询聚合、benchmark 和趋势。新增 mapper 聚合方法和 `BenchmarkCalculator`。准备测试数据：同类目 6 个 SKU，其中一个查询 SKU、一个缺失 SKU、多个不同曝光量的指标行。运行：

    mvn -pl pixflow-module-commerce -am test

预期：曝光量求和，点击率等比率按曝光加权均值计算；类目样本数达到阈值时返回 benchmark，不足阈值时 `insufficientSample=true` 或 benchmark 置空；缺失 SKU 出现在 `missingSkus`。

第七步实现实时刷新与降级。用 fake `PlatformApiClient` 模拟成功、超时、429 限流、5xx、熔断打开。运行：

    mvn -pl pixflow-module-commerce -am test

预期：TTL 内查询不调用平台；过期时只刷新被查 SKU；平台成功后先 upsert 再查 MySQL；平台失败时查询仍返回库存数据并标 `stale=true`；库存也无时进入 `missingSkus`，异常不外泄。

第八步接入 `pixflow-app`。在 app 的 Maven 依赖中加入 `pixflow-module-commerce`，保证 auto-configuration 和 controller 能被扫描。运行：

    mvn -pl pixflow-app -am test

预期：Spring context 可启动，commerce controller/service 可注入。如果 app 还没有统一数据库迁移入口，controller 集成测试可暂用模块测试资源 schema，生产迁移接入记录为后续任务。

第九步运行相关模块测试：

    mvn -pl pixflow-module-commerce,pixflow-app -am test

预期：commerce 单测、集成测试和 app 装配测试通过。若 Docker 不可用，Testcontainers 集成测试应按条件跳过，普通单元测试必须通过。

## Validation and Acceptance

验收必须证明可观察行为，而不只是代码编译。

本地导入验收：准备一个 CSV，包含类目 `dress` 下 6 个 SKU 的 30 天日粒度数据，字段包括 `sku_id`、`category`、`impressions`、`ctr`、`add_cart_rate`、`purchase_rate`、`period_type`、`period_start`、`period_end`。调用本地导入服务或 HTTP 接口后，应返回 `ImportReport`，其中 `succeeded` 等于有效行数，`skipped` 等于坏行数。再次导入同一文件，`commerce_data` 行数不增加，指标按自然键覆盖。

查询验收：调用 `CommerceService.query(...)` 或 HTTP 查询接口，请求 `skuIds=["SKU001","SKU_MISSING"]`、`withBenchmark=true`、`withTrend=true`、默认近 30 天。结果中 `perSku` 应包含 `SKU001`，`missingSkus` 应包含 `SKU_MISSING`。`SKU001.aggregated.impressions` 应等于窗口内曝光量总和，`ctr` 应为曝光加权平均。`benchmark.sampleCount` 达到配置阈值时，应返回类目均值和偏离百分比；样本不足时，应明确标记样本不足。

API 异步导入验收：调用 API 导入任务创建接口，传入几个 SKU 和时间窗口。接口应立即返回 `jobId` 与 `PENDING` 或 `RUNNING` 状态，不等待平台全量拉取完成。RabbitMQ consumer 处理后，查询任务详情应看到 `SUCCEEDED`，`commerce_data` 出现来源为 `EXTERNAL_API:{platform}` 的行。若 fake platform 返回部分坏行，任务应为 `PARTIAL`，报告里包含失败明细。

实时刷新验收：先插入一条过期库存数据，再配置 `pixflow.commerce.source.live-enabled=true` 和很短 TTL。查询该 SKU 时，fake platform 成功返回新指标，结果应反映新指标，且对应行已写入 MySQL。把 fake platform 改为超时后再次查询，应返回库存数据并标记 stale，不应抛出网络异常。若库存不存在且平台失败，该 SKU 应进入 `missingSkus`。

错误与脱敏验收：构造含平台 token 或绝对路径的异常消息，经过导入报告、job error summary 或 HTTP 错误响应后，不应出现原始 token 和敏感路径。`CommerceErrorCode` 应并入 common 风格的错误码目录测试，保证 code 唯一、category 非空、i18n key 可补齐。

必须至少运行并通过：

    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-app -am test

如果运行集成测试需要 Docker，在 Windows 本地优先使用：

    mvn -pl pixflow-module-commerce -am test -Pwindows-docker-npipe

如果 Docker 不可用，集成测试可以跳过，但最终结果必须明确记录哪些测试被跳过，以及普通单测覆盖了哪些降级路径。

## Idempotence and Recovery

本计划的实现应可重复运行测试和重复导入。重复导入同一 CSV/XLSX 文件不应增加 `commerce_data` 行数，因为自然键 `(sku_id, period_type, period_start, source)` 会触发 upsert。重复消费同一个 `CommerceApiImportMessage{jobId}` 不应重复写入指标行，任务状态更新应幂等：已完成的 job 再次消费时可以直接跳过，或重新校准 report 后保持同一终态。

如果 HTTP 请求创建 API 导入 job 后发布 MQ 失败，job 应保持 `PENDING` 或 `PUBLISH_FAILED`，并由补偿扫描或用户重试入口重新发布。若第一版不实现补偿扫描，必须在 job 查询结果中明确显示失败状态和可重试原因，不能让任务无声卡住。

如果 worker 在拉取平台数据后、落库前崩溃，RabbitMQ 重投后重新拉取并 upsert，最终状态仍收敛。若 worker 在部分落库后崩溃，重投后自然键 upsert 保证不重复污染类目均值。`succeeded_count` 不应简单累加，应在任务完成时按实际处理结果或报告重算，避免重投导致计数翻倍。

如果平台 API 限流、超时、5xx 或熔断，实时查询必须降级库存数据；异步导入任务可以重试，重试耗尽后置 `FAILED` 或进入 DLQ。实时查询失败不改变已有库存数据，不删除 MySQL 行，不把平台异常抛给 Agent 主循环。

测试数据库可以在每个测试类前重建 schema 或使用独立容器。所有临时 CSV/XLSX 文件应放在测试临时目录，测试结束清理。不要写入仓库外目录，除非命令获得明确授权。

## Artifacts and Notes

核心数据流如下：

    本地 CSV/XLSX 导入
      -> CommerceFileParser 解析为 RawCommerceRow
      -> ColumnMapping 映射字段
      -> RowValidator 行级校验
      -> 有效行标准化为 CommerceData
      -> 按自然键批量 upsert commerce_data
      -> 返回 ImportReport

    外部 API 异步导入
      -> POST 创建 CommerceImportJob
      -> MessagePublisher 发布 CommerceApiImportMessage{jobId}
      -> RabbitMQ worker 消费 jobId
      -> ExternalPlatformSource.pull(PullSpec)
      -> PlatformApiClient 拉取平台标准 record
      -> 校验 + 批量 upsert commerce_data
      -> 更新 CommerceImportJob 终态和 report

    实时查询
      -> CommerceQueryService.query
      -> FreshnessPolicy 检查被查 SKU
      -> 库存够新或 live 关闭：直接 MySQL 聚合
      -> 过期且 live 开启：ExternalPlatformSource 拉取被查 SKU
      -> 成功：write-through upsert 后 MySQL 聚合
      -> 失败：库存降级 + stale；库存也无则 missingSkus

建议的 `commerce_import_job.status` 稳定枚举值：

    PENDING
    RUNNING
    SUCCEEDED
    PARTIAL
    FAILED
    PUBLISH_FAILED

建议的 `CommerceSourceScope` 稳定枚举值：

    ALL
    LOCAL_ONLY
    EXTERNAL_ONLY
    PREFERRED_EXTERNAL
    PREFERRED_LOCAL

`ALL` 是默认值。`PREFERRED_EXTERNAL` 和 `PREFERRED_LOCAL` 的第一版可以只保留 DTO 和配置入口，若实现成本高，可以在 Decision Log 中记录延后；但 source 过滤能力必须在 mapper 查询条件中预留。

建议的 HTTP 端点：

    POST /api/commerce/import/local
    POST /api/commerce/import/api
    GET  /api/commerce/import/jobs/{jobId}
    POST /api/commerce/query

这些端点用于 Wave 2 验证和前端调试。真正的 Agent 工具名以 `harness/tools.md` 为准：`search` 用于发现候选 SKU，`read` 用于精读单 SKU，`read(include=["data"])` 才拉取电商指标。等 `harness/tools` 和 `agent` 模块就绪后再注册，不在 commerce 内部直接实现 Tool Registry。

## Interfaces and Dependencies

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/CommerceService.java` 定义：

    package com.pixflow.module.commerce;

    import com.pixflow.module.commerce.importer.ImportOptions;
    import com.pixflow.module.commerce.importer.ImportReport;
    import com.pixflow.module.commerce.query.CommerceQuery;
    import com.pixflow.module.commerce.query.CommerceQueryResult;
    import com.pixflow.module.commerce.source.ApiImportRequest;
    import com.pixflow.module.commerce.source.ImportJobStatusView;
    import java.io.InputStream;

    public interface CommerceService {
        ImportReport importLocal(InputStream input, String filename, ImportOptions options);
        ImportJobStatusView startApiImport(ApiImportRequest request);
        ImportJobStatusView getImportJob(long jobId);
        CommerceQueryResult query(CommerceQuery query);
    }

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/query/CommerceQuery.java` 定义包含以下信息的 record：`List<String> skuIds`、`TimeWindow window`、`PeriodType periodType`、`boolean withBenchmark`、`boolean withTrend`、`CommerceSourceScope sourceScope`、`String preferredSource`。`sourceScope` 为空时使用配置默认值 `ALL`。

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/query/SkuMetrics.java` 定义包含以下信息的 record：`skuId`、`category`、`Metrics aggregated`、`Benchmark benchmark`、`List<TrendPoint> trend`、`FreshnessInfo freshness`。`FreshnessInfo` 至少包含 `boolean stale`、`Instant fetchedAt`、`String source` 和 `String reason`。

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/query/Benchmark.java` 定义包含 `Metrics categoryAverage`、`Deviation deviation`、`int sampleCount`、`boolean insufficientSample`。当样本数不足或类目均值为零无法计算偏离时，必须用字段表达原因，不要返回看似正常但实际无依据的百分比。

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/source/CommerceDataSource.java` 定义：

    package com.pixflow.module.commerce.source;

    import com.pixflow.module.commerce.store.CommerceData;
    import java.util.List;

    public interface CommerceDataSource {
        List<CommerceData> pull(PullSpec spec);
        boolean supportsLive();
    }

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/source/PlatformApiClient.java` 定义：

    package com.pixflow.module.commerce.source;

    public interface PlatformApiClient {
        PlatformPullResult pull(PlatformPullRequest request);
    }

`PlatformPullRequest` 和 `PlatformPullResult` 必须是 commerce 自有 record。不要在接口签名中出现淘宝、京东、Shopify、抖店或任何真实平台 SDK 类型。真实平台 provider 只能实现这个接口。

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/importer/CommerceFileParser.java` 定义：

    package com.pixflow.module.commerce.importer;

    import java.io.InputStream;
    import java.util.stream.Stream;

    public interface CommerceFileParser {
        boolean supports(String filename, String contentType);
        Stream<RawCommerceRow> parse(InputStream input, ColumnMapping mapping);
    }

如果使用 `Stream<RawCommerceRow>`，实现必须确保底层资源被关闭。若资源关闭难以保证，可以改为回调式 parser，例如 `void parse(InputStream input, ColumnMapping mapping, RowConsumer consumer)`，并在 Decision Log 中记录原因。

在 `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/importer/CommerceImportService.java` 定义本地导入和标准化记录导入两个入口。外部 API worker 不应绕过导入校验直接写 mapper，而应复用标准化记录的校验、类目冲突处理和 upsert 路径。

commerce 模块必须使用现有底层接口，不得直接依赖上层 Agent 或 Tool Registry：

    com.pixflow.common.error.ErrorCode
    com.pixflow.common.error.PixFlowException
    com.pixflow.common.sanitize.Sanitizer
    com.pixflow.infra.mq.MessagePublisher
    com.pixflow.infra.mq.consumer.ConsumerErrorHandler
    com.pixflow.infra.mq.consumer.ManagedMessageHandler
    com.pixflow.infra.mq.topology.QueueTopologyBuilder
    MyBatis-Plus Mapper
    Apache POI
    commons-csv
    Resilience4j
    Spring RestClient

commerce 模块不得依赖：

    pixflow-infra-thirdparty
    pixflow-infra-cache
    pixflow-hooks
    pixflow-context
    pixflow-state
    pixflow-module-file
    pixflow-module-memory
    pixflow-agent

如果实现 API 异步导入时确实需要进度推送，可以复用 `common.ProgressNotifier`，但第一版只要求 job 查询接口可观察，不强制 WebSocket。

## Change Note

2026-06-28 / Codex: 初次创建计划。本文把 commerce 模块从设计讨论落成可执行规格，明确生产级范围、外部平台可替换、外部 API 导入走 RabbitMQ 异步、查询默认 `sourceScope=ALL` 但保留 source 过滤、类目冲突默认 WARN，以及 `stale/freshness` 必须进入查询结果。

2026-06-29 / Codex: 同步 `harness/tools.md` 的新工具口径，将后续 Agent 工具引用从 `query_commerce_data` 改为 `search` / `read`。commerce 模块本身的已实现服务边界不变，仍只提供导入、查询和数据事实。
