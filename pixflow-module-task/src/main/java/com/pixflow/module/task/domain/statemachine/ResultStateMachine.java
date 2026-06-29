package com.pixflow.module.task.domain.statemachine;

import com.pixflow.module.task.domain.model.ResultStatus;
import java.util.Map;
import java.util.Set;

public final class ResultStateMachine {
    public static final ResultStateMachine INSTANCE = new ResultStateMachine();

    private static final Map<ResultStatus, Set<ResultStatus>> ALLOWED = Map.of(
            ResultStatus.PENDING, Set.of(ResultStatus.RUNNING, ResultStatus.SUCCESS,
                    ResultStatus.FAILED, ResultStatus.SKIPPED),
            ResultStatus.RUNNING, Set.of(ResultStatus.SUCCESS, ResultStatus.FAILED, ResultStatus.SKIPPED),
            ResultStatus.SUCCESS, Set.of(),
            ResultStatus.FAILED, Set.of(),
            ResultStatus.SKIPPED, Set.of());

    private ResultStateMachine() {
    }

    public boolean canTransit(ResultStatus from, ResultStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void verify(ResultStatus from, ResultStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
