package com.pixflow.module.rubrics.feedback;

import com.pixflow.module.memory.skuhistory.SkuHistoryRubricsScoreCommand;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import com.pixflow.module.rubrics.persistence.RubricsScoreEntity;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import com.pixflow.module.rubrics.run.ItemEvaluationResult;
import com.pixflow.module.rubrics.score.RubricScore;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.task.domain.model.ProcessResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class ScoreFeedbackWriter {
    private final RubricsScoreMapper scoreMapper;
    private final SkuHistoryService skuHistoryService;

    public ScoreFeedbackWriter(RubricsScoreMapper scoreMapper, SkuHistoryService skuHistoryService) {
        this.scoreMapper = Objects.requireNonNull(scoreMapper, "scoreMapper");
        this.skuHistoryService = Objects.requireNonNull(skuHistoryService, "skuHistoryService");
    }

    public void write(long runId, RubricTemplate template, ProcessResult result, ItemEvaluationResult evaluation) {
        RubricScore score = evaluation.score();
        RubricsScoreEntity entity = new RubricsScoreEntity();
        entity.setResultId(result.getId());
        entity.setTaskId(result.getTaskId());
        entity.setRunId(runId);
        entity.setTemplateId(template.id());
        entity.setTemplateVersion(template.version());
        entity.setOverallScore(score.overallScore());
        entity.setImageScore(score.imageScore());
        entity.setCopyScore(score.copyScore());
        entity.setDecisionScore(score.decisionScore());
        entity.setDimensionScoresJson(evaluation.dimensionScoresJson());
        entity.setExplanationJson(evaluation.explanationJson());
        entity.setAlertFlag(score.overallScore().doubleValue() < 60.0);
        entity.setCreatedAt(Instant.now());
        scoreMapper.upsert(entity);

        if (result.getSkuId() != null && !result.getSkuId().isBlank()) {
            skuHistoryService.appendRubricsScore(new SkuHistoryRubricsScoreCommand(
                    result.getSkuId(),
                    result.getTaskId() == null ? null : String.valueOf(result.getTaskId()),
                    score.overallScore(),
                    result.getBranchId(),
                    Map.of("result_id", result.getId(), "run_id", runId)));
        }
    }
}
