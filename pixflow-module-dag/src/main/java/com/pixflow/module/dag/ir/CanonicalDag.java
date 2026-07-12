package com.pixflow.module.dag.ir;

import java.util.List;
import java.util.Objects;

public record CanonicalDag(byte[] canonicalJson, String canonicalHash, String schemaVersion,
                           List<DagNode> nodes, List<DagEdge> edges) {
    public CanonicalDag {
        canonicalJson = canonicalJson.clone();
        canonicalHash = Objects.requireNonNull(canonicalHash);
        schemaVersion = Objects.requireNonNull(schemaVersion);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
    @Override public byte[] canonicalJson() { return canonicalJson.clone(); }
}
