package com.etherealstar.pixflow.module.dag.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single tool node in a DAG.
 *
 * <p>Domain object as described in design.md: {@code DagNode { id, tool, params }}.
 */
public final class DagNode {

    private final String id;
    private final String tool;
    private final Map<String, Object> params;

    public DagNode(String id, String tool, Map<String, Object> params) {
        this.id = Objects.requireNonNull(id, "node id must not be null");
        this.tool = tool;
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(params));
    }

    public DagNode(String id, String tool) {
        this(id, tool, null);
    }

    public String getId() {
        return id;
    }

    public String getTool() {
        return tool;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DagNode)) {
            return false;
        }
        DagNode dagNode = (DagNode) o;
        return id.equals(dagNode.id)
                && Objects.equals(tool, dagNode.tool)
                && params.equals(dagNode.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tool, params);
    }

    @Override
    public String toString() {
        return "DagNode{id='" + id + "', tool='" + tool + "', params=" + params + '}';
    }
}
