package com.pixflow.harness.eval.error;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.observability.ErrorRecorder;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.eval.recorder.CurrentTurnTraceHolder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EvalErrorRecorder implements ErrorRecorder {
    private static final Logger log = LoggerFactory.getLogger(EvalErrorRecorder.class);

    private final MeterRegistry meterRegistry;

    public EvalErrorRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void record(PixFlowException error) {
        if (error == null) {
            return;
        }
        meterRegistry.counter(
                        "pixflow.error.count",
                        "category", error.category().name(),
                        "code", error.code().code(),
                        "recovery", error.recovery().name())
                .increment();
        boolean attached = CurrentTurnTraceHolder.current()
                .map(trace -> {
                    trace.recordError(error);
                    return true;
                })
                .orElse(false);
        if (!attached) {
            log.warn(
                    "pixflow error outside turn trace: code={}, category={}, recovery={}, traceId={}, message={}",
                    error.code().code(),
                    error.category().name(),
                    error.recovery().name(),
                    error.traceId(),
                    Sanitizer.sanitizeTraceText(error.getMessage()));
        }
    }
}
