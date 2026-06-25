package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.module.dag.domain.Branch;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchExpanderTest {

    private final BranchExpander expander = new BranchExpander();

    private DagNode node(String id) {
        return new DagNode(id, "remove_bg");
    }

    @Test
    void linearChainProducesSingleBranch() {
        // n1 -> n2 -> n3
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3")),
                Arrays.asList(new DagEdge("n1", "n2"), new DagEdge("n2", "n3")));

        List<Branch> branches = expander.expand(dag);

        assertEquals(1, branches.size());
        assertEquals(Arrays.asList("n1", "n2", "n3"), branches.get(0).getNodeSequence());
    }

    @Test
    void singleNodeIsItsOwnBranch() {
        Dag dag = new Dag(Collections.singletonList(node("n1")), Collections.emptyList());

        List<Branch> branches = expander.expand(dag);

        assertEquals(1, branches.size());
        assertEquals(Collections.singletonList("n1"), branches.get(0).getNodeSequence());
    }

    @Test
    void emptyDagHasNoBranches() {
        Dag dag = new Dag(Collections.emptyList(), Collections.emptyList());

        assertTrue(expander.expand(dag).isEmpty());
    }

    @Test
    void forkProducesOneBranchPerSink() {
        // n1 -> n2 (sink), n1 -> n3 (sink): two independent output paths
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3")),
                Arrays.asList(new DagEdge("n1", "n2"), new DagEdge("n1", "n3")));

        List<Branch> branches = expander.expand(dag);

        assertEquals(2, branches.size());
        Set<List<String>> sequences = branches.stream()
                .map(Branch::getNodeSequence)
                .collect(Collectors.toSet());
        assertTrue(sequences.contains(Arrays.asList("n1", "n2")));
        assertTrue(sequences.contains(Arrays.asList("n1", "n3")));
    }

    @Test
    void diamondProducesTwoDistinctPaths() {
        // n1 -> n2 -> n4, n1 -> n3 -> n4
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3"), node("n4")),
                Arrays.asList(
                        new DagEdge("n1", "n2"),
                        new DagEdge("n1", "n3"),
                        new DagEdge("n2", "n4"),
                        new DagEdge("n3", "n4")));

        List<Branch> branches = expander.expand(dag);

        assertEquals(2, branches.size());
        Set<List<String>> sequences = branches.stream()
                .map(Branch::getNodeSequence)
                .collect(Collectors.toSet());
        assertTrue(sequences.contains(Arrays.asList("n1", "n2", "n4")));
        assertTrue(sequences.contains(Arrays.asList("n1", "n3", "n4")));
    }

    @Test
    void independentBranchesFromMultipleSources() {
        // copy branch (independent) + pixel branch
        // c1 (sink), n1 -> n2 (sink)
        Dag dag = new Dag(
                Arrays.asList(node("c1"), node("n1"), node("n2")),
                Arrays.asList(new DagEdge("n1", "n2")));

        List<Branch> branches = expander.expand(dag);

        assertEquals(2, branches.size());
        Set<List<String>> sequences = branches.stream()
                .map(Branch::getNodeSequence)
                .collect(Collectors.toSet());
        assertTrue(sequences.contains(Collections.singletonList("c1")));
        assertTrue(sequences.contains(Arrays.asList("n1", "n2")));
    }

    @Test
    void branchIdsAreUnique() {
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3"), node("n4")),
                Arrays.asList(
                        new DagEdge("n1", "n2"),
                        new DagEdge("n1", "n3"),
                        new DagEdge("n2", "n4"),
                        new DagEdge("n3", "n4")));

        List<Branch> branches = expander.expand(dag);

        List<String> ids = branches.stream().map(Branch::getBranchId).collect(Collectors.toList());
        Set<String> uniqueIds = new HashSet<>(ids);
        assertEquals(ids.size(), uniqueIds.size());
    }

    @Test
    void expansionIsDeterministic() {
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3")),
                Arrays.asList(new DagEdge("n1", "n2"), new DagEdge("n1", "n3")));

        assertEquals(expander.expand(dag), expander.expand(dag));
    }

    @Test
    void cyclicInputDoesNotRecurseInfinitely() {
        // n1 -> n2 -> n1 (cycle) plus a real sink n2 -> n3
        Dag dag = new Dag(
                Arrays.asList(node("n1"), node("n2"), node("n3")),
                Arrays.asList(
                        new DagEdge("n1", "n2"),
                        new DagEdge("n2", "n1"),
                        new DagEdge("n2", "n3")));

        // Should terminate; n1 has no incoming-free source here, so guard via traversal.
        List<Branch> branches = new ArrayList<>(expander.expand(dag));
        // Reaching here without StackOverflow is the assertion of interest.
        assertTrue(branches.stream().allMatch(b -> !b.getNodeSequence().isEmpty()));
    }
}
