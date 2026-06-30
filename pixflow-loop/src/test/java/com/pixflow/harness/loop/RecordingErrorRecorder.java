package com.pixflow.harness.loop;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.observability.ErrorRecorder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用 ErrorRecorder：记录所有调用。
 */
public final class RecordingErrorRecorder implements ErrorRecorder {

    private final List<PixFlowException> errors = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(PixFlowException error) {
        errors.add(error);
    }

    public List<PixFlowException> errors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    public int count() {
        return errors.size();
    }
}