package com.pixflow.module.rubrics.baseline;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.rubrics.error.RubricsErrorCode;
import com.pixflow.module.rubrics.persistence.RubricsBaselineEntity;
import com.pixflow.module.rubrics.persistence.RubricsBaselineMapper;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import java.time.Clock;
import java.util.Objects;

public class BaselineService {
    private final RubricsBaselineMapper baselineMapper;
    private final RubricsRunMapper runMapper;
    private final Clock clock;

    public BaselineService(RubricsBaselineMapper baselineMapper, RubricsRunMapper runMapper, Clock clock) {
        this.baselineMapper = Objects.requireNonNull(baselineMapper, "baselineMapper");
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RubricsBaselineEntity create(String name, long runId) {
        RubricsRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new PixFlowException(RubricsErrorCode.RUBRICS_BASELINE_NOT_FOUND, "Run not found for baseline: " + runId);
        }
        baselineMapper.deactivateActive(run.getTemplateId());
        RubricsBaselineEntity baseline = new RubricsBaselineEntity();
        baseline.setName(name == null || name.isBlank() ? "run-" + runId : name);
        baseline.setRunId(runId);
        baseline.setTemplateId(run.getTemplateId());
        baseline.setTemplateVersion(run.getTemplateVersion());
        baseline.setActive(true);
        baseline.setCreatedAt(clock.instant());
        baselineMapper.insert(baseline);
        return baseline;
    }

    public RubricsBaselineEntity active(String templateId) {
        RubricsBaselineEntity baseline = baselineMapper.findActive(templateId);
        if (baseline == null) {
            throw new PixFlowException(RubricsErrorCode.RUBRICS_BASELINE_NOT_FOUND, "Active baseline not found for " + templateId);
        }
        return baseline;
    }
}
