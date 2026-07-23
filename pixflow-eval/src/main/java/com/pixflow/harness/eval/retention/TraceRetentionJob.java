package com.pixflow.harness.eval.retention;

import com.pixflow.harness.eval.config.EvalProperties;
import com.pixflow.harness.eval.store.AgentTraceEntity;
import com.pixflow.harness.eval.store.AgentTraceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

public class TraceRetentionJob {
    private final EvalProperties properties;

    private final AgentTraceRepository repository;

    public TraceRetentionJob(EvalProperties properties, AgentTraceRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Scheduled(cron = "${pixflow.eval.retention.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(properties.getRetention().getDays(), ChronoUnit.DAYS);
        while (true) {
            List<AgentTraceEntity> expired = repository.findExpired(
                    cutoff, properties.getRetention().getCleanupBatchSize());
            if (expired.isEmpty()) {
                return;
            }
            repository.deleteByIds(expired.stream().map(AgentTraceEntity::id).toList());
            if (expired.size() < properties.getRetention().getCleanupBatchSize()) {
                return;
            }
        }
    }
}
