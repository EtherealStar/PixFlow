package com.pixflow.module.dag.ir;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalDagFactoryTest {
    @Test
    void equivalentDagOrderProducesSameCanonicalHash() {
        DagNode a = new DagNode("a", PixelTool.RESIZE, Map.of("height", 20, "width", 10));
        DagNode b = new DagNode("b", PixelTool.REMOVE_BG, Map.of());
        CanonicalDagFactory factory = new CanonicalDagFactory(new ObjectMapper());

        CanonicalDag first = factory.fromDocument(new DagDocument(List.of(a, b),
                List.of(new DagEdge("a", "b"))), new DagSchemaVersion("1.0"));
        CanonicalDag second = factory.fromDocument(new DagDocument(List.of(b, a),
                List.of(new DagEdge("a", "b"))), new DagSchemaVersion("1.0"));

        assertThat(first.canonicalHash()).isEqualTo(second.canonicalHash());
        assertThat(first.canonicalJson()).containsExactly(second.canonicalJson());
    }
}
