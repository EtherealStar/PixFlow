package com.pixflow.harness.session.seq;

import com.pixflow.harness.session.persistence.MessageWriteMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceAllocatorTest {

    @Test
    void allocateStartsFromMaxPlusOne() {
        SequenceAllocator allocator = new SequenceAllocator(new StubWriteMapper(7));

        assertThat(allocator.allocate("conv-1", 3)).containsExactly(8L, 9L, 10L);
    }

    @Test
    void allocateReturnsEmptyForNonPositiveCount() {
        SequenceAllocator allocator = new SequenceAllocator(new StubWriteMapper(7));

        assertThat(allocator.allocate("conv-1", 0)).isEmpty();
        assertThat(allocator.allocate("conv-1", -1)).isEmpty();
    }

    private record StubWriteMapper(long maxSeq) implements MessageWriteMapper {
        @Override
        public long maxSeq(String conversationId) {
            return maxSeq;
        }

        @Override
        public int insertIgnoreBatch(List<com.pixflow.harness.session.persistence.MessageEntity> messages) {
            return 0;
        }
    }
}
