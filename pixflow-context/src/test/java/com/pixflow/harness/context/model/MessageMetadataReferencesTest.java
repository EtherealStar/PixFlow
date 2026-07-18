package com.pixflow.harness.context.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageMetadataReferencesTest {
    @Test
    void copiesInputAndRestoresJacksonMapShapeInOrder() {
        List<MessageReference> input = new ArrayList<>();
        input.add(new MessageReference("package:1", "summer.zip"));
        MessageMetadata metadata = MessageMetadata.empty().withReferences(input);
        input.clear();

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("referenceKey", "package:1");
        first.put("displayPathSnapshot", "summer.zip");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("referenceKey", "package:1/image:2");
        second.put("displayPathSnapshot", "summer.zip / front.png");
        MessageMetadata restored = MessageMetadata.of(Map.of(
                MessageMetadata.REFERENCES, List.of(first, second)));
        first.put("referenceKey", "forged");

        assertThat(metadata.references()).extracting(MessageReference::referenceKey)
                .containsExactly("package:1");
        assertThat(restored.references()).containsExactly(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"));
    }

    @Test
    void failsClosedForDamagedReferenceMetadata() {
        MessageMetadata metadata = MessageMetadata.of(Map.of(
                MessageMetadata.REFERENCES,
                List.of(Map.of("referenceKey", "package:1"))));

        assertThatThrownBy(metadata::references).isInstanceOf(IllegalArgumentException.class);
    }
}
