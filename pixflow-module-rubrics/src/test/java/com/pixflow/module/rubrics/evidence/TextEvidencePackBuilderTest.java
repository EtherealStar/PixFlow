package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.CopyResultSubject;
import com.pixflow.module.rubrics.subject.TaskDecisionSubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextEvidencePackBuilderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static TraceEvidenceProvider noTrace() {
        return subject -> List.of();
    }

    @Test
    void enforcesTheTextLimitInUtf8Bytes() {
        TextEvidencePackBuilder builder = new TextEvidencePackBuilder(CLOCK, noTrace());
        CopyResultSubject subject = new CopyResultSubject(
                "1", 2, "中".repeat(30_000), null, null, Instant.EPOCH, "snapshot");

        assertThatThrownBy(() -> builder.build(subject))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 KiB");
    }

    @Test
    void taskDecisionPackExposesDagSnapshotAndTraceSpans() {
        // trace provider 返回两条 span，验证它们与 DAG_SNAPSHOT 一起进入 pack 且 criterion view 能按类型过滤。
        TraceEvidenceProvider provider = subject -> List.of(
                new EvidenceEntry(
                        "T1", EvidenceType.TRACE_SPAN, "trace:c1:1",
                        "span-hash-1", Instant.EPOCH, java.util.Map.of("turnNo", 1, "toolCalls", "[]")),
                new EvidenceEntry(
                        "T2", EvidenceType.TRACE_SPAN, "trace:c1:2",
                        "span-hash-2", Instant.EPOCH, java.util.Map.of("turnNo", 2, "toolCalls", "[]")));
        TextEvidencePackBuilder builder = new TextEvidencePackBuilder(CLOCK, provider);
        TaskDecisionSubject subject = new TaskDecisionSubject(
                "7@rev", 7, "rev", "IMAGE_PROCESS", "c1", 3L,
                "{\"nodes\":[]}", "1.0", Instant.EPOCH, "snapshot");

        EvidencePack pack = builder.build(subject);

        assertThat(pack.failure()).isNull();
        // DAG_SNAPSHOT 与 TRACE_SPAN 各自只对声明该类型的 criterion 可见。
        assertThat(pack.view(Set.of(EvidenceType.DAG_SNAPSHOT)))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.id()).isEqualTo("E1");
                    assertThat(entry.type()).isEqualTo(EvidenceType.DAG_SNAPSHOT);
                });
        assertThat(pack.view(Set.of(EvidenceType.TRACE_SPAN))).hasSize(2);
    }

    @Test
    void taskDecisionWithoutTraceStillProducesDagOnlyPack() {
        // best-effort：trace 缺失时不伪造 span，pack 仅含 DAG_SNAPSHOT。
        TextEvidencePackBuilder builder = new TextEvidencePackBuilder(CLOCK, noTrace());
        TaskDecisionSubject subject = new TaskDecisionSubject(
                "7@rev", 7, "rev", "IMAGE_PROCESS", "c1", 3L,
                "{\"nodes\":[]}", "1.0", Instant.EPOCH, "snapshot");

        EvidencePack pack = builder.build(subject);

        assertThat(pack.failure()).isNull();
        assertThat(pack.view(Set.of(EvidenceType.TRACE_SPAN))).isEmpty();
        assertThat(pack.view(Set.of(EvidenceType.DAG_SNAPSHOT))).hasSize(1);
    }
}
