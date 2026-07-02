package com.pixflow.module.vision.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CopyEnrichmentDestinationTest {
    @Test
    void usesVisionRocketMqDestination() {
        var destination = CopyEnrichmentDestination.destination(42L);
        var binding = CopyEnrichmentDestination.binding();
        assertThat(destination.topic()).isEqualTo("pixflow-vision");
        assertThat(destination.tag()).isEqualTo("COPY_ENRICH");
        assertThat(destination.keys()).containsExactly("package:42");
        assertThat(binding.consumerGroup()).isEqualTo("pixflow-vision-enricher");
    }
}
