package com.pixflow.infra.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;
import com.pixflow.infra.mq.consumer.RabbitManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.RetryDecision;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.retry.RetryHeaders;
import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.topology.QueueTopologyBuilder;
import com.pixflow.infra.mq.topology.RabbitTopologyRegistrar;
import com.pixflow.infra.mq.trace.MdcTraceHeaderPropagator;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RabbitMqIntegrationTest {
    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitAdmin rabbitAdmin;
    private static ObjectMapper objectMapper;
    private static TraceHeaderPropagator traceHeaderPropagator;

    private MessageListenerContainer container;

    @BeforeAll
    static void setUpBroker() {
        objectMapper = new ObjectMapper();
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper, "com.pixflow.infra.mq"));
        rabbitTemplate.setMandatory(true);
        rabbitAdmin = new RabbitAdmin(connectionFactory);
        traceHeaderPropagator = new MdcTraceHeaderPropagator();
    }

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
        MDC.clear();
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void declaresTopologyIdempotentlyAndPublishesConfirmedOrReturned() {
        QueueTopology topology = topology("publish");
        new RabbitTopologyRegistrar(rabbitAdmin).register(topology);
        new RabbitTopologyRegistrar(rabbitAdmin).register(topology);

        RabbitMessagePublisher publisher = publisher();
        PublishResult confirmed = publisher.publish(PublishRequest
                .of(topology.exchange(), topology.routingKey(), new TestPayload("ok")));
        PublishResult returned = publisher.publish(PublishRequest
                .of(topology.exchange(), "missing.route", new TestPayload("lost"))
                .withConfirmTimeout(Duration.ofSeconds(3)));

        assertThat(confirmed.confirmed()).isTrue();
        assertThat(returned.failed()).isTrue();
        assertThat(returned.failure().type()).isEqualTo(PublishFailureType.RETURNED);
        assertThat(passiveQueueName(topology.queue()))
                .isEqualTo(topology.queue());
        assertThat(passiveQueueName(topology.retryQueue()))
                .isEqualTo(topology.retryQueue());
        assertThat(passiveQueueName(topology.deadLetterQueue()))
                .isEqualTo(topology.deadLetterQueue());
    }

    @Test
    void listenerAcksAfterHandlerAcceptsAndRestoresTraceId() throws Exception {
        QueueTopology topology = topology("ack");
        new RabbitTopologyRegistrar(rabbitAdmin).register(topology);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<String> traceInHandler = new AtomicReference<>();

        container = listenerFactory().create(
                topology,
                TestPayload.class,
                (ManagedMessageHandler<TestPayload>) envelope -> {
                    traceInHandler.set(MDC.get("traceId"));
                    handled.countDown();
                },
                deadLetterOnError());
        container.start();

        MDC.put("traceId", "trace-ack");
        publisher().publish(PublishRequest.of(topology.exchange(), topology.routingKey(), new TestPayload("accept")));

        assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(traceInHandler.get()).isEqualTo("trace-ack");
        assertThat(queueDepth(topology.queue())).isZero();
    }

    @Test
    void retryDecisionRoutesThroughRetryQueueAndReturnsToMainQueueAfterTtl() throws Exception {
        QueueTopology topology = topology("retry");
        new RabbitTopologyRegistrar(rabbitAdmin).register(topology);
        CountDownLatch failedOnce = new CountDownLatch(1);

        container = listenerFactory(0, 5).create(
                topology,
                TestPayload.class,
                (ManagedMessageHandler<TestPayload>) envelope -> {
                    failedOnce.countDown();
                    throw new PixFlowException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, "temporary");
                },
                (envelope, error, retryCount) -> new RetryDecision.Retry(Duration.ofMillis(250), "retry"));
        container.start();

        publisher().publish(PublishRequest.of(topology.exchange(), topology.routingKey(), new TestPayload("retry")));

        assertThat(failedOnce.await(10, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> queueDepth(topology.retryQueue()) == 1, Duration.ofSeconds(10));
        container.stop();
        container = null;
        waitUntil(() -> queueDepth(topology.queue()) == 1, Duration.ofSeconds(10));

        @SuppressWarnings("unchecked")
        MessageEnvelope<Map<String, Object>> envelope = (MessageEnvelope<Map<String, Object>>) rabbitTemplate
                .receiveAndConvert(topology.queue(), 2000);
        assertThat(envelope).isNotNull();
        assertThat(RetryHeaders.retryCount(envelope.headers())).isEqualTo(1);
    }

    @Test
    void deadLetterAndUnknownSchemaVersionGoToDlqWithoutCallingHandler() throws Exception {
        QueueTopology topology = topology("dlq");
        new RabbitTopologyRegistrar(rabbitAdmin).register(topology);
        CountDownLatch handlerCalled = new CountDownLatch(1);

        container = listenerFactory(0, 1).create(
                topology,
                TestPayload.class,
                (ManagedMessageHandler<TestPayload>) envelope -> handlerCalled.countDown(),
                deadLetterOnError());
        container.start();

        MessageEnvelope<TestPayload> unsupported =
                new MessageEnvelope<>(99, new TestPayload("future"), Map.of());
        rabbitTemplate.convertAndSend(topology.exchange(), topology.routingKey(), unsupported, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });

        waitUntil(() -> queueDepth(topology.deadLetterQueue()) == 1, Duration.ofSeconds(10));
        assertThat(handlerCalled.await(300, TimeUnit.MILLISECONDS)).isFalse();

        @SuppressWarnings("unchecked")
        MessageEnvelope<Map<String, Object>> dlqEnvelope = (MessageEnvelope<Map<String, Object>>) rabbitTemplate
                .receiveAndConvert(topology.deadLetterQueue(), 2000);
        assertThat(dlqEnvelope).isNotNull();
        assertThat(dlqEnvelope.headers())
                .containsEntry(RetryHeaders.LAST_ERROR_CODE, "MQ_MESSAGE_SCHEMA_UNSUPPORTED");
        assertThat(String.valueOf(dlqEnvelope.headers().get(RetryHeaders.LAST_ERROR_MESSAGE)))
                .contains("unsupported schemaVersion");
    }

    private RabbitMessagePublisher publisher() {
        return new RabbitMessagePublisher(rabbitTemplate, traceHeaderPropagator, NoopMqMetrics.INSTANCE);
    }

    private RabbitManagedListenerContainerFactory listenerFactory() {
        return listenerFactory(0, 5);
    }

    private RabbitManagedListenerContainerFactory listenerFactory(int inProcessRetries, int maxRetries) {
        MqProperties properties = new MqProperties();
        properties.setConsumerConcurrency(1);
        properties.setInProcessRetries(inProcessRetries);
        properties.setMaxRetries(maxRetries);
        properties.setPublishConfirmTimeout(Duration.ofSeconds(3));
        return new RabbitManagedListenerContainerFactory(
                connectionFactory,
                rabbitTemplate,
                objectMapper,
                new ErrorNormalizer(),
                traceHeaderPropagator,
                NoopMqMetrics.INSTANCE,
                properties);
    }

    private ConsumerErrorHandler deadLetterOnError() {
        return (envelope, error, retryCount) -> new RetryDecision.DeadLetter(error.getMessage());
    }

    private QueueTopology topology(String label) {
        String suffix = label + "." + UUID.randomUUID().toString().replace("-", "");
        return QueueTopologyBuilder.direct("pixflow.test." + suffix)
                .queue("pixflow.test." + suffix + ".q")
                .routingKey("test.submit")
                .deadLetter("pixflow.test." + suffix + ".dlx", "pixflow.test." + suffix + ".dlq", "test.dead")
                .retry("pixflow.test." + suffix + ".retry.q", "test.retry")
                .build();
    }

    private long queueDepth(String queue) {
        Object depth = rabbitTemplate.execute(channel -> channel.queueDeclarePassive(queue).getMessageCount());
        return depth == null ? 0L : ((Number) depth).longValue();
    }

    private String passiveQueueName(String queue) {
        Object name = rabbitTemplate.execute(channel -> channel.queueDeclarePassive(queue).getQueue());
        return name == null ? "" : String.valueOf(name);
    }

    private void waitUntil(BooleanProbe probe, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (probe.get()) {
                return;
            }
            Thread.sleep(75);
        }
        assertThat(probe.get()).isTrue();
    }

    private record TestPayload(String value) {
    }

    @FunctionalInterface
    private interface BooleanProbe {
        boolean get() throws Exception;
    }

    private enum NoopMqMetrics implements MqMetrics {
        INSTANCE;

        @Override
        public void recordPublishConfirmed(String exchange, String routingKey) {
        }

        @Override
        public void recordPublishFailed(String exchange, String routingKey, PublishFailureType failureType) {
        }

        @Override
        public void recordConsumeAck(String queue) {
        }

        @Override
        public void recordConsumeRetry(String queue, int retryCount) {
        }

        @Override
        public void recordConsumeDeadLetter(String queue) {
        }

        @Override
        public void recordConsumeAckDrop(String queue) {
        }

        @Override
        public void recordDlqDepth(String queue, long depth) {
        }
    }
}
