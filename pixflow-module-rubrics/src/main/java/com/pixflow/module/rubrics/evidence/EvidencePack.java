package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.model.EvidenceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvidencePack(String hash, List<EvidenceEntry> entries, EvidenceFailure failure) {

    public EvidencePack {
        entries = List.copyOf(entries);
    }

    public EvidencePack(String hash, List<EvidenceEntry> entries) {
        this(hash, entries, null);
    }

    public static EvidencePack create(String subjectSnapshotHash, List<EvidenceEntry> entries) {
        if (subjectSnapshotHash == null || subjectSnapshotHash.isBlank()) {
            throw new IllegalArgumentException("subject snapshot hash must not be blank");
        }
        List<EvidenceEntry> stableEntries = new ArrayList<>(entries);
        stableEntries.sort(Comparator.comparing(EvidenceEntry::id));
        Set<String> ids = new HashSet<>();
        StringBuilder identity = new StringBuilder(subjectSnapshotHash);
        for (EvidenceEntry entry : stableEntries) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("evidence entry id must be unique: " + entry.id());
            }
            identity.append('\n')
                    .append(entry.id()).append('|')
                    .append(entry.type()).append('|')
                    .append(entry.sourceRef()).append('|')
                    .append(entry.contentHash()).append('|')
                    .append(hashMetadata(entry.metadata()));
        }
        String hash = EvidenceHashing.sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
        return new EvidencePack(hash, stableEntries);
    }

    public static EvidencePack unavailable(
            String subjectSnapshotHash, EvidenceFailure failure) {
        String identity = subjectSnapshotHash + "|unavailable|" + failure.kind() + "|" + failure.code();
        return new EvidencePack(EvidenceHashing.sha256(identity), List.of(), failure);
    }

    public List<EvidenceEntry> view(Set<EvidenceType> allowed) {
        return entries.stream().filter(entry -> allowed.contains(entry.type())).toList();
    }

    private static String hashMetadata(Map<String, Object> metadata) {
        String canonical = canonical(metadata);
        return EvidenceHashing.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(Object value) {
        if (value == null) {
            return frame("null", "");
        }
        if (value instanceof Map<?, ?> map) {
            String content = map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> canonical(String.valueOf(entry.getKey())) + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining());
            return frame("map", content);
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(canonical(item)));
            return frame("list", String.join("", items));
        }
        return frame(value.getClass().getName(), String.valueOf(value));
    }

    private static String frame(String type, String content) {
        return type.length() + ":" + type + content.length() + ":" + content;
    }
}
