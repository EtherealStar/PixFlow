package com.pixflow.module.task.internal.stateadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckpointReadPortImplTest {

  private final ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
  private final ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
  private final CheckpointReadPortImpl port = new CheckpointReadPortImpl(taskMapper, resultMapper);

  @Test
  void returnsAllSuccessfulWorkUnitKindsFromExplicitUnitKeys() {
    ProcessTask task = new ProcessTask();
    task.setId(42L);
    when(taskMapper.selectById(42L)).thenReturn(task);
    UnitKey branch = UnitKey.branch("42", "image-1", "branch-a");
    UnitKey group = UnitKey.group("42", "group-1", "branch-b");
    UnitKey generative = UnitKey.generative("42", "image-2");
    when(resultMapper.findByTaskIdAndStatus(42L, ResultStatus.SUCCESS))
        .thenReturn(List.of(result(branch), result(group), result(generative)));

    var skippable = port.loadSkippableWorkUnits("42").orElseThrow();

    assertThat(skippable.succeeded()).containsExactlyInAnyOrder(branch, group, generative);
    verify(resultMapper).findByTaskIdAndStatus(42L, ResultStatus.SUCCESS);
  }

  @Test
  void missingTaskDoesNotMasqueradeAsEmptyCheckpointSet() {
    when(taskMapper.selectById(404L)).thenReturn(null);

    assertThat(port.loadSkippableWorkUnits("404")).isEmpty();
  }

  private static ProcessResult result(UnitKey key) {
    ProcessResult result = new ProcessResult();
    result.setUnitKey(UnitKeyCodec.encode(key));
    return result;
  }
}
