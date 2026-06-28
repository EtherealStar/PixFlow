package com.pixflow.module.memory.lifecycle;

import com.pixflow.module.memory.insight.InsightLifecycleService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public class ScheduledInsightMaintenance {
    private final InsightLifecycleService lifecycleService;

    public ScheduledInsightMaintenance(InsightLifecycleService lifecycleService) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
    }

    @Scheduled(cron = "${pixflow.memory.insight.lifecycle.maintenance-cron:0 0 * * * *}")
    public void maintain() {
        lifecycleService.maintain();
    }
}
