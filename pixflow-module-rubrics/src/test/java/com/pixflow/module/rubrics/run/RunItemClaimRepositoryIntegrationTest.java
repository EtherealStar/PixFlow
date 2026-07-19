package com.pixflow.module.rubrics.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import com.pixflow.module.rubrics.model.QualityGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class RunItemClaimRepositoryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private JdbcTemplate jdbc;

    private RunItemClaimRepository claims;

    @BeforeEach
    void prepareItem() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists rubrics_run_item");
        jdbc.execute("drop table if exists rubrics_run");
        jdbc.execute("""
                create table rubrics_run (
                  id bigint not null auto_increment primary key,
                  template_hash char(64) not null,
                  evaluator_version varchar(255) null
                )
                """);
        jdbc.execute("""
                create table rubrics_run_item (
                  id bigint not null auto_increment primary key,
                  run_id bigint not null,
                  status varchar(32) not null,
                  claim_epoch bigint not null default 0,
                  claim_owner varchar(128) null,
                  lease_expires_at datetime(3) null,
                  heartbeat_at datetime(3) null,
                  attempt_count int not null default 0,
                  subject_snapshot_hash char(64) null,
                  quality_gate varchar(32) null,
                  pass_rate decimal(8,6) null,
                  coverage decimal(8,6) null,
                  evidence_pack_hash char(64) null,
                  error_msg varchar(1000) null,
                  finished_at datetime(3) null,
                  updated_at datetime(3) not null
                )
                """);
        jdbc.update("insert into rubrics_run(template_hash) values (?)", "a".repeat(64));
        jdbc.update("insert into rubrics_run_item(run_id, status, updated_at) values (1, 'PENDING', ?)",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        claims = new RunItemClaimRepository(jdbc);
    }

    @Test
    void staleEpochCannotHeartbeatOrFinishAfterLeaseTakeover() {
        Instant initialTime = Instant.parse("2026-01-01T00:00:00Z");
        RunItemClaim first = claims.claim(1, "worker-a", initialTime, Duration.ofSeconds(30))
                .orElseThrow();
        RunItemClaim second = claims.claim(
                1, "worker-b", initialTime.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

        assertThat(second.epoch()).isEqualTo(first.epoch() + 1);
        assertThat(claims.heartbeat(first, initialTime.plusSeconds(32), Duration.ofSeconds(30)))
                .isFalse();
        assertThat(claims.finish(first, "snapshot-a", RunItemStatus.SUCCEEDED,
                initialTime.plusSeconds(32))).isFalse();
        assertThat(claims.heartbeat(second, initialTime.plusSeconds(32), Duration.ofSeconds(30)))
                .isTrue();
        assertThat(claims.finish(second, "snapshot-a", RunItemStatus.SUCCEEDED,
                initialTime.plusSeconds(33))).isTrue();
        assertThat(claims.claim(
                1, "worker-c", initialTime.plusSeconds(100), Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void evaluationFactsAndTerminalCheckpointUseTheSameFence() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        RunItemClaim claim = claims.claim(1, "worker-a", now, Duration.ofSeconds(30))
                .orElseThrow();

        assertThat(claims.finishEvaluation(
                claim,
                1,
                "a".repeat(64),
                "deterministic",
                "snapshot-a",
                RunItemStatus.PARTIAL,
                QualityGate.UNKNOWN,
                0.5,
                0.75,
                "evidence-pack-a",
                now.plusSeconds(1)))
                .isTrue();

        assertThat(jdbc.queryForMap("select * from rubrics_run_item where id = 1"))
                .containsEntry("status", "PARTIAL")
                .containsEntry("subject_snapshot_hash", "snapshot-a")
                .containsEntry("quality_gate", "UNKNOWN")
                .containsEntry("evidence_pack_hash", "evidence-pack-a");
        assertThat(claims.finishEvaluation(
                claim,
                1,
                "a".repeat(64),
                "deterministic",
                "snapshot-a",
                RunItemStatus.SUCCEEDED,
                QualityGate.PASSED,
                1.0,
                1.0,
                "evidence-pack-a",
                now.plusSeconds(2)))
                .isFalse();
    }
}
