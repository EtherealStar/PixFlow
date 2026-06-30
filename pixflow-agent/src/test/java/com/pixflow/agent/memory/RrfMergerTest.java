package com.pixflow.agent.memory;

import com.pixflow.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfMergerTest {

    private RrfMerger merger;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.getMemory().getRecall().setRrfK(60);
        props.getMemory().getRecall().setMaxItems(50);
        props.getMemory().getRecall().setMaxTokens(4000);
        merger = new RrfMerger(props, new TokenEstimator());
    }

    @Test
    void single_channel_keeps_all_items() {
        Map<RecallChannel, List<MemoryItem>> channels = new LinkedHashMap<>();
        channels.put(RecallChannel.SKU_HISTORY, List.of(
                MemoryItem.of("SKU-A", RecallChannel.SKU_HISTORY, 0.9, "item a"),
                MemoryItem.of("SKU-B", RecallChannel.SKU_HISTORY, 0.7, "item b")
        ));
        RrfMerger.MergedRecall result = merger.merge(channels);
        assertEquals(2, result.items().size());
    }

    @Test
    void multiple_channels_rrf_fusion() {
        Map<RecallChannel, List<MemoryItem>> channels = new LinkedHashMap<>();
        channels.put(RecallChannel.SKU_HISTORY, List.of(
                MemoryItem.of("SKU-A", RecallChannel.SKU_HISTORY, 0.9, "a"),
                MemoryItem.of("SKU-B", RecallChannel.SKU_HISTORY, 0.7, "b")
        ));
        channels.put(RecallChannel.INSIGHT_VECTOR, List.of(
                MemoryItem.of("SKU-B", RecallChannel.INSIGHT_VECTOR, 0.95, "b-v"),
                MemoryItem.of("SKU-C", RecallChannel.INSIGHT_VECTOR, 0.8, "c-v")
        ));
        RrfMerger.MergedRecall result = merger.merge(channels);
        // 3 unique items: A, B, C
        assertEquals(3, result.items().size());
        // SKU-B should rank higher due to 2-channel fusion
        assertEquals("SKU-B", result.items().get(0).itemId());
    }

    @Test
    void empty_channels_returns_empty() {
        Map<RecallChannel, List<MemoryItem>> channels = new LinkedHashMap<>();
        RrfMerger.MergedRecall result = merger.merge(channels);
        assertTrue(result.items().isEmpty());
        assertEquals(0L, result.totalTokens());
    }

    @Test
    void max_items_caps_results() {
        AgentProperties props = new AgentProperties();
        props.getMemory().getRecall().setRrfK(60);
        props.getMemory().getRecall().setMaxItems(2);
        props.getMemory().getRecall().setMaxTokens(4000);
        RrfMerger cappedMerger = new RrfMerger(props, new TokenEstimator());
        Map<RecallChannel, List<MemoryItem>> channels = new LinkedHashMap<>();
        channels.put(RecallChannel.SKU_HISTORY, List.of(
                MemoryItem.of("SKU-A", RecallChannel.SKU_HISTORY, 0.9, "a"),
                MemoryItem.of("SKU-B", RecallChannel.SKU_HISTORY, 0.7, "b"),
                MemoryItem.of("SKU-C", RecallChannel.SKU_HISTORY, 0.5, "c")
        ));
        RrfMerger.MergedRecall result = cappedMerger.merge(channels);
        assertEquals(2, result.items().size());
    }
}