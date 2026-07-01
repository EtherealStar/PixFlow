package com.pixflow.module.rubrics.feedback;

import com.pixflow.module.memory.ingest.MemoryIngestRequest;
import com.pixflow.module.memory.ingest.MemoryIngestService;
import com.pixflow.module.rubrics.persistence.RubricsScoreEntity;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MemoryFeedbackTrigger {
    private final RubricsScoreMapper scoreMapper;
    private final MemoryIngestService ingestService;

    public MemoryFeedbackTrigger(RubricsScoreMapper scoreMapper, MemoryIngestService ingestService) {
        this.scoreMapper = Objects.requireNonNull(scoreMapper, "scoreMapper");
        this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
    }

    public void triggerForRun(long runId) {
        List<RubricsScoreEntity> scores = scoreMapper.findByRunId(runId);
        long lowCount = scores.stream().filter(score -> score.getOverallScore().doubleValue() < 60.0).count();
        if (lowCount == 0) {
            return;
        }
        ingestService.ingestAsync(new MemoryIngestRequest(
                "rubrics",
                0,
                "rubrics-run-" + runId,
                "Rubrics low-score pattern extraction",
                "Run " + runId + " produced " + lowCount + " low-score results.",
                null,
                List.of(),
                scores.stream().map(RubricsScoreEntity::getResultId).map(String::valueOf).toList(),
                List.of("NEUTRAL"),
                Map.of("run_id", runId, "low_score_count", lowCount)));
    }
}
