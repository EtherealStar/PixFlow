package com.pixflow.infra.mq.consumer;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.error.MqErrorCode;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.retry.RetryHeaders;
import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import com.pixflow.infra.mq.trace.TraceScope;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

public class ManagedMessageListener<T> implements ChannelAwareMessageListener {
    private final QueueTopology topology;
    private final Class<T> payloadType;
    private final ManagedMessageHandler<T> handler;
    private final ConsumerErrorHandler errorHandler;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ErrorNormalizer errorNormalizer;
    private final TraceHeaderPropagator traceHeaderPropagator;
    private final MqMetrics metrics;
    private final MqProperties properties;

    public ManagedMessageListener(
            QueueTopology topology,
            Class<T> payloadType,
            ManagedMessageHandler<T> handler,
            ConsumerErrorHandler errorHandler,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            ErrorNormalizer errorNormalizer,
            TraceHeaderPropagator traceHeaderPropagator,
            MqMetrics metrics,
            MqProperties properties) {
        this.topology = topology;
        this.payloadType = payloadType;
        this.handler = handler;
        this.errorHandler = errorHandler;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.errorNormalizer = errorNormalizer;
        this.traceHeaderPropagator = traceHeaderPropagator;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        MessageEnvelope<T> envelope;
        try {
            envelope = readEnvelope(message);
        } catch (Exception ex) {
            MessageEnvelope<Object> failed = unknownEnvelope(message, Map.of());
            forwardToDeadLetter(
                    failed,
                    message,
                    MqErrorCode.MQ_MESSAGE_DESERIALIZATION_FAILED,
                    "message deserialization failed: " + Sanitizer.sanitizeMessage(ex.getMessage()));
            channel.basicAck(deliveryTag, false);
            metrics.recordConsumeDeadLetter(topology.queue());
            return;
        }

        try (TraceScope ignored = traceHeaderPropagator.restore(envelope.headers())) {
            if (!envelope.supportedVersion()) {
                forwardToDeadLetter(
                        envelope,
                        message,
                        MqErrorCode.MQ_MESSAGE_SCHEMA_UNSUPPORTED,
                        "unsupported schemaVersion: " + envelope.schemaVersion());
                channel.basicAck(deliveryTag, false);
                metrics.recordConsumeDeadLetter(topology.queue());
                return;
            }

            try {
                handleWithInProcessRetries(envelope);
                // handler 返回只代表“接管成功”；长任务失败由 task 模块后续扫描恢复，不再依赖 MQ 重投。
                channel.basicAck(deliveryTag, false);
                metrics.recordConsumeAck(topology.queue());
            } catch (Exception error) {
                handleFailure(envelope, message, channel, deliveryTag, error);
            } catch (Error error) {
                throw error;
            }
        }
    }

    private void handleFailure(
            MessageEnvelope<T> envelope,
            Message message,
            Channel channel,
            long deliveryTag,
            Throwable error) throws IOException {
        PixFlowException normalized = errorNormalizer.normalize(error);
        int retryCount = RetryHeaders.retryCount(envelope.headers());
        RetryDecision decision = errorHandler.onError(envelope, normalized, retryCount);
        if (decision instanceof RetryDecision.Retry retry && retryCount < properties.getMaxRetries() && topology.retryEnabled()) {
            // 先确认 retry 消息发布成功，再 ack 原消息；否则让原消息保持未 ack，避免消息丢失。
            forwardToRetry(envelope, message, retry, normalized);
            channel.basicAck(deliveryTag, false);
            metrics.recordConsumeRetry(topology.queue(), retryCount + 1);
            return;
        }
        if (decision instanceof RetryDecision.AckDrop) {
            channel.basicAck(deliveryTag, false);
            metrics.recordConsumeAckDrop(topology.queue());
            return;
        }
        String reason = decision instanceof RetryDecision.DeadLetter deadLetter
                ? deadLetter.reason()
                : normalized.getMessage();
        forwardToDeadLetter(envelope, message, MqErrorCode.MQ_DLQ_FORWARD_FAILED, reason);
        channel.basicAck(deliveryTag, false);
        metrics.recordConsumeDeadLetter(topology.queue());
    }

    private void handleWithInProcessRetries(MessageEnvelope<T> envelope) throws Exception {
        int attempts = Math.max(0, properties.getInProcessRetries()) + 1;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                handler.handle(envelope);
                return;
            } catch (Exception ex) {
                last = ex;
                if (i + 1 >= attempts) {
                    break;
                }
                sleepQuietly(Duration.ofMillis(50L * (i + 1)));
            }
        }
        throw last;
    }

    private MessageEnvelope<T> readEnvelope(Message message) throws IOException {
        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(MessageEnvelope.class, payloadType);
        return objectMapper.readValue(message.getBody(), envelopeType);
    }

    private MessageEnvelope<Object> unknownEnvelope(Message message, Map<String, Object> fallbackHeaders) {
        Map<String, Object> headers = new LinkedHashMap<>(fallbackHeaders);
        headers.putAll(message.getMessageProperties().getHeaders());
        return new MessageEnvelope<>(MessageEnvelope.CURRENT_SCHEMA_VERSION, Map.of("rawBodySize", message.getBody().length), headers);
    }

    private void forwardToRetry(
            MessageEnvelope<T> envelope,
            Message original,
            RetryDecision.Retry retry,
            PixFlowException error) {
        Map<String, Object> headers = RetryHeaders.withOriginalRoute(
                RetryHeaders.incrementRetry(RetryHeaders.withFailure(envelope.headers(), error)),
                topology.exchange(),
                topology.routingKey());
        MessageEnvelope<T> retryEnvelope = new MessageEnvelope<>(envelope.schemaVersion(), envelope.payload(), headers);
        send(
                topology.deadLetterExchange(),
                topology.retryRoutingKey(),
                retryEnvelope,
                headers,
                original,
                retry.delay(),
                MqErrorCode.MQ_RETRY_FORWARD_FAILED);
    }

    private void forwardToDeadLetter(
            MessageEnvelope<?> envelope,
            Message original,
            MqErrorCode reasonCode,
            String reason) {
        Map<String, Object> headers = RetryHeaders.withOriginalRoute(
                RetryHeaders.withFailure(envelope.headers(), reasonCode.code(), reason),
                topology.exchange(),
                topology.routingKey());
        MessageEnvelope<?> dlqEnvelope = new MessageEnvelope<>(envelope.schemaVersion(), envelope.payload(), headers);
        send(
                topology.deadLetterExchange(),
                topology.deadLetterRoutingKey(),
                dlqEnvelope,
                headers,
                original,
                null,
                MqErrorCode.MQ_DLQ_FORWARD_FAILED);
    }

    private void send(
            String exchange,
            String routingKey,
            MessageEnvelope<?> envelope,
            Map<String, Object> headers,
            Message original,
            Duration expiration,
            MqErrorCode forwardErrorCode) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, envelope, message -> {
                MessageProperties properties = message.getMessageProperties();
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                headers.forEach(properties::setHeader);
                copyOriginalMessageId(original, properties);
                if (expiration != null && !expiration.isZero()) {
                    properties.setExpiration(String.valueOf(expiration.toMillis()));
                }
                return message;
            }, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(this.properties.getPublishConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (correlationData.getReturned() != null) {
                var returned = correlationData.getReturned();
                throw new PixFlowException(
                        MqErrorCode.MQ_PUBLISH_RETURNED,
                        "MQ forward was returned: " + returned.getReplyText(),
                        null,
                        Map.of(
                                "exchange", returned.getExchange(),
                                "routingKey", returned.getRoutingKey(),
                                "replyCode", returned.getReplyCode(),
                                "replyText", returned.getReplyText()));
            }
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "publisher confirm returned null" : confirm.getReason();
                throw new PixFlowException(
                        MqErrorCode.MQ_PUBLISH_NACKED,
                        "MQ forward was not confirmed: " + reason,
                        null,
                        Map.of("exchange", exchange, "routingKey", routingKey, "forwardErrorCode", forwardErrorCode.code()));
            }
        } catch (AmqpException ex) {
            throw new PixFlowException(forwardErrorCode, "MQ forward failed", ex);
        } catch (TimeoutException ex) {
            throw new PixFlowException(
                    MqErrorCode.MQ_CONFIRM_TIMEOUT,
                    "MQ forward confirm timed out",
                    ex,
                    Map.of("exchange", exchange, "routingKey", routingKey, "forwardErrorCode", forwardErrorCode.code()));
        } catch (PixFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PixFlowException(forwardErrorCode, "MQ forward confirm failed", ex);
        }
    }

    private void copyOriginalMessageId(Message original, MessageProperties target) {
        String messageId = original.getMessageProperties().getMessageId();
        if (messageId != null) {
            target.setMessageId(messageId);
        }
    }

    private void sleepQuietly(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
