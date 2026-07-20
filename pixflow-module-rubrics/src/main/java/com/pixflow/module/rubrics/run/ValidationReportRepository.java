package com.pixflow.module.rubrics.run;

import org.springframework.jdbc.core.JdbcTemplate;

/** 查询某个不可变模板、Dataset 与 evaluator 组合是否已经通过校准门。 */
public final class ValidationReportRepository {
    private final JdbcTemplate jdbc;

    public ValidationReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasPassingReport(
            long datasetDatabaseId,
            String templateId,
            String templateVersion,
            String templateHash,
            String evaluatorVersion) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from rubrics_validation_report
                where dataset_pk = ?
                  and template_id = ?
                  and template_version = ?
                  and template_hash = ?
                  and evaluator_version = ?
                  and thresholds_met = true
                """,
                Integer.class,
                datasetDatabaseId,
                templateId,
                templateVersion,
                templateHash,
                evaluatorVersion);
        return count != null && count > 0;
    }

    public boolean hasPassingReportForRelease(
            String templateId,
            String templateVersion,
            String templateHash,
            String evaluatorVersion) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from rubrics_validation_report
                where template_id = ? and template_version = ? and template_hash = ?
                  and evaluator_version = ? and thresholds_met = true
                """, Integer.class, templateId, templateVersion, templateHash, evaluatorVersion);
        return count != null && count > 0;
    }
}
