package com.pixflow.harness.state.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.testsupport.FakeCacheStore;
import com.pixflow.harness.state.testsupport.FakeTaskRuntimeKeyPort;
import org.junit.jupiter.api.Test;

class CancellationReaderTest {

    @Test
    void readsCancelFlagAndThrowsWhenRequested() {
        FakeCacheStore cache = new FakeCacheStore();
        FakeTaskRuntimeKeyPort keyPort = new FakeTaskRuntimeKeyPort();
        cache.put(keyPort.cancelKey("task-1"), true, keyPort.cancelKey("task-1").suggestedTtl());
        CancellationReader reader = new DefaultCancellationReader(cache, keyPort);

        assertThat(reader.isCancelRequested("task-1")).isTrue();
        assertThatThrownBy(() -> reader.throwIfCancelled("task-1"))
                .isInstanceOf(PixFlowException.class)
                .extracting(ex -> ((PixFlowException) ex).code())
                .isEqualTo(StateErrorCode.STATE_TASK_CANCELLED);
    }

    @Test
    void cancelReadFailureIsNotSilentlySwallowed() {
        FakeCacheStore cache = new FakeCacheStore();
        cache.failReads(true);
        CancellationReader reader = new DefaultCancellationReader(cache, new FakeTaskRuntimeKeyPort());

        assertThatThrownBy(() -> reader.isCancelRequested("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache read failed");
    }
}
