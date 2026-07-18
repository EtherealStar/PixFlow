package com.pixflow.module.task.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.publication.CandidateKind;
import com.pixflow.module.task.api.publication.ProducerIdentity;
import com.pixflow.module.task.api.publication.SourceImageIdentity;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkUnitResultRepositoryTest {
  @Test
  void staleEpochCannotCommitCompletion() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    ProcessResultMemberMapper members = mock(ProcessResultMemberMapper.class);
    WorkUnit unit = unit();
    ProcessResult pending = new ProcessResult();
    pending.setId(7L);
    pending.setStatus(ResultStatus.PENDING);
    when(mapper.selectByUnit(1L, UnitKeyCodec.encode(unit.unitKey()))).thenReturn(pending);
    when(mapper.commitForEpoch(anyLong(), any(String.class), anyLong(), any())).thenReturn(0);
    WorkUnitResultRepository repository = repository(mapper, members);
    ExecutionRun run = new ExecutionRun("1", 2, () -> true);

    var result = repository.commit(run, success(unit, 2));

    assertThat(result).isEqualTo(WorkUnitResultRepository.CommitResult.FENCED);
    verify(members, never()).insert(any(ProcessResultMember.class));
  }

  @Test
  void existingSuccessIsImmutableAcrossHigherEpoch() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    WorkUnit unit = unit();
    ProcessResult success = new ProcessResult();
    success.setStatus(ResultStatus.SUCCESS);
    when(mapper.selectByUnit(1L, UnitKeyCodec.encode(unit.unitKey()))).thenReturn(success);
    WorkUnitResultRepository repository = repository(mapper, mock(ProcessResultMemberMapper.class));

    var result = repository.commit(new ExecutionRun("1", 3, () -> true), success(unit, 3));

    assertThat(result).isEqualTo(WorkUnitResultRepository.CommitResult.ALREADY_SUCCEEDED);
    verify(mapper, never()).commitForEpoch(anyLong(), any(String.class), anyLong(), any());
  }

  @Test
  void sameEpochCannotRewriteAnExistingAttempt() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    WorkUnit unit = unit();
    ProcessResult failed = new ProcessResult();
    failed.setStatus(ResultStatus.FAILED);
    failed.setRunEpoch(2L);
    when(mapper.selectByUnit(1L, UnitKeyCodec.encode(unit.unitKey()))).thenReturn(failed);
    when(mapper.commitForEpoch(anyLong(), any(String.class), anyLong(), any())).thenReturn(0);
    WorkUnitResultRepository repository = repository(mapper, mock(ProcessResultMemberMapper.class));

    assertThat(repository.commit(new ExecutionRun("1", 2, () -> true), success(unit, 2)))
        .isEqualTo(WorkUnitResultRepository.CommitResult.FENCED);
  }

  @Test
  void successIsRejectedWhenEpochObjectDoesNotExist() {
    ProcessResultMapper mapper = mock(ProcessResultMapper.class);
    WorkUnit unit = unit();
    ProcessResult pending = new ProcessResult();
    pending.setStatus(ResultStatus.PENDING);
    when(mapper.selectByUnit(1L, UnitKeyCodec.encode(unit.unitKey()))).thenReturn(pending);
    ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
    ObjectStorage storage = mock(ObjectStorage.class);
    WorkUnitResultRepository repository =
        new WorkUnitResultRepository(
            mapper, mock(ProcessResultMemberMapper.class), tasks, storage, new ObjectMapper());

    assertThatThrownBy(
            () -> repository.commit(new ExecutionRun("1", 2, () -> true), success(unit, 2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("尚不存在");
    verify(tasks, never()).lockRunningEpoch(anyLong(), anyLong());
  }

  private static WorkUnit unit() {
    WorkUnit unit = mock(WorkUnit.class);
    when(unit.taskId()).thenReturn("1");
    when(unit.unitKey()).thenReturn(UnitKey.branch("1", "image-1", "branch-1"));
    when(unit.taskType()).thenReturn(TaskType.IMAGE_PROCESS);
    return unit;
  }

  private static WorkUnitCompletion.Succeeded success(WorkUnit unit, long epoch) {
    Instant now = Instant.parse("2026-07-13T00:00:00Z");
    String key =
        "results/1/units/"
            + UnitKeyCodec.sha256(unit.unitKey())
            + "/epochs/"
            + epoch
            + "/output.png";
    CandidateArtifact candidate =
        new CandidateArtifact(
            ObjectLocation.of(BucketType.TMP, key),
            12L,
            "image/png",
            "png",
            CandidateKind.DETERMINISTIC,
            java.util.List.of(new SourceImageIdentity("image-1")),
            ProducerIdentity.deterministic("dag-pipeline", "branch-1"));
    return new WorkUnitCompletion.Succeeded(
        unit, epoch, now, now.plusSeconds(1), candidate, null, java.util.List.of());
  }

  private static WorkUnitResultRepository repository(
      ProcessResultMapper results, ProcessResultMemberMapper members) {
    ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
    when(tasks.lockRunningEpoch(anyLong(), anyLong())).thenReturn(new ProcessTask());
    ObjectStorage storage = mock(ObjectStorage.class);
    when(storage.exists(any())).thenReturn(true);
    return new WorkUnitResultRepository(results, members, tasks, storage, new ObjectMapper());
  }
}
