package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologicalSorterTest {

    private final TopologicalSorter sorter = new TopologicalSorter();

    private DagNode node(String id) {
        return new DagNode(id, "resize", new HashMap<>());
    }

    private Dag dag(List<DagNode> nodes, List<DagEdge> edges) {
        return new Dag(nodes, edges);
    }

    @Test
    void emptyDagYieldsNoLayers() {
        assertEquals(0, sorter.sortIntoLayers(
                new Dag(Collections.emptyList(), Collections.emptyList())).size());
        assertEquals(0, sorter.sortIntoLayers(null).size());
    }

    @Test
    void singleNodeFormsOneLayer() {
        Dag d = dag(List.of(node("n1")), Collections.emptyList());
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);
        assertEquals(1, layers.size());
        assertEquals("n1", layers.get(0).get(0).getId());
    }

    @Test
    void linearChainProducesOneNodePerLayer() {
        // n1 -> n2 -> n3
        Dag d = dag(
                List.of(node("n1"), node("n2"), node("n3")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n2", "n3")));
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);
        assertEquals(3, layers.size());
        assertEquals("n1", layers.get(0).get(0).getId());
        assertEquals("n2", layers.get(1).get(0).getId());
        assertEquals("n3", layers.get(2).get(0).getId());
    }

    @Test
    void independentNodesShareSameLayer() {
        // n1, n2 have no edges -> same first layer, sorted by id
        Dag d = dag(List.of(node("n2"), node("n1")), Collections.emptyList());
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);
        assertEquals(1, layers.size());
        assertEquals(2, layers.get(0).size());
        assertEquals("n1", layers.get(0).get(0).getId());
        assertEquals("n2", layers.get(0).get(1).getId());
    }

    @Test
    void diamondPlacesNodesInCorrectLayers() {
        // n1 -> n2, n1 -> n3, n2 -> n4, n3 -> n4
        Dag d = dag(
                List.of(node("n1"), node("n2"), node("n3"), node("n4")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n1", "n3"),
                        new DagEdge("n2", "n4"), new DagEdge("n3", "n4")));
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);
        assertEquals(3, layers.size());
        assertEquals(List.of("n1"), ids(layers.get(0)));
        assertEquals(List.of("n2", "n3"), ids(layers.get(1)));
        assertEquals(List.of("n4"), ids(layers.get(2)));
    }

    @Test
    void everyNodeAppearsAfterAllPredecessors() {
        // n1 -> n3, n2 -> n3, n3 -> n4
        Dag d = dag(
                List.of(node("n1"), node("n2"), node("n3"), node("n4")),
                List.of(new DagEdge("n1", "n3"), new DagEdge("n2", "n3"),
                        new DagEdge("n3", "n4")));
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);

        // Build a map of nodeId -> layer index
        Map<String, Integer> layerOf = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            for (DagNode n : layers.get(i)) {
                layerOf.put(n.getId(), i);
            }
        }
        // For each edge from->to, from's layer must be strictly earlier
        for (DagEdge e : d.getEdges()) {
            assertTrue(layerOf.get(e.getFrom()) < layerOf.get(e.getTo()),
                    "predecessor " + e.getFrom() + " must precede " + e.getTo());
        }
    }

    @Test
    void cycleThrowsCycleDetectedException() {
        // n1 -> n2 -> n3 -> n1
        Dag d = dag(
                List.of(node("n1"), node("n2"), node("n3")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n2", "n3"),
                        new DagEdge("n3", "n1")));
        CycleDetectedException ex = assertThrows(CycleDetectedException.class,
                () -> sorter.sortIntoLayers(d));
        assertNotNull(ex.getRemainingNodeIds());
        assertEquals(Set.of("n1", "n2", "n3"), Set.copyOf(ex.getRemainingNodeIds()));
        assertTrue(sorter.hasCycle(d));
    }

    @Test
    void selfLoopIsDetectedAsCycle() {
        Dag d = dag(List.of(node("n1")), List.of(new DagEdge("n1", "n1")));
        assertTrue(sorter.hasCycle(d));
        assertThrows(CycleDetectedException.class, () -> sorter.sortIntoLayers(d));
    }

    @Test
    void duplicateEdgesDoNotBreakSorting() {
        Dag d = dag(
                List.of(node("n1"), node("n2")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n1", "n2")));
        List<List<DagNode>> layers = sorter.sortIntoLayers(d);
        assertEquals(2, layers.size());
        assertEquals("n1", layers.get(0).get(0).getId());
        assertEquals("n2", layers.get(1).get(0).getId());
    }

    @Test
    void flattenedSortContainsAllNodes() {
        Dag d = dag(
                List.of(node("n1"), node("n2"), node("n3")),
                List.of(new DagEdge("n1", "n2"), new DagEdge("n2", "n3")));
        List<DagNode> ordered = sorter.sort(d);
        assertEquals(3, ordered.size());
        assertFalse(sorter.hasCycle(d));
    }

    private List<String> ids(List<DagNode> nodes) {
        List<String> result = new ArrayList<>();
        for (DagNode n : nodes) {
            result.add(n.getId());
        }
        return result;
    }

    private static void assertFalse(boolean condition) {
        assertTrue(!condition);
    }
}
