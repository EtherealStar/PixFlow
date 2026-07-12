package com.pixflow.module.dag.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CanonicalDagFactory {
    private final ObjectMapper objectMapper;

    public CanonicalDagFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public CanonicalDag fromDocument(DagDocument dag, DagSchemaVersion schemaVersion) {
        List<DagNode> nodes = dag.nodes().stream().sorted(Comparator.comparing(DagNode::id)).toList();
        List<DagEdge> edges = dag.edges().stream()
                .sorted(Comparator.comparing(DagEdge::from).thenComparing(DagEdge::to)).toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", schemaVersion.raw());
        canonical.put("nodes", nodes.stream().map(this::nodeValue).toList());
        canonical.put("edges", edges);
        byte[] json = CanonicalJson.canonicalize(objectMapper.valueToTree(canonical));
        return new CanonicalDag(json, sha256(json), schemaVersion.raw(), nodes, edges);
    }

    private Map<String, Object> nodeValue(DagNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", node.id());
        value.put("tool", node.tool().wireName());
        value.put("params", node.params());
        return value;
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
