package com.pixflow.module.vision.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.mq.topology.QueueTopology;
import org.junit.jupiter.api.Test;

class CopyEnrichmentTopologyTest {

    @Test
    void topologyUsesVisionQueueGroup() {
        QueueTopology topology = CopyEnrichmentTopology.topology();

        assertThat(topology.exchange()).isEqualTo("pixflow.vision");
        assertThat(topology.queue()).isEqualTo("pixflow.vision.q");
        assertThat(topology.routingKey()).isEqualTo("vision.copy_enrich");
        assertThat(topology.deadLetterQueue()).isEqualTo("pixflow.vision.dlq");
        assertThat(topology.retryEnabled()).isTrue();
    }
}
