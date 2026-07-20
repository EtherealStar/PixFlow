package com.pixflow.module.rubrics.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pixflow.module.rubrics.model.SubjectType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 注册不可变 Dataset manifest；同一 ID/version 内容变化会直接失败。 */
public final class DatasetManifestRegistrar {
    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    private final TransactionOperations transactions;

    public DatasetManifestRegistrar(
            JdbcTemplate jdbc, ObjectMapper mapper, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public long register(DatasetManifest manifest) {
        validate(manifest);
        String manifestHash = hash(manifest);
        Long result = transactions.execute(status -> registerInTransaction(manifest, manifestHash));
        if (result == null) {
            throw new IllegalStateException("dataset registration returned no identity");
        }
        return result;
    }

    private long registerInTransaction(DatasetManifest manifest, String manifestHash) {
        List<StoredDataset> existing = jdbc.query("""
                select id, manifest_hash from rubrics_dataset
                where dataset_id = ? and version = ?
                """, (result, row) -> new StoredDataset(
                        result.getLong("id"), result.getString("manifest_hash")),
                manifest.datasetId(), manifest.version());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().manifestHash().equals(manifestHash)) {
                throw new IllegalStateException("dataset identity is immutable");
            }
            return existing.getFirst().id();
        }
        jdbc.update("""
                insert into rubrics_dataset(
                  dataset_id, version, subject_type, description, manifest_hash,
                  gold_label_version, evidence_schema_version)
                values (?, ?, ?, ?, ?, ?, ?)
                """, manifest.datasetId(), manifest.version(), manifest.subjectType().name(),
                manifest.description(), manifestHash, manifest.goldLabelVersion(),
                manifest.evidenceSchemaVersion());
        long datasetPk = jdbc.queryForObject("select last_insert_id()", Long.class);
        for (DatasetItem item : manifest.items()) {
            jdbc.update("""
                    insert into rubrics_dataset_item(
                      dataset_pk, subject_id, subject_snapshot_hash, partition_name,
                      category_name, difficulty, replayable, replay_error)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, datasetPk, item.subjectId(), item.subjectSnapshotHash(), item.partition(),
                    item.category(), item.difficulty(), item.replayable(), item.replayError());
        }
        return datasetPk;
    }

    private static void validate(DatasetManifest manifest) {
        if (manifest.datasetId() == null || manifest.datasetId().isBlank()
                || manifest.version() == null || manifest.version().isBlank()
                || manifest.subjectType() == null || manifest.items() == null
                || manifest.items().isEmpty()
                || manifest.evidenceSchemaVersion() == null
                || manifest.evidenceSchemaVersion().isBlank()) {
            throw new IllegalArgumentException("dataset manifest identity and items are required");
        }
        HashSet<String> subjectIds = new HashSet<>();
        for (DatasetItem item : manifest.items()) {
            if (item.subjectId() == null || item.subjectId().isBlank()
                    || item.subjectSnapshotHash() == null
                    || !item.subjectSnapshotHash().matches("[0-9a-f]{64}")
                    || !SetHolder.PARTITIONS.contains(item.partition())
                    || item.category() == null || item.category().isBlank()
                    || item.difficulty() == null || item.difficulty().isBlank()
                    || (!item.replayable()
                    && (item.replayError() == null || item.replayError().isBlank()))
                    || !subjectIds.add(item.subjectId())) {
                throw new IllegalArgumentException("dataset item identity is invalid or duplicated");
            }
        }
    }

    private String hash(DatasetManifest manifest) {
        try {
            Map<String, Object> canonical = Map.of(
                    "datasetId", manifest.datasetId(),
                    "version", manifest.version(),
                    "subjectType", manifest.subjectType().name(),
                    "description", manifest.description() == null ? "" : manifest.description(),
                    "goldLabelVersion", manifest.goldLabelVersion() == null
                            ? "" : manifest.goldLabelVersion(),
                    "evidenceSchemaVersion", manifest.evidenceSchemaVersion(),
                    "items", manifest.items());
            byte[] bytes = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to canonicalize dataset manifest", error);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record DatasetManifest(
            String datasetId,
            String version,
            SubjectType subjectType,
            String description,
            String goldLabelVersion,
            String evidenceSchemaVersion,
            List<DatasetItem> items) {
        public DatasetManifest {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record DatasetItem(
            String subjectId,
            String subjectSnapshotHash,
            String partition,
            String category,
            String difficulty,
            boolean replayable,
            String replayError) {
    }

    private record StoredDataset(long id, String manifestHash) {
    }

    private static final class SetHolder {
        private static final java.util.Set<String> PARTITIONS =
                java.util.Set.of("TRAIN", "TUNING", "HOLDOUT");

        private SetHolder() {
        }
    }
}
