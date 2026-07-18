package com.pixflow.module.file.internal.publication;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedImagePublicationRecoveryTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:02:00Z");

  @Test
  void onlyClaimWinnersReplayBoundedPublicationAndCleanupWork() {
    AssetImageMapper images = mock(AssetImageMapper.class);
    DefaultGeneratedImagePublisher publisher = mock(DefaultGeneratedImagePublisher.class);
    AssetImage publishing = image(11L, NOW.minusSeconds(120));
    AssetImage lostRace = image(12L, NOW.minusSeconds(120));
    AssetImage cleanup = image(13L, NOW.minusSeconds(120));
    AssetImage laterCleanup = image(14L, NOW.minusSeconds(120));
    when(images.findPublishingBefore(NOW.minusSeconds(60), 50))
        .thenReturn(List.of(publishing, lostRace));
    when(images.findCleanupPendingBefore(NOW.minusSeconds(60), 50))
        .thenReturn(List.of(cleanup, laterCleanup));
    when(images.claimPublishing(11L, publishing.getPublicationUpdatedAt(), NOW)).thenReturn(1);
    when(images.claimPublishing(12L, lostRace.getPublicationUpdatedAt(), NOW)).thenReturn(0);
    when(images.claimCleanup(13L, cleanup.getPublicationUpdatedAt(), NOW)).thenReturn(1);
    when(images.claimCleanup(14L, laterCleanup.getPublicationUpdatedAt(), NOW)).thenReturn(1);
    doThrow(new IllegalStateException("broken reservation"))
        .when(publisher).recoverCleanup(13L);
    var recovery = new GeneratedImagePublicationRecovery(
        images, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    recovery.recover();

    verify(publisher).recoverPublishing(11L);
    verify(publisher, never()).recoverPublishing(12L);
    verify(publisher).recoverCleanup(13L);
    verify(publisher).recoverCleanup(14L);
  }

  private static AssetImage image(long id, Instant updatedAt) {
    AssetImage image = new AssetImage();
    image.setId(id);
    image.setPublicationUpdatedAt(updatedAt);
    return image;
  }
}
