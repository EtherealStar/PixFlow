package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.config.RubricsProperties;
import org.springframework.scheduling.annotation.Scheduled;

public class RubricsDailyBatchScheduler {
    private final EvaluationRunner runner;
    private final RubricsProperties properties;

    public RubricsDailyBatchScheduler(EvaluationRunner runner, RubricsProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(cron = "${pixflow.rubrics.scheduler.daily-batch-cron:0 0 3 * * ?}")
    public void runDailyBatch() {
        if (properties.getScheduler().isDailyBatchEnabled()) {
            runner.startDailyBatch();
        }
    }
}
