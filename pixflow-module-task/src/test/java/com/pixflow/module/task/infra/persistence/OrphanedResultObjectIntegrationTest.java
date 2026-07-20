package com.pixflow.module.task.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.DefaultStorageBucketResolver;
import com.pixflow.infra.storage.MinioObjectStorage;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageInitializer;
import com.pixflow.infra.storage.StorageProperties;
import com.pixflow.module.task.api.publication.CandidateKind;
import com.pixflow.module.task.api.publication.ProducerIdentity;
import com.pixflow.module.task.api.publication.SourceImageIdentity;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.internal.worker.CandidateArtifact;
import com.pixflow.module.task.internal.worker.ExecutionRun;
import com.pixflow.module.task.internal.worker.WorkUnitCompletion;
import com.pixflow.module.task.internal.worker.WorkUnitResultRepository;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = OrphanedResultObjectIntegrationTest.TestApp.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/task/V1__create_task_execution.sql"
    })
class OrphanedResultObjectIntegrationTest {
  private static final String ACCESS_KEY = "minioadmin";
  private static final String SECRET_KEY = "minioadmin";

  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("pixflow_task_orphan")
          .withUsername("pixflow")
          .withPassword("pixflow");

  @Container
  static final GenericContainer<?> MINIO =
      new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"))
          .withExposedPorts(9000)
          .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
          .withCommand("server", "/data");

  private static ObjectStorage objectStorage;

  @Autowired ProcessTaskMapper taskMapper;
  @Autowired ProcessResultMapper resultMapper;
  @Autowired ProcessResultMemberMapper memberMapper;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
  }

  @BeforeAll
  static void setUpStorage() {
    StorageProperties properties = new StorageProperties();
    properties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    properties.setAccessKey(ACCESS_KEY);
    properties.setSecretKey(SECRET_KEY);
    properties.setUploadPartSize(DataSize.ofMegabytes(5));
    properties.getBuckets().setPackages("orphan-packages");
    properties.getBuckets().setResults("orphan-results");
    properties.getBuckets().setGenerated("orphan-generated");
    properties.getBuckets().setToolResults("orphan-tool-results");
    properties.getBuckets().setTmp("orphan-tmp");
    var client =
        io.minio.MinioClient.builder()
            .endpoint(properties.getEndpoint())
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build();
    var resolver = new DefaultStorageBucketResolver(properties);
    new StorageInitializer(client, resolver, properties).run(null);
    objectStorage = new MinioObjectStorage(client, resolver, properties);
  }

  @Test
  void objectWrittenByStaleEpochRemainsUnreferencedAndInvisible() {
    Instant now = Instant.parse("2026-07-13T00:00:00Z");
    ProcessTask task = task(now);
    taskMapper.insert(task);
    UnitKey unitKey = UnitKey.branch(task.getId().toString(), "image-1", "branch-1");
    ProcessResult pending = pending(task.getId(), unitKey, now);
    resultMapper.insert(pending);

    assertThat(taskMapper.claimExecution(task.getId(), "owner-a", now, now.minusSeconds(60)))
        .isEqualTo(1);
    String objectKey =
        "results/"
            + task.getId()
            + "/units/"
            + UnitKeyCodec.sha256(unitKey)
            + "/epochs/1/output.png";
    ObjectLocation orphan = ObjectLocation.of(BucketType.TMP, objectKey);
    objectStorage.put(orphan, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/png");

    taskMapper.heartbeatEpoch(task.getId(), 1, now.minusSeconds(120));
    assertThat(taskMapper.claimExecution(task.getId(), "owner-b", now, now.minusSeconds(60)))
        .isEqualTo(1);

    WorkUnit unit =
        new WorkUnit(
            task.getId().toString(), TaskType.IMAGE_PROCESS, unitKey, List.of(), null, null);
    CandidateArtifact candidate =
        new CandidateArtifact(
            orphan,
            3L,
            "image/png",
            "png",
            CandidateKind.DETERMINISTIC,
            List.of(new SourceImageIdentity("image-1")),
            ProducerIdentity.deterministic("dag-pipeline", "branch-1"));
    WorkUnitCompletion completion =
        new WorkUnitCompletion.Succeeded(
            unit, 1, now, now.plusSeconds(1), candidate, null, List.of());
    var repository =
        new WorkUnitResultRepository(
            resultMapper, memberMapper, taskMapper, objectStorage, new ObjectMapper());

    assertThat(
            repository.commit(new ExecutionRun(task.getId().toString(), 1, () -> true), completion))
        .isEqualTo(WorkUnitResultRepository.CommitResult.FENCED);
    assertThat(objectStorage.exists(orphan)).isTrue();
    ProcessResult durable = resultMapper.selectByUnit(task.getId(), UnitKeyCodec.encode(unitKey));
    assertThat(durable.getStatus()).isEqualTo(ResultStatus.PENDING);
    assertThat(durable.getOutputMinioKey()).isNull();
    // 对外图库只读取 MySQL SUCCESS；孤儿对象不会因存在于 MinIO 而被扫描成结果。
    assertThat(resultMapper.pageConversationImages("orphan-conversation", 0, 20)).isEmpty();
  }

  private static ProcessTask task(Instant now) {
    ProcessTask task = new ProcessTask();
    task.setTaskType(TaskType.IMAGE_PROCESS);
    task.setConversationId("orphan-conversation");
    task.setPackageId(1L);
    task.setIdempotencyKey("orphan-object-fencing");
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

  private static ProcessResult pending(long taskId, UnitKey unitKey, Instant now) {
    ProcessResult row = new ProcessResult();
    row.setTaskId(taskId);
    row.setUnitKey(UnitKeyCodec.encode(unitKey));
    row.setUnitKind(UnitKind.BRANCH);
    row.setImageId("image-1");
    row.setBranchId("branch-1");
    row.setStatus(ResultStatus.PENDING);
    row.setRunEpoch(0L);
    row.setCreatedAt(now);
    return row;
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    SqlInitializationAutoConfiguration.class,
    MybatisPlusAutoConfiguration.class
  })
  @MapperScan("com.pixflow.module.task.infra.persistence")
  static class TestApp {}
}
