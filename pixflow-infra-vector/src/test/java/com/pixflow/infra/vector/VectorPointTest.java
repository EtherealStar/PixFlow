package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorPointTest {

    @Test
    void copiesVectorDefensively() {
        float[] vector = new float[] {1.0f, 2.0f};
        VectorPoint point = new VectorPoint("550e8400-e29b-41d4-a716-446655440000", vector, Map.of());

        vector[0] = 9.0f;
        float[] returned = point.vector();
        returned[1] = 8.0f;

        assertThat(point.vector()).containsExactly(1.0f, 2.0f);
    }

    @Test
    void copiesPayloadDefensively() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "test");
        VectorPoint point = new VectorPoint("550e8400-e29b-41d4-a716-446655440000", new float[] {1.0f}, payload);

        payload.put("source", "changed");

        assertThat(point.payload()).containsEntry("source", "test");
        assertThatThrownBy(() -> point.payload().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void vectorPropertiesRejectInvalidRetryConfiguration() {
        VectorProperties properties = new VectorProperties();

        assertThatThrownBy(() -> properties.setTimeout(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> properties.getRetry().setMaxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> properties.getRetry().setWaitDuration(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wait-duration");

        properties.getRetry().setWaitDuration(Duration.ofMillis(10));
        assertThat(properties.getRetry().getWaitDuration()).isEqualTo(Duration.ofMillis(10));
    }
}
