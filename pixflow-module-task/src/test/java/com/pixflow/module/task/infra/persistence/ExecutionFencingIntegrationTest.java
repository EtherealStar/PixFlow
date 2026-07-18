package com.pixflow.module.task.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = ExecutionFencingIntegrationTest.TestApp.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/migration/V1__create_process_task_tables.sql"
    })
class ExecutionFencingIntegrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("pixflow_task")
          .withUsername("pixflow")
          .withPassword("pixflow");

  @Autowired ProcessTaskMapper taskMapper;
  @Autowired ProcessResultMapper resultMapper;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
  }

  @Test
  void higherEpochFencesOldHeartbeatResultAndTerminalWritesWhileSuccessRemainsImmutable() {
    Instant now = Instant.parse("2026-07-13T00:00:00Z");
    ProcessTask task = task(now);
    taskMapper.insert(task);
    ProcessResult result = pending(task.getId(), now);
    resultMapper.insert(result);

    assertThat(taskMapper.claimExecution(task.getId(), "owner-a", now, now.minusSeconds(60)))
        .isEqualTo(1);
    assertThat(resultMapper.commitForEpoch(task.getId(), result.getUnitKey(), 1, success(now)))
        .isEqualTo(1);

    taskMapper.heartbeatEpoch(task.getId(), 1, now.minusSeconds(120));
    assertThat(taskMapper.claimExecution(task.getId(), "owner-b", now, now.minusSeconds(60)))
        .isEqualTo(1);
    assertThat(taskMapper.heartbeatEpoch(task.getId(), 1, now)).isZero();
    assertThat(resultMapper.commitForEpoch(task.getId(), result.getUnitKey(), 1, failed(now)))
        .isZero();
    assertThat(resultMapper.commitForEpoch(task.getId(), result.getUnitKey(), 2, failed(now)))
        .isZero();
    assertThat(
            taskMapper.markTerminalEpoch(
                task.getId(), 1, TaskStatus.FAILED, 1, 1, "stale owner", now))
        .isZero();
    assertThat(resultMapper.selectByUnit(task.getId(), result.getUnitKey()).getStatus())
        .isEqualTo(ResultStatus.SUCCESS);
    assertThat(taskMapper.markTerminalEpoch(task.getId(), 2, TaskStatus.COMPLETED, 1, 1, null, now))
        .isEqualTo(1);
    assertThat(taskMapper.selectById(task.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  private static ProcessTask task(Instant now) {
    ProcessTask task = new ProcessTask();
    task.setTaskType(TaskType.IMAGE_PROCESS);
    task.setConversationId("c1");
    task.setPackageId(1L);
    task.setIdempotencyKey("fence-1");
    task.setPriority(0);
    task.setStatus(TaskStatus.QUEUED);
    task.setTotalCount(1);
    task.setDoneCount(0);
    task.setDagJson("{}");
    task.setUnitSelectionJson("{}");
    task.setSchemaVersion("1.0");
    task.setRunEpoch(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private static ProcessResult pending(long taskId, Instant now) {
    ProcessResult row = new ProcessResult();
    row.setTaskId(taskId);
    row.setUnitKey("BRANCH|image-1|branch-1");
    row.setUnitKind(UnitKind.BRANCH);
    row.setImageId("image-1");
    row.setBranchId("branch-1");
    row.setStatus(ResultStatus.PENDING);
    row.setRunEpoch(0L);
    row.setCreatedAt(now);
    return row;
  }

  private static ProcessResult success(Instant now) {
    ProcessResult row = new ProcessResult();
    row.setStatus(ResultStatus.SUCCESS);
    row.setOutputMinioKey("results/1/output.png");
    row.setAttemptCount(1);
    row.setStartedAt(now);
    row.setFinishedAt(now);
    return row;
  }

  private static ProcessResult failed(Instant now) {
    ProcessResult row = new ProcessResult();
    row.setStatus(ResultStatus.FAILED);
    row.setFailureCode("FAILED");
    row.setAttemptCount(1);
    row.setStartedAt(now);
    row.setFinishedAt(now);
    return row;
  }

  @SpringBootConfiguration
  // Mapper 集成测试只装配持久化基础设施，避免业务模块自动配置污染测试边界。
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    SqlInitializationAutoConfiguration.class,
    MybatisPlusAutoConfiguration.class
  })
  @MapperScan("com.pixflow.module.task.infra.persistence")
  static class TestApp {}
}
