package com.pixflow.harness.eval.recorder;

import com.pixflow.harness.eval.api.TurnTrace;
import java.util.Optional;

public final class CurrentTurnTraceHolder {
    private static final ThreadLocal<TurnTrace> CURRENT = new ThreadLocal<>();

    private CurrentTurnTraceHolder() {
    }

    public static Scope bind(TurnTrace trace) {
        TurnTrace previous = CURRENT.get();
        CURRENT.set(trace);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<TurnTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
