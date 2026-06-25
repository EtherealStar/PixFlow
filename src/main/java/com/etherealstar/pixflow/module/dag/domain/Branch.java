package com.etherealstar.pixflow.module.dag.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An independent output path through a DAG (source &rarr; sink).
 *
 * <p>Domain object as described in design.md: {@code Branch { branchId, nodeSequence[] }}.
 * Each branch corresponds to one produced output file and therefore one
 * {@code process_result} record (需求 9.1, 9.2). The {@code branchId} is unique
 * within the DAG so that results for the same image carry distinct branch ids.
 */
public final class Branch {

    private final String branchId;
    private final List<String> nodeSequence;

    public Branch(String branchId, List<String> nodeSequence) {
        this.branchId = Objects.requireNonNull(branchId, "branchId must not be null");
        this.nodeSequence = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(nodeSequence, "nodeSequence must not be null")));
    }

    public String getBranchId() {
        return branchId;
    }

    /**
     * Ordered node ids from source to sink along this branch.
     */
    public List<String> getNodeSequence() {
        return nodeSequence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Branch)) {
            return false;
        }
        Branch branch = (Branch) o;
        return branchId.equals(branch.branchId) && nodeSequence.equals(branch.nodeSequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(branchId, nodeSequence);
    }

    @Override
    public String toString() {
        return "Branch{branchId='" + branchId + "', nodeSequence=" + nodeSequence + '}';
    }
}
