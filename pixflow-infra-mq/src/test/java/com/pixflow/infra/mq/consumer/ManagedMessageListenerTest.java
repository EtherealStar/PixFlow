package com.pixflow.infra.mq.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.rocket.RocketMessageCodec;
import com.pixflow.infra.mq.trace.MdcTraceHeaderPropagator;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedMessageListenerTest {

    @Test
    void retriesCountsZeroThroughTwoAndTerminatesAtCountThree() throws Exception {
        ManagedMessageHandler<TestPayload> handler = failingHandler();
        ConsumerErrorHandler errorHandler = (envelope, error, retryCount) ->
                new RetryDecision.Retry(Duration.ZERO, "transient");
        MqMetrics metrics = mock(MqMetrics.class);
        ManagedMessageListener<TestPayload> listener = listener(handler, errorHandler, metrics);

        assertThat(listener.onMessage(message(0, MessageEnvelope.CURRENT_SCHEMA_VERSION)))
                .isEqualTo(ManagedMessageListener.ListenerResult.RECONSUME_LATER);
        assertThat(listener.onMessage(message(1, MessageEnvelope.CURRENT_SCHEMA_VERSION)))
                .isEqualTo(ManagedMessageListener.ListenerResult.RECONSUME_LATER);
        assertThat(listener.onMessage(message(2, MessageEnvelope.CURRENT_SCHEMA_VERSION)))
                .isEqualTo(ManagedMessageListener.ListenerResult.RECONSUME_LATER);
        assertThat(listener.onMessage(message(3, MessageEnvelope.CURRENT_SCHEMA_VERSION)))
                .isEqualTo(ManagedMessageListener.ListenerResult.SUCCESS);

        verify(handler, times(4)).handle(any());
        verify(metrics).recordConsumeRetry("transport-events", "transport-workers", 1);
        verify(metrics).recordConsumeRetry("transport-events", "transport-workers", 2);
        verify(metrics).recordConsumeRetry("transport-events", "transport-workers", 3);
        verify(metrics).recordConsumeDeadLetter("transport-events", "transport-workers");
    }

    @Test
    void ackDropAlwaysAcknowledgesWithoutBrokerRetry() throws Exception {
        ManagedMessageHandler<TestPayload> handler = failingHandler();
        ConsumerErrorHandler errorHandler = (envelope, error, retryCount) ->
                new RetryDecision.AckDrop("invalid input");
        MqMetrics metrics = mock(MqMetrics.class);
        ManagedMessageListener<TestPayload> listener = listener(handler, errorHandler, metrics);

        assertThat(listener.onMessage(message(0, MessageEnvelope.CURRENT_SCHEMA_VERSION)))
                .isEqualTo(ManagedMessageListener.ListenerResult.SUCCESS);

        verify(metrics).recordConsumeAckDrop("transport-events", "transport-workers");
        verify(metrics, never()).recordConsumeRetry(any(), any(), anyInt());
    }

    @Test
    void unsupportedSchemaDoesNotInvokeBusinessHandler() throws Exception {
        ManagedMessageHandler<TestPayload> handler = mock(ManagedMessageHandler.class);
        ConsumerErrorHandler errorHandler = mock(ConsumerErrorHandler.class);
        MqMetrics metrics = mock(MqMetrics.class);
        ManagedMessageListener<TestPayload> listener = listener(handler, errorHandler, metrics);

        assertThat(listener.onMessage(message(0, MessageEnvelope.CURRENT_SCHEMA_VERSION + 1)))
                .isEqualTo(ManagedMessageListener.ListenerResult.SUCCESS);

        verify(handler, never()).handle(any());
        verify(errorHandler, never()).onError(any(), any(), anyInt());
        verify(metrics).recordConsumeDeadLetter("transport-events", "transport-workers");
    }

    @SuppressWarnings("unchecked")
    private ManagedMessageHandler<TestPayload> failingHandler() throws Exception {
        ManagedMessageHandler<TestPayload> handler = mock(ManagedMessageHandler.class);
        doThrow(new IllegalStateException("temporary failure")).when(handler).handle(any());
        return handler;
    }

    private ManagedMessageListener<TestPayload> listener(
            ManagedMessageHandler<TestPayload> handler,
            ConsumerErrorHandler errorHandler,
            MqMetrics metrics) {
        MqProperties properties = new MqProperties();
        properties.setInProcessRetries(0);
        return new ManagedMessageListener<>(
                ConsumerBinding.of("transport-events", "*", "transport-workers", TestPayload.class),
                TestPayload.class,
                handler,
                errorHandler,
                new RocketMessageCodec(new ObjectMapper(), new MdcTraceHeaderPropagator()),
                new ErrorNormalizer(),
                new MdcTraceHeaderPropagator(),
                metrics,
                properties);
    }

    private ManagedMessageListener.InboundMessage message(int brokerRetryCount, int schemaVersion) throws Exception {
        MessageEnvelope<TestPayload> envelope = new MessageEnvelope<>(
                schemaVersion,
                new TestPayload("event-1"),
                Map.of());
        return new ManagedMessageListener.InboundMessage(
                new ObjectMapper().writeValueAsBytes(envelope),
                Map.of(),
                brokerRetryCount,
                "message-1");
    }

    private record TestPayload(String id) {
    }
}
