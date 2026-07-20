package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 导入人工 Gold Label；相同身份只能幂等重放，不能覆盖既有判断。 */
public final class GoldLabelRepository {
    private final JdbcTemplate jdbc;

    private final TransactionOperations transactions;

    public GoldLabelRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void importLabels(String datasetId, String datasetVersion, List<GoldLabel> labels) {
        transactions.executeWithoutResult(status ->
                importInTransaction(datasetId, datasetVersion, labels));
    }

    private void importInTransaction(
            String datasetId, String datasetVersion, List<GoldLabel> labels) {
        for (GoldLabel label : List.copyOf(labels)) {
            validate(label);
            Long itemId = jdbc.queryForObject("""
                    select item.id
                    from rubrics_dataset_item item
                    join rubrics_dataset dataset on dataset.id = item.dataset_pk
                    where dataset.dataset_id = ? and dataset.version = ?
                      and item.subject_id = ?
                    """, Long.class, datasetId, datasetVersion, label.subjectId());
            if (itemId == null) {
                throw new IllegalArgumentException("dataset item not found: " + label.subjectId());
            }
            List<StoredLabel> existing = jdbc.query("""
                    select verdict, adjudicated
                    from rubrics_gold_label
                    where dataset_item_id = ? and criterion_key = ? and annotator_id = ?
                    """, (result, row) -> new StoredLabel(
                            CriterionVerdict.valueOf(result.getString("verdict")),
                            result.getBoolean("adjudicated")),
                    itemId, label.criterionKey(), label.annotatorId());
            if (!existing.isEmpty()) {
                StoredLabel stored = existing.getFirst();
                if (stored.verdict() != label.verdict()
                        || stored.adjudicated() != label.adjudicated()) {
                    throw new IllegalStateException("gold label identity is immutable");
                }
                continue;
            }
            jdbc.update("""
                    insert into rubrics_gold_label(
                      dataset_item_id, criterion_key, annotator_id, verdict, adjudicated)
                    values (?, ?, ?, ?, ?)
                    """, itemId, label.criterionKey(), label.annotatorId(),
                    label.verdict().name(), label.adjudicated());
        }
    }

    private static void validate(GoldLabel label) {
        if (label.subjectId() == null || label.subjectId().isBlank()
                || label.criterionKey() == null || label.criterionKey().isBlank()
                || label.annotatorId() == null || label.annotatorId().isBlank()) {
            throw new IllegalArgumentException("gold label identity must not be blank");
        }
        if (label.verdict() == CriterionVerdict.INCONCLUSIVE) {
            throw new IllegalArgumentException("human gold labels cannot be INCONCLUSIVE");
        }
    }

    public record GoldLabel(
            String subjectId,
            String criterionKey,
            String annotatorId,
            CriterionVerdict verdict,
            boolean adjudicated) {
    }

    private record StoredLabel(CriterionVerdict verdict, boolean adjudicated) {
    }
}
