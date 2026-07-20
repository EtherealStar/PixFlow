package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 持久化 Rubrics 自有回归告警；该事实不会写入 Memory。 */
public final class RegressionAlertRepository {
    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public RegressionAlertRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void createIfRegressed(
            RubricsRunEntity run, Map<String, Object> regressionFacts, Instant now) {
        Object transitionValue = regressionFacts.get("passToFailCount");
        int passToFailCount = transitionValue instanceof Number number
                ? number.intValue() : 0;
        if (passToFailCount == 0) {
            return;
        }
        try {
            jdbc.update("""
                    insert into rubrics_alert(
                      run_id, baseline_run_id, template_id, template_version,
                      dataset_id, dataset_version, severity, criterion_details_json,
                      acknowledged, created_at)
                    values (?, ?, ?, ?, ?, ?, 'HIGH', ?, false, ?)
                    on duplicate key update run_id = values(run_id)
                    """,
                    run.getId(),
                    run.getBaselineRunId(),
                    run.getTemplateId(),
                    run.getTemplateVersion(),
                    run.getDatasetId(),
                    run.getDatasetVersion(),
                    mapper.writeValueAsString(regressionFacts),
                    java.sql.Timestamp.from(now));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize regression alert", error);
        }
    }
}
