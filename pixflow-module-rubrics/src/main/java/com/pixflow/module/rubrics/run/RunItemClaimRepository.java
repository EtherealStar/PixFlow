package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.model.QualityGate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RunItemClaimRepository {
    private final JdbcTemplate jdbc;

    public RunItemClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunItemClaim> claim(
            long itemId, String owner, Instant now, Duration leaseDuration) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("claim owner must not be blank");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("claim lease duration must be positive");
        }
        Instant expiresAt = now.plus(leaseDuration);
        return jdbc.execute((ConnectionCallback<Optional<RunItemClaim>>) connection -> {
            try (var update = connection.prepareStatement("""
                    update rubrics_run_item
                    set status = 'RUNNING',
                        claim_epoch = last_insert_id(claim_epoch + 1),
                        claim_owner = ?, lease_expires_at = ?, heartbeat_at = ?,
                        attempt_count = attempt_count + 1, updated_at = ?
                    where id = ?
                      and (status in ('PENDING', 'FAILED_RETRYABLE')
                           or (status = 'RUNNING' and lease_expires_at < ?))
                    """)) {
                update.setString(1, owner);
                update.setTimestamp(2, Timestamp.from(expiresAt));
                update.setTimestamp(3, Timestamp.from(now));
                update.setTimestamp(4, Timestamp.from(now));
                update.setLong(5, itemId);
                update.setTimestamp(6, Timestamp.from(now));
                if (update.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            try (var query = connection.prepareStatement("select last_insert_id()");
                    var result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("claimed epoch was not returned");
                }
                return Optional.of(new RunItemClaim(itemId, result.getLong(1), owner, expiresAt));
            }
        });
    }

    public boolean heartbeat(
            RunItemClaim claim, Instant now, Duration leaseDuration) {
        Instant expiresAt = now.plus(leaseDuration);
        int updated = jdbc.update("""
                update rubrics_run_item
                set heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
                where id = ? and status = 'RUNNING'
                  and claim_epoch = ? and claim_owner = ? and lease_expires_at >= ?
                """,
                Timestamp.from(now), Timestamp.from(expiresAt), Timestamp.from(now),
                claim.itemId(), claim.epoch(), claim.owner(), Timestamp.from(now));
        return updated == 1;
    }

    public boolean finish(
            RunItemClaim claim,
            String subjectSnapshotHash,
            RunItemStatus status,
            Instant now) {
        if (status != RunItemStatus.SUCCEEDED
                && status != RunItemStatus.PARTIAL
                && status != RunItemStatus.FAILED) {
            throw new IllegalArgumentException("finish requires a terminal item status");
        }
        // epoch、owner、lease 和 snapshot 四重条件共同阻止旧 worker 的迟到提交。
        int updated = jdbc.update("""
                update rubrics_run_item
                set status = ?, subject_snapshot_hash = ?, finished_at = ?, updated_at = ?
                where id = ? and status = 'RUNNING'
                  and claim_epoch = ? and claim_owner = ? and lease_expires_at >= ?
                  and (subject_snapshot_hash is null or subject_snapshot_hash = ?)
                """,
                status.name(), subjectSnapshotHash, Timestamp.from(now), Timestamp.from(now),
                claim.itemId(), claim.epoch(), claim.owner(), Timestamp.from(now),
                subjectSnapshotHash);
        return updated == 1;
    }

    public boolean finishEvaluation(
            RunItemClaim claim,
            long runId,
            String templateHash,
            String evaluatorVersion,
            String subjectSnapshotHash,
            RunItemStatus status,
            QualityGate qualityGate,
            Double passRate,
            Double coverage,
            String evidencePackHash,
            Instant now) {
        if (status != RunItemStatus.SUCCEEDED && status != RunItemStatus.PARTIAL) {
            throw new IllegalArgumentException("evaluation checkpoint requires a successful terminal status");
        }
        // 评估事实和终态 checkpoint 共用同一 fencing 条件，旧 worker 无法覆盖接管者结果。
        int updated = jdbc.update("""
                update rubrics_run_item item
                join rubrics_run run on run.id = item.run_id
                set item.status = ?, item.subject_snapshot_hash = ?, item.quality_gate = ?,
                    item.pass_rate = ?, item.coverage = ?, item.evidence_pack_hash = ?,
                    item.error_msg = null, item.finished_at = ?, item.updated_at = ?,
                    run.evaluator_version = coalesce(run.evaluator_version, ?)
                where item.id = ? and item.run_id = ? and item.status = 'RUNNING'
                  and item.claim_epoch = ? and item.claim_owner = ?
                  and item.lease_expires_at >= ?
                  and (item.subject_snapshot_hash is null or item.subject_snapshot_hash = ?)
                  and run.template_hash = ?
                  and (run.evaluator_version is null or run.evaluator_version = ?)
                """,
                status.name(), subjectSnapshotHash, qualityGate.name(), decimal(passRate),
                decimal(coverage), evidencePackHash, Timestamp.from(now), Timestamp.from(now),
                evaluatorVersion, claim.itemId(), runId, claim.epoch(), claim.owner(),
                Timestamp.from(now), subjectSnapshotHash, templateHash, evaluatorVersion);
        // MySQL multi-table UPDATE 会按实际改动的两张表返回 1 或 2，任一正值都表示同一 fence 命中。
        return updated > 0;
    }

    public boolean failRetryable(RunItemClaim claim, String errorCode, Instant now) {
        int updated = jdbc.update("""
                update rubrics_run_item
                set status = 'FAILED_RETRYABLE', error_msg = ?, updated_at = ?
                where id = ? and status = 'RUNNING'
                  and claim_epoch = ? and claim_owner = ? and lease_expires_at >= ?
                """,
                errorCode, Timestamp.from(now), claim.itemId(), claim.epoch(), claim.owner(),
                Timestamp.from(now));
        return updated == 1;
    }

    /**
     * 将运行期发现的不可回放项收敛为 PARTIAL。
     *
     * <p>这里仍校验 claim epoch、owner 与 lease，防止失去所有权的 worker 覆盖接管者结果。
     */
    public boolean finishNonReplayable(
            RunItemClaim claim, String errorCode, Instant now) {
        int updated = jdbc.update("""
                update rubrics_run_item
                set status = 'PARTIAL', error_msg = ?, finished_at = ?, updated_at = ?
                where id = ? and status = 'RUNNING'
                  and claim_epoch = ? and claim_owner = ? and lease_expires_at >= ?
                """,
                "NON_REPLAYABLE:" + errorCode,
                Timestamp.from(now),
                Timestamp.from(now),
                claim.itemId(),
                claim.epoch(),
                claim.owner(),
                Timestamp.from(now));
        return updated == 1;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
