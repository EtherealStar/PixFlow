package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.Message;
import java.util.List;

public interface TokenEstimator {
    int estimate(List<Message> messages);
}
