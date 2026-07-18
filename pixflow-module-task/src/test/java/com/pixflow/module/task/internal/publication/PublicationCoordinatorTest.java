package com.pixflow.module.task.internal.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.cache.lock.LockGuard;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedGeneratedAsset;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.PublicationStatus;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.internal.worker.ExecutionRun;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void replaysBacklogAndBindsPublishedIdentityWithOwnerFence() {
    Fixture fixture = fixture();
    when(fixture.results.bindPublished(
            eq(5L), eq(6L), eq(3L), eq(4L), eq(91L),
            eq("package:7/image:91"), eq(NOW)))
        .thenReturn(1);

    assertThat(fixture.coordinator.publishBacklog(fixture.task, fixture.run)).isTrue();

    verify(fixture.publication).publish(any());
    verify(fixture.results)
        .bindPublished(5L, 6L, 3L, 4L, 91L, "package:7/image:91", NOW);
  }

  @Test
  void returnsFalseWhenPublishedBindLosesOwnerFence() {
    Fixture fixture = fixture();
    when(fixture.results.bindPublished(eq(5L), eq(6L), eq(3L), eq(4L), eq(91L),
            eq("package:7/image:91"), eq(NOW)))
        .thenReturn(0);

    assertThat(fixture.coordinator.publishBacklog(fixture.task, fixture.run)).isFalse();
  }

  @Test
  void publicationFailureKeepsBacklogPendingAndRecordsDiagnostic() {
    Fixture fixture = fixture();
    when(fixture.publication.publish(any())).thenThrow(new IllegalStateException("copy unavailable"));

    assertThat(fixture.coordinator.publishBacklog(fixture.task, fixture.run)).isFalse();

    verify(fixture.results).recordPublicationFailure(5L, 6L, 4L, "copy unavailable");
    assertThat(fixture.result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(fixture.result.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
  }

  private static Fixture fixture() {
    ProcessResultMapper results = mock(ProcessResultMapper.class);
    ProcessResultMemberMapper members = mock(ProcessResultMemberMapper.class);
    GeneratedAssetPublicationPort publication = mock(GeneratedAssetPublicationPort.class);
    ProcessTask task = new ProcessTask();
    task.setId(5L);
    task.setPackageId(7L);
    ProcessResult result = result();
    ProcessResultMember member = new ProcessResultMember();
    member.setImageId("11");
    when(results.findPublicationBacklog(5L)).thenReturn(List.of(result));
    when(members.findByResultId(6L)).thenReturn(List.of(member));
    when(publication.publish(any()))
        .thenReturn(new PublishedGeneratedAsset(91L, "package:7/image:91"));
    LockGuard guard = () -> true;
    ExecutionRun run = new ExecutionRun("5", 4L, guard);
    PublicationCoordinator coordinator =
        new PublicationCoordinator(
            results, members, publication, Clock.fixed(NOW, ZoneOffset.UTC));
    return new Fixture(results, publication, task, result, run, coordinator);
  }

  private static ProcessResult result() {
    ProcessResult result = new ProcessResult();
    result.setId(6L);
    result.setTaskId(5L);
    result.setUnitKey("BRANCH|7|11|front");
    result.setRunEpoch(3L);
    result.setStatus(ResultStatus.SUCCESS);
    result.setPublicationStatus(PublicationStatus.PENDING);
    result.setCandidateBucket("TMP");
    result.setOutputMinioKey("results/5/units/u/epochs/3/output.png");
    result.setCandidateContentType("image/png");
    result.setCandidateExtension("png");
    result.setProducerKind("DETERMINISTIC");
    result.setProducerTool("dag-pipeline");
    result.setProducerNodeId("front");
    result.setBytesOut(8L);
    return result;
  }

  private record Fixture(
      ProcessResultMapper results,
      GeneratedAssetPublicationPort publication,
      ProcessTask task,
      ProcessResult result,
      ExecutionRun run,
      PublicationCoordinator coordinator) { }
}
