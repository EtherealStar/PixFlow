package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.List;
import java.util.Set;

public record EvidencePack(String hash, List<EvidenceEntry> entries, String availabilityError) {
    public EvidencePack { entries = List.copyOf(entries); }
    public EvidencePack(String hash, List<EvidenceEntry> entries) { this(hash, entries, null); }
    public List<EvidenceEntry> view(Set<EvidenceType> allowed) {
        return entries.stream().filter(entry -> allowed.contains(entry.type())).toList();
    }
}
