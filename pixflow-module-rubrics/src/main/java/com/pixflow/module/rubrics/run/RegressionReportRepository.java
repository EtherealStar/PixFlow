package com.pixflow.module.rubrics.run;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 从已提交的不可变 criterion facts 生成配对回归统计。 */
public final class RegressionReportRepository {
    private final JdbcTemplate jdbc;

    public RegressionReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> compare(long runId, long baselineRunId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("pairedSubjectCount", pairedSubjectCount(runId, baselineRunId));
        facts.put("passToFailCount", transitionCount(
                runId, baselineRunId, "PASS", "FAIL"));
        facts.put("failToPassCount", transitionCount(
                runId, baselineRunId, "FAIL", "PASS"));
        facts.put("passToFailByCriterion", transitionsByCriterion(
                runId, baselineRunId, "PASS", "FAIL"));
        facts.put("failToPassByCriterion", transitionsByCriterion(
                runId, baselineRunId, "FAIL", "PASS"));
        return Map.copyOf(facts);
    }

    private int pairedSubjectCount(long runId, long baselineRunId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from rubrics_evaluation current_evaluation
                join rubrics_evaluation baseline_evaluation
                  on baseline_evaluation.run_id = ?
                 and baseline_evaluation.subject_type = current_evaluation.subject_type
                 and baseline_evaluation.subject_id = current_evaluation.subject_id
                 and baseline_evaluation.subject_snapshot_hash =
                     current_evaluation.subject_snapshot_hash
                where current_evaluation.run_id = ?
                """, Integer.class, baselineRunId, runId);
        return count == null ? 0 : count;
    }

    private int transitionCount(
            long runId,
            long baselineRunId,
            String baselineVerdict,
            String currentVerdict) {
        Integer count = jdbc.queryForObject(transitionSql("count(*)"), Integer.class,
                baselineRunId, runId, baselineVerdict, currentVerdict);
        return count == null ? 0 : count;
    }

    private Map<String, Integer> transitionsByCriterion(
            long runId,
            long baselineRunId,
            String baselineVerdict,
            String currentVerdict) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(transitionSql("current_criterion.criterion_key, count(*) transition_count")
                        + " group by current_criterion.criterion_key"
                        + " order by current_criterion.criterion_key",
                (RowCallbackHandler) result -> counts.put(
                            result.getString("criterion_key"),
                            result.getInt("transition_count")),
                baselineRunId,
                runId,
                baselineVerdict,
                currentVerdict);
        return Map.copyOf(counts);
    }

    private static String transitionSql(String projection) {
        return """
                select %s
                from rubrics_evaluation current_evaluation
                join rubrics_evaluation baseline_evaluation
                  on baseline_evaluation.run_id = ?
                 and baseline_evaluation.subject_type = current_evaluation.subject_type
                 and baseline_evaluation.subject_id = current_evaluation.subject_id
                 and baseline_evaluation.subject_snapshot_hash =
                     current_evaluation.subject_snapshot_hash
                join rubrics_criterion_result current_criterion
                  on current_criterion.evaluation_id = current_evaluation.id
                join rubrics_criterion_result baseline_criterion
                  on baseline_criterion.evaluation_id = baseline_evaluation.id
                 and baseline_criterion.criterion_key = current_criterion.criterion_key
                where current_evaluation.run_id = ?
                  and baseline_criterion.verdict = ?
                  and current_criterion.verdict = ?
                """.formatted(projection);
    }
}
