package com.pixflow.module.rubrics.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.persistence.RubricsAlertEntity;
import com.pixflow.module.rubrics.persistence.RubricsAlertMapper;
import java.time.Clock;
import java.util.Objects;

public class RegressionAlertService {
    private final RubricsAlertMapper alertMapper;
    private final ObjectMapper objectMapper;
    private final RubricsProperties properties;
    private final Clock clock;

    public RegressionAlertService(
            RubricsAlertMapper alertMapper,
            ObjectMapper objectMapper,
            RubricsProperties properties,
            Clock clock) {
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void persistIfDegraded(RegressionReport report, String templateId) {
        long degradedDimensions = report.dimensions().stream().filter(DimensionDelta::degraded).count();
        if (degradedDimensions < 2 && report.overallDelta().doubleValue() >= properties.getBaseline().getRegressionOverallThreshold()) {
            return;
        }
        RubricsAlertEntity alert = new RubricsAlertEntity();
        alert.setRunId(report.currentRunId());
        alert.setBaselineRunId(report.baselineRunId());
        alert.setTemplateId(templateId);
        alert.setSeverity(report.overallDelta().doubleValue() < properties.getBaseline().getRegressionOverallThreshold() ? "HIGH" : "MEDIUM");
        alert.setOverallDelta(report.overallDelta());
        alert.setDegradedDimensionsJson(writeJson(report.dimensions().stream().filter(DimensionDelta::degraded).toList()));
        alert.setAcknowledged(false);
        alert.setCreatedAt(clock.instant());
        alertMapper.insert(alert);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize rubrics alert detail", ex);
        }
    }
}
