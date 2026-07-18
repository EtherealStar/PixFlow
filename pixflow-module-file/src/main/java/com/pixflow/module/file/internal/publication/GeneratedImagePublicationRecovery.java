package com.pixflow.module.file.internal.publication;

import com.pixflow.module.file.image.AssetImageMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;

/** 有界重放 PUBLISHING 和 READY 后未完成的 candidate 清理。 */
public final class GeneratedImagePublicationRecovery {
  private static final int BATCH_SIZE = 50;

  private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(1);

  private final AssetImageMapper images;

  private final DefaultGeneratedImagePublisher publisher;

  private final Clock clock;

  public GeneratedImagePublicationRecovery(
      AssetImageMapper images, DefaultGeneratedImagePublisher publisher, Clock clock) {
    this.images = images;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${pixflow.file.generated-publication-recovery.interval:PT1M}")
  public void recover() {
    var before = clock.instant().minus(CLAIM_TIMEOUT);
    for (var image : images.findPublishingBefore(before, BATCH_SIZE)) {
      var claimedAt = clock.instant();
      if (images.claimPublishing(image.getId(), image.getPublicationUpdatedAt(), claimedAt) == 1) {
        try {
          publisher.recoverPublishing(image.getId());
        } catch (RuntimeException ignored) {
          // publisher 已持久化可安全公开的诊断；下一个扫描周期继续同一 reservation。
        }
      }
    }
    for (var image : images.findCleanupPendingBefore(before, BATCH_SIZE)) {
      var claimedAt = clock.instant();
      if (images.claimCleanup(image.getId(), image.getPublicationUpdatedAt(), claimedAt) == 1) {
        try {
          publisher.recoverCleanup(image.getId());
        } catch (RuntimeException ignored) {
          // 单条损坏 reservation 不能阻塞同一批次中其他 READY asset 的候选清理。
        }
      }
    }
  }
}
