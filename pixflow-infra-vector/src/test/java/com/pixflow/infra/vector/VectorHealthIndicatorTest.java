package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class VectorHealthIndicatorTest {
    @Test
    void reportsDownWithStableCategoryAndDegradedMetric() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.healthCheckAsync(any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.PERMISSION_DENIED.asRuntimeException()));
        VectorMetrics metrics = mock(VectorMetrics.class);
        QdrantVectorSearch search = new QdrantVectorSearch(client, new VectorProperties(), metrics);

        org.springframework.boot.actuate.health.Health health = new VectorHealthIndicator(search).health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
        assertThat(health.getDetails()).containsEntry("kind", "DEPENDENCY");
        assertThat(health.getDetails().toString()).doesNotContain("PERMISSION_DENIED");
        verify(metrics).recordOperation(org.mockito.ArgumentMatchers.eq("health"),
                org.mockito.ArgumentMatchers.eq("degraded"), any(Duration.class));
    }
}
