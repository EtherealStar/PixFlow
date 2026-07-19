package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.rubrics.model.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidencePackTest {

    @Test
    void identityIgnoresCaptureTimeButIncludesCanonicalMetadata() {
        EvidenceEntry first = entry(Instant.parse("2026-01-01T00:00:00Z"), Map.of(
                "width", 800,
                "nested", Map.of("format", "PNG", "alpha", true)));
        EvidenceEntry recaptured = entry(Instant.parse("2026-02-01T00:00:00Z"), Map.of(
                "nested", Map.of("alpha", true, "format", "PNG"),
                "width", 800));
        EvidenceEntry changed = entry(Instant.parse("2026-02-01T00:00:00Z"), Map.of(
                "width", 801,
                "nested", Map.of("format", "PNG", "alpha", true)));

        EvidencePack original = EvidencePack.create("snapshot-a", List.of(first));
        EvidencePack sameFacts = EvidencePack.create("snapshot-a", List.of(recaptured));
        EvidencePack changedFacts = EvidencePack.create("snapshot-a", List.of(changed));
        EvidencePack changedSnapshot = EvidencePack.create("snapshot-b", List.of(first));

        assertThat(sameFacts.hash()).isEqualTo(original.hash());
        assertThat(changedFacts.hash()).isNotEqualTo(original.hash());
        assertThat(changedSnapshot.hash()).isNotEqualTo(original.hash());
    }

    private static EvidenceEntry entry(Instant capturedAt, Map<String, Object> metadata) {
        return new EvidenceEntry(
                "E1",
                EvidenceType.IMAGE_METADATA,
                "package:1/image:2",
                "content-hash",
                capturedAt,
                metadata);
    }
}
