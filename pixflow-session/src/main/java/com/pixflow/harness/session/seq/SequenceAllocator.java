package com.pixflow.harness.session.seq;

import com.pixflow.harness.session.persistence.MessageWriteMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SequenceAllocator {
    private final MessageWriteMapper writeMapper;

    public SequenceAllocator(MessageWriteMapper writeMapper) {
        this.writeMapper = Objects.requireNonNull(writeMapper, "writeMapper");
    }

    public List<Long> allocate(String conversationId, int count) {
        if (count <= 0) {
            return List.of();
        }
        long start = writeMapper.maxSeq(conversationId) + 1;
        List<Long> seqs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            seqs.add(start + i);
        }
        return seqs;
    }
}
