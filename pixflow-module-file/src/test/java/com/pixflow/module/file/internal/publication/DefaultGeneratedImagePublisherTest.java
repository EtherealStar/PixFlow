package com.pixflow.module.file.internal.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImageProducer;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.SourceImageRef;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DefaultGeneratedImagePublisherTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void publishesOnceAndReplaysTheSameReadyIdentity() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    AssetImageLineageSourceMapper lineage = mock(AssetImageLineageSourceMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    AtomicReference<AssetImage> row = new AtomicReference<>();
    when(images.insert(any(AssetImage.class)))
        .thenAnswer(
            invocation -> {
              AssetImage image = invocation.getArgument(0);
              image.setId(91L);
              row.set(image);
              return 1;
            });
    when(images.finalizeReady(anyLong(), any(String.class), any(Instant.class)))
        .thenAnswer(
            invocation -> {
              row.get().setPublicationStatus("READY");
              row.get().setMinioKey(invocation.getArgument(1));
              row.get().setCleanupStatus("CLEANUP_PENDING");
              return 1;
            });
    when(images.selectById(91L)).thenAnswer(ignored -> row.get());
    when(lineage.findByImageId(91L))
        .thenReturn(List.of(lineage(91L, 0, "11"), lineage(91L, 1, "12")));
    ObjectLocation candidate = ObjectLocation.of(BucketType.TMP, "results/7/units/u/epochs/3/output.png");
    ObjectLocation stable =
        ObjectLocation.of(BucketType.RESULTS, "7/images/91/output.png");
    when(storage.exists(candidate)).thenReturn(true);
    when(storage.exists(stable)).thenReturn(false);
    when(storage.stat(any())).thenReturn(new StoredObjectMetadata(8L, "image/png", "etag", NOW));
    PublishGeneratedImage command = command(candidate);
    var publisher = publisher(images, lineage, storage);

    var first = publisher.publish(command);

    assertThat(first.imageId()).isEqualTo(91L);
    assertThat(first.referenceKey()).isEqualTo("package:7/image:91");
    verify(storage).copy(candidate, stable);
    verify(lineage, times(2)).insert(any(AssetImageLineageSource.class));

    when(images.findBySourceResult(5L, 6L)).thenReturn(row.get());
    var replay = publisher.publish(command);

    assertThat(replay).isEqualTo(first);
    verify(images).insert(any(AssetImage.class));
  }

  @Test
  void keepsCandidateWhenReadyFinalizeFailsAfterCopy() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    AssetImageLineageSourceMapper lineage = mock(AssetImageLineageSourceMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    AssetImage reservation = reservation();
    when(images.findBySourceResult(5L, 6L)).thenReturn(reservation);
    when(images.selectById(91L)).thenReturn(reservation);
    when(lineage.findByImageId(91L))
        .thenReturn(List.of(lineage(91L, 0, "11"), lineage(91L, 1, "12")));
    ObjectLocation stable = ObjectLocation.of(BucketType.RESULTS, "7/images/91/output.png");
    when(storage.exists(stable)).thenReturn(false);
    when(storage.exists(candidate())).thenReturn(true);
    when(storage.stat(any())).thenReturn(new StoredObjectMetadata(8L, "image/png", "etag", NOW));
    when(images.finalizeReady(91L, stable.key(), NOW)).thenReturn(0);

    assertThatThrownBy(() -> publisher(images, lineage, storage).publish(command(candidate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced");

    verify(storage).copy(candidate(), stable);
    verify(storage, never()).delete(candidate());
  }

  @Test
  void cleanupFailureDoesNotRollBackReadyAsset() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    AssetImageLineageSourceMapper lineage = mock(AssetImageLineageSourceMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    AssetImage ready = reservation();
    ready.setPublicationStatus("READY");
    ready.setMinioKey("7/images/91/output.png");
    when(images.findBySourceResult(5L, 6L)).thenReturn(ready);
    when(lineage.findByImageId(91L))
        .thenReturn(List.of(lineage(91L, 0, "11"), lineage(91L, 1, "12")));
    doThrow(new IllegalStateException("temporary delete failure"))
        .when(storage).delete(candidate());

    var published = publisher(images, lineage, storage).publish(command(candidate()));

    assertThat(published.imageId()).isEqualTo(91L);
    verify(images).recordCleanupError(91L, "temporary delete failure", NOW);
    verify(images, never()).markCleaned(anyLong(), any());
  }

  @Test
  void stableMetadataConflictFailsClosed() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    AssetImageLineageSourceMapper lineage = mock(AssetImageLineageSourceMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    AssetImage reservation = reservation();
    when(images.findBySourceResult(5L, 6L)).thenReturn(reservation);
    when(lineage.findByImageId(91L)).thenReturn(List.of(lineage(91L, 0, "11"), lineage(91L, 1, "12")));
    ObjectLocation stable =
        ObjectLocation.of(BucketType.RESULTS, "7/images/91/output.png");
    when(storage.exists(stable)).thenReturn(true);
    when(storage.stat(stable)).thenReturn(new StoredObjectMetadata(9L, "image/png", "other", NOW));

    assertThatThrownBy(() -> publisher(images, lineage, storage).publish(command(candidate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("metadata");

    verify(images).recordPublicationError(91L, "object metadata conflict", NOW);
    verify(storage, never()).copy(any(), any());
    verify(images, never()).finalizeReady(anyLong(), any(), any());
  }

  @Test
  void missingContentTypeFailsClosed() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    AssetImageLineageSourceMapper lineage = mock(AssetImageLineageSourceMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    AssetImage reservation = reservation();
    when(images.findBySourceResult(5L, 6L)).thenReturn(reservation);
    when(lineage.findByImageId(91L))
        .thenReturn(List.of(lineage(91L, 0, "11"), lineage(91L, 1, "12")));
    ObjectLocation stable = ObjectLocation.of(BucketType.RESULTS, "7/images/91/output.png");
    when(storage.exists(stable)).thenReturn(true);
    when(storage.stat(stable)).thenReturn(new StoredObjectMetadata(8L, null, "etag", NOW));

    assertThatThrownBy(() -> publisher(images, lineage, storage).publish(command(candidate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("metadata");

    verify(images).recordPublicationError(91L, "object metadata conflict", NOW);
    verify(images, never()).finalizeReady(anyLong(), any(), any());
  }

  @Test
  void rejectsUnsupportedOrMismatchedMediaExtension() {
    assertThatThrownBy(() -> command(
            ObjectLocation.of(BucketType.TMP, "results/7/units/u/epochs/3/output.gif")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extension");
    assertThatThrownBy(
            () ->
                new PublishGeneratedImage(
                    5L,
                    6L,
                    "BRANCH|7|11|branch-a",
                    3L,
                    7L,
                    candidate(),
                    8L,
                    "image/jpeg",
                    "png",
                    GeneratedImageKind.DETERMINISTIC,
                    List.of(new SourceImageRef("11")),
                    new GeneratedImageProducer(
                        GeneratedImageKind.DETERMINISTIC,
                        null,
                        null,
                        "dag-pipeline",
                        "branch-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contentType");
  }

  private static DefaultGeneratedImagePublisher publisher(
      AssetImageMapper images, AssetImageLineageSourceMapper lineage, ObjectStorage storage) {
    return new DefaultGeneratedImagePublisher(
        images,
        lineage,
        storage,
        new CanonicalAssetReferenceCodec(),
        new TransactionTemplate(transactionManager()),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PublishGeneratedImage command(ObjectLocation candidate) {
    return new PublishGeneratedImage(
        5L,
        6L,
        "BRANCH|7|11|branch-a",
        3L,
        7L,
        candidate,
        8L,
        "image/png",
        "png",
        GeneratedImageKind.DETERMINISTIC,
        List.of(new SourceImageRef("11"), new SourceImageRef("12")),
        new GeneratedImageProducer(
            GeneratedImageKind.DETERMINISTIC, null, null, "dag-pipeline", "branch-a"));
  }

  private static ObjectLocation candidate() {
    return ObjectLocation.of(BucketType.TMP, "results/7/units/u/epochs/3/output.png");
  }

  private static AssetImage reservation() {
    AssetImage image = new AssetImage();
    image.setId(91L);
    image.setPackageId(7L);
    image.setPublicationStatus("PUBLISHING");
    image.setCandidateBucket("TMP");
    image.setCandidateKey(candidate().key());
    image.setStableBucket("RESULTS");
    image.setContentType("image/png");
    image.setByteSize(8L);
    image.setSourceTaskId(5L);
    image.setSourceResultId(6L);
    image.setSourceUnitKey("BRANCH|7|11|branch-a");
    image.setSourceRunEpoch(3L);
    image.setProducerKind("DETERMINISTIC");
    image.setProducerTool("dag-pipeline");
    image.setProducerNodeId("branch-a");
    return image;
  }

  private static AssetImageLineageSource lineage(long imageId, int ordinal, String sourceId) {
    AssetImageLineageSource source = new AssetImageLineageSource();
    source.setAssetImageId(imageId);
    source.setOrdinal(ordinal);
    source.setSourceImageId(sourceId);
    return source;
  }

  private static PlatformTransactionManager transactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {
      }

      @Override
      public void rollback(TransactionStatus status) {
      }
    };
  }
}
