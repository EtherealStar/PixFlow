package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.CopyResultSubject;
import com.pixflow.module.rubrics.subject.TaskDecisionSubject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 为文本 Subject 构造有界、可哈希的系统证据。 */
public final class TextEvidencePackBuilder {
    private static final int MAX_TEXT_LENGTH = 65_536;

    private final Clock clock;

    private final TraceEvidenceProvider traces;

    public TextEvidencePackBuilder(Clock clock, TraceEvidenceProvider traces) {
        this.clock = clock;
        this.traces = traces;
    }

    public EvidencePack build(CopyResultSubject subject) {
        String text = bounded(subject.text());
        return EvidencePack.create(subject.snapshotHash(), List.of(
                textEntry("E1", EvidenceType.COPY_TEXT, "copy-result:" + subject.id(), text)));
    }

    public EvidencePack build(TaskDecisionSubject subject) {
        String proposal = bounded(subject.confirmedProposal());
        String dagSnapshot = bounded(subject.dagSnapshot());
        // trace 是 best-effort：缺失时不伪造 span，由声明 TRACE_SPAN 的 criterion 得到 MISSING_EVIDENCE。
        List<EvidenceEntry> trace = traces.trace(subject);
        List<EvidenceEntry> entries = new ArrayList<>(2 + trace.size());
        // confirmedProposal 由 Task owner 从创建时冻结的 canonical payload 提供，Rubrics 不从 DAG 或 trace 猜测需求。
        entries.add(textEntry("E1", EvidenceType.PROPOSAL,
                "task-decision:" + subject.id(), proposal));
        entries.add(textEntry("E2", EvidenceType.DAG_SNAPSHOT,
                "task-decision:" + subject.id(), dagSnapshot));
        entries.addAll(trace);
        return EvidencePack.create(subject.snapshotHash(), entries);
    }

    /** 构造以文本为内容的单条证据（COPY_TEXT / DAG_SNAPSHOT 共用同一形状）。 */
    private EvidenceEntry textEntry(String id, EvidenceType type, String sourceRef, String text) {
        return new EvidenceEntry(id, type, sourceRef, hash(text), clock.instant(), Map.of("text", text));
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("text evidence must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text evidence exceeds the 64 KiB limit");
        }
        return value;
    }

    private static String hash(String value) {
        return EvidenceHashing.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
