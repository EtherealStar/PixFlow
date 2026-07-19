package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 读取已经注册且不可变的 Evaluation Dataset manifest。 */
public final class EvaluationDatasetRepository {
    private final JdbcTemplate jdbc;

    public EvaluationDatasetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EvaluationDataset require(String datasetId, String version) {
        List<EvaluationDataset> datasets = jdbc.query("""
                select id, subject_type, manifest_hash
                from rubrics_dataset
                where dataset_id = ? and version = ?
                """,
                (result, row) -> new EvaluationDataset(
                        result.getLong("id"),
                        datasetId,
                        version,
                        SubjectType.valueOf(result.getString("subject_type")),
                        result.getString("manifest_hash"),
                        items(result.getLong("id"))),
                datasetId,
                version);
        if (datasets.size() != 1) {
            throw new IllegalArgumentException(
                    "evaluation dataset not found: " + datasetId + ":" + version);
        }
        return datasets.getFirst();
    }

    private List<EvaluationDatasetItem> items(long datasetPk) {
        return jdbc.query("""
                select subject_id, subject_snapshot_hash, replayable, replay_error
                from rubrics_dataset_item
                where dataset_pk = ?
                order by id asc
                """,
                (result, row) -> new EvaluationDatasetItem(
                        result.getString("subject_id"),
                        result.getString("subject_snapshot_hash"),
                        result.getBoolean("replayable"),
                        result.getString("replay_error")),
                datasetPk);
    }

    public record EvaluationDataset(
            long databaseId,
            String datasetId,
            String version,
            SubjectType subjectType,
            String manifestHash,
            List<EvaluationDatasetItem> items) {

        public EvaluationDataset {
            items = List.copyOf(items);
        }
    }

    public record EvaluationDatasetItem(
            String subjectId,
            String subjectSnapshotHash,
            boolean replayable,
            String replayError) {
    }
}
