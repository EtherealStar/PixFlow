package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.module.dag.domain.Branch;
import com.etherealstar.pixflow.module.dag.domain.Dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Identifies the independent branches (output paths) of a DAG.
 *
 * <p>A branch is a path from a source node (no incoming edge) to a sink node
 * (no outgoing edge). Each sink represents a produced output file, so every
 * distinct source&rarr;sink path becomes one {@link Branch}. The DAG engine
 * persists one {@code process_result} record per branch output, with each
 * branch carrying a distinct {@code branchId} (需求 9.1, 9.2).
 *
 * <p>The expander assumes the DAG is acyclic (guaranteed by the DAG_Validator
 * before execution) but still guards against revisiting a node within a single
 * path so a malformed cyclic input cannot cause infinite recursion.
 *
 * <p>Traversal is deterministic: sources are explored in node declaration order
 * and successors in edge declaration order, so the same DAG always yields the
 * same branches with the same ids.
 */
@Component
public class BranchExpander {

    private static final String BRANCH_ID_PREFIX = "branch-";

    /**
     * Expands the DAG into its independent source-to-sink branches.
     *
     * @param dag the DAG to expand (must not be {@code null})
     * @return the branches in deterministic order, each with a unique branchId
     */
    public List<Branch> expand(Dag dag) {
        if (dag == null) {
            throw new IllegalArgumentException("dag must not be null");
        }

        List<Branch> branches = new ArrayList<>();
        int[] branchCounter = {0};

        for (String source : dag.sources()) {
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new HashSet<>();
            walk(dag, source, path, onPath, branches, branchCounter);
        }

        return branches;
    }

    private void walk(Dag dag,
                      String nodeId,
                      Deque<String> path,
                      Set<String> onPath,
                      List<Branch> branches,
                      int[] branchCounter) {
        // Guard against cycles: never revisit a node already on the current path.
        if (!onPath.add(nodeId)) {
            return;
        }
        path.addLast(nodeId);

        List<String> successors = dag.successors(nodeId);
        if (successors.isEmpty()) {
            // Reached a sink: the current path is a complete branch.
            branches.add(new Branch(nextBranchId(branchCounter), new ArrayList<>(path)));
        } else {
            for (String next : successors) {
                walk(dag, next, path, onPath, branches, branchCounter);
            }
        }

        path.removeLast();
        onPath.remove(nodeId);
    }

    private String nextBranchId(int[] branchCounter) {
        return BRANCH_ID_PREFIX + (branchCounter[0]++);
    }
}
