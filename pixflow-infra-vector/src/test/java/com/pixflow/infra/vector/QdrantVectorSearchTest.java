package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.grpc.Metadata;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QdrantVectorSearchTest {
    @Test
    void retriesTransientHealthFailureUpToConfiguredLimit() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.healthCheckAsync(any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException()));
        QdrantVectorSearch search = new QdrantVectorSearch(client, properties(3), mock(VectorMetrics.class));

        assertThatThrownBy(search::healthCheck)
                .isInstanceOfSatisfying(VectorException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.failureKind()).isEqualTo(VectorException.FailureKind.DEPENDENCY);
                });
        verify(client, times(3)).healthCheckAsync(any(Duration.class));
    }

    @Test
    void doesNotRetryPermissionFailureOrExposeSensitiveConfiguration() {
        QdrantClient client = mock(QdrantClient.class);
        String sensitiveDescription = "super-secret-api-key vector=[0.1,0.2] payload={long-private-text}";
        Metadata trailers = new Metadata();
        trailers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "super-secret-api-key");
        when(client.healthCheckAsync(any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(
                        Status.PERMISSION_DENIED.withDescription(sensitiveDescription).asRuntimeException(trailers)));
        VectorProperties properties = properties(3);
        properties.getQdrant().setApiKey("super-secret-api-key");
        QdrantVectorSearch search = new QdrantVectorSearch(client, properties, mock(VectorMetrics.class));

        assertThatThrownBy(search::healthCheck)
                .isInstanceOfSatisfying(VectorException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("super-secret-api-key");
                    assertThat(exception.details().toString()).doesNotContain("super-secret-api-key");
                    StringWriter output = new StringWriter();
                    exception.printStackTrace(new PrintWriter(output));
                    assertThat(output.toString())
                            .doesNotContain("super-secret-api-key", "vector=[0.1,0.2]", "long-private-text", "authorization");
                });
        verify(client).healthCheckAsync(any(Duration.class));
    }

    @Test
    void rejectsDeterministicInputBeforeCallingQdrant() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantVectorSearch search = new QdrantVectorSearch(client, properties(3), mock(VectorMetrics.class));

        assertThatThrownBy(() -> search.search(
                "collection", new float[] {Float.NaN}, 5, 0.5f, null))
                .isInstanceOfSatisfying(VectorException.class,
                        exception -> assertThat(exception.retryable()).isFalse());

        verifyNoInteractions(client);
    }

    @Test
    void rejectsOnlyNonFiniteThresholdBeforeCallingQdrant() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantVectorSearch search = new QdrantVectorSearch(client, properties(3), mock(VectorMetrics.class));

        assertThatThrownBy(() -> search.search(
                "collection", new float[] {1.0f}, 5, Float.POSITIVE_INFINITY, null))
                .isInstanceOf(VectorException.class);

        verifyNoInteractions(client);
    }

    @Test
    void doesNotRetryNotFoundFailure() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.healthCheckAsync(any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.NOT_FOUND.asRuntimeException()));
        QdrantVectorSearch search = new QdrantVectorSearch(client, properties(3), mock(VectorMetrics.class));

        assertThatThrownBy(search::healthCheck).isInstanceOf(VectorException.class);

        verify(client).healthCheckAsync(any(Duration.class));
    }

    private static VectorProperties properties(int maxAttempts) {
        VectorProperties properties = new VectorProperties();
        properties.getQdrant().getRetry().setMaxAttempts(maxAttempts);
        properties.getQdrant().getRetry().setWaitDuration(Duration.ZERO);
        return properties;
    }
}
