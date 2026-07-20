package com.pixflow.module.rubrics.observability;

import com.pixflow.module.rubrics.judge.JudgeRollout;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.summary.EvaluationSummary;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/** Rubrics 指标只使用低基数领域标签，不携带 Subject、prompt 或 evidence 内容。 */
public final class RubricsMetrics {
    private final MeterRegistry registry;

    public RubricsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void bindQueue(ThreadPoolExecutor executor) {
        Gauge.builder("pixflow.rubrics.queue.depth", executor, value -> value.getQueue().size())
                .register(registry);
    }

    public void recordRun(String purpose, String status, Duration duration) {
        registry.timer("pixflow.rubrics.run.duration",
                        Tags.of("purpose", purpose, "status", status))
                .record(duration);
        registry.counter("pixflow.rubrics.run.status", "purpose", purpose, "status", status)
                .increment();
    }

    public void recordItem(String subjectType, String status, Duration duration) {
        registry.timer("pixflow.rubrics.item.duration",
                        Tags.of("subject.type", subjectType, "status", status))
                .record(duration);
    }

    public void recordSummary(EvaluationSummary summary) {
        if (summary.coverage() != null) {
            registry.summary("pixflow.rubrics.coverage").record(summary.coverage());
        }
    }

    public void recordCriterion(
            String criterionKey,
            CriterionVerdict verdict,
            VerdictReason reason,
            Double agreement) {
        List<Tag> tags = List.of(
                Tag.of("criterion", criterionKey),
                Tag.of("verdict", verdict.name()),
                Tag.of("reason", reason.name()));
        registry.counter("pixflow.rubrics.criterion.verdict", tags).increment();
        if (agreement != null) {
            registry.summary("pixflow.rubrics.criterion.agreement", "criterion", criterionKey)
                    .record(agreement);
        }
        if (reason == VerdictReason.PARSER_FAILURE
                || reason == VerdictReason.INVALID_EVIDENCE
                || reason == VerdictReason.MISSING_EVIDENCE) {
            registry.counter("pixflow.rubrics.criterion.failure", tags).increment();
        }
    }

    public void recordJudge(JudgeRollout rollout) {
        Tags tags = Tags.of(
                "provider", rollout.provider(),
                "model", rollout.model(),
                "verdict", rollout.verdict().name(),
                "reason", rollout.reason().name());
        registry.timer("pixflow.rubrics.judge.duration", tags)
                .record(Duration.ofMillis(rollout.latencyMs()));
        registry.summary("pixflow.rubrics.judge.tokens", tags)
                .record(rollout.totalTokens());
        if (rollout.verdict() == CriterionVerdict.INCONCLUSIVE) {
            registry.counter("pixflow.rubrics.judge.error", tags).increment();
        }
    }

    public void recordAutomationSkipped(String trigger, String reason) {
        registry.counter("pixflow.rubrics.automation.skipped",
                "trigger", trigger, "reason", reason).increment();
    }

    public void recordRecovery(int submitted) {
        registry.counter("pixflow.rubrics.recovery.scan").increment();
        registry.summary("pixflow.rubrics.recovery.submitted").record(submitted);
    }
}
