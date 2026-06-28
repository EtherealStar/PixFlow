package com.pixflow.harness.state.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.state.config.StateProperties;
import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.model.ProgressSource;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.observability.NoopStateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort.PersistedCounts;
import com.pixflow.harness.state.testsupport.FakeAtomicCounter;
import com.pixflow.harness.state.testsupport.FakeCheckpointReadPort;
import com.pixflow.harness.state.testsupport.FakeTaskRuntimeKeyPort;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProgressReaderTest {

    @Test
    void usesRedisWhenAvailableAndKeepsMysqlDrift() {
        FakeTaskRuntimeKeyPort keyPort = new FakeTaskRuntimeKeyPort();
        FakeAtomicCounter counter = new FakeAtomicCounter();
        counter.put(keyPort.progressKey("task-1"), 5);
        ProgressReader reader = newReader(counter, keyPort, new StateProperties());

        var view = reader.read("task-1");

        assertThat(view.source()).isEqualTo(ProgressSource.REDIS);
        assertThat(view.done()).isEqualTo(5);
        assertThat(view.persisted().succeeded()).isEqualTo(4);
        assertThat(view.drift()).isEqualTo(1);
    }

    @Test
    void fallsBackToMysqlWhenRedisFails() {
        FakeTaskRuntimeKeyPort keyPort = new FakeTaskRuntimeKeyPort();
        FakeAtomicCounter counter = new FakeAtomicCounter();
        counter.failReads(true);
        ProgressReader reader = newReader(counter, keyPort, new StateProperties());

        var view = reader.read("task-1");

        assertThat(view.source()).isEqualTo(ProgressSource.MYSQL);
        assertThat(view.done()).isEqualTo(4);
        assertThat(view.redisDone()).isNull();
    }

    @Test
    void preferRedisFalseDoesNotCallCounter() {
        FakeTaskRuntimeKeyPort keyPort = new FakeTaskRuntimeKeyPort();
        FakeAtomicCounter counter = new FakeAtomicCounter();
        StateProperties properties = new StateProperties();
        properties.getProgress().setPreferRedis(false);

        var view = newReader(counter, keyPort, properties).read("task-1");

        assertThat(view.source()).isEqualTo(ProgressSource.MYSQL);
        assertThat(counter.getCalls()).isZero();
    }

    private ProgressReader newReader(FakeAtomicCounter counter, FakeTaskRuntimeKeyPort keyPort, StateProperties properties) {
        FakeCheckpointReadPort checkpoint = new FakeCheckpointReadPort();
        checkpoint.putTask(
                "task-1",
                new CompletedUnits("task-1", Set.of()),
                new PersistedCounts(10, 4, 1),
                TaskRunStatus.RUNNING);
        return new DefaultProgressReader(checkpoint, keyPort, counter, properties, new NoopStateMetrics());
    }
}
