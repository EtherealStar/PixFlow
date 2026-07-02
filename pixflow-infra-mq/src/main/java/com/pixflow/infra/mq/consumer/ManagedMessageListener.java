package com.pixflow.infra.mq.consumer;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.retry.RetryHeaders;
import com.pixflow.infra.mq.rocket.RocketMessageCodec;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import com.pixflow.infra.mq.trace.TraceScope;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagedMessageListener<T> {
    private static final Logger log = LoggerFactory.getLogger(ManagedMessageListener.class);
    private final ConsumerBinding binding;
    private final Class<T> payloadType;
    private final ManagedMessageHandler<T> handler;
    private final ConsumerErrorHandler errorHandler;
    private final RocketMessageCodec codec;
    private final ErrorNormalizer errorNormalizer;
    private final TraceHeaderPropagator traceHeaderPropagator;
    private final MqMetrics metrics;
    private final MqProperties properties;

    public ManagedMessageListener(ConsumerBinding binding, Class<T> payloadType, ManagedMessageHandler<T> handler,
            ConsumerErrorHandler errorHandler, RocketMessageCodec codec, ErrorNormalizer errorNormalizer,
            TraceHeaderPropagator traceHeaderPropagator, MqMetrics metrics, MqProperties properties) {
        this.binding = binding;
        this.payloadType = payloadType;
        this.handler = handler;
        this.errorHandler = errorHandler;
        this.codec = codec;
        this.errorNormalizer = errorNormalizer;
        this.traceHeaderPropagator = traceHeaderPropagator;
        this.metrics = metrics;
        this.properties = properties;
    }

    public ListenerResult onMessage(InboundMessage message) {
        MessageEnvelope<T> envelope;
        try {
            envelope = codec.decode(message.body(), message.headers(), payloadType);
        } catch (Exception ex) {
            log.warn("RocketMQ message deserialization failed, topic={}, group={}, messageId={}, reason={}",
                    binding.topic(), binding.consumerGroup(), message.messageId(), Sanitizer.sanitizeMessage(ex.getMessage()));
            metrics.recordConsumeDeadLetter(binding.topic(), binding.consumerGroup());
            return ListenerResult.SUCCESS;
        }
        try (TraceScope ignored = traceHeaderPropagator.restore(envelope.headers())) {
            if (!envelope.supportedVersion()) {
                log.warn("RocketMQ message schema unsupported, topic={}, group={}, schemaVersion={}",
                        binding.topic(), binding.consumerGroup(), envelope.schemaVersion());
                metrics.recordConsumeDeadLetter(binding.topic(), binding.consumerGroup());
                return ListenerResult.SUCCESS;
            }
            try {
                handleWithInProcessRetries(envelope);
                metrics.recordConsumeAck(binding.topic(), binding.consumerGroup());
                return ListenerResult.SUCCESS;
            } catch (Exception error) {
                return handleFailure(envelope, error, message.brokerRetryCount());
            }
        }
    }

    private ListenerResult handleFailure(MessageEnvelope<T> envelope, Throwable error, int brokerRetryCount) {
        PixFlowException normalized = errorNormalizer.normalize(error);
        int retryCount = Math.max(RetryHeaders.retryCount(envelope.headers()), brokerRetryCount);
        RetryDecision decision = errorHandler.onError(envelope, normalized, retryCount);
        if (decision instanceof RetryDecision.Retry && retryCount < properties.getMaxRetries()) {
            metrics.recordConsumeRetry(binding.topic(), binding.consumerGroup(), retryCount + 1);
            return ListenerResult.RECONSUME_LATER;
        }
        if (decision instanceof RetryDecision.AckDrop) {
            metrics.recordConsumeAckDrop(binding.topic(), binding.consumerGroup());
            return ListenerResult.SUCCESS;
        }
        String reason = decision instanceof RetryDecision.DeadLetter deadLetter ? deadLetter.reason() : normalized.getMessage();
        log.error("RocketMQ message moved to terminal failure path, topic={}, group={}, reason={}",
                binding.topic(), binding.consumerGroup(), Sanitizer.sanitizeMessage(reason));
        metrics.recordConsumeDeadLetter(binding.topic(), binding.consumerGroup());
        return ListenerResult.SUCCESS;
    }

    private void handleWithInProcessRetries(MessageEnvelope<T> envelope) throws Exception {
        int attempts = Math.max(0, properties.getInProcessRetries()) + 1;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try { handler.handle(envelope); return; }
            catch (Exception ex) { last = ex; if (i + 1 >= attempts) break; sleepQuietly(Duration.ofMillis(50L * (i + 1))); }
        }
        throw last;
    }

    private void sleepQuietly(Duration delay) {
        try { Thread.sleep(delay.toMillis()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public enum ListenerResult { SUCCESS, RECONSUME_LATER }
    public record InboundMessage(byte[] body, Map<String, Object> headers, int brokerRetryCount, String messageId) {}
}
