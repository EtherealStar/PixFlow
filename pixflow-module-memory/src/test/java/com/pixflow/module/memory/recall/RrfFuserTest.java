package com.pixflow.module.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RrfFuserTest {

    @Test
    void fusesMultipleRankedListsById() {
        RrfFuser fuser = new RrfFuser();

        List<MemoryItem> fused = fuser.fuse(60,
                List.of(item("a"), item("b")),
                List.of(item("b"), item("c")));

        assertThat(fused).extracting(MemoryItem::id).containsExactly("b", "a", "c");
        assertThat(fused.get(0).rrfScore()).isGreaterThan(fused.get(1).rrfScore());
    }

    @Test
    void emptyInputsReturnEmptyList() {
        assertThat(new RrfFuser().fuse(List.of(), 60)).isEmpty();
    }

    private static MemoryItem item(String id) {
        return new MemoryItem(id, MemoryType.INSIGHT, id, "test", "连衣裙", "", 0, 0,
                0.8, 0.7, 1.0, Instant.parse("2026-01-01T00:00:00Z"), null, Map.of());
    }
}
