# 不保留旧代码地将 RabbitMQ 替换为 RocketMQ

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/infra/mq.md`、`docs/design-docs/design.md`，以及 `docs/design-docs/exec-plans/` 下的活跃计划。后续任何修订都必须保持本文自包含，并在同一次修改中同步更新进度、决策记录、验证证据与修订说明。

## Purpose / Big Picture（目的 / 总览）

PixFlow 的目标架构已经改为使用 RocketMQ 分发异步作业。完成本次重构后，用户可以通过 RocketMQ topic 与 consumer group 完成素材包上传后的解压、视觉文案富化、commerce 数据导入，以及图片处理任务入队。系统不应再启动 RabbitMQ，不应再依赖 Spring AMQP，不应再暴露 exchange / queue / routing-key 抽象，也不应把旧 RabbitMQ 代码作为迁移兜底保留下来。

这是一次破坏式实现重构，不是双栈迁移。最终状态只有一套 MQ 实现：RocketMQ。任何只为 RabbitMQ 存在的类、依赖、配置、测试或 Docker 服务都必须删除或改写。完成后，允许出现 RabbitMQ 字样的地方仅限历史修订说明、已完成的历史计划、本文，以及 `infra/mq.md` 中明确解释“从 RabbitMQ 迁出”的段落。生产源码和活跃运行配置中不得再出现 RabbitMQ 或 Spring AMQP 符号。

## Progress（进度）

- [x] (2026-07-02 00:00+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`，以及 `docs/design-docs/exec-plans/` 下的活跃执行计划。
- [x] (2026-07-02 00:00+08:00) 已阅读 `docs/design-docs/infra/mq.md` 与 `docs/design-docs/design.md`；确认权威目标是 RocketMQ，公共模型为 `topic + tag + keys + consumerGroup`。
- [x] (2026-07-02 00:00+08:00) 已阅读 `docs/design-docs/module/task.md`、`docs/design-docs/module/file.md` 与 `docs/design-docs/module/vision.md`；确认 task、file、vision 的设计口径已经同步到 RocketMQ。
- [x] (2026-07-02 00:00+08:00) 已搜索当前代码和配置中的 RabbitMQ / Spring AMQP 引用；发现旧实现分布在 `pixflow-infra-mq`、`pixflow-module-file`、`pixflow-module-vision`、`pixflow-module-task`、`pixflow-module-commerce`、`docker-compose.yml` 与 `pixflow-app/src/main/resources/application-dev.yml`。
- [x] (2026-07-03 02:05+08:00) 将 `pixflow-infra-mq` 公共 API 从 exchange / queue / routing-key 词汇改为 topic / tag / keys / consumer-group 词汇。
- [x] (2026-07-03 02:05+08:00) 将 `pixflow-infra-mq` 传输实现替换为 RocketMQ，并删除 RabbitMQ 专属 publisher、topology、registrar、listener 与测试。
- [x] (2026-07-03 02:05+08:00) 重写 file、vision、task、commerce 模块的业务 MQ 目的地声明与 listener 接线，使其使用新的 RocketMQ 抽象。
- [x] (2026-07-03 02:05+08:00) 将 Docker Compose 与开发环境配置从 RabbitMQ 替换为 RocketMQ NameServer + Broker。
- [x] (2026-07-03 02:05+08:00) 同步仍含过期 RabbitMQ 表述的活跃设计文档。
- [x] (2026-07-03 02:05+08:00) 增加测试与基于搜索的守护，证明不再保留 RabbitMQ / Spring AMQP 代码。
- [x] (2026-07-03 02:05+08:00) 运行模块与 app 验证命令，并把结果记录到本计划。

## Surprises & Discoveries（意外与发现）

- Observation: 设计文档已经部分同步到 RocketMQ，但实现仍深度使用 RabbitMQ。
  Evidence: `rg -l "Rabbit|rabbit|AMQP|Amqp|amqp|RabbitTemplate|RabbitAdmin|RabbitListener|spring-boot-starter-amqp|MessageListenerContainer|QueueTopology"` 返回了 `pixflow-infra-mq`、`pixflow-module-file`、`pixflow-module-vision`、`pixflow-module-task`、`pixflow-module-commerce`、`docker-compose.yml` 与 app 配置文件。

- Observation: commerce 当前使用了 MQ 实现类，但活跃依赖 DAG 尚未列出 commerce 对 MQ 的依赖边。
  Evidence: `pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java` 导入了 `MessageListenerContainer`，`pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/importjob/CommerceImportTopology.java` 导入了 `QueueTopologyBuilder`。

- Observation: 若干未归档的活跃设计文档在 MQ 设计更新后仍保留 RabbitMQ 旧文案。
  Evidence: `docs/design-docs/api.md`、`docs/design-docs/base/common.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/module/conversation.md` 与 `docs/design-docs/infra/ai.md` 仍包含 RabbitMQ 或 Spring AMQP 表述。

- Observation: Apache RocketMQ 文档区分 Remoting SDK 坐标 `rocketmq-client` 与 gRPC SDK 坐标 `rocketmq-client-java`；gRPC Java SDK 要求 RocketMQ 5.0+ 且启用 Proxy。
  Evidence: Apache RocketMQ 5.0 Client SDK 文档说明 `rocketmq-client` 是 Remoting 协议 SDK，`rocketmq-client-java` 是 gRPC 协议 SDK，且 gRPC SDK 需要 server 5.0+ 与 Proxy。

## Decision Log（决策记录）

- Decision: 本次重构删除 RabbitMQ 与 Spring AMQP 代码，而不是在旁边新增 RocketMQ 双栈。
  Rationale: 用户明确要求这是重构，不能为了迁移安全保留任何旧 RabbitMQ 代码。保留 adapter、feature flag 或兼容类会违反该要求，也会误导后续模块作者。
  Date/Author: 2026-07-02 / Codex

- Decision: PixFlow MQ 公共模型使用 `topic`、`tag`、`keys` 与 `consumerGroup`；不暴露 `exchange`、`queue`、`routingKey`、`binding`、`prefetch` 或 Spring AMQP listener container。
  Rationale: 这是 `docs/design-docs/infra/mq.md` 的核心不变量。业务模块应直接表达 RocketMQ 目的地，而不是在重命名 wrapper 中保留 RabbitMQ 心智模型。
  Date/Author: 2026-07-02 / Codex

- Decision: 首版实现使用 RocketMQ Remoting Java client，除非项目明确决定引入 RocketMQ Proxy。
  Rationale: 当前项目设计与配置使用 `namesrv-addr`，Docker 目标是 NameServer + Broker。gRPC Java SDK 对新 RocketMQ 5.x 应用更清爽，但需要 Proxy；加入 Proxy 会把基础设施变更扩大到现有设计之外。若后续执行者选择 gRPC，必须先修订本计划，并在 Docker Compose 中加入 Proxy，同时改用 `pixflow.mq.endpoint` 配置。
  Date/Author: 2026-07-02 / Codex

- Decision: `ManagedListenerContainerFactory` 必须返回 PixFlow 自有的 `ManagedMessageContainer`，而不是 Spring AMQP 的 `MessageListenerContainer`。
  Rationale: 返回 Spring AMQP 类型会把旧 broker 泄漏到每个业务模块。一个只含 `start()`、`stop()`、`isRunning()` 的 PixFlow 小接口可以让业务接线保持 broker-neutral，而实现侧只保留 RocketMQ。
  Date/Author: 2026-07-02 / Codex

- Decision: 源码与运行配置最终必须通过 no-RabbitMQ 搜索门禁。
  Rationale: 这是执行用户“无旧代码残留”要求的最直接可靠手段。它能捕获 import、依赖声明、类名、Testcontainers 模块、Docker 服务与 YAML key，这些点有些不是编译测试能覆盖的。
  Date/Author: 2026-07-02 / Codex

## Outcomes & Retrospective（结果与复盘）

2026-07-03 / Codex: 已完成破坏式 RocketMQ 重构。pixflow-infra-mq 现在提供 MessageDestination、ConsumerBinding、DestinationRegistrar、ManagedMessageContainer、RocketMessageCodec、RocketMessagePublisher 与 RocketMQ listener factory；旧 RabbitMQ publisher、topology、registrar、listener container 与 Spring AMQP 依赖已删除。file、vision、task、commerce 模块均改用 topic/tag/consumer group 目的地，Docker Compose 与 dev YAML 改为 RocketMQ NameServer + Broker。验证通过 mvn -pl pixflow-infra-mq -am test、mvn -pl pixflow-module-file -am test、mvn -pl pixflow-module-vision -am test、mvn -pl pixflow-module-task -am test、mvn -pl pixflow-module-commerce -am test 与 mvn -pl pixflow-app -am -DskipTests package。源码/运行配置 no-legacy 搜索没有 RabbitMQ、Spring AMQP、QueueTopology 等旧实现词汇残留；旧路由词搜索只命中 thirdparty HTTP invoker 的 xchange() 方法，与 MQ 无关。

## Context and Orientation（上下文与导航）

仓库根目录是 `D:\study\PixFlow`。项目是 Spring Boot 3.5 多模块 Maven 应用。现有 RabbitMQ 实现不是单个类中的孤立实现；它分布在 MQ 基础设施模块、业务模块自动配置、测试、本地 Docker Compose 与开发 YAML 中。

`infra/mq` 是消息传输基础设施模块。它的包名是 `com.pixflow.infra.mq`，Maven 模块是 `pixflow-infra-mq`。该模块必须保持领域无关：它不应该知道任务、文件、视觉或 commerce。它应提供通用发布、消费绑定、消息信封、重试决策、trace 透传与指标。

当前代码仍有 RabbitMQ 形态的公共 API：

    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/PublishRequest.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/PublishResult.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/RabbitMessagePublisher.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/QueueTopology.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/QueueTopologyBuilder.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/RabbitTopologyRegistrar.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedListenerContainerFactory.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedMessageListener.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/RabbitManagedListenerContainerFactory.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/config/MqAutoConfiguration.java

该模块的主要业务消费者是：

    pixflow-module-file/src/main/java/com/pixflow/module/file/ingest/ExtractionTopology.java
    pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java
    pixflow-module-vision/src/main/java/com/pixflow/module/vision/enrich/CopyEnrichmentTopology.java
    pixflow-module-vision/src/main/java/com/pixflow/module/vision/config/VisionServiceAutoConfiguration.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/infra/mq/TaskMessagePublisher.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/infra/mq/TaskMessageListener.java
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/importjob/CommerceImportTopology.java
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java

需要更新的本地运行文件是：

    docker-compose.yml
    pixflow-app/src/main/resources/application-dev.yml

本计划中的术语含义如下：

`Topic` 是 RocketMQ 中命名的消息流。PixFlow topic 应该是较宽的业务通道，例如 `pixflow-task`、`pixflow-file`、`pixflow-vision` 与 `pixflow-commerce`。

`Tag` 是 topic 内的 RocketMQ 消息标签。PixFlow 用 tag 表示消息类型，例如 `TASK_EXECUTE`、`PACKAGE_EXTRACT`、`COPY_ENRICH` 与 `COMMERCE_IMPORT`。

`Keys` 是附加在 RocketMQ 消息上的业务排障字符串，例如 `task:123` 或 `package:456`。它们不作为 Micrometer tag 使用。

`Consumer group` 是一类消费者的 RocketMQ 负载均衡组，例如 `pixflow-task-worker`。

`At-least-once` 表示消息可能被投递多次，但 broker 接收后不应静默丢失。业务 consumer 必须保持幂等。

`DLQ` 表示死信队列。在 RocketMQ 中，消息在重试耗尽后可以进入 consumer group 的死信路径。业务模块自行决定观察到 DLQ 条件后如何把自己的事实记录标为失败。

## Reference Document Search Keywords（参考文档检索关键词）

在 `D:\study\PixFlow` 下使用以下命令定位本计划实现的设计文本。这些关键词是后续执行者应输入到 `rg -n` 中的准确检索词。

`docs/design-docs/infra/mq.md`：

- 目标设计与无 RabbitMQ 公共模型：`Broker 语义显式化`、`topic + tag + keys + consumerGroup`、`exchange / queue / routing key`
- 模块结构：`模块结构与依赖位置`、`RocketMessagePublisher`、`ConsumerBinding`、`ManagedMessageContainer`、`RocketMessageCodec`
- 可靠发布机制：`可靠投递`、`SendResult`、`恢复扫描`
- 消费确认模型：`接管成功即确认`、`process-then-ack`、`ack`
- 重试与 DLQ：`分层重试`、`RECONSUME_LATER`、`DeadLetter`、`AckDrop`
- trace 透传：`traceId 透传`、`x-trace-id`
- 配置：`namesrv-addr`、`consumerGroup`、`topic-auto-create`
- 迁移约束：`迁移约束与同步点`、`Spring AMQP`、`RabbitTemplate`

`docs/design-docs/design.md`：

- 技术选型：`任务队列`、`RocketMQ`
- 总体架构：`RocketMQ│(任务分发)`
- 任务异步时序：`发布任务消息 → RocketMQ`
- 数据恢复不变量：`MySQL 是事实源`、`process_result`
- 模块边界：`infra/mq`、`RocketMQ 封装`

`docs/design-docs/module/task.md`：

- 任务目的地：`pixflow-task`、`TASK_EXECUTE`、`pixflow-task-worker`
- 确认模型：`消费成功只代表接管成功`、`接管成功`
- DLQ 行为：`DLQ 语义`、`RocketMQ consumer group DLQ`
- 发布与消费文件：`TaskMessagePublisher`、`TaskMessageConsumer`

`docs/design-docs/module/file.md`：

- 文件解压目的地：`pixflow-file`、`PACKAGE_EXTRACT`、`pixflow-file-extractor`
- 处理模型：`process-then-ack`、`投递缺口`、`PublishGapRescan`
- 重试与毒包行为：`毒包`、`DeadLetter`

`docs/design-docs/module/vision.md`：

- 视觉富化目的地：`pixflow-vision`、`COPY_ENRICH`、`pixflow-vision-enricher`
- 处理模型：`CopyEnrichmentConsumer`、`gap-fill`、`process-then-ack`

`docs/design-docs/module/conversation.md`：

- 待同步的旧文案：`RabbitMQ`、`Redis pub/sub / RabbitMQ topic`、`入 RabbitMQ`

`docs/design-docs/base/common.md`：

- 待同步的旧文案：`Spring AMQP`、`RabbitMQ`、`traceId`

`docs/design-docs/api.md`：

- 上传 complete 流程旧文案：`发 RabbitMQ 解压消息`

源码树：

- 旧实现搜索：`Rabbit|rabbit|AMQP|Amqp|amqp|RabbitTemplate|RabbitAdmin|RabbitListener|spring-boot-starter-amqp|MessageListenerContainer|QueueTopology`
- 旧路由模型搜索：`exchange|routingKey|routing-key|queue|prefetch`
- 迁移后的 RocketMQ 实现搜索：`RocketMessagePublisher|ConsumerBinding|MessageDestination|RocketManagedMessageContainer`

## Plan of Work（工作计划）

里程碑 1 替换 MQ 依赖集合。编辑 `pixflow-infra-mq/pom.xml`，删除 `spring-boot-starter-amqp`、`com.rabbitmq:amqp-client` 与 `org.testcontainers:rabbitmq`。加入本项目选定的 RocketMQ client 依赖。由于当前设计使用 `namesrv-addr`，且 Docker Compose 应运行 NameServer + Broker，默认实现路径是 RocketMQ Remoting Java client 坐标 `org.apache.rocketmq:rocketmq-client`，版本与 Docker broker tag 对齐。在根 `pom.xml` 中加入类似 `rocketmq.version` 的 Maven property。如果后续实现改用 gRPC SDK 坐标 `org.apache.rocketmq:rocketmq-client-java`，必须先修订本计划和 Docker Compose，加入 RocketMQ Proxy，因为官方 gRPC Java SDK 需要 Proxy。

里程碑 2 重写 `pixflow-infra-mq` 公共契约。将 `PublishRequest` 从 `exchange`、`routingKey` 改为 `topic`、`tag`、`keys`、`payload`、`headers`、`schemaVersion` 与 `sendTimeout`。将 `PublishResult` 从 `exchange`、`routingKey`、`correlationId` 改为 `topic`、`tag`、`messageId`、`transactionId` 或等价 broker 结果 id、可用时的 `queueInfo`，以及 `failure`。重命名或替换 `PublishFailure` 中提到 returned exchange 或 routing key 的字段。新增 `destination/MessageDestination.java`、`destination/ConsumerBinding.java` 与 `destination/DestinationRegistrar.java`。删除 `topology/QueueTopology.java`、`QueueTopologyBuilder.java` 与 `RabbitTopologyRegistrar.java`；不要留下 deprecated adapter。

里程碑 3 实现 RocketMQ 发布与编码。删除 `RabbitMessagePublisher.java`，新增 `rocket/RocketMessagePublisher.java`。publisher 使用 topic、tag、keys、JSON body、schema version 与来自 `headers` 的 user properties 构造 RocketMQ message。它同步调用 RocketMQ producer，并把成功映射为 `PublishResult.confirmed`。它把超时、序列化失败、topic 校验失败与 broker 发送失败映射为带 `MqErrorCode` 与脱敏原因的 `PublishResult.failed`。现有 `MessageEnvelope` 继续作为 JSON 外层形状，但 `RocketMessageCodec` 负责字节转换与 user-property 映射。

里程碑 4 实现不含 Spring AMQP 类型的 RocketMQ 消费。定义 `consumer/ManagedMessageContainer.java`，包含 `start()`、`stop()` 与 `isRunning()`。把 `ManagedListenerContainerFactory.create(...)` 改为接收 `ConsumerBinding`、payload type、`ManagedMessageHandler<?>` 与 `ConsumerErrorHandler`，并返回 `ManagedMessageContainer`。从该接口删除 Spring AMQP 的 `MessageListenerContainer` import。新增 `rocket/RocketManagedMessageContainer.java` 与 `rocket/RocketManagedListenerContainerFactory.java`。container 在 `binding.consumerGroup()` 下订阅 `binding.topic()` 与 `binding.tagExpression()`，重建 trace context，解码 `MessageEnvelope<T>`，调用 handler，并把 handler 结果映射为 RocketMQ consume success 或 reconsume later。

里程碑 5 以 RocketMQ 语义保留 PixFlow 的重试语义。保留 `ConsumerErrorHandler`、`ManagedMessageHandler` 与 `RetryDecision`，但移除 Rabbit 专属的 retry exchange 与 DLX 转发。对于 `RetryDecision.Retry`，默认使用 RocketMQ broker reconsume。如果实现 explicit-delay retry，则向同一 topic/tag 发布一条新的延迟消息，递增 retry headers，并且只有在延迟发布确认后才确认当前消息。对于 `RetryDecision.DeadLetter`，按所选 RocketMQ client API 进入 RocketMQ 终态路径，或发布一条脱敏 dead-letter 记录，并确保业务模块仍把自己的事实记录标为失败。对于 `RetryDecision.AckDrop`，记录日志与指标后成功消费。

里程碑 6 重写 MQ 配置。用以下配置替换 `MqProperties` 中的 publish confirm timeout、prefetch、exchange、queue、routing key 等字段：

    pixflow.mq.namesrv-addr
    pixflow.mq.producer-group
    pixflow.mq.send-timeout
    pixflow.mq.consumer.consume-thread-min
    pixflow.mq.consumer.consume-thread-max
    pixflow.mq.consumer.consume-timeout
    pixflow.mq.in-process-retries
    pixflow.mq.max-retries
    pixflow.mq.retry-backoff
    pixflow.mq.retry-mode
    pixflow.mq.dlq-alert-threshold
    pixflow.mq.topic-auto-create

更新 `MqAutoConfiguration`，使其构造 RocketMQ producer、registrar、listener factory、trace propagator 与 metrics。它不得 import 或暴露任何 `org.springframework.amqp`、`com.rabbitmq`、`RabbitTemplate`、`RabbitAdmin`、`ConnectionFactory` 或 `Jackson2JsonMessageConverter`。

里程碑 7 更新业务目的地。在 `pixflow-module-file` 中，用 `ExtractionDestination` 或 `ExtractionBinding` 替换 `ExtractionTopology`，返回 topic 为 `pixflow-file`、tag 为 `PACKAGE_EXTRACT`、group 为 `pixflow-file-extractor`、key 为 `package:{packageId}` 的 `MessageDestination` / `ConsumerBinding`。更新 `ExtractionPublisher`，使其调用 `PublishRequest.ofTopicTag(...)` 或最终等价方法。更新 `FileAutoConfiguration`，使 `fileExtractionListenerContainer` 返回 `ManagedMessageContainer`，而不是 Spring AMQP 的 `MessageListenerContainer`。

里程碑 8 更新 vision 富化作业。用 RocketMQ 目的地声明替换 `CopyEnrichmentTopology`：topic `pixflow-vision`、tag `COPY_ENRICH`、group `pixflow-vision-enricher`、key `package:{packageId}`。更新 `VisionServiceAutoConfiguration`，使其使用 `ConsumerBinding` 与 `ManagedMessageContainer`。把 `CopyEnrichmentTopologyTest` 改为 destination / binding 测试，断言 topic、tag、group 与 keys。

里程碑 9 更新 task 分发。把 `TaskProperties.Mq.exchange`、`routingKey`、`queue` 替换为 `topic`、`tag`、`consumerGroup` 与 `sendTimeout`，默认值分别为 `pixflow-task`、`TASK_EXECUTE` 与 `pixflow-task-worker`。替换 `TaskMessagePublisher`，使其用 key `task:{taskId}` 发布 `TaskMessage`。删除 `TaskMessageListener` 中的 `@RabbitListener`；可以在 task auto-configuration 中注册 `ManagedMessageContainer`，也可以实现一个通过 `ManagedListenerContainerFactory` 接线的 `TaskMessageConsumer`。

里程碑 10 更新 commerce 导入，因为当前源码使用旧 MQ topology。用 RocketMQ 目的地替换 `CommerceImportTopology`：topic `pixflow-commerce`、tag `COMMERCE_IMPORT`、group `pixflow-commerce-importer`、key `import:{jobId}`。更新 `CommerceAutoConfiguration`，移除 Spring AMQP listener container 类型。如果活跃业务设计仍缺少这条依赖，应更新相关设计文本，说明 commerce 外部 API 导入通过 `infra/mq` 使用 RocketMQ。

里程碑 11 替换本地运行基础设施。编辑 `docker-compose.yml`，删除 RabbitMQ service 与 `rabbitmq_data` volume。加入 RocketMQ NameServer 与 Broker service。Broker 必须依赖 NameServer，并暴露所选 RocketMQ client 使用的 NameServer 和 broker 端口。如果需要 broker 配置文件，将它提交到仓库路径，例如 `docker/rocketmq/broker.conf`，并让 compose service 挂载它。不要把 RabbitMQ service 以注释形式保留为 fallback。更新 `pixflow-app/src/main/resources/application-dev.yml`，删除 `spring.rabbitmq.*`，并把旧的 `pixflow.mq.publish-confirm-timeout`、`prefetch`、`consumer-concurrency` 替换为 `pixflow.mq` 下的 RocketMQ 字段。

里程碑 12 重写测试。删除或重写 RabbitMQ Testcontainers 测试。`RabbitMqIntegrationTest` 应改为 `RocketMqIntegrationTest`，或在 Docker 不可用时用模块级集成测试和确定性 fake producer / consumer 替代。单元测试应覆盖 `PublishRequest` 校验、`MessageDestination` 校验、`ConsumerBinding` 校验、`RocketMessageCodec`、重试决策映射、metrics 与 trace header 透传。业务模块测试应断言 file、vision、task、commerce 预期的 RocketMQ topic/tag/group。

里程碑 13 同步活跃文档。更新仍把 RabbitMQ 描述为当前行为的未归档活跃设计文档：

    docs/design-docs/api.md
    docs/design-docs/base/common.md
    docs/design-docs/harness/state.md
    docs/design-docs/module/conversation.md
    docs/design-docs/infra/ai.md
    docs/design-docs/infra/thirdparty.md

除非某个活跃计划明确把已完成计划作为当前实现指令，否则不要重写 completed plans。已完成计划是历史记录，因此可以保留 RabbitMQ 表述。在活跃文档中，只保留明确的迁移历史说明；当前实现指令必须写 RocketMQ。

里程碑 14 增加无旧代码守护。新增测试或构建检查，扫描源码和运行配置中的禁用词。守护范围应覆盖 `pom.xml`、`docker-compose.yml`、`pixflow-* /src/main`、活跃设计文档与 app resources。可以排除 `docs/design-docs/exec-plans/completed`、本文，以及不会指导实现的明确迁移历史说明。禁用实现词如下：

    RabbitMQ
    Rabbit
    rabbit
    AMQP
    Amqp
    amqp
    RabbitTemplate
    RabbitAdmin
    RabbitListener
    spring-boot-starter-amqp
    MessageListenerContainer
    QueueTopology

还要扫描源码 API 中的旧路由词汇：

    exchange
    routingKey
    routing-key
    prefetch

如果 `exchange` 出现在无关 HTTP/client 代码中，例如第三方 HTTP method 名称，应在守护测试中记录允许的文件和原因。不要宽泛地白名单该词。

## Concrete Steps（具体步骤）

从 `D:\study\PixFlow` 运行以下命令。

首先建立变更前基线：

    rg -n "Rabbit|rabbit|AMQP|Amqp|amqp|RabbitTemplate|RabbitAdmin|RabbitListener|spring-boot-starter-amqp|MessageListenerContainer|QueueTopology|exchange|routingKey|routing-key|prefetch" docker-compose.yml pom.xml pixflow-* docs/design-docs

实现前的预期结果：应能在 `Context and Orientation` 列出的文件中看到匹配。

然后更新 `pixflow-infra-mq`：

    # 用 apply_patch 或 IDE 编辑
    pixflow-infra-mq/pom.xml
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/PublishRequest.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/PublishResult.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/PublishFailure.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/config/MqProperties.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/config/MqAutoConfiguration.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedListenerContainerFactory.java
    pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedMessageListener.java

删除旧 RabbitMQ 专属文件，并以 RocketMQ 文件替代：

    Delete:
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/RabbitMessagePublisher.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/QueueTopology.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/QueueTopologyBuilder.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/topology/RabbitTopologyRegistrar.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/RabbitManagedListenerContainerFactory.java

    Add:
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/destination/MessageDestination.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/destination/ConsumerBinding.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/destination/DestinationRegistrar.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/consumer/ManagedMessageContainer.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/rocket/RocketMessagePublisher.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/rocket/RocketManagedMessageContainer.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/rocket/RocketManagedListenerContainerFactory.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/rocket/RocketDestinationRegistrar.java
      pixflow-infra-mq/src/main/java/com/pixflow/infra/mq/rocket/RocketMessageCodec.java

更新业务模块：

    pixflow-module-file/src/main/java/com/pixflow/module/file/ingest/ExtractionTopology.java
    pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java
    pixflow-module-vision/src/main/java/com/pixflow/module/vision/enrich/CopyEnrichmentTopology.java
    pixflow-module-vision/src/main/java/com/pixflow/module/vision/config/VisionServiceAutoConfiguration.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/config/TaskProperties.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/infra/mq/TaskMessagePublisher.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/infra/mq/TaskMessageListener.java
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/importjob/CommerceImportTopology.java
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java

更新本地运行环境：

    docker-compose.yml
    pixflow-app/src/main/resources/application-dev.yml

增量运行编译和模块测试：

    mvn -pl pixflow-infra-mq -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-vision -am test
    mvn -pl pixflow-module-task -am test
    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-app -am -DskipTests package

如果 Docker 可用且已实现 RocketMQ 集成测试：

    docker compose up -d rocketmq-namesrv rocketmq-broker
    mvn -pl pixflow-infra-mq -am -Pwindows-docker-npipe test

最后运行 no-legacy 源码 / 配置门禁：

    rg -n "Rabbit|rabbit|AMQP|Amqp|amqp|RabbitTemplate|RabbitAdmin|RabbitListener|spring-boot-starter-amqp|MessageListenerContainer|QueueTopology" pom.xml docker-compose.yml pixflow-* docs/design-docs

实现后的预期结果：`pom.xml`、`docker-compose.yml`、`pixflow-*` 或活跃设计正文中不应有匹配。匹配只能保留在 `docs/design-docs/exec-plans/completed`、本文，以及不会指导实现的明确迁移历史说明中。

运行旧路由词汇门禁：

    rg -n "exchange|routingKey|routing-key|prefetch" pixflow-infra-mq pixflow-module-file pixflow-module-vision pixflow-module-task pixflow-module-commerce pixflow-app

实现后的预期结果：除非守护测试已记录无关 HTTP/client 名称，否则不应有匹配。MQ API 不得使用这些词。

## Validation and Acceptance（验证与验收）

只有以下可观察行为和检查都通过时，才接受本次重构。

构建必须通过：

    mvn -pl pixflow-infra-mq -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-vision -am test
    mvn -pl pixflow-module-task -am test
    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-app -am -DskipTests package

发布通过新 API 工作：测试构造一个 `PublishRequest`，topic 为 `pixflow-task`，tag 为 `TASK_EXECUTE`，key 为 `task:1`，payload 为小对象。fake 或真实 RocketMQ publisher 返回带非空 message id 的 `PublishResult.confirmed`。测试中不得提到 exchange 或 routing key。

消费通过新 API 工作：测试注册一个 `ConsumerBinding`，topic 为 `pixflow-file`，tag expression 为 `PACKAGE_EXTRACT`，group 为 `pixflow-file-extractor`，payload type 为 `ExtractionMessage`。一条消息被解码为 `MessageEnvelope<ExtractionMessage>`，handler 运行一次，container 返回 consume success。

重试决策映射正确：`RetryDecision.Retry` 导致 broker reconsume 或确认过的延迟重投；`RetryDecision.DeadLetter` 到达终态失败路径；`RetryDecision.AckDrop` 不触发 retry 且消费成功。

Trace 透传工作：发布侧 trace id 写入 RocketMQ user properties，消费侧 handler 能从 MDC 或 trace propagation context 读到同一个 trace id。

业务目的地正确：

    file: topic pixflow-file, tag PACKAGE_EXTRACT, group pixflow-file-extractor
    vision: topic pixflow-vision, tag COPY_ENRICH, group pixflow-vision-enricher
    task: topic pixflow-task, tag TASK_EXECUTE, group pixflow-task-worker
    commerce: topic pixflow-commerce, tag COMMERCE_IMPORT, group pixflow-commerce-importer

本地运行环境不再包含 RabbitMQ：

    docker compose config

渲染后的 compose config 必须包含 RocketMQ NameServer 与 Broker service，且不得包含 RabbitMQ service 或 `rabbitmq_data` volume。

no-legacy 门禁通过。任何生产源码、测试源码、Maven POM、Docker Compose 文件或 application YAML 都不得 import、依赖、实例化或配置 RabbitMQ / Spring AMQP。项目不得因为某个直接模块依赖意外拉入 `spring-boot-starter-amqp` 而仍能编译。

## Idempotence and Recovery（幂等与恢复）

本计划的实现步骤应可重复执行。重复运行 Maven 测试不应需要手动删除 RabbitMQ 容器或 volume，因为项目运行时不应再存在这些对象。

如果重构只应用了一半且编译失败，使用 no-legacy 搜索门禁定位仍保留 RabbitMQ 形状公共 API 的位置，然后迁移调用方，不要重新引入兼容 overload。不要添加从 `QueueTopology` 到 `ConsumerBinding` 的临时 adapter；应删除旧 API 并修复每个调用点。

如果 RocketMQ 集成测试在 Windows Docker 上不稳定，纯单元测试与 fake-client 测试仍必须保留为强制项；Docker 支持的 RocketMQ 测试应沿用现有 Testcontainers skip 策略。不要把 RabbitMQ 测试加回来作为替代。

如果选定的 RocketMQ client 版本不支持某个期望的 helper API，只能在 `pixflow-infra-mq/rocket/*` 内部适配。业务模块不得直接 import RocketMQ client 类。

如果 Docker Compose 启动 RocketMQ 需要 broker 配置文件，将其创建在仓库内路径，例如 `docker/rocketmq/broker.conf`。不要要求开发者进入运行中的容器编辑文件。

## Artifacts and Notes（产物与备注）

最重要的预期文件转换是：

    RabbitMessagePublisher.java                    -> RocketMessagePublisher.java
    QueueTopology.java / QueueTopologyBuilder.java -> MessageDestination.java / ConsumerBinding.java
    RabbitTopologyRegistrar.java                   -> RocketDestinationRegistrar.java
    RabbitManagedListenerContainerFactory.java     -> RocketManagedListenerContainerFactory.java
    org.springframework.amqp MessageListenerContainer -> com.pixflow.infra.mq.consumer.ManagedMessageContainer
    spring.rabbitmq.* application config           -> pixflow.mq.namesrv-addr and RocketMQ client config
    rabbitmq docker-compose service                -> rocketmq-namesrv + rocketmq-broker services

本计划有意不要求兼容期。兼容期会让旧 RabbitMQ 类或旧 exchange / queue 语义继续存在，这不符合用户要求的重构。

编写本计划时参考的 Apache RocketMQ 官方文档说明，RocketMQ 5.x 有两个 Java client 家族：Remoting 协议的 `rocketmq-client`，以及 gRPC 协议的 `rocketmq-client-java`。文档还说明 gRPC Java SDK 要求 RocketMQ 5.0+ 且启用 Proxy。本计划选择 Remoting client 路径，因为 PixFlow 当前设计使用基于 NameServer 的配置。

## Interfaces and Dependencies（接口与依赖）

重构结束时，`PublishRequest` 应具有等价于以下的形状：

    public record PublishRequest(
        String topic,
        String tag,
        List<String> keys,
        Object payload,
        Map<String, Object> headers,
        int schemaVersion,
        Duration sendTimeout
    )

提供类似 `PublishRequest.of(String topic, String tag, Object payload)` 与 `withKey(String key)` 的便捷构造方法。不要提供 `of(exchange, routingKey, payload)`。

`PublishResult` 应具有等价于以下的形状：

    public record PublishResult(
        boolean confirmed,
        PublishFailure failure,
        String topic,
        String tag,
        String messageId,
        String queueInfo
    )

`MessageDestination` 应是值对象：

    public record MessageDestination(
        String topic,
        String tag,
        List<String> keys
    )

`ConsumerBinding` 应是值对象：

    public record ConsumerBinding(
        String topic,
        String tagExpression,
        String consumerGroup,
        Class<?> payloadType
    )

`ManagedMessageContainer` 应由 PixFlow 自己拥有：

    public interface ManagedMessageContainer {
        void start();
        void stop();
        boolean isRunning();
    }

`ManagedListenerContainerFactory` 只应暴露 PixFlow 类型和 JDK 类型：

    ManagedMessageContainer create(
        ConsumerBinding binding,
        ManagedMessageHandler<?> handler,
        ConsumerErrorHandler errorHandler
    );

业务目的地声明应稳定且易于发现：

    ExtractionDestination.binding()          -> pixflow-file / PACKAGE_EXTRACT / pixflow-file-extractor
    CopyEnrichmentDestination.binding()      -> pixflow-vision / COPY_ENRICH / pixflow-vision-enricher
    TaskMessageDestination.binding()         -> pixflow-task / TASK_EXECUTE / pixflow-task-worker
    CommerceImportDestination.binding()      -> pixflow-commerce / COMMERCE_IMPORT / pixflow-commerce-importer

`pixflow-infra-mq` 之外的任何类不得 import `org.apache.rocketmq..`。业务模块只能依赖 PixFlow MQ 抽象。

## Revision Notes（修订说明）

2026-07-02 / Codex: 初次创建本 ExecPlan。创建前已阅读活跃执行计划与 MQ 相关设计文档。本计划按重构导向编写：要求删除 RabbitMQ / Spring AMQP 代码，把公共 MQ 模型替换为 RocketMQ topic/tag/keys/consumer-group 语义，迁移所有已知业务调用方，替换本地运行基础设施，同步活跃文档，并增加基于搜索的 no-legacy 守护。

2026-07-03 / Codex: 将计划全文翻译为中文，同时保留 ExecPlan 必需英文段名、路径、命令、类名与接口名，确保符合 `PLANS.md` 的 living document 结构并便于直接执行。
2026-07-03 / Codex: 执行并完成 RabbitMQ 到 RocketMQ 的破坏式重构，更新 living sections 记录已完成里程碑、实现中发现的依赖变化、Docker 不可用时的验证边界，以及 Maven/搜索门禁验证结果。

