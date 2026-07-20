package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.VerifierType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** 基于 holdout 双人标注和裁定事实生成 criterion 级校准报告。 */
public final class CalibrationReportRepository {
    private static final double MIN_HUMAN_KAPPA = 0.60;

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public CalibrationReportRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> createIfComplete(RubricsRunEntity run, LoadedTemplate template) {
        Map<String, Object> existing = findByRunId(run.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        Long datasetPk = jdbc.queryForObject("""
                select id from rubrics_dataset where dataset_id = ? and version = ?
                """, Long.class, run.getDatasetId(), run.getDatasetVersion());
        if (datasetPk == null) {
            throw new IllegalStateException("calibration dataset is missing");
        }
        List<CalibrationRow> rows = rows(run.getId(), datasetPk);
        Integer holdoutCount = jdbc.queryForObject("""
                select count(*) from rubrics_dataset_item
                where dataset_pk = ? and partition_name = 'HOLDOUT'
                """, Integer.class, datasetPk);
        if (holdoutCount == null || holdoutCount == 0 || rows.isEmpty()) {
            throw new IllegalStateException("calibration requires a non-empty HOLDOUT partition");
        }
        Map<String, List<CalibrationRow>> byCriterion = rows.stream()
                .collect(Collectors.groupingBy(
                        CalibrationRow::criterionKey, LinkedHashMap::new, Collectors.toList()));
        Map<String, Object> criterionReports = new LinkedHashMap<>();
        boolean thresholdsMet = true;
        for (Criterion criterion : template.template().criteria()) {
            List<CalibrationRow> criterionRows = byCriterion.getOrDefault(
                    criterion.key(), List.of());
            long labeledSubjects = criterionRows.stream()
                    .map(CalibrationRow::subjectId)
                    .distinct()
                    .count();
            if (labeledSubjects != holdoutCount) {
                throw new IllegalStateException(
                        "every HOLDOUT subject requires labels and an evaluation for criterion: "
                                + criterion.key());
            }
            CriterionReport report = calculate(criterion, criterionRows);
            criterionReports.put(criterion.key(), report.asMap());
            thresholdsMet &= report.thresholdMet();
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("partition", "HOLDOUT");
        report.put("subjectCount", rows.stream().map(CalibrationRow::subjectId).distinct().count());
        report.put("criteria", Map.copyOf(criterionReports));
        report.put("thresholdsMet", thresholdsMet);
        String evidenceSchema = jdbc.queryForObject(
                "select evidence_schema_version from rubrics_dataset where id = ?",
                String.class, datasetPk);
        jdbc.update("""
                insert into rubrics_validation_report(
                  run_id, dataset_pk, template_id, template_version, template_hash,
                  evaluator_version, evidence_schema_version, report_json, thresholds_met)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.getId(), datasetPk, run.getTemplateId(), run.getTemplateVersion(),
                run.getTemplateHash(), run.getEvaluatorVersion(), evidenceSchema,
                json(report), thresholdsMet);
        return Map.copyOf(report);
    }

    public Map<String, Object> findByRunId(long runId) {
        List<String> reports = jdbc.queryForList(
                "select report_json from rubrics_validation_report where run_id = ?",
                String.class, runId);
        if (reports.isEmpty()) {
            return Map.of();
        }
        try {
            return mapper.readValue(reports.getFirst(), new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored validation report is invalid", error);
        }
    }

    private List<CalibrationRow> rows(long runId, long datasetPk) {
        return jdbc.query("""
                select item.subject_id, item.category_name, item.difficulty,
                       result.criterion_key, result.verdict judge_verdict,
                       result.reason_code, result.agreement,
                       label.annotator_id, label.verdict human_verdict, label.adjudicated
                from rubrics_dataset_item item
                join rubrics_evaluation evaluation
                  on evaluation.run_id = ? and evaluation.subject_id = item.subject_id
                 and evaluation.subject_snapshot_hash = item.subject_snapshot_hash
                join rubrics_criterion_result result on result.evaluation_id = evaluation.id
                join rubrics_gold_label label on label.dataset_item_id = item.id
                 and label.criterion_key = result.criterion_key
                where item.dataset_pk = ? and item.partition_name = 'HOLDOUT'
                order by item.id, result.criterion_key, label.adjudicated, label.annotator_id
                """, (result, row) -> new CalibrationRow(
                        result.getString("subject_id"),
                        result.getString("category_name"),
                        result.getString("difficulty"),
                        result.getString("criterion_key"),
                        CriterionVerdict.valueOf(result.getString("judge_verdict")),
                        result.getString("reason_code"),
                        (Double) result.getObject("agreement"),
                        result.getString("annotator_id"),
                        CriterionVerdict.valueOf(result.getString("human_verdict")),
                        result.getBoolean("adjudicated")), runId, datasetPk);
    }

    private static CriterionReport calculate(Criterion criterion, List<CalibrationRow> rows) {
        Map<String, List<CalibrationRow>> bySubject = rows.stream().collect(Collectors.groupingBy(
                CalibrationRow::subjectId, LinkedHashMap::new, Collectors.toList()));
        List<ResolvedExample> examples = new ArrayList<>();
        for (Map.Entry<String, List<CalibrationRow>> entry : bySubject.entrySet()) {
            examples.add(resolve(entry.getKey(), entry.getValue()));
        }
        if (examples.isEmpty()) {
            return CriterionReport.empty();
        }
        double kappa = kappa(examples);
        double macroF1 = macroF1(examples);
        double minimumF1 = criterion.kind() == CriterionKind.PRINCIPLE ? 0.75 : 0.85;
        boolean thresholdMet = kappa >= MIN_HUMAN_KAPPA && macroF1 >= minimumF1;
        return new CriterionReport(
                examples.size(),
                kappa,
                macroF1,
                averageAgreement(examples),
                rate(examples, value -> value.judgeVerdict() == CriterionVerdict.INCONCLUSIVE),
                rate(examples, value -> "PARSER_FAILURE".equals(value.reasonCode())),
                rate(examples, value -> "INVALID_EVIDENCE".equals(value.reasonCode())),
                rate(examples, value -> "MISSING_EVIDENCE".equals(value.reasonCode())),
                criterion.kind().name(),
                criterion.verifier().type() == VerifierType.RULE ? "RULE" : "LLM",
                slices(examples, ResolvedExample::category),
                slices(examples, ResolvedExample::difficulty),
                thresholdMet);
    }

    private static ResolvedExample resolve(String subjectId, List<CalibrationRow> rows) {
        List<CalibrationRow> primary = rows.stream().filter(row -> !row.adjudicated()).toList();
        List<CalibrationRow> adjudicated = rows.stream().filter(CalibrationRow::adjudicated).toList();
        if (primary.size() != 2
                || Objects.equals(primary.get(0).annotatorId(), primary.get(1).annotatorId())) {
            throw new IllegalStateException(
                    "each gold criterion requires two independent annotators: " + subjectId);
        }
        CriterionVerdict finalVerdict;
        if (primary.get(0).humanVerdict() == primary.get(1).humanVerdict()) {
            finalVerdict = primary.get(0).humanVerdict();
        } else {
            if (adjudicated.size() != 1
                    || primary.stream().anyMatch(row ->
                            row.annotatorId().equals(adjudicated.getFirst().annotatorId()))) {
                throw new IllegalStateException(
                        "disputed gold criterion requires an independent adjudication: " + subjectId);
            }
            finalVerdict = adjudicated.getFirst().humanVerdict();
        }
        CalibrationRow judge = rows.getFirst();
        return new ResolvedExample(
                primary.get(0).humanVerdict(), primary.get(1).humanVerdict(), finalVerdict,
                judge.judgeVerdict(), judge.reasonCode(), judge.agreement(),
                judge.category(), judge.difficulty());
    }

    private static double kappa(List<ResolvedExample> examples) {
        double observed = rate(examples, value -> value.firstHuman() == value.secondHuman());
        double expected = 0;
        for (CriterionVerdict verdict : humanVerdicts()) {
            double first = rate(examples, value -> value.firstHuman() == verdict);
            double second = rate(examples, value -> value.secondHuman() == verdict);
            expected += first * second;
        }
        return expected == 1.0 ? (observed == 1.0 ? 1.0 : 0.0)
                : (observed - expected) / (1.0 - expected);
    }

    private static double macroF1(List<ResolvedExample> examples) {
        Set<CriterionVerdict> labels = EnumSet.noneOf(CriterionVerdict.class);
        examples.forEach(value -> {
            labels.add(value.goldVerdict());
            if (value.judgeVerdict() != CriterionVerdict.INCONCLUSIVE) {
                labels.add(value.judgeVerdict());
            }
        });
        return labels.stream().mapToDouble(label -> f1(examples, label)).average().orElse(0.0);
    }

    private static double f1(List<ResolvedExample> examples, CriterionVerdict label) {
        long truePositive = examples.stream().filter(value ->
                value.goldVerdict() == label && value.judgeVerdict() == label).count();
        long falsePositive = examples.stream().filter(value ->
                value.goldVerdict() != label && value.judgeVerdict() == label).count();
        long falseNegative = examples.stream().filter(value ->
                value.goldVerdict() == label && value.judgeVerdict() != label).count();
        long denominator = 2 * truePositive + falsePositive + falseNegative;
        return denominator == 0 ? 0.0 : (2.0 * truePositive) / denominator;
    }

    private static double averageAgreement(List<ResolvedExample> examples) {
        return examples.stream().map(ResolvedExample::agreement).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static Map<String, Object> slices(
            List<ResolvedExample> examples,
            java.util.function.Function<ResolvedExample, String> classifier) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, List<ResolvedExample>> grouped = examples.stream().collect(Collectors.groupingBy(
                classifier, LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((name, values) -> result.put(name, Map.of(
                "sampleCount", values.size(),
                "macroF1", macroF1(values),
                "accuracy", rate(values, value ->
                        value.goldVerdict() == value.judgeVerdict()))));
        return Map.copyOf(result);
    }

    private static <T> double rate(List<T> values, java.util.function.Predicate<T> predicate) {
        return values.isEmpty() ? 0.0
                : (double) values.stream().filter(predicate).count() / values.size();
    }

    private static Set<CriterionVerdict> humanVerdicts() {
        return EnumSet.of(
                CriterionVerdict.PASS,
                CriterionVerdict.FAIL,
                CriterionVerdict.NOT_APPLICABLE);
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize validation report", error);
        }
    }

    private record CalibrationRow(
            String subjectId,
            String category,
            String difficulty,
            String criterionKey,
            CriterionVerdict judgeVerdict,
            String reasonCode,
            Double agreement,
            String annotatorId,
            CriterionVerdict humanVerdict,
            boolean adjudicated) {
    }

    private record ResolvedExample(
            CriterionVerdict firstHuman,
            CriterionVerdict secondHuman,
            CriterionVerdict goldVerdict,
            CriterionVerdict judgeVerdict,
            String reasonCode,
            Double agreement,
            String category,
            String difficulty) {
    }

    private record CriterionReport(
            int sampleCount,
            double humanKappa,
            double judgeHumanMacroF1,
            double repeatedAgreement,
            double inconclusiveRate,
            double parserFailureRate,
            double invalidEvidenceRate,
            double missingEvidenceRate,
            String criterionKind,
            String verifierType,
            Map<String, Object> categorySlices,
            Map<String, Object> difficultySlices,
            boolean thresholdMet) {

        private static CriterionReport empty() {
            return new CriterionReport(
                    0, 0, 0, 0, 0, 0, 0, 0, "UNKNOWN", "UNKNOWN",
                    Map.of(), Map.of(), false);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sampleCount", sampleCount);
            values.put("humanKappa", humanKappa);
            values.put("judgeHumanMacroF1", judgeHumanMacroF1);
            values.put("repeatedAgreement", repeatedAgreement);
            values.put("inconclusiveRate", inconclusiveRate);
            values.put("parserFailureRate", parserFailureRate);
            values.put("invalidEvidenceRate", invalidEvidenceRate);
            values.put("missingEvidenceRate", missingEvidenceRate);
            values.put("criterionKind", criterionKind);
            values.put("verifierType", verifierType);
            values.put("categorySlices", categorySlices);
            values.put("difficultySlices", difficultySlices);
            values.put("thresholdMet", thresholdMet);
            return Map.copyOf(values);
        }
    }
}
