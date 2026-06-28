package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.Message;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ConservativeTokenEstimator implements TokenEstimator {
    @Override
    public int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages == null ? List.<Message>of() : messages) {
            int bytes = message.content().getBytes(StandardCharsets.UTF_8).length;
            total += Math.max(1, (int) Math.ceil(bytes / 3.0)) + 4;
        }
        return total;
    }
}
