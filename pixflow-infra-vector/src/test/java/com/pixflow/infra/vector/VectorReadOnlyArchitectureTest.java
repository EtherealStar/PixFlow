package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.vector.config.VectorAutoConfiguration;
import io.qdrant.client.QdrantClient;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VectorReadOnlyArchitectureTest {
    @Test
    void publicContractContainsOnlyVerifySearchAndGet() {
        assertThat(Arrays.stream(VectorSearch.class.getDeclaredMethods()).map(method -> method.getName()))
                .containsExactlyInAnyOrder("verifyCollection", "search", "get");
        assertThat(Arrays.stream(ScoredPoint.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("id", "score", "payload");
    }

    @Test
    void springContextDoesNotExposeNativeQdrantClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(VectorAutoConfiguration.class))
                .withPropertyValues("pixflow.vector.qdrant.host=localhost")
                .run(context -> {
                    assertThat(context).hasSingleBean(VectorSearch.class);
                    assertThat(context).hasSingleBean(VectorHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(QdrantClient.class);
                });
    }
}
