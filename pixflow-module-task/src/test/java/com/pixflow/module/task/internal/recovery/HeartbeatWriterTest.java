package com.pixflow.module.task.internal.recovery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.worker.ExecutionRun;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HeartbeatWriterTest {
    @Test
    void zeroRowHeartbeatDeactivatesExecutionRun() {
        ProcessTaskMapper mapper = mock(ProcessTaskMapper.class);
        when(mapper.heartbeatEpoch(anyLong(), anyLong(), any())).thenReturn(0);
        ExecutionRun run = new ExecutionRun("1", 4, () -> true);

        try (HeartbeatWriter writer = new HeartbeatWriter(mapper, Clock.systemUTC());
             HeartbeatWriter.HeartbeatSession ignored = writer.start(run, Duration.ofMillis(5))) {
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThatThrownBy(run::assertCommitAllowed)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("epoch"));
        }
    }
}
