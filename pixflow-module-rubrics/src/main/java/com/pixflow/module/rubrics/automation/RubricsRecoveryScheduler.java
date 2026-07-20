package com.pixflow.module.rubrics.automation;

import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 从 MySQL run facts 恢复未完成评估，进程内队列不承担持久性。 */
public final class RubricsRecoveryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RubricsRecoveryScheduler.class);

    private final RubricsEvaluationService evaluations;

    private final RubricsRunMapper runs;

    private final RubricsProperties properties;

    private final Executor executor;

    private final RubricsMetrics metrics;

    public RubricsRecoveryScheduler(
            RubricsEvaluationService evaluations,
            RubricsRunMapper runs,
            RubricsProperties properties,
            Executor executor,
            RubricsMetrics metrics) {
        this.evaluations = evaluations;
        this.runs = runs;
        this.properties = properties;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pixflow.rubrics.recovery-interval:PT30S}")
    public void recover() {
        int batchSize = Math.max(1, properties.getRecoveryBatchSize());
        int submitted = 0;
        for (var run : runs.findRecoverable(batchSize)) {
            try {
                EvaluationRunId runId = new EvaluationRunId(run.getId());
                executor.execute(() -> resume(runId));
                submitted++;
            } catch (RejectedExecutionException error) {
                LOGGER.debug("Rubrics recovery queue is full; pending runs remain durable");
                metrics.recordRecovery(submitted);
                return;
            }
        }
        metrics.recordRecovery(submitted);
    }

    private void resume(EvaluationRunId runId) {
        try {
            evaluations.resume(runId);
        } catch (RuntimeException error) {
            LOGGER.warn("Rubrics recovery was isolated for run {}: {}",
                    runId.value(), error.getClass().getSimpleName());
        }
    }
}
