# 实现生产级 infra/vector Qdrant 向量存储模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document must be maintained in accordance with `PLANS.md`.

## Purpose / Big Picture

完成这个模块后，`module/memory` 可以把已经由 `infra/ai` 生成好的文本向量写入 Qdrant，并用同一套 `VectorStore` 接口完成集合初始化、按 UUID 幂等 upsert、dense 相似检索、payload 过滤、按 id 查询和删除。对用户来说，结果是 PixFlow 的“分析结论记忆”可以稳定支持语义召回：Agent 在给出电商图片处理建议时，能从历史分析结论中找到相似经验，而不需要业务模块直接面对 Qdrant 的 gRPC API。

这个计划不是 MVP，也不是临时封装一个 Qdrant client。目标是直接交付生产级 `infra/vector`：独立 Maven 模块、纯向量 I/O 抽象、Qdrant 官方 Java client 实现、UUID point id、集合生命周期校验、最小过滤 DSL、Resilience4j 韧性治理、错误收口、Micrometer 指标、Actuator 健康检查、Testcontainers 集成测试。模块内部不得出现 `memory`、`analysis_insight`、`sku`、`confidence` 等业务词；这些语义由后续 `module/memory` 负责。

## Progress

- [x] (2026-06-27 +08:00) 阅读 `PLANS.md`，确认 ExecPlan 必须自包含、面向新人，并维护 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`。
- [x] (2026-06-27 +08:00) 阅读 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，确认 `infra/vector` 是 Wave 1 基础设施模块，当前尚未完成，唯一直接上层消费者是后续 `module/memory`。
- [x] (2026-06-27 +08:00) 阅读 `docs/design-docs/design.md` 与 `docs/design-docs/infra/vector.md`，确认 Qdrant 只承载 dense 向量召回，MySQL FULLTEXT 负责关键词召回，RRF 融合在 `module/memory` 应用层完成。
- [x] (2026-06-27 +08:00) 阅读 `docs/design-docs/base/common.md` 和已实现的 `pixflow-infra-storage`、`pixflow-infra-ai` 部分代码，确认本模块应保持独立异常并在边界由 common 归一化，工程结构沿用现有 infra 模块风格。
- [x] (2026-06-27 +08:00) 按用户决策确定 Qdrant point id 使用 UUID 字符串，而不是 Long 主键或任意业务字符串。
- [x] (2026-06-27 +08:00) 查询 Qdrant 稳定版本：`io.qdrant:client` 当前稳定版为 `1.18.3`，Qdrant 服务端最新稳定 release 为 `v1.18.2`；本计划据此锁定客户端依赖和测试容器镜像。
- [x] (2026-06-27 +08:00) 创建本 ExecPlan，记录架构思路、机制、实施步骤、验证方式和设计文档搜索关键词。
- [x] (2026-06-27 +08:00) 新增 `pixflow-infra-vector` Maven module，并把它接入根 `pom.xml` 的模块列表、依赖管理、版本属性和 `pixflow-app` 依赖。
- [x] (2026-06-27 +08:00) 定义 `VectorStore`、`VectorPoint`、`ScoredPoint`、`Distance`、`VectorFilter`、`VectorException` 等纯向量 I/O 契约，并对向量数组/payload 做防御拷贝。
- [x] (2026-06-27 +08:00) 实现 `QdrantVectorStore`、`VectorFilterTranslator`、配置、自动装配、Resilience4j retry/time limiter、Micrometer 指标和 Actuator 健康检查。
- [x] (2026-06-27 +08:00) 补齐 DTO/过滤翻译/common 归一化单测和 Qdrant Testcontainers 集成测试；执行 `mvn -pl pixflow-infra-vector -am test` 与 `mvn -pl pixflow-app -am test -DskipTests` 均成功，当前 Docker 环境不可用导致 Qdrant 集成测试按 `disabledWithoutDocker` 跳过。

## Surprises & Discoveries

- Observation: 用户提供的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在，实际总体设计文档是 `docs/design-docs/design.md`。
  Evidence: 读取 `.kiro/specs/pixflow/design.md` 时返回路径不存在；`docs/design-docs/design.md` 存在并包含 RAG 记忆层、Qdrant 数据模型和模块划分。

- Observation: `docs/design-docs/infra/vector.md` 中写的 Qdrant Java client 版本是 `1.12.0`，但 2026-06-27 查询 Maven Central 与官方 GitHub release 后，当前稳定客户端是 `1.18.3`。
  Evidence: Maven Central 页面 `https://central.sonatype.com/artifact/io.qdrant/client/versions` 显示 `pkg:maven/io.qdrant/client@1.18.3`；Qdrant Java client release 页面 `https://github.com/qdrant/java-client/releases` 标记 `v1.18.3` 为 Latest。

- Observation: Qdrant 服务端最新稳定 release 是 `v1.18.2`，与 Java client 的 `1.18.x` 支持线匹配。
  Evidence: Qdrant 服务端 release 页面 `https://github.com/qdrant/qdrant/releases` 显示 `v1.18.2` 为 Latest；Java client release 历史中 `v1.18.0` 和 `v1.18.1` 明确支持 Qdrant `v1.18.x`。

- Observation: 当前根 `pom.xml` 已有 `pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-mq`、`pixflow-infra-ai`、`pixflow-infra-image`、`pixflow-infra-thirdparty`，但没有 `pixflow-infra-vector`。
  Evidence: 根 `pom.xml` 的 `<modules>` 列表缺少 vector；`docs/design-docs/exec-plans/module-dependency-dag-plan.md` 仍把 `infra/vector` 标为未完成。

- Observation: Qdrant Java client `1.18.3` 的 `Collections.Distance` 枚举常量是 `UnknownDistance/Cosine/Euclid/Dot/Manhattan/UNRECOGNIZED`，不是旧设计里可能推断出的 `DistanceUnknown`。
  Evidence: 对本地 `io.qdrant:client:1.18.3` jar 执行 `javap io.qdrant.client.grpc.Collections$Distance` 后确认常量名；实现中改为显式 if 映射，避免与本模块 `Distance` 枚举命名冲突。

## Decision Log

- Decision: Qdrant point id 统一使用 UUID 字符串，由 `module/memory` 在创建 MySQL `analysis_insight` 镜像行时生成并保存，之后同一个 UUID 作为 Qdrant point id。
  Rationale: UUID 是 Qdrant point id 的稳定通用格式，避免 Long 主键与 Qdrant id 类型适配细节绑定，也避免任意业务字符串带来合法性和迁移风险。MySQL 与 Qdrant 共享同一个 UUID 后，补偿写入、按 id 删除和全量重建都能幂等执行。
  Date/Author: 2026-06-27 / Codex

- Decision: Qdrant Java client 使用 `io.qdrant:client:1.18.3`，并在根 `pom.xml` 中新增 `qdrant.client.version` 属性集中管理。
  Rationale: `1.18.3` 是 Maven Central 与官方 GitHub release 当前可见的稳定客户端版本，比设计文档旧版本 `1.12.0` 更新。集中成属性便于以后安全升级，也符合当前项目在根 `pom.xml` 管理第三方版本的风格。
  Date/Author: 2026-06-27 / Codex

- Decision: Testcontainers 集成测试使用固定镜像 `qdrant/qdrant:v1.18.2`，不使用 `latest`。
  Rationale: 固定镜像让本地与 CI 行为可复现。服务端 `v1.18.2` 是当前稳定 release，且 Java client `1.18.x` 支持 Qdrant `v1.18.x`。
  Date/Author: 2026-06-27 / Codex

- Decision: `infra/vector` 只依赖 `pixflow-common` 和基础库，不依赖 `infra/ai`、`infra/cache`、`module/memory`、任何 harness 或 agent 模块。
  Rationale: `infra/ai` 负责“文本到向量”，`infra/vector` 负责“向量存取检索”，`module/memory` 负责编排两者并处理业务语义。保持这个分工才能让 Qdrant 存储实现可替换，也避免基础设施模块反向依赖业务。
  Date/Author: 2026-06-27 / Codex

- Decision: `VectorPoint` 和 `ScoredPoint` 可以在公共契约中暴露 `float[]`，但实现必须在构造和访问时做 defensive copy。
  Rationale: Java `record` 对数组不是深不可变，裸暴露 `float[]` 会让调用方在 upsert 或 search 后修改内部状态。向量通常较大，`float[]` 比 `List<Float>` 少装箱开销；通过拷贝可以兼顾性能和不可变语义。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

已完成 `pixflow-infra-vector` 基础实现。`module/memory` 后续可以只通过 `VectorStore` 完成集合初始化、UUID point 写入、dense 检索、按 id 查询/删除和按过滤删除；Qdrant client、payload/filter 转换、Resilience4j、Micrometer 和 HealthIndicator 都封装在 infra 内部。已加入 Testcontainers 集成测试覆盖真实 Qdrant 的集合幂等、维度不一致失败、upsert 覆盖、search 过滤、get/delete/deleteByFilter；本机 Docker 当前不可用，测试以 `disabledWithoutDocker=true` 跳过。纯单测覆盖 DTO 防御拷贝、过滤翻译和 `VectorException` 到 common `DEPENDENCY` 的归一化。

## Context and Orientation

`infra/vector` 是 PixFlow 的基础设施模块。基础设施模块的意思是：它封装外部系统或底层能力，向上层提供稳定的内部接口，而不承载业务规则。这里的外部系统是 Qdrant，一个向量数据库；向量数据库保存的是浮点数数组，每个数组表示一段文本的语义。`infra/vector` 不负责生成向量，生成向量属于 `infra/ai` 的 `EmbeddingClient`；`infra/vector` 只负责把调用方传进来的向量写进 Qdrant、从 Qdrant 查回来、按 payload 过滤、按 id 删除。

当前仓库已经有多个 infra 模块作为参照。`pixflow-infra-storage` 展示了独立异常、配置属性、自动装配和 Testcontainers 的风格；`pixflow-infra-mq` 展示了 `error`、`observability`、`config` 分包风格；`pixflow-infra-ai` 展示了能力接口与供应商实现解耦的思路。新的 vector 模块应沿用这些习惯，避免形成另一套工程风格。

为了快速定位参考设计文本，后续实施时可以用这些关键词在对应文档里搜索：

- 在 `docs/design-docs/design.md` 中搜索 `RAG 记忆层`、`分析结论记忆`、`Qdrant（向量）`、`analysis_insight`、`混合检索 + RRF`、`MySQL 为事实源`、`多存储一致性`。
- 在 `docs/design-docs/infra/vector.md` 中搜索 `纯向量 I/O`、`VectorStore`、`幂等建集合`、`upsert 按 id 覆盖`、`dense + 过滤 + 阈值`、`过滤 DSL`、`为什么不做 sparse`、`Resilience4j`、`Testcontainers`。
- 在 `docs/design-docs/base/common.md` 中搜索 `infra 异常收口策略`、`ErrorCategory`、`RecoveryHint`、`DEPENDENCY`、`ErrorNormalizer`、`Sanitizer`。
- 在 `docs/design-docs/infra/ai.md` 中搜索 `嵌入与重排边界`、`EmbeddingClient`、`Qdrant 存取归 infra/vector`、`不使用 Spring AI 的 VectorStore`。
- 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `infra/vector`、`Wave 1`、`module/memory`、`vector --> memory`。
- 在现有代码中搜索 `StorageException`、`StorageAutoConfiguration`、`AiAutoConfiguration`、`MicrometerMqMetrics`，可以快速看到本仓库已有 infra 模块的结构与编码习惯。

几个术语在本计划中的含义如下。“集合”是 Qdrant collection，类似一张专门存放同维度向量的表。“point”是 Qdrant 里的单条向量记录，由 id、向量和 payload 组成。“payload”是一段 JSON 形态的元数据，例如上层可能放文本、类目、来源、时间，但 vector 模块只把它当作 `Map<String,Object>`。“dense 向量”是普通稠密浮点数组，本期只支持这一种召回方式。“过滤 DSL”是 `VectorFilter`，它是 PixFlow 自己定义的最小过滤表达式，目的是不让上层代码直接依赖 Qdrant 的 `Filter` 类型。

## Plan of Work

先把工程模块接入。新增 `pixflow-infra-vector` 目录和 `pom.xml`，在根 `pom.xml` 的 `<modules>` 中加入该模块，在 dependencyManagement 中加入 `pixflow-infra-vector`，并新增 `qdrant.client.version` 属性，值为 `1.18.3`。模块依赖 `pixflow-common`、`io.qdrant:client`、Spring Boot autoconfigure、Spring context、Micrometer core、Resilience4j retry 和 timelimiter、Actuator health 需要的基础依赖，以及测试用 `spring-boot-starter-test`、`testcontainers`。如果 Resilience4j 已由父工程或其他模块统一管理版本，应复用根 `resilience4j.version`。

随后定义公共契约。新建包 `com.pixflow.infra.vector`，放入 `VectorStore`、`VectorPoint`、`ScoredPoint`、`Distance`、`VectorFilter`、`VectorException`。接口只表达纯 I/O：`ensureCollection`、`upsert`、`search`、`get`、`delete`、`deleteByFilter`、`collectionExists`。`VectorPoint` 的 id 是 UUID 字符串，实施时要用 `UUID.fromString(id)` 做格式校验；校验失败属于确定性错误，不重试。`VectorPoint` 与 `ScoredPoint` 的 payload 要做不可变拷贝，vector 数组要做 defensive copy。

再实现 Qdrant 适配层。`QdrantVectorStore` 封装 Qdrant 官方 Java client，并把所有 Qdrant 类型限制在实现内部。`ensureCollection` 要先检查集合是否存在，存在就读取向量配置并校验维度和距离，不一致直接抛不可重试 `VectorException`；不存在时，只有 `pixflow.vector.auto-create-collection=true` 才创建，否则快速失败。`upsert` 要批量写入并按 id 覆盖，所有 id 都必须是 UUID。`search` 要把 `VectorFilter` 翻译为 Qdrant payload filter，下推到向量检索，同时按 threshold 过滤低分结果，并返回按 score 降序排列的 `ScoredPoint`。`get`、`delete`、`deleteByFilter` 要保持幂等，找不到 id 不应变成业务失败。

然后实现过滤翻译。`VectorFilter` 只支持 `none`、`must`、`should`、`mustNot`、`match`、`matchAny`、`range`。`VectorFilterTranslator` 负责把这些条件翻译成 Qdrant 的 `Filter`。这里不支持全文 `MatchText`，不支持 sparse/BM25，不支持实体集合，不支持服务端 RRF。原因是总体架构已经决定关键词召回走 MySQL FULLTEXT，RRF 在 `module/memory` 应用层完成，vector 模块只提供 dense 召回这一路。

接着补配置和自动装配。`VectorProperties` 使用前缀 `pixflow.vector`，字段包括 `host`、`port`、`useTls`、`apiKey`、`timeout`、`autoCreateCollection`、`retry` 参数。向量维度和集合名不放在配置里，因为它们由 `module/memory` 根据 embedding 模型和业务集合决定。`VectorAutoConfiguration` 负责创建 Qdrant client、`VectorStore`、`VectorMetrics` 和健康检查。与 storage 模块不同，vector 可以在配置了 `pixflow.vector.host` 时创建真实 client；未配置时不创建真实 Qdrant bean，避免无 Qdrant 环境启动失败。

再加入韧性和错误收口。`QdrantVectorStore` 的网络 I/O 操作包一层 Resilience4j retry 与 timeout。只重试网络抖动、超时、临时不可用这类可恢复错误；维度不一致、集合缺失、id 非 UUID、filter 非法、向量维度与集合维度不匹配这类确定性错误不重试。内部所有异常统一包装成 `VectorException`，携带 `operation`、`collection`、`retryable`、`details` 和原始 cause。跨出 infra 边界时，`common` 的 `ErrorNormalizer` 应能把它归一化为 `DEPENDENCY`，默认恢复建议是 `RETRY`；如果当前 `ErrorNormalizer` 尚未识别 `VectorException`，实施时要补映射测试。

最后补可观测和测试。`VectorMetrics` 用 Micrometer 记录 `pixflow.vector.op` 的耗时和结果，记录 `pixflow.vector.search.returned` 的返回条数，避免把 collection 以外的高基数业务字段作为 tag。健康检查用 Qdrant 的轻量请求判断连接是否可用，不泄露 api key。测试分三层：纯单测验证 DTO 不可变性、UUID 校验、filter 翻译和错误映射；fake 或 stub 测试验证 retry 不重试确定性错误；Testcontainers 用 `qdrant/qdrant:v1.18.2` 验证真实集合生命周期、upsert 覆盖、search topK/threshold/filter、get/delete/deleteByFilter 和全量重建。

## Concrete Steps

先在仓库根目录检查工作区状态，确认不要覆盖用户已有改动：

    cd D:\study\PixFlow
    git status --short

预期会看到当前开发阶段已有若干文档变更和未跟踪设计目录。不要还原这些改动；vector 实现只添加和修改与本计划相关的文件。

第一步，新增 Maven 模块。创建 `pixflow-infra-vector/pom.xml`，并修改根 `pom.xml`。根 POM 需要增加：

    <module>pixflow-infra-vector</module>
    <qdrant.client.version>1.18.3</qdrant.client.version>

dependencyManagement 中需要加入 `com.pixflow:pixflow-infra-vector:${project.version}`。`pixflow-infra-vector/pom.xml` 至少依赖 `pixflow-common`、`io.qdrant:client:${qdrant.client.version}`、Spring autoconfigure、Spring context、Micrometer core、Resilience4j retry/timelimiter、Spring Boot starter test 和 Testcontainers。

第二步，新增公共类型。创建 `pixflow-infra-vector/src/main/java/com/pixflow/infra/vector/`，定义接口和 record。最终至少应存在这些类型：

    package com.pixflow.infra.vector;

    public interface VectorStore {
        void ensureCollection(String collection, int dim, Distance distance);
        void upsert(String collection, List<VectorPoint> points);
        List<ScoredPoint> search(String collection, float[] query, int topK, float threshold, VectorFilter filter);
        Optional<VectorPoint> get(String collection, String id);
        void delete(String collection, List<String> ids);
        void deleteByFilter(String collection, VectorFilter filter);
        boolean collectionExists(String collection);
    }

    public enum Distance { COSINE, DOT, EUCLID }

这些签名是对上层的稳定契约。不要在接口里加入 Qdrant 类型，不要在接口里加入 embedding client，不要加入业务集合常量。

第三步，实现 Qdrant client 封装。创建 `QdrantVectorStore`，它构造时接收 Qdrant client、properties、metrics 和 resilience 组件。实现中应集中处理这些转换：UUID 字符串到 Qdrant point id、`float[]` 到 Qdrant vector、`Map<String,Object>` 到 Qdrant payload、Qdrant scored point 到 `ScoredPoint`。所有转换都应有单测覆盖。

第四步，实现 `VectorFilter` 与 `VectorFilterTranslator`。先用纯单测固定表达能力，再接入 search。测试样例要覆盖：无过滤、AND、OR、NOT、等值、IN、数值区间、嵌套组合。不要为了未来能力提前加入全文条件。

第五步，实现配置、自动装配、metrics 和 health。创建 `VectorProperties` 与 `config/VectorAutoConfiguration.java`，风格参考 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/config/StorageAutoConfiguration.java`。创建 `observability/VectorMetrics.java`，风格参考 `pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/observability/MicrometerMqMetrics.java` 和 `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/observability/AiMetrics.java`。

第六步，补测试。先跑纯单测：

    mvn -pl pixflow-infra-vector test

然后跑依赖一起构建：

    mvn -pl pixflow-infra-vector -am test

如果 Docker 可用，Testcontainers 应自动拉起 `qdrant/qdrant:v1.18.2`。如果 CI 或本机没有 Docker，集成测试应按 JUnit/Testcontainers 标准机制跳过，而不是失败；纯单测必须仍然通过。

## Validation and Acceptance

最小验收是 `mvn -pl pixflow-infra-vector -am test` 成功，并且测试覆盖真实 Qdrant 行为。成功输出应类似：

    [INFO] Tests run: <N>, Failures: 0, Errors: 0, Skipped: <0 or docker-related skips>
    [INFO] BUILD SUCCESS

行为验收要证明以下场景。重复调用 `ensureCollection("test_collection", 3, COSINE)` 不报错；再用不同维度调用同一集合会抛不可重试 `VectorException`。用同一个 UUID upsert 两次不同 payload，`get` 只能看到第二次内容，说明按 id 覆盖而不是新增重复点。写入三条已知向量后，用相近 query search，返回结果按分数降序排列，`topK` 限制生效，threshold 会过滤低分结果。带 payload 条件 search 时，Qdrant 只返回满足 filter 的点。按 UUID 删除后，`get` 返回空；按 filter 删除后，匹配点消失，不匹配点保留。

错误验收要证明确定性失败不重试，临时失败才重试。可以用 fake Qdrant adapter 或可控异常包装验证：id 不是 UUID 时直接抛 `VectorException(retryable=false)`；连接超时或 gRPC unavailable 被包装成 `VectorException(retryable=true)` 并进入 retry 策略。异常 message 和 details 中不得包含 api key。

可观测验收要证明至少注册这些指标：`pixflow.vector.op` 和 `pixflow.vector.search.returned`。健康检查在 Qdrant 可达时为 UP，不可达时为 DOWN。指标 tag 保持低基数，只允许 `op`、`result` 这类稳定标签，不允许 point id、query 文本、业务 sku 进入 tag。

## Idempotence and Recovery

本计划的实现步骤应当是幂等的。重复运行 Maven 测试不会修改源码。重复执行 `ensureCollection` 不会重建已有集合；已有集合配置匹配时直接通过，配置不匹配时快速失败。重复执行同一批 `upsert` 不产生重复 point，因为 point id 是 UUID 且 upsert 按 id 覆盖。全量重建时，`module/memory` 可以从 MySQL 事实源读出 UUID、文本、payload 和向量后批量 upsert 到 Qdrant，重复重放仍然得到同一批点。

如果 Qdrant 不可用，vector 模块应抛出携带 `operation` 和 `collection` 的 `VectorException`，由上层决定是否降级为“向量召回为空”或触发补偿。vector 模块自己不吞异常，也不假装召回成功。如果 Qdrant client API 在 `1.18.3` 中和旧设计文档不同，实施者应以本计划锁定版本的源码和测试为准，更新 `Surprises & Discoveries` 与 `Decision Log` 后再继续。

不要执行 destructive git 命令，不要还原用户已有改动。若实现过程中需要删除错误创建的文件，只删除本计划新增且确认无用的文件，并在 `Progress` 中记录。

## Artifacts and Notes

计划完成后，仓库里应至少出现这些文件和目录：

    pixflow-infra-vector/
      pom.xml
      src/main/java/com/pixflow/infra/vector/
        VectorStore.java
        VectorPoint.java
        ScoredPoint.java
        Distance.java
        VectorFilter.java
        VectorException.java
        QdrantVectorStore.java
        VectorFilterTranslator.java
        VectorProperties.java
        config/VectorAutoConfiguration.java
        observability/VectorMetrics.java
      src/test/java/com/pixflow/infra/vector/

根 `pom.xml` 应出现 `pixflow-infra-vector` 模块和 `qdrant.client.version` 属性。后续如果添加 docker compose，本计划建议使用固定镜像 `qdrant/qdrant:v1.18.2`，不要使用 `latest`。

不要回到这些反模式：

    在 infra/vector 中写死 analysis_insight 集合名
    在 infra/vector 中调用 EmbeddingClient
    在 infra/vector 中实现 MySQL FULLTEXT 或 RRF
    把 Qdrant Filter 类型暴露给 module/memory
    允许非 UUID 任意字符串作为 point id
    用 latest 作为测试或开发 Qdrant 镜像
    catch Exception 后返回空列表伪装检索成功

## Interfaces and Dependencies

实现结束时，以下接口和类型必须稳定存在：

    package com.pixflow.infra.vector;

    public interface VectorStore {
        void ensureCollection(String collection, int dim, Distance distance);
        void upsert(String collection, List<VectorPoint> points);
        List<ScoredPoint> search(String collection, float[] query, int topK, float threshold, VectorFilter filter);
        Optional<VectorPoint> get(String collection, String id);
        void delete(String collection, List<String> ids);
        void deleteByFilter(String collection, VectorFilter filter);
        boolean collectionExists(String collection);
    }

    public record VectorPoint(String id, float[] vector, Map<String, Object> payload) { }

    public record ScoredPoint(String id, float score, Map<String, Object> payload) { }

    public enum Distance {
        COSINE, DOT, EUCLID
    }

    public final class VectorFilter {
        public static VectorFilter none();
        public static VectorFilter must(Condition... conditions);
        public static VectorFilter should(Condition... conditions);
        public static VectorFilter mustNot(Condition... conditions);
        public VectorFilter and(VectorFilter other);
        public sealed interface Condition permits Match, MatchAny, Range { }
    }

`VectorPoint` 与 `ScoredPoint` 的实际实现必须保护数组和 payload 不被外部修改；上面的 record 只是稳定 API 形态，不能成为裸数组泄漏的理由。`VectorFilter` 可以根据 Java 17 sealed class 限制选择 nested record 或普通 final class，但公共能力必须保持一致。

依赖约束如下。`pixflow-infra-vector` 只能直接依赖 `pixflow-common`、Qdrant Java client、Spring autoconfigure/context、Micrometer、Resilience4j 和测试库。它不能依赖 `pixflow-infra-ai`，因为向量生成不属于本模块；不能依赖 `pixflow-infra-cache`，因为本模块不需要全局信号量；不能依赖 `module/memory`，因为业务记忆语义属于上层；不能依赖任何 harness 或 agent 模块。

Qdrant 版本约束如下。客户端依赖使用 `io.qdrant:client:1.18.3`。Testcontainers 使用服务端镜像 `qdrant/qdrant:v1.18.2`。如果未来升级，必须同时更新本 ExecPlan 的 `Decision Log`、根 POM 版本属性、测试镜像版本和相关兼容性说明。

## Revision Notes

2026-06-27 / Codex: 创建本计划。原因是用户已确认 point id 使用 UUID，并要求检查 Qdrant 稳定版后按 `PLANS.md` 格式撰写中文计划文档；本计划锁定 `io.qdrant:client:1.18.3` 与 `qdrant/qdrant:v1.18.2`，并把架构边界、机制、实施步骤、验证方式和设计文档搜索关键词写入同一份自包含 ExecPlan。
