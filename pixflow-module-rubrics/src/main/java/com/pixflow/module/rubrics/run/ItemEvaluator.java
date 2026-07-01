package com.pixflow.module.rubrics.run;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.rubrics.judge.JudgeVerdict;
import com.pixflow.module.rubrics.judge.LlmJudge;
import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.rule.RuleCheckInput;
import com.pixflow.module.rubrics.rule.RuleCheckResult;
import com.pixflow.module.rubrics.rule.RuleVerifier;
import com.pixflow.module.rubrics.score.DimensionScore;
import com.pixflow.module.rubrics.score.RubricScore;
import com.pixflow.module.rubrics.score.ScoreAggregator;
import com.pixflow.module.rubrics.template.RubricDimension;
import com.pixflow.module.rubrics.template.RubricDomain;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.VerifierType;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ItemEvaluator {
    private final ObjectStorage objectStorage;
    private final List<RuleVerifier> ruleVerifiers;
    private final LlmJudge llmJudge;
    private final ScoreAggregator scoreAggregator;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ItemEvaluator(
            ObjectStorage objectStorage,
            List<RuleVerifier> ruleVerifiers,
            LlmJudge llmJudge,
            ScoreAggregator scoreAggregator,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
        this.ruleVerifiers = ruleVerifiers == null ? List.of() : List.copyOf(ruleVerifiers);
        this.llmJudge = llmJudge;
        this.scoreAggregator = Objects.requireNonNull(scoreAggregator, "scoreAggregator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ItemEvaluationResult evaluate(RubricTemplate template, ProcessResult result, ProcessTask task) {
        byte[] imageBytes = loadImageBytes(result);
        Map<String, Object> taskContext = taskContext(task);
        List<DimensionScore> scores = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (RubricDomain domain : template.domains()) {
            for (RubricDimension dimension : domain.dimensions()) {
                try {
                    DimensionScore raw = verifyDimension(domain, dimension, result, imageBytes, taskContext);
                    scores.add(scoreAggregator.withProgramScore(raw));
                } catch (RuntimeException ex) {
                    skipped.add(Map.of(
                            "domain", domain.key(),
                            "dimension", dimension.key(),
                            "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                }
            }
        }
        RubricScore aggregate = scoreAggregator.aggregate(template, scores);
        return new ItemEvaluationResult(
                aggregate,
                writeJson(scores),
                writeJson(Map.of("skippedDimensions", skipped, "domainScores", aggregate.domainScores())));
    }

    private DimensionScore verifyDimension(
            RubricDomain domain,
            RubricDimension dimension,
            ProcessResult result,
            byte[] imageBytes,
            Map<String, Object> taskContext) {
        if (dimension.verifier() != null && dimension.verifier().type() == VerifierType.RULE) {
            RuleVerifier verifier = ruleVerifiers.stream()
                    .filter(candidate -> candidate.dimensionKey().equals(dimension.key()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No rule verifier for " + dimension.key()));
            RuleCheckResult check = verifier.verify(new RuleCheckInput(
                    result.getId(),
                    result,
                    imageBytes,
                    dimension.verifier().params(),
                    taskContext));
            return new DimensionScore(
                    domain.key(),
                    dimension.key(),
                    check.verdict(),
                    check.confidence(),
                    null,
                    check.rationale(),
                    check.evidence(),
                    check.numericMetric().isPresent() ? check.numericMetric().getAsDouble() : null);
        }
        if (llmJudge == null) {
            return new DimensionScore(
                    domain.key(),
                    dimension.key(),
                    com.pixflow.module.rubrics.model.Verdict.FAIL,
                    Confidence.LOW,
                    null,
                    "LLM judge unavailable",
                    List.of(),
                    null);
        }
        JudgeVerdict verdict = llmJudge.judge(dimension, result, imageBytes, taskContext);
        return new DimensionScore(
                domain.key(),
                dimension.key(),
                verdict.verdict(),
                verdict.confidence(),
                null,
                verdict.rationale(),
                verdict.evidence(),
                null);
    }

    private byte[] loadImageBytes(ProcessResult result) {
        if (result == null || result.getOutputMinioKey() == null || result.getOutputMinioKey().isBlank()) {
            return null;
        }
        BucketType bucket = result.getKind() == com.pixflow.module.task.domain.model.UnitKind.GENERATIVE
                ? BucketType.GENERATED
                : BucketType.RESULTS;
        return objectStorage.getBytes(ObjectLocation.of(bucket, result.getOutputMinioKey()));
    }

    private static Map<String, Object> taskContext(ProcessTask task) {
        if (task == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("task_id", task.getId());
        context.put("task_total_count", task.getTotalCount() == null ? 0 : task.getTotalCount());
        context.put("task_done_count", task.getDoneCount() == null ? 0 : task.getDoneCount());
        context.put("dag_json", task.getDagJson() == null ? "" : task.getDagJson());
        return context;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize rubrics score detail", ex);
        }
    }
}
