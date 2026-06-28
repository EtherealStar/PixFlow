package com.pixflow.infra.mq;

import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitMessagePublisher implements MessagePublisher {
    private final RabbitTemplate rabbitTemplate;
    private final TraceHeaderPropagator traceHeaderPropagator;
    private final MqMetrics metrics;

    public RabbitMessagePublisher(
            RabbitTemplate rabbitTemplate,
            TraceHeaderPropagator traceHeaderPropagator,
            MqMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.traceHeaderPropagator = traceHeaderPropagator;
        this.metrics = metrics;
    }

    @Override
    public PublishResult publish(PublishRequest request) {
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);
        Map<String, Object> headers = traceHeaderPropagator.inject(request.headers());
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(request.schemaVersion(), request.payload(), headers);

        try {
            rabbitTemplate.convertAndSend(
                    request.exchange(),
                    request.routingKey(),
                    envelope,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        headers.forEach(message.getMessageProperties()::setHeader);
                        return message;
                    },
                    correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(request.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                return failed(request, correlationId, new PublishFailure(
                        PublishFailureType.RETURNED,
                        returned.getReplyText(),
                        returned.getReplyCode(),
                        returned.getExchange(),
                        returned.getRoutingKey()));
            }
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "publisher confirm returned null" : confirm.getReason();
                return failed(request, correlationId, new PublishFailure(
                        PublishFailureType.NACKED,
                        reason,
                        null,
                        null,
                        null));
            }
            metrics.recordPublishConfirmed(request.exchange(), request.routingKey());
            return PublishResult.confirmed(request.exchange(), request.routingKey(), correlationId);
        } catch (TimeoutException ex) {
            correlationData.getFuture().cancel(true);
            return failed(request, correlationId, new PublishFailure(
                    PublishFailureType.CONFIRM_TIMEOUT,
                    "publisher confirm timed out after " + request.confirmTimeout(),
                    null,
                    null,
                    null));
        } catch (AmqpException ex) {
            return failed(request, correlationId, new PublishFailure(
                    PublishFailureType.BROKER_UNAVAILABLE,
                    ex.getMessage(),
                    null,
                    null,
                    null));
        } catch (RuntimeException ex) {
            return failed(request, correlationId, new PublishFailure(
                    PublishFailureType.SERIALIZATION_FAILED,
                    ex.getMessage(),
                    null,
                    null,
                    null));
        } catch (Exception ex) {
            return failed(request, correlationId, new PublishFailure(
                    PublishFailureType.UNKNOWN,
                    ex.getMessage(),
                    null,
                    null,
                    null));
        }
    }

    private PublishResult failed(PublishRequest request, String correlationId, PublishFailure failure) {
        metrics.recordPublishFailed(request.exchange(), request.routingKey(), failure.type());
        return PublishResult.failed(request.exchange(), request.routingKey(), correlationId, failure);
    }
}
