package com.etherealstar.pixflow.module.dag.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A parsed tool-orchestration DAG.
 *
 * <p>Domain object as described in design.md: {@code Dag { nodes[], edges[] }}.
 * Provides read-only adjacency helpers used by the topological sorter and the
 * branch expander. Node declaration order is preserved to keep downstream
 * traversals deterministic.
 */
public final class Dag {

    private final List<DagNode> nodes;
    private final List<DagEdge> edges;
    private final Map<String, DagNode> nodeById;

    public Dag(List<DagNode> nodes, List<DagEdge> edges) {
        this.nodes = Collections.unmodifiableList(new ArrayList<>(
                nodes == null ? Collections.emptyList() : nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(
                edges == null ? Collections.emptyList() : edges));
        Map<String, DagNode> index = new LinkedHashMap<>();
        for (DagNode node : this.nodes) {
            index.put(node.getId(), node);
        }
        this.nodeById = Collections.unmodifiableMap(index);
    }

    public List<DagNode> getNodes() {
        return nodes;
    }

    public List<DagEdge> getEdges() {
        return edges;
    }

    public DagNode getNode(String id) {
        return nodeById.get(id);
    }

    public boolean containsNode(String id) {
        return nodeById.containsKey(id);
    }

    /**
     * Returns the ids of the direct successors of {@code nodeId}, preserving
     * edge declaration order and de-duplicating parallel edges.
     */
    public List<String> successors(String nodeId) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DagEdge edge : edges) {
            if (edge.getFrom().equals(nodeId) && seen.add(edge.getTo())) {
                result.add(edge.getTo());
            }
        }
        return result;
    }

    /**
     * Source nodes are nodes with no incoming edge.
     */
    public List<String> sources() {
        Set<String> withIncoming = new LinkedHashSet<>();
        for (DagEdge edge : edges) {
            withIncoming.add(edge.getTo());
        }
        List<String> result = new ArrayList<>();
        for (DagNode node : nodes) {
            if (!withIncoming.contains(node.getId())) {
                result.add(node.getId());
            }
        }
        return result;
    }

    /**
     * Sink nodes are nodes with no outgoing edge.
     */
    public List<String> sinks() {
        Set<String> withOutgoing = new LinkedHashSet<>();
        for (DagEdge edge : edges) {
            withOutgoing.add(edge.getFrom());
        }
        List<String> result = new ArrayList<>();
        for (DagNode node : nodes) {
            if (!withOutgoing.contains(node.getId())) {
                result.add(node.getId());
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Dag)) {
            return false;
        }
        Dag dag = (Dag) o;
        return nodes.equals(dag.nodes) && edges.equals(dag.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }

    @Override
    public String toString() {
        return "Dag{nodes=" + nodes + ", edges=" + edges + '}';
    }
}
