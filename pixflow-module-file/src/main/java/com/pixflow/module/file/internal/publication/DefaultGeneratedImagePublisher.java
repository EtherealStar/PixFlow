package com.pixflow.module.file.internal.publication;

import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.PublishedImage;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.internal.output.GeneratedOutputContextMapper;
import com.pixflow.module.file.internal.output.GeneratedOutputContextRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL reservation 与对象 copy 分阶段执行的幂等 Generated Image publisher。 */
public final class DefaultGeneratedImagePublisher implements GeneratedImagePublisher {
  private final AssetImageMapper imageMapper;

  private final AssetImageLineageSourceMapper lineageMapper;

  private final GeneratedOutputContextMapper outputContextMapper;

  private final ObjectStorage objectStorage;

  private final CanonicalAssetReferenceCodec codec;

  private final TransactionTemplate transactions;

  private final Clock clock;

  public DefaultGeneratedImagePublisher(
      AssetImageMapper imageMapper,
      AssetImageLineageSourceMapper lineageMapper,
      GeneratedOutputContextMapper outputContextMapper,
      ObjectStorage objectStorage,
      CanonicalAssetReferenceCodec codec,
      TransactionTemplate transactions,
      Clock clock) {
    this.imageMapper = imageMapper;
    this.lineageMapper = lineageMapper;
    this.outputContextMapper = outputContextMapper;
    this.objectStorage = objectStorage;
    this.codec = codec;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public PublishedImage publish(PublishGeneratedImage command) {
    Objects.requireNonNull(command, "command");
    AssetImage reservation = reserve(command);
    if (reservation == null) {
      throw new IllegalStateException("Generated Image reservation was not created");
    }
    verifyIdempotency(reservation, command);
    ObjectLocation stable = stableLocation(reservation);
    if (!"READY".equals(reservation.getPublicationStatus())) {
      materialize(reservation, stable);
      reservation = imageMapper.selectById(reservation.getId());
    }
    cleanupCandidate(reservation);
    return published(reservation, stable);
  }

  private AssetImage reserve(PublishGeneratedImage command) {
    AssetImage existing =
        imageMapper.findBySourceResult(command.sourceTaskId(), command.sourceResultId());
    if (existing != null) {
      return existing;
    }
    try {
      return transactions.execute(status -> insertReservation(command));
    } catch (DuplicateKeyException duplicate) {
      // 唯一键竞争会令插入事务回滚；必须在事务外捕获，再用新事务读取胜者。
      AssetImage winner =
          transactions.execute(
              status ->
                  imageMapper.findBySourceResult(command.sourceTaskId(), command.sourceResultId()));
      if (winner == null) {
        throw duplicate;
      }
      return winner;
    }
  }

  private AssetImage insertReservation(PublishGeneratedImage command) {
    ensureOutputContext(command);
    AssetImage image = new AssetImage();
    image.setPackageId(command.packageId());
    image.setSourceType("GENERATED");
    image.setPublicationStatus("PUBLISHING");
    image.setCandidateBucket(command.candidate().bucket().name());
    image.setCandidateKey(command.candidate().key());
    image.setStableBucket(stableBucket(command.kind()).name());
    image.setContentType(command.contentType());
    image.setByteSize(command.size());
    image.setSourceTaskId(command.sourceTaskId());
    image.setSourceResultId(command.sourceResultId());
    image.setSourceUnitKey(command.sourceUnitKey());
    image.setSourceRunEpoch(command.sourceRunEpoch());
    image.setSourceImageId(
        command.sourceImages().size() == 1 ? command.sourceImages().getFirst().imageId() : null);
    AssetImage primarySource = imageMapper.selectById(Long.parseLong(command.sourceImages().getFirst().imageId()));
    if (primarySource != null) {
      image.setSkuId(primarySource.getSkuId());
      image.setDisplayName("generated-" + primarySource.getDisplayName());
    }
    image.setProducerKind(command.kind().name());
    image.setProducerProvider(command.producer().provider());
    image.setProducerModel(command.producer().model());
    image.setProducerTool(command.producer().tool());
    image.setProducerNodeId(command.producer().nodeId());
    image.setCleanupStatus("NOT_READY");
    image.setCleanupAttemptCount(0);
    Instant now = clock.instant();
    image.setCreatedAt(now);
    image.setUpdatedAt(now);
    image.setPublicationUpdatedAt(now);
    imageMapper.insert(image);
    insertLineage(image.getId(), command, now);
    return image;
  }

  private void insertLineage(long imageId, PublishGeneratedImage command, Instant now) {
    for (int ordinal = 0; ordinal < command.sourceImages().size(); ordinal++) {
      AssetImageLineageSource source = new AssetImageLineageSource();
      source.setAssetImageId(imageId);
      source.setOrdinal(ordinal);
      source.setSourceImageId(command.sourceImages().get(ordinal).imageId());
      source.setCreatedAt(now);
      lineageMapper.insert(source);
    }
  }

  private void materialize(AssetImage reservation, ObjectLocation stable) {
    ObjectLocation candidate = candidateLocation(reservation);
    if (objectStorage.exists(stable)) {
      verifyMetadata(reservation, stable);
    } else if (objectStorage.exists(candidate)) {
      verifyMetadata(reservation, candidate);
      objectStorage.copy(candidate, stable);
      verifyMetadata(reservation, stable);
    } else {
      recordError(reservation.getId(), "candidate and stable object are both missing");
      throw new IllegalStateException("candidate and stable object are both missing");
    }
    Integer finalized =
        transactions.execute(
            status ->
                imageMapper.finalizeReady(
                    reservation.getId(), stable.key(), contentHash(stable), clock.instant()));
    if (finalized == null || finalized != 1) {
      AssetImage current = imageMapper.selectById(reservation.getId());
      if (current == null || !"READY".equals(current.getPublicationStatus())) {
        throw new IllegalStateException("Generated Image READY finalize was fenced");
      }
    }
  }

  private String contentHash(ObjectLocation location) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream source = objectStorage.getStream(location);
          DigestInputStream input = new DigestInputStream(source, digest)) {
        input.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("unable to hash generated image", failure);
    }
  }

  private void verifyMetadata(AssetImage image, ObjectLocation location) {
    StoredObjectMetadata metadata = objectStorage.stat(location);
    if (metadata.size() != image.getByteSize()
        || metadata.contentType() == null
        || !metadata.contentType().equalsIgnoreCase(image.getContentType())) {
      recordError(image.getId(), "object metadata conflict");
      throw new IllegalStateException("object metadata conflicts with publication reservation");
    }
  }

  private void cleanupCandidate(AssetImage image) {
    if (!"READY".equals(image.getPublicationStatus())) {
      return;
    }
    try {
      objectStorage.delete(candidateLocation(image));
      imageMapper.markCleaned(image.getId(), clock.instant());
    } catch (RuntimeException failure) {
      // READY 已提交，候选清理只能留给后续 bounded recovery，不能回滚稳定资产。
      imageMapper.recordCleanupError(image.getId(), safeMessage(failure), clock.instant());
    }
  }

  private void verifyIdempotency(AssetImage image, PublishGeneratedImage command) {
    ensureOutputContext(command);
    List<String> lineage =
        lineageMapper.findByImageId(image.getId()).stream()
            .map(AssetImageLineageSource::getSourceImageId)
            .toList();
    List<String> requested =
        command.sourceImages().stream().map(source -> source.imageId()).toList();
    boolean matches =
        Objects.equals(image.getPackageId(), command.packageId())
            && Objects.equals(image.getSourceUnitKey(), command.sourceUnitKey())
            && Objects.equals(image.getSourceRunEpoch(), command.sourceRunEpoch())
            && Objects.equals(image.getCandidateBucket(), command.candidate().bucket().name())
            && Objects.equals(image.getCandidateKey(), command.candidate().key())
            && Objects.equals(image.getStableBucket(), stableBucket(command.kind()).name())
            && Objects.equals(image.getByteSize(), command.size())
            && Objects.equals(image.getContentType(), command.contentType())
            && Objects.equals(image.getProducerKind(), command.kind().name())
            && Objects.equals(image.getProducerProvider(), command.producer().provider())
            && Objects.equals(image.getProducerModel(), command.producer().model())
            && Objects.equals(image.getProducerTool(), command.producer().tool())
            && Objects.equals(image.getProducerNodeId(), command.producer().nodeId())
            && lineage.equals(requested);
    if (!matches) {
      throw new IllegalStateException("source result idempotency conflict");
    }
  }

  private void ensureOutputContext(PublishGeneratedImage command) {
    Instant now = clock.instant();
    outputContextMapper.insertIfAbsent(command.outputContext(), now);
    GeneratedOutputContextRow current = outputContextMapper.find(command.outputContext().taskId());
    boolean matches = current != null
        && Objects.equals(current.conversationId(), command.outputContext().conversationId())
        && Objects.equals(current.conversationTitleSnapshot(), command.outputContext().conversationTitleSnapshot())
        && Objects.equals(current.taskType(), command.outputContext().taskType().name())
        && Objects.equals(current.taskCreatedAt(), command.outputContext().taskCreatedAt());
    if (!matches) {
      throw new IllegalStateException("output context idempotency conflict");
    }
  }

  @Override
  public void markTaskFinished(long taskId, Instant finishedAt) {
    if (taskId <= 0 || finishedAt == null) {
      throw new IllegalArgumentException("taskId and finishedAt are required");
    }
    outputContextMapper.markFinished(Long.toString(taskId), finishedAt);
  }

  private PublishedImage published(AssetImage image, ObjectLocation stable) {
    if (!"READY".equals(image.getPublicationStatus())
        || !stable.key().equals(image.getMinioKey())) {
      throw new IllegalStateException("Generated Image is not READY");
    }
    String reference =
        codec.serialize(new ImageAssetReferenceKey(image.getPackageId(), image.getId()));
    return new PublishedImage(image.getId(), reference);
  }

  ObjectLocation stableLocation(AssetImage image) {
    String extension = extensionOf(image.getCandidateKey());
    return "GENERATIVE".equals(image.getProducerKind())
        ? StorageKeys.generatedAsset(image.getPackageId(), image.getId(), extension)
        : StorageKeys.resultAsset(image.getPackageId(), image.getId(), extension);
  }

  void recoverPublishing(long imageId) {
    AssetImage image = requireImage(imageId);
    if (!"PUBLISHING".equals(image.getPublicationStatus())) {
      return;
    }
    materialize(image, stableLocation(image));
  }

  void recoverCleanup(long imageId) {
    AssetImage image = requireImage(imageId);
    cleanupCandidate(image);
  }

  private AssetImage requireImage(long imageId) {
    AssetImage image = imageMapper.selectById(imageId);
    if (image == null) {
      throw new IllegalStateException("Generated Image reservation does not exist: " + imageId);
    }
    return image;
  }

  private static ObjectLocation candidateLocation(AssetImage image) {
    return ObjectLocation.of(
        BucketType.valueOf(image.getCandidateBucket()), image.getCandidateKey());
  }

  private static String extensionOf(String key) {
    int separator = key.lastIndexOf('.');
    if (separator < 0 || separator == key.length() - 1) {
      throw new IllegalStateException("candidate key has no extension");
    }
    return key.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
  }

  private static String safeMessage(RuntimeException failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    return message.length() <= 1000 ? message : message.substring(0, 1000);
  }

  private static BucketType stableBucket(GeneratedImageKind kind) {
    return kind == GeneratedImageKind.GENERATIVE ? BucketType.GENERATED : BucketType.RESULTS;
  }

  private void recordError(long imageId, String error) {
    imageMapper.recordPublicationError(imageId, error, clock.instant());
  }
}
