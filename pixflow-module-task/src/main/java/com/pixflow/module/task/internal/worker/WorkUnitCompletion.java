package com.pixflow.module.task.internal.worker;

import com.pixflow.module.dag.exec.UnitOutcome;
import com.pixflow.module.task.domain.model.WorkUnit;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public sealed interface WorkUnitCompletion
        permits WorkUnitCompletion.Succeeded, WorkUnitCompletion.Failed, WorkUnitCompletion.Skipped {
    WorkUnit unit();
    long runEpoch();
    Instant startedAt();
    Instant finishedAt();

    record Succeeded(WorkUnit unit, long runEpoch, Instant startedAt, Instant finishedAt,
                     String outputObjectKey, String generatedCopy, Long bytesOut,
                     java.util.List<UnitOutcome.MemberRef> members) implements WorkUnitCompletion {
        public Succeeded {
            Objects.requireNonNull(unit, "unit");
            members = members == null ? java.util.List.of() : java.util.List.copyOf(members);
            if ((outputObjectKey == null || outputObjectKey.isBlank())
                    && (generatedCopy == null || generatedCopy.isBlank())) {
                throw new IllegalArgumentException("成功 completion 必须包含对象或文案产物");
            }
        }
    }

    record Failed(WorkUnit unit, long runEpoch, Instant startedAt, Instant finishedAt,
                  String code, String category, String recovery, String safeMessage,
                  Map<String, Object> details) implements WorkUnitCompletion {
        public Failed {
            Objects.requireNonNull(unit, "unit");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    record Skipped(WorkUnit unit, long runEpoch, Instant startedAt, Instant finishedAt)
            implements WorkUnitCompletion {}
}
