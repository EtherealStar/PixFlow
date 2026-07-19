package com.pixflow.module.file.internal.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.DefaultStorageBucketResolver;
import com.pixflow.infra.storage.MinioObjectStorage;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageInitializer;
import com.pixflow.infra.storage.StorageProperties;
import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImageProducer;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.PublishedImage;
import com.pixflow.module.file.api.publication.SourceImageRef;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.PackageStatus;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.internal.deletion.AssetCleanupIntentMapper;
import com.pixflow.module.file.internal.deletion.AssetReferenceTombstoneMapper;
import com.pixflow.module.file.internal.deletion.DefaultAssetDeletionService;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GeneratedImagePublicationIntegrationTest.TestApp.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema.sql"
    })
class GeneratedImagePublicationIntegrationTest {
  private static final String ACCESS_KEY = "minioadmin";

  private static final String SECRET_KEY = "minioadmin";

  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("pixflow_file")
          .withUsername("pixflow")
          .withPassword("pixflow");

  @Container
  static final GenericContainer<?> MINIO =
      new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"))
          .withExposedPorts(9000)
          .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
          .withCommand("server", "/data");

  private static ObjectStorage storage;

  @Autowired AssetImageMapper images;

  @Autowired AssetImageLineageSourceMapper lineage;

  @Autowired AssetPackageMapper packages;

  @Autowired AssetCopyMapper copies;

  @Autowired AssetIngestErrorMapper errors;

  @Autowired AssetReferenceTombstoneMapper tombstones;

  @Autowired AssetCleanupIntentMapper cleanupIntents;

  @Autowired PlatformTransactionManager transactionManager;

  @BeforeAll
  static void configureStorage() {
    StorageProperties properties = new StorageProperties();
    properties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    properties.setAccessKey(ACCESS_KEY);
    properties.setSecretKey(SECRET_KEY);
    properties.setUploadPartSize(DataSize.ofMegabytes(5));
    properties.getBuckets().setPackages("publication-it-packages");
    properties.getBuckets().setResults("publication-it-results");
    properties.getBuckets().setGenerated("publication-it-generated");
    properties.getBuckets().setToolResults("publication-it-tool-results");
    properties.getBuckets().setTmp("publication-it-tmp");
    var client =
        io.minio.MinioClient.builder()
            .endpoint(properties.getEndpoint())
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build();
    var resolver = new DefaultStorageBucketResolver(properties);
    new StorageInitializer(client, resolver, properties).run(null);
    storage = new MinioObjectStorage(client, resolver, properties);
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
  }

  @BeforeEach
  void clearDatabase() {
    lineage.delete(new QueryWrapper<>());
    images.delete(new QueryWrapper<>());
    copies.delete(new QueryWrapper<>());
    errors.delete(new QueryWrapper<>());
    cleanupIntents.delete(new QueryWrapper<>());
    tombstones.delete(new QueryWrapper<>());
    packages.delete(new QueryWrapper<>());
  }

  @Test
  void concurrentReplayCreatesOneReadyImageAndOrderedLineage() throws Exception {
    var command = command(5L, 6L, List.of(new SourceImageRef("11"), new SourceImageRef("12")));
    putCandidate(command);
    var publisher = publisher(images, storage);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              List.<Callable<PublishedImage>>of(
                  () -> publisher.publish(command), () -> publisher.publish(command)));
      var first = results.get(0).get();
      var second = results.get(1).get();

      assertThat(first.imageId()).isEqualTo(second.imageId());
      assertThat(first.referenceKey()).isEqualTo(second.referenceKey());
    }

    var rows = images.selectList(new QueryWrapper<>());
    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.getPublicationStatus()).isEqualTo("READY");
      assertThat(row.getSourceImageId()).isNull();
      assertThat(storage.exists(ObjectLocation.of(
          BucketType.RESULTS, row.getMinioKey()))).isTrue();
    });
    assertThat(lineage.findByImageId(rows.getFirst().getId()))
        .extracting(AssetImageLineageSource::getSourceImageId)
        .containsExactly("11", "12");
    assertThat(storage.exists(command.candidate())).isFalse();
  }

  @Test
  void reservationFailureReplaysSameIdentity() {
    var command = command(7L, 8L, List.of(new SourceImageRef("21")));
    putCandidate(command);
    ObjectStorage failingStorage = spy(storage);
    doThrow(new IllegalStateException("crash after reservation"))
        .when(failingStorage)
        .exists(any(ObjectLocation.class));

    assertThatThrownBy(() -> publisher(images, failingStorage).publish(command))
        .hasMessageContaining("crash after reservation");
    long reservedId = images.findBySourceResult(7L, 8L).getId();

    var published = publisher(images, storage).publish(command);

    assertThat(published.imageId()).isEqualTo(reservedId);
    assertThat(images.findBySourceResult(7L, 8L).getPublicationStatus()).isEqualTo("READY");
  }

  @Test
  void copiedStableObjectFinalizesOnReplayWithoutSecondIdentity() {
    var command = command(9L, 10L, List.of(new SourceImageRef("31")));
    putCandidate(command);
    AssetImageMapper failingMapper = spy(images);
    doReturn(0).when(failingMapper).finalizeReady(anyLong(), any(), any());

    assertThatThrownBy(() -> publisher(failingMapper, storage).publish(command))
        .hasMessageContaining("fenced");
    var reservation = images.findBySourceResult(9L, 10L);
    long reservedId = reservation.getId();
    assertThat(storage.exists(publisher(images, storage).stableLocation(reservation))).isTrue();
    assertThat(storage.exists(command.candidate())).isTrue();

    var published = publisher(images, storage).publish(command);

    assertThat(published.imageId()).isEqualTo(reservedId);
    assertThat(storage.exists(command.candidate())).isFalse();
  }

  @Test
  void readyCleanupFailureIsRecoveredWithoutRollingBackAsset() {
    var command = command(11L, 12L, List.of(new SourceImageRef("41")));
    putCandidate(command);
    ObjectStorage failingStorage = spy(storage);
    doThrow(new IllegalStateException("crash before cleanup"))
        .when(failingStorage)
        .delete(command.candidate());

    var published = publisher(images, failingStorage).publish(command);
    var ready = images.selectById(published.imageId());
    assertThat(ready.getPublicationStatus()).isEqualTo("READY");
    assertThat(ready.getCleanupStatus()).isEqualTo("CLEANUP_PENDING");
    assertThat(storage.exists(command.candidate())).isTrue();

    publisher(images, storage).recoverCleanup(published.imageId());

    assertThat(images.selectById(published.imageId()).getCleanupStatus()).isEqualTo("CLEANED");
    assertThat(storage.exists(command.candidate())).isFalse();
  }

  @Test
  void missingCandidateAndStableKeepsDiagnosticOnSameReservation() {
    var command = command(13L, 14L, List.of(new SourceImageRef("51")));

    assertThatThrownBy(() -> publisher(images, storage).publish(command))
        .hasMessageContaining("both missing");

    var reservation = images.findBySourceResult(13L, 14L);
    assertThat(reservation.getPublicationStatus()).isEqualTo("PUBLISHING");
    assertThat(reservation.getPublicationError()).contains("both missing");
    assertThat(images.selectList(new QueryWrapper<>())).hasSize(1);
  }

  @Test
  void generatedImageDeletionRemovesStableBytesAndKeepsOnlyTombstone() {
    AssetImage image = generatedImage(77L, "generated/delete-me.png");
    images.insert(image);
    ObjectLocation location = ObjectLocation.of(BucketType.GENERATED, image.getMinioKey());
    storage.put(location, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/png");

    deletionService().deleteImage(77L, image.getId());
    deletionService().deleteImage(77L, image.getId());

    assertThat(images.selectById(image.getId())).isNull();
    assertThat(storage.exists(location)).isFalse();
    assertThat(tombstones.findIdentity("IMAGE", 77L, "", image.getId()))
        .isNotNull()
        .extracting(com.pixflow.module.file.internal.deletion.AssetReferenceTombstone::getDisplayName)
        .isEqualTo("Generated Image " + image.getId());
  }

  @Test
  void packageDeletionRemovesOriginalsButPreservesGeneratedImage() {
    AssetPackage assetPackage = new AssetPackage();
    assetPackage.setName("summer.zip");
    assetPackage.setStatus(PackageStatus.READY);
    assetPackage.setCreatedAt(NOW);
    assetPackage.setUpdatedAt(NOW);
    packages.insert(assetPackage);

    AssetImage original = new AssetImage();
    original.setPackageId(assetPackage.getId());
    original.setOriginalPath("SKU-1/front.png");
    original.setMinioKey(assetPackage.getId() + "/images/SKU-1/front.png");
    original.setSourceType("ORIGINAL");
    original.setPublicationStatus("READY");
    original.setStableBucket(BucketType.PACKAGES.name());
    original.setCreatedAt(NOW);
    images.insert(original);
    AssetImage generated = generatedImage(assetPackage.getId(), "generated/keep.png");
    images.insert(generated);
    ObjectLocation originalLocation = ObjectLocation.of(BucketType.PACKAGES, original.getMinioKey());
    ObjectLocation generatedLocation = ObjectLocation.of(BucketType.GENERATED, generated.getMinioKey());
    storage.put(originalLocation, new ByteArrayInputStream(new byte[] {1}), 1, "image/png");
    storage.put(generatedLocation, new ByteArrayInputStream(new byte[] {2}), 1, "image/png");

    deletionService().deletePackage(assetPackage.getId());

    assertThat(packages.selectById(assetPackage.getId())).isNull();
    assertThat(images.selectById(original.getId())).isNull();
    assertThat(storage.exists(originalLocation)).isFalse();
    assertThat(images.selectById(generated.getId())).isNotNull();
    assertThat(storage.exists(generatedLocation)).isTrue();
    assertThat(tombstones.findPackageDisplayName(assetPackage.getId())).isEqualTo("summer.zip");
  }

  private DefaultAssetDeletionService deletionService() {
    return new DefaultAssetDeletionService(
        packages, images, copies, errors, tombstones, cleanupIntents, storage,
        new TransactionTemplate(transactionManager), CLOCK);
  }

  private static AssetImage generatedImage(long packageId, String key) {
    AssetImage image = new AssetImage();
    image.setPackageId(packageId);
    image.setMinioKey(key);
    image.setSourceType("GENERATED");
    image.setPublicationStatus("READY");
    image.setStableBucket(BucketType.GENERATED.name());
    image.setContentType("image/png");
    image.setByteSize(3L);
    image.setCreatedAt(NOW);
    return image;
  }

  private DefaultGeneratedImagePublisher publisher(
      AssetImageMapper imageMapper, ObjectStorage objectStorage) {
    return new DefaultGeneratedImagePublisher(
        imageMapper,
        lineage,
        objectStorage,
        new CanonicalAssetReferenceCodec(),
        new TransactionTemplate(transactionManager),
        CLOCK);
  }

  private static PublishGeneratedImage command(
      long taskId, long resultId, List<SourceImageRef> sources) {
    byte[] payload = "generated-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ObjectLocation candidate =
        ObjectLocation.of(BucketType.TMP, "publication/" + UUID.randomUUID() + ".png");
    return new PublishGeneratedImage(
        taskId,
        resultId,
        "BRANCH|image|front",
        3L,
        7L,
        candidate,
        payload.length,
        "image/png",
        "png",
        GeneratedImageKind.DETERMINISTIC,
        sources,
        new GeneratedImageProducer(
            GeneratedImageKind.DETERMINISTIC, null, null, "dag-pipeline", "front"));
  }

  private static void putCandidate(PublishGeneratedImage command) {
    byte[] payload = "generated-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    storage.put(
        command.candidate(),
        new ByteArrayInputStream(payload),
        payload.length,
        command.contentType());
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    SqlInitializationAutoConfiguration.class,
    MybatisPlusAutoConfiguration.class
  })
  @MapperScan(
      basePackageClasses = {
        AssetImageMapper.class,
        AssetImageLineageSourceMapper.class,
        AssetPackageMapper.class,
        AssetCopyMapper.class,
        AssetIngestErrorMapper.class,
        AssetReferenceTombstoneMapper.class,
        AssetCleanupIntentMapper.class
      })
  static class TestApp {}
}
