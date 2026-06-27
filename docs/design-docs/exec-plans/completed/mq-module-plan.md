# infra/mq 模块实现计划（Wave 1 基础设施）

本 ExecPlan 必须按照仓库根目录的 [PLANS.md](../../../PLANS.md) 维护；它是活文档，后续在实现、验证、发现新约束时都要同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`。

## Purpose / Big Picture

`infra/mq` 是 PixFlow 异步任务链路的消息传输底座。实现完成后，后续的 `module/task` 可以把“创建后的任务”可靠地投递到 RabbitMQ，由后台 worker 消费并接管执行，而不需要在业务模块里重复处理 RabbitMQ 连接、确认、手动 ack、重试、死信队列、traceId 透传和指标打点这些底层细节。

从用户视角看，这一步完成后，用户确认执行 DAG 时，前端可以很快拿到 `taskId`，任务在后台继续处理；如果消息发布失败、worker 接管失败、网络短暂抖动或消费者重启，系统也有明确的补偿和重试路径，不会因为一次 MQ 异常让任务无声丢失。这个模块本身不直接处理图片，也不认识任务业务字段；它提供可验证的消息设施，让 Wave 4 的 `module/task` 能安全复用。

## Progress

- [x] (2026-06-27) 已阅读 `PLANS.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/design.md`、`docs/design-docs/module/mq.md`、`docs/design-docs/module/common.md`，并完成本计划初稿。
- [x] (2026-06-27) 参考 `docs/design-docs/exec-plans/completed/permission-module-implementation-plan.md` 的接口描述方式，补强 MQ 对外 API 契约、方法签名、业务侧调用方式和测试夹具接口。
- [x] (2026-06-27) 实现 `infra/mq` 包骨架，保持只依赖 `common`、Spring AMQP、RabbitMQ client、Micrometer，不依赖任何业务模块。
- [x] (2026-06-27) 实现 MQ 配置绑定与自动配置，包括 JSON converter、mandatory `RabbitTemplate`、`RabbitAdmin`、手动 ack listener container、prefetch 和消费者并发；publisher confirms/returns 的真实 broker 行为仍需集成测试验证。
- [x] (2026-06-27) 实现领域无关的拓扑声明模型，包括主 exchange、主 queue、retry queue、DLX、DLQ 和绑定关系。
- [x] (2026-06-27) 实现可靠发布入口 `MessagePublisher` 与投递结果 `PublishResult`，代码路径已覆盖 confirm、return、timeout、broker 异常和序列化异常的结果映射。
- [x] (2026-06-27) 实现 `MessageEnvelope`、消息 header、schemaVersion 校验和 Jackson JSON 序列化入口。
- [x] (2026-06-27) 实现消费侧 `ManagedMessageListener`、`ConsumerErrorHandler`、`RetryDecision` 和 retry/DLQ 路由机制；转发成功后才 ack 原消息的顺序已写入实现注释。
- [x] (2026-06-27) 实现 traceId 发布注入和消费重建，当前实现基于 MDC 的 `traceId`，后续可替换或扩展 Micrometer Tracing span。
- [x] (2026-06-27) 实现 MQ 指标与错误码，并用单元测试覆盖错误码分类、指标计数、重试 header、拓扑 builder 和 MDC trace 透传。
- [ ] 使用 Testcontainers RabbitMQ 覆盖拓扑声明、可靠发布、ack-then-process、retry queue、DLQ、trace header 和未知 schemaVersion 行为。

## Surprises & Discoveries

- 观察：当前完整重写架构和 `.kiro/specs/pixflow/design.md` 的 MVP 同步执行方案不同，MQ 计划必须以 `docs/design-docs/design.md` 和 `docs/design-docs/module/mq.md` 为准。  
  证据：`docs/design-docs/design.md` 第九章和第十四章定义了 RabbitMQ 异步任务分发；`.kiro/specs/pixflow/design.md` 仍描述 MVP 的同步阻塞执行。
- 观察：`infra/mq` 不是“任务队列模块”，而是“领域无关消息设施”。  
  证据：`docs/design-docs/module/mq.md` 明确写到本模块只依赖 `common`，不依赖任何业务模块，任务语义属于 Wave 4 的 `module/task`。
- 观察：`common.md` 里的 MQ 投递判定示例提到 “nack 重投”，但 `mq.md` 的重试队列设计要求 per-message TTL 和 `x-retry-count`。  
  证据：`docs/design-docs/module/mq.md` 第七章要求由 `ManagedMessageListener` 重新发布到 `retry.q` 并带 TTL；因此本计划选择“发布到 retry/DLQ 后 ack 原消息”的实现方式，避免 nack 自动死信无法携带精细 TTL 与原因 header。
- 观察：当前项目已经引入 `spring-boot-starter-amqp`，无需为 MQ 主代码新增 Maven 依赖；但仓库已有 storage 集成测试通过环境变量控制跳过，MQ 的真实 RabbitMQ 行为也应采用同样的可选集成测试策略。  
  证据：`mvn test` 输出中 `MinioObjectStorageIntegrationTest` skipped 2，新增 MQ 单元测试和已有测试共 41 个运行、0 失败、2 跳过。
- 观察：Spring AMQP 的 confirm 与 mandatory return API 能在当前 Spring Boot 3.3.0 依赖下编译通过，但单元测试无法证明 broker 实际 confirm/return 顺序。  
  证据：`mvn -q -DskipTests compile` 与 `mvn test` 均成功；Testcontainers RabbitMQ 验收项仍保持未完成。

## Decision Log

- Decision: `infra/mq` 不内建 `TaskMessage`、`task.exchange`、`process_task` 或任何 DAG / 素材包概念。  
  Rationale: 执行计划把 `infra/mq` 放在 Wave 1，仅依赖 `common`；`module/task` 在 Wave 4 才依赖 `mq + cache + dag + storage + state`。如果 MQ 反向依赖 task，会破坏模块依赖 DAG。  
  Date/Author: 2026-06-27 / Codex
- Decision: 可靠发布采用 Publisher Confirms + mandatory returns + 同步等待 confirm，不实现事务性 outbox。  
  Rationale: 设计明确选择“以 MySQL 为事实源 + 恢复扫描补发”闭合投递缺口；消息量低，同步等待 confirm 的复杂度和性能代价都可接受。  
  Date/Author: 2026-06-27 / Codex
- Decision: 延迟重试采用 RabbitMQ 原生 TTL + DLX，不依赖 `rabbitmq_delayed_message_exchange` 插件。  
  Rationale: 这样 Docker Compose 单节点 RabbitMQ 即可运行，降低本期运维依赖；递增退避通过 per-message expiration 实现。  
  Date/Author: 2026-06-27 / Codex
- Decision: 消费侧采用 ack-then-process 语义，MQ 模块只要求业务 handler “接管成功后返回”。  
  Rationale: 单个任务会触发长时间图片批处理，process-then-ack 会占住消费线程并可能触发 RabbitMQ consumer timeout；接管后 ack，再由 task 的断点恢复兜底，更符合总体设计。  
  Date/Author: 2026-06-27 / Codex
- Decision: `RetryDecision.Retry`、`DeadLetter`、`AckDrop` 的执行方式采用“发布到目标队列/交换机后 ack 原消息”，而不是依赖原消息 nack。  
  Rationale: 重新发布可以设置 `x-retry-count`、失败原因、原始路由键、per-message TTL，并保持重试与终态 DLQ 可观测。  
  Date/Author: 2026-06-27 / Codex
- Decision: 未知 `schemaVersion` 不让消费者崩溃，也不进入业务 handler，而是直接进入终态 DLQ。  
  Rationale: 消息版本不兼容不是业务可恢复异常；进入 DLQ 可以保留原始 payload 供人工排查或手动重投。  
  Date/Author: 2026-06-27 / Codex
- Decision: MQ 的对外 API 以 `PublishRequest`、`PublishResult`、`QueueTopology`、`ManagedMessageHandler`、`ConsumerErrorHandler` 这些稳定接口为中心，而不是让业务模块直接操作 `RabbitTemplate`、`Channel` 或 Spring AMQP listener annotation。  
  Rationale: 这样能把 RabbitMQ 的 confirm、return、ack、retry、DLQ 和 trace 细节封装在 `infra/mq`，后续 `module/task` 只表达业务拓扑、消息体和失败判定。  
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

本计划完成后，`infra/mq` 应当成为后续异步能力的稳定边界。`module/task` 只需要声明自己的队列拓扑、发布一个包含 `taskId` 的小消息、实现接管 handler 和错误判定 SPI，就能得到可靠发布、手动 ack、延迟重试、DLQ、traceId 和指标。

2026-06-27 / Codex: 已完成 `com.pixflow.infra.mq` 的核心代码骨架和不依赖外部 broker 的单元契约验证。新增能力包括领域无关发布 API、消息信封、拓扑 builder/registrar、MDC trace header 透传、retry header 集中读写、手动 ack listener 包装、retry/DLQ 转发路径、配置属性、自动装配、MQ 错误码和 Micrometer 指标封装。已运行 `mvn test`，结果为 41 tests run、0 failures、0 errors、2 skipped；跳过项为既有 MinIO 集成测试。仍未完成 Testcontainers RabbitMQ 集成测试，因此真实 broker 的 exchange/queue 声明、publisher confirm/return、TTL 回流、DLQ 入队和未知 schemaVersion 进 DLQ 仍需后续验收。

## Context and Orientation

当前仓库的执行计划在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`。其中 Wave 1 包含 `infra/mq`，Wave 4 才包含 `module/task`。这意味着 MQ 模块先实现“通用消息设施”，而不是先实现任务业务。`infra/mq` 的权威设计在 `docs/design-docs/module/mq.md`，错误模型和 traceId 约定在 `docs/design-docs/module/common.md`，总架构和异步任务时序在 `docs/design-docs/design.md`。

这里先定义本计划会用到的术语。RabbitMQ 是消息代理，负责接收生产者发布的消息，并把消息分发给消费者。exchange 是消息入口，按照 routing key 把消息路由到 queue。queue 是消息排队的地方，消费者从 queue 取消息。ack 是消费者告诉 RabbitMQ “这条消息已经处理，可以删除”。DLX 是 dead-letter exchange，意思是死信交换机；消息失败或过期后会被路由到这里。DLQ 是 dead-letter queue，意思是死信队列；终态失败、无法自动恢复的消息最终停在这里供人工排查。TTL 是 time to live，意思是消息存活时间；本计划用 retry queue 中每条消息自己的 TTL 实现延迟重试。publisher confirm 是 RabbitMQ broker 对发布者的确认，告诉发布者消息是否被 broker 接收。mandatory return 是当消息无法路由到任何队列时，RabbitMQ 把消息退回给发布者。prefetch 是消费者一次最多预取多少条消息；本项目默认 1，保证公平分发。

为了快速定位参考设计，建议在对应文档中搜索下面的关键词。

在 `docs/design-docs/module/mq.md` 中，优先搜索：

- `领域无关`
- `为什么 infra/mq 必须领域无关`
- `模块结构与依赖位置`
- `QueueTopology`
- `TopologyRegistrar`
- `拓扑模型`
- `Publisher Confirms`
- `submit_dag 投递缺口的闭合`
- `ack-then-process`
- `分层重试与 DLQ`
- `ConsumerErrorHandler`
- `RetryDecision`
- `TTL + DLX`
- `traceId 透传`
- `MessageEnvelope`
- `schemaVersion`
- `prefetch`
- `可观测`
- `测试策略`

在 `docs/design-docs/module/common.md` 中，优先搜索：

- `RecoveryHint`
- `ErrorCategory`
- `ErrorNormalizer`
- `MQ 投递判定`
- `traceId：Micrometer Tracing + 异步边界手动透传`
- `Sanitizer`
- `错误码目录约定`
- `对其他模块的契约`

在 `docs/design-docs/design.md` 中，优先搜索：

- `RabbitMQ`
- `异步任务分发`
- `断点恢复与失败隔离`
- `异步执行时序`
- `prefetch 公平分发`
- `process_task`
- `process_result`
- `以 MySQL 为事实源`

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中，优先搜索：

- `Wave 1 基础设施`
- `infra/mq`
- `Wave 4 主循环 + 编排模块`
- `module/task`
- `模块依赖 DAG`
- `关键路径`

## API Contracts

本节是实现时必须优先保持稳定的 Java API 契约。这里的 API 不是 HTTP REST API，而是后端模块之间的 Java 调用边界。`infra/mq` 通过这些接口向上提供能力，业务模块不能绕过它们直接操作 `RabbitTemplate`、RabbitMQ `Channel` 或裸 listener container。

发布侧 API 由 `MessagePublisher`、`PublishRequest`、`PublishResult` 和 `MessageEnvelope` 组成。`MessagePublisher` 是唯一发布入口，负责 trace header 注入、消息信封包装、persistent 消息属性、mandatory 发布、confirm 等待和指标记录。建议接口如下：

    public interface MessagePublisher {
        PublishResult publish(PublishRequest request);
    }

    public record PublishRequest(
        String exchange,
        String routingKey,
        Object payload,
        Map<String, Object> headers,
        int schemaVersion,
        Duration confirmTimeout
    ) {
        public static PublishRequest of(String exchange, String routingKey, Object payload);
    }

    public record PublishResult(
        boolean confirmed,
        PublishFailure failure,
        String exchange,
        String routingKey,
        String correlationId
    ) {
        public static PublishResult confirmed(String exchange, String routingKey, String correlationId);
        public static PublishResult failed(String exchange, String routingKey, String correlationId, PublishFailure failure);
        public boolean failed();
    }

    public record PublishFailure(
        PublishFailureType type,
        String reason,
        Integer replyCode,
        String returnedExchange,
        String returnedRoutingKey
    ) {}

    public enum PublishFailureType {
        RETURNED,
        NACKED,
        CONFIRM_TIMEOUT,
        SERIALIZATION_FAILED,
        BROKER_UNAVAILABLE,
        UNKNOWN
    }

`PublishResult.confirmed=true` 的含义必须很窄：broker 已 confirm，且 mandatory return 没有发生。只要消息不可路由、confirm nack、等待超时、序列化失败或 broker 不可用，都必须返回 `failed`，不要让调用方通过 catch RabbitMQ 细节判断。`module/task` 后续只需要判断 `result.confirmed()`；失败时保持任务为“待执行”，交给恢复扫描补发。

消息信封 API 应保持领域无关，不允许引入 `TaskMessage`。建议如下：

    public record MessageEnvelope<T>(
        int schemaVersion,
        T payload,
        Map<String, Object> headers
    ) {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

payload 是业务模块自己的小消息。对 task 来说，未来 payload 可以是 `{ "taskId": 123 }`，但这个类型定义在 `module/task`，不在 `infra/mq`。headers 是技术 header 和少量业务 header 的容器，常用 header 名由 `RetryHeaders` 与 `TraceHeaderPropagator` 集中管理。

拓扑 API 由 `QueueTopology`、`QueueTopologyBuilder`、`TopologyRegistrar` 组成。它描述“一个业务队列组”需要声明哪些 RabbitMQ 对象，但不预置业务队列名。建议接口如下：

    public record QueueTopology(
        String exchange,
        String exchangeType,
        String queue,
        String routingKey,
        String deadLetterExchange,
        String deadLetterQueue,
        String deadLetterRoutingKey,
        String retryQueue,
        String retryRoutingKey,
        boolean retryEnabled,
        Map<String, Object> queueArguments
    ) {}

    public final class QueueTopologyBuilder {
        public static QueueTopologyBuilder direct(String exchange);
        public QueueTopologyBuilder queue(String queue);
        public QueueTopologyBuilder routingKey(String routingKey);
        public QueueTopologyBuilder deadLetter(String deadLetterExchange, String deadLetterQueue, String deadLetterRoutingKey);
        public QueueTopologyBuilder retry(String retryQueue, String retryRoutingKey);
        public QueueTopologyBuilder queueArgument(String key, Object value);
        public QueueTopology build();
    }

    public interface TopologyRegistrar {
        void register(QueueTopology topology);
    }

业务侧未来声明 task 拓扑时，应类似这样使用：

    QueueTopology topology = QueueTopologyBuilder
        .direct("pixflow.task")
        .queue("pixflow.task.q")
        .routingKey("task.submit")
        .deadLetter("pixflow.task.dlx", "pixflow.task.dlq", "task.dead")
        .retry("pixflow.task.retry.q", "task.retry")
        .build();

    topologyRegistrar.register(topology);

`TopologyRegistrar.register` 必须幂等。它可以重复声明同一个 exchange、queue、binding，但不应因为重复调用失败。主队列必须 durable，主 exchange 和 DLX 必须 durable，消息发布时必须 persistent。retry queue 不应注册消费者；它只靠消息 TTL 到期后 dead-letter 回主 exchange。

消费侧 API 由 `ManagedMessageHandler`、`ManagedMessageListener`、`ManagedListenerContainerFactory`、`ConsumerErrorHandler` 和 `RetryDecision` 组成。业务 handler 只表达“收到消息后如何接管”，不负责 ack、nack、重试队列或 DLQ。建议接口如下：

    @FunctionalInterface
    public interface ManagedMessageHandler<T> {
        void handle(MessageEnvelope<T> envelope) throws Exception;
    }

    public interface ManagedListenerContainerFactory {
        MessageListenerContainer create(
            QueueTopology topology,
            Class<?> payloadType,
            ManagedMessageHandler<?> handler,
            ConsumerErrorHandler errorHandler
        );
    }

    public interface ConsumerErrorHandler {
        RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount);
    }

    public sealed interface RetryDecision permits RetryDecision.Retry, RetryDecision.DeadLetter, RetryDecision.AckDrop {
        record Retry(Duration delay, String reason) implements RetryDecision {}
        record DeadLetter(String reason) implements RetryDecision {}
        record AckDrop(String reason) implements RetryDecision {}
    }

如果当前 Java 编译设置不允许 sealed interface，允许退化为普通 interface 加三个 record 实现，但语义必须仍然是三态：延迟重试、终态死信、确认并丢弃。`ManagedMessageListener` 是内部实现类，可以不是业务直接 new 的对象；业务模块通过 `ManagedListenerContainerFactory.create(...)` 获得容器并启动。

task 模块未来的消费接入应类似这样：

    ManagedMessageHandler<TaskSubmitMessage> handler = envelope -> {
        TaskSubmitMessage message = envelope.payload();
        taskDispatcher.acceptAndStart(message.taskId());
    };

    ConsumerErrorHandler errorHandler = (envelope, error, retryCount) -> {
        PixFlowException normalized = errorNormalizer.normalize(error);
        if (normalized.recovery() == RecoveryHint.RETRY) {
            return new RetryDecision.Retry(backoffPolicy.delayFor(retryCount), normalized.getMessage());
        }
        if (normalized.recovery() == RecoveryHint.SKIP) {
            return new RetryDecision.AckDrop(normalized.getMessage());
        }
        return new RetryDecision.DeadLetter(normalized.getMessage());
    };

    MessageListenerContainer container = managedListenerContainerFactory.create(
        topology,
        TaskSubmitMessage.class,
        handler,
        errorHandler
    );

这里 `taskDispatcher.acceptAndStart(...)` 必须是“接管”方法，不是完整批处理方法。它应完成抢锁、置执行中、提交本地线程池，然后返回。返回后 `infra/mq` 才 ack 原消息。

重试 header API 必须集中，不允许散落字符串。建议如下：

    public final class RetryHeaders {
        public static final String RETRY_COUNT = "x-retry-count";
        public static final String ORIGINAL_EXCHANGE = "x-original-exchange";
        public static final String ORIGINAL_ROUTING_KEY = "x-original-routing-key";
        public static final String FIRST_FAILURE_AT = "x-first-failure-at";
        public static final String LAST_ERROR_CODE = "x-last-error-code";
        public static final String LAST_ERROR_MESSAGE = "x-last-error-message";

        public static int retryCount(Map<String, Object> headers);
        public static Map<String, Object> incrementRetry(Map<String, Object> headers);
        public static Map<String, Object> withFailure(Map<String, Object> headers, PixFlowException error);
    }

trace API 必须只处理技术 trace，不处理业务回合。建议如下：

    public interface TraceHeaderPropagator {
        Map<String, Object> inject(Map<String, Object> headers);
        TraceScope restore(Map<String, Object> headers);
    }

    public interface TraceScope extends AutoCloseable {
        String traceId();
        @Override
        void close();
    }

消费时应使用 try-with-resources 恢复 MDC 或 tracing span：

    try (TraceScope scope = traceHeaderPropagator.restore(envelope.headers())) {
        handler.handle(envelope);
    }

配置 API 由 `MqProperties` 承载。建议字段和默认值如下：

    @ConfigurationProperties(prefix = "pixflow.mq")
    public class MqProperties {
        private Duration publishConfirmTimeout = Duration.ofSeconds(5);
        private int prefetch = 1;
        private int consumerConcurrency = 4;
        private int inProcessRetries = 2;
        private int maxRetries = 5;
        private List<Duration> retryBackoff = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30)
        );
        private int dlqAlertThreshold = 1;
    }

错误码 API 应至少包含下面这些 MQ 码，全部实现 common 的 `ErrorCode`，category 为 `DEPENDENCY`：

    public enum MqErrorCode implements ErrorCode {
        MQ_PUBLISH_RETURNED,
        MQ_PUBLISH_NACKED,
        MQ_CONFIRM_TIMEOUT,
        MQ_BROKER_UNAVAILABLE,
        MQ_TOPOLOGY_DECLARE_FAILED,
        MQ_MESSAGE_SERIALIZATION_FAILED,
        MQ_MESSAGE_DESERIALIZATION_FAILED,
        MQ_MESSAGE_SCHEMA_UNSUPPORTED,
        MQ_RETRY_FORWARD_FAILED,
        MQ_DLQ_FORWARD_FAILED
    }

指标 API 应封装在 `MqMetrics`，业务模块不能自己拼指标名。建议如下：

    public interface MqMetrics {
        void recordPublishConfirmed(String exchange, String routingKey);
        void recordPublishFailed(String exchange, String routingKey, PublishFailureType failureType);
        void recordConsumeAck(String queue);
        void recordConsumeRetry(String queue, int retryCount);
        void recordConsumeDeadLetter(String queue);
        void recordConsumeAckDrop(String queue);
        void recordDlqDepth(String queue, long depth);
    }

指标 tag 可以包含 exchange、queue、routingKey、result、failureType，但不能包含 taskId、messageId、skuId 等高基数业务值。

## Plan of Work

第一阶段先建立 `infra/mq` 的包结构和配置入口。目标是在 `src/main/java/.../infra/mq` 下形成清晰边界：根包放发布入口、投递结果和消息信封；`topology` 放拓扑声明；`consumer` 放消费容器与错误决策 SPI；`retry` 放 header 常量和重试计数读写；`trace` 放 traceId 透传；`config` 放属性绑定和自动配置；`error` 放 MQ 错误码；`observability` 放 MQ 指标。实现时必须沿用当前项目的实际 Java 根包；如果当前源码根包不是设计文档示例里的 `com.pixflow`，以现有 `PixFlowApplication` 所在包为准，避免为了本模块引入全局包名迁移。

第二阶段实现配置和 Spring AMQP 装配。`MqProperties` 应绑定 `pixflow.mq.publish-confirm-timeout`、`prefetch`、`consumer-concurrency`、`in-process-retries`、`max-retries`、`retry-backoff`、`dlq-alert-threshold`。`MqAutoConfiguration` 应装配 `Jackson2JsonMessageConverter`、带 publisher confirms 和 returns 的 `RabbitTemplate`、`RabbitAdmin`、手动 ack 的 listener container factory，以及 `MessagePublisher`、`TopologyRegistrar`、`TraceHeaderPropagator` 等基础 bean。这里的重点是把默认值写进配置对象，让后续业务模块不用硬编码。

第三阶段实现拓扑声明。`QueueTopology` 是一个不可变值对象，描述一个队列组需要的名字和路由关系。一个队列组至少包含主 direct exchange、主 durable queue、retry queue、DLX 和 DLQ。`QueueTopologyBuilder` 给业务模块提供声明式 API，但不预置任何业务队列。`TopologyRegistrar` 接收 `QueueTopology` 后通过 `RabbitAdmin` 幂等声明对象，重复调用不应报错。主队列应带 `x-dead-letter-exchange` 指向 DLX；retry queue 无消费者，消息过期后 dead-letter 回主 exchange；终态失败消息路由到 DLQ。

第四阶段实现可靠发布。`MessagePublisher.publish(...)` 接收 exchange、routing key、payload、业务 header 和可选 schemaVersion，构造成 `MessageEnvelope` 后发布。发布时必须把消息设置为 persistent，使用 `mandatory=true`，注入 traceId header，并同步等待 confirm。broker ack 且没有 return 才返回 `PublishResult.confirmed`；confirm nack、return、等待超时、序列化失败或连接异常都返回 `PublishResult.failed(reason)`，同时记录指标。这个阶段不应该因为发布失败直接替业务抛断请求；后续 `module/task` 要根据 `PublishResult` 做待执行任务补发扫描。

第五阶段实现消息信封和 header 规则。`MessageEnvelope` 应包含 `schemaVersion`、`payload` 和 `headers`。payload 可以是泛型或 JSON 树，但 MQ 模块不能要求 payload 是任务类型。header 中应保留 traceId、retry count、原始 exchange、原始 routing key、失败原因、第一次失败时间等通用信息。schemaVersion 默认为 1；消费侧看到不支持的版本时，应将消息转入 DLQ，而不是让业务 handler 接触未知结构。

第六阶段实现消费侧包装。`ManagedMessageListener` 包住业务 handler。它收到消息后先重建 traceId，再反序列化信封和读取 retry count，然后调用业务 handler。handler 正常返回代表“接管成功”，`ManagedMessageListener` 立即 ack 原消息。handler 抛错时，先用 `ErrorNormalizer` 归一化，再调用 `ConsumerErrorHandler.onError(envelope, error, retryCount)` 得到 `RetryDecision`。如果结果是 `Retry(delay)`，则发布到 retry queue 并设置 per-message TTL 和 `x-retry-count + 1`，发布成功后 ack 原消息；如果结果是 `DeadLetter(reason)`，则发布到 DLQ 并 ack 原消息；如果结果是 `AckDrop(reason)`，则直接 ack 原消息。

第七阶段实现进程内瞬时重试。`ManagedMessageListener` 可以在调用业务 handler 时，对明确可重试的依赖类错误做有限次快速重试，默认次数由 `pixflow.mq.in-process-retries` 控制。这个重试必须短、小、有限，因为它占用消费线程。持续失败后仍交给 `ConsumerErrorHandler`，由其决定进入 retry queue、DLQ 或 ack drop。

第八阶段实现 trace 和可观测。`TraceHeaderPropagator` 发布时从当前 Micrometer tracing 或 MDC 中读取 traceId，写入 `x-trace-id`；消费时从 header 读取 traceId，写入 MDC，并在可用时创建消费 span。`MqMetrics` 应记录发布确认结果、消费 ack/retry/dead_letter/ack_drop、重试次数和 DLQ 深度。指标 tag 不应包含 taskId、messageId 这类高基数值。

第九阶段补齐错误码、文案和测试。`MqErrorCode` 应实现 `common.error.ErrorCode`，分类统一为 `DEPENDENCY`，默认恢复策略由 `common` 的 `ErrorCategory.DEPENDENCY` 决定。测试要用 Testcontainers 启动 RabbitMQ，证明拓扑真实可声明、confirm/return 真实有效、TTL 到期能回主队列、超限能进 DLQ、ack-then-process 会移除原消息、trace header 能跨发布和消费保留。

## Concrete Steps

先确认文档和代码结构。工作目录始终是仓库根目录：

    cd D:\study\PixFlow
    rg --files docs/design-docs

预期可以看到：

    docs\design-docs\design.md
    docs\design-docs\module\mq.md
    docs\design-docs\module\common.md
    docs\design-docs\exec-plans\module-dependency-dag-plan.md

然后确认 Java 工程结构和根包：

    cd D:\study\PixFlow
    rg --files src/main/java

如果看到类似 `src/main/java/com/pixflow/PixFlowApplication.java`，则新代码放在 `src/main/java/com/pixflow/infra/mq` 下。如果实际根包不同，以现有应用入口所在包为准。

实现时建议按下面顺序新增文件。路径中的 `<root-package-path>` 表示项目实际 Java 根包路径，例如 `com/pixflow`。

    src/main/java/<root-package-path>/infra/mq/MessagePublisher.java
    src/main/java/<root-package-path>/infra/mq/PublishResult.java
    src/main/java/<root-package-path>/infra/mq/MessageEnvelope.java
    src/main/java/<root-package-path>/infra/mq/topology/QueueTopology.java
    src/main/java/<root-package-path>/infra/mq/topology/QueueTopologyBuilder.java
    src/main/java/<root-package-path>/infra/mq/topology/TopologyRegistrar.java
    src/main/java/<root-package-path>/infra/mq/consumer/ConsumerErrorHandler.java
    src/main/java/<root-package-path>/infra/mq/consumer/RetryDecision.java
    src/main/java/<root-package-path>/infra/mq/consumer/ManagedMessageListener.java
    src/main/java/<root-package-path>/infra/mq/consumer/ListenerContainerFactory.java
    src/main/java/<root-package-path>/infra/mq/retry/RetryHeaders.java
    src/main/java/<root-package-path>/infra/mq/trace/TraceHeaderPropagator.java
    src/main/java/<root-package-path>/infra/mq/config/MqProperties.java
    src/main/java/<root-package-path>/infra/mq/config/MqAutoConfiguration.java
    src/main/java/<root-package-path>/infra/mq/error/MqErrorCode.java
    src/main/java/<root-package-path>/infra/mq/observability/MqMetrics.java

如果项目还没有 Spring AMQP、RabbitMQ client、Micrometer 或 Testcontainers 依赖，应在构建文件中加入。Maven 项目通常修改 `pom.xml`；Gradle 项目通常修改 `build.gradle` 或 `build.gradle.kts`。依赖选择必须与当前 Spring Boot 版本匹配，不要手写不兼容版本号。优先使用 Spring Boot dependency management 管理版本。

实现完成后，运行测试：

    cd D:\study\PixFlow
    mvn test

如果项目使用 Gradle，则运行对应测试命令，例如：

    cd D:\study\PixFlow
    .\gradlew test

如果 Testcontainers 需要 Docker，确保本机 Docker 正在运行。测试成功时，应看到 MQ 相关测试全部通过，并且没有启动真实业务模块的要求。

## Validation and Acceptance

验收要看真实可观察行为，不只看类是否存在。

第一类验收是拓扑声明。测试启动 RabbitMQ 容器，调用 `TopologyRegistrar` 注册一个测试拓扑，例如 exchange 为 `pixflow.test`、主队列为 `pixflow.test.q`、routing key 为 `test.submit`、retry queue 为 `pixflow.test.retry.q`、DLQ 为 `pixflow.test.dlq`。测试应能通过 RabbitMQ 管理 API 或 AMQP 被动声明确认这些 exchange、queue 和 binding 存在。重复注册同一个拓扑不应失败。

第二类验收是可靠发布。发布到已绑定 routing key 的消息应返回 `PublishResult.confirmed`。发布到不存在绑定的 routing key 且 `mandatory=true` 时，应返回 `PublishResult.failed`，失败原因能说明消息被 return 或不可路由。模拟 confirm 超时时，也应返回 failed，而不是让调用方永久等待。

第三类验收是 ack-then-process。测试创建一个业务 handler，handler 只记录“已接管”并立即返回。消息投递后，`ManagedMessageListener` 应 ack 原消息，主队列深度变为 0。另一个测试中，handler 接管后启动的异步任务失败不应让原 MQ 消息重新出现，因为 MQ 模块只负责接管阶段；真正的任务恢复由 Wave 4 的 `module/task` 处理。

第四类验收是分层重试。handler 持续抛出可重试异常，`ConsumerErrorHandler` 返回 `Retry(delay)` 时，原消息应从主队列消失，带 `x-retry-count` 的新消息进入 retry queue；TTL 到期后消息回到主队列。超过 `max-retries` 时，消息应进入 DLQ。`RetryDecision.AckDrop` 应直接 ack，不进入 retry queue 或 DLQ。`RetryDecision.DeadLetter` 应直接进入 DLQ。

第五类验收是 traceId。发布侧在 MDC 或 tracing context 中放入一个测试 traceId，发布后消费侧应能在 handler 内从 MDC 或传入上下文读取同一个 traceId。这个测试证明 RabbitMQ 这个异步边界没有断链。

第六类验收是消息版本。发送一个 `schemaVersion` 不受支持的消息，消费者不应调用业务 handler，也不应崩溃；消息应进入 DLQ，并保留能说明版本不支持的 header 或失败原因。

第七类验收是错误码和指标。`MqErrorCode` 的所有 code 必须全局唯一、category 为 `DEPENDENCY`，并满足 common 的错误码目录测试要求。指标至少能在单元或集成测试中观察到 publish confirmed/failed、consume ack/retry/dead_letter/ack_drop 计数变化。

## Idempotence and Recovery

本计划的实现步骤应当是可重复执行的。拓扑声明必须幂等，重复声明同名 exchange、queue 和 binding 不应报错；测试每次应使用独立的队列名或在容器销毁时自动清理，避免前一次测试状态污染后一次测试。

如果发布成功但调用方在读取结果前崩溃，RabbitMQ 中仍已有消息；如果 DB 已创建任务但发布失败，后续 `module/task` 的超龄“待执行”任务扫描会补发。这是设计中的恢复路径，不需要 `infra/mq` 自建 outbox 表。

如果消费侧在 ack 前失败，消息仍由 RabbitMQ 保留或进入 retry/DLQ；如果消费侧在接管成功并 ack 后进程崩溃，MQ 不再重投该消息，后续由 `module/task` 扫描 `status=执行中` 的任务重新入队。实现时必须在注释和测试名中体现这个边界，避免后续维护者误以为 MQ 会覆盖整批任务执行过程。

如果 retry 发布失败，不应先 ack 原消息。安全顺序是：先确认 retry 或 DLQ 发布成功，再 ack 原消息；如果转发失败，让原消息保持未 ack，由 RabbitMQ 在 channel 关闭或连接断开后重新投递，避免消息丢失。

## Artifacts and Notes

实现完成后，建议保留这些测试产物作为证据：

- `TopologyRegistrarIntegrationTest`：证明主队列、retry queue、DLX、DLQ 可以真实声明且重复声明幂等。
- `MessagePublisherIntegrationTest`：证明 confirm、return、timeout 映射到 `PublishResult`。
- `ManagedMessageListenerIntegrationTest`：证明 ack、retry、dead letter、ack drop 四条路径。
- `TraceHeaderPropagatorTest`：证明 `x-trace-id` 发布注入和消费恢复。
- `MessageEnvelopeVersionTest`：证明未知 schemaVersion 进入 DLQ。
- `MqErrorCodeCatalogTest`：证明 MQ 错误码符合 common 的全局错误码约束。

一个理想的测试输出应能说明下面这些事实：

    TopologyRegistrarIntegrationTest > declares topology idempotently PASSED
    MessagePublisherIntegrationTest > returns failed when routing key is unroutable PASSED
    ManagedMessageListenerIntegrationTest > retries with incremented x-retry-count PASSED
    ManagedMessageListenerIntegrationTest > routes to dlq after max retries PASSED
    TraceHeaderPropagatorTest > preserves trace id across rabbit boundary PASSED

## Interfaces and Dependencies

本节与上文 `API Contracts` 一起构成实现时的接口清单。`API Contracts` 给出方法签名和调用示例；本节补充每个文件的职责、依赖边界和禁止事项。

`src/main/java/<root-package-path>/infra/mq/MessagePublisher.java` 应提供领域无关发布入口。稳定形态是接收 `PublishRequest`，返回 `PublishResult`。它不应暴露 `RabbitTemplate` 给业务模块，也不应要求 payload 是 task 类型。

`src/main/java/<root-package-path>/infra/mq/PublishRequest.java` 应承载 exchange、routingKey、payload、headers、schemaVersion、confirmTimeout。业务模块发布消息时只构造这个请求，不直接设置 Spring AMQP message properties。

`src/main/java/<root-package-path>/infra/mq/PublishResult.java` 和 `PublishFailure.java` 应表达 confirmed 或 failed。失败结果至少包含失败类型、原因字符串、是否 returned、是否 confirm timeout、是否 broker nack 这类可诊断信息。业务模块据此决定是否记录补偿状态。

`src/main/java/<root-package-path>/infra/mq/MessageEnvelope.java` 应包含 `schemaVersion`、`payload`、`headers`。它是 MQ 模块的统一外层消息格式，不替代业务 payload。

`src/main/java/<root-package-path>/infra/mq/topology/QueueTopology.java` 应描述一个队列组。字段应覆盖主 exchange、主 queue、主 routing key、retry queue、retry routing key、DLX、DLQ、dead routing key，以及是否启用 retry queue。它是值对象，应尽量不可变。

`src/main/java/<root-package-path>/infra/mq/topology/QueueTopologyBuilder.java` 应提供声明式构建器，让业务模块能写出清晰的拓扑声明。构建器可以提供默认命名约定，但默认值必须仍然是领域无关的。

`src/main/java/<root-package-path>/infra/mq/topology/TopologyRegistrar.java` 应通过 `RabbitAdmin` 声明 exchange、queue、binding。它的 public 方法应是幂等的，例如 `register(QueueTopology topology)`。

`src/main/java/<root-package-path>/infra/mq/consumer/ConsumerErrorHandler.java` 是业务侧 SPI。它接收信封、异常和当前 retry count，返回 `RetryDecision`。MQ 模块可以提供默认实现，但 `module/task` 后续会用业务语义覆盖。

`src/main/java/<root-package-path>/infra/mq/consumer/RetryDecision.java` 应是封闭的三态结果：`Retry(Duration delay)`、`DeadLetter(String reason)`、`AckDrop(String reason)`。如果项目 Java 版本和编译设置支持 sealed interface，可使用 sealed；否则用普通 interface 加 record/class 实现也可以。

`src/main/java/<root-package-path>/infra/mq/consumer/ManagedMessageHandler.java` 应是业务侧 handler 函数式接口。handler 返回即表示接管成功，MQ 模块随后 ack 原消息。

`src/main/java/<root-package-path>/infra/mq/consumer/ManagedMessageListener.java` 应是消费侧核心包装器。它负责 trace 重建、版本检查、进程内重试、业务 handler 调用、异常归一化、retry/DLQ 转发和最终 ack。业务模块通常不直接 new 它，而是通过工厂创建容器。

`src/main/java/<root-package-path>/infra/mq/consumer/ManagedListenerContainerFactory.java` 或等价配置类应创建手动 ack、prefetch=配置值、concurrency=配置值的消费容器工厂。不要要求业务模块使用 `@RabbitListener` 自己处理 ack。

`src/main/java/<root-package-path>/infra/mq/retry/RetryHeaders.java` 应集中定义和读写 header。建议包含 `x-retry-count`、`x-original-exchange`、`x-original-routing-key`、`x-first-failure-at`、`x-last-error-code`、`x-last-error-message`、`x-trace-id`。header 名称不要散落在业务代码里。

`src/main/java/<root-package-path>/infra/mq/trace/TraceHeaderPropagator.java` 应提供 `inject(...)` 和 `restore(...)` 或等价方法。它只处理技术 traceId，不处理业务回合维度。

`src/main/java/<root-package-path>/infra/mq/config/MqProperties.java` 应绑定 `pixflow.mq.*` 配置，并提供设计文档中的默认值。

`src/main/java/<root-package-path>/infra/mq/config/MqAutoConfiguration.java` 应装配 MQ 模块需要的 Spring bean。它应尽量只配置基础设施，不注册任何业务 listener。

`src/main/java/<root-package-path>/infra/mq/error/MqErrorCode.java` 应实现 common 的 `ErrorCode`，所有 MQ 错误都归为 `ErrorCategory.DEPENDENCY`，并通过 common 的错误码目录测试。计划中的初始错误码包括 `MQ_PUBLISH_RETURNED`、`MQ_PUBLISH_NACKED`、`MQ_CONFIRM_TIMEOUT`、`MQ_BROKER_UNAVAILABLE`、`MQ_TOPOLOGY_DECLARE_FAILED`、`MQ_MESSAGE_SERIALIZATION_FAILED`、`MQ_MESSAGE_DESERIALIZATION_FAILED`、`MQ_MESSAGE_SCHEMA_UNSUPPORTED`、`MQ_RETRY_FORWARD_FAILED`、`MQ_DLQ_FORWARD_FAILED`。

`src/main/java/<root-package-path>/infra/mq/observability/MqMetrics.java` 应封装 Micrometer 指标记录。业务模块不应直接拼 MQ 指标名。

本模块依赖 Spring AMQP 和 RabbitMQ client 执行真实消息操作，依赖 Jackson 做 JSON 消息转换，依赖 Micrometer 做指标和 trace 辅助，依赖 Testcontainers 在测试中启动 RabbitMQ。它依赖 `common` 的 `ErrorCode`、`PixFlowException`、`ErrorNormalizer`、`Sanitizer`、`ErrorCategory` 和 `RecoveryHint`，但不依赖 `module/task`、`module/dag`、`infra/cache`、`infra/storage` 或任何数据库实体。

## Note

本计划根据 `docs/design-docs/module/mq.md`、`docs/design-docs/module/common.md`、`docs/design-docs/design.md` 和 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 撰写，重点补足了“具体实现架构和机制”“验证方式”“可搜索关键词定位设计依据”。本次只新增计划文档，不实现代码。

2026-06-27 / Codex: 参考 `docs/design-docs/exec-plans/completed/permission-module-implementation-plan.md` 的写法，新增 `API Contracts` 章节，并把发布、拓扑、消费、重试、trace、配置、错误码、指标的 Java API 签名写清楚。原因是原计划偏架构描述，接口可执行性不够强。

2026-06-27 / Codex: 按本计划新增 `src/main/java/com/pixflow/infra/mq` 与对应单元测试，完成 MQ 模块核心骨架、配置、发布、拓扑、消费包装、重试 header、trace、错误码和指标实现。同步更新 `Progress`、`Surprises & Discoveries`、`Outcomes & Retrospective`，明确当前已通过 `mvn test` 的单元契约验证，但 Testcontainers RabbitMQ 集成验收仍未完成。
