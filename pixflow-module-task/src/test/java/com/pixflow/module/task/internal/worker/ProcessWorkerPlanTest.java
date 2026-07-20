package com.pixflow.module.task.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.dag.DagFacade;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessWorkerPlanTest {
  @Test
  void rebuildsOnlyFrozenDerivedRetrySubsetFromFullCanonicalPlan() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    DagFacade dagFacade = mock(DagFacade.class);
    BranchExpander expander = mock(BranchExpander.class);
    CanonicalDag canonical = mock(CanonicalDag.class);
    TypedExecutionPlan typed = mock(TypedExecutionPlan.class);
    when(dagFacade.validateToCanonical(any(), any())).thenReturn(canonical);
    when(dagFacade.compile(canonical)).thenReturn(typed);

    ImageDescriptor image =
        ImageDescriptor.single(
            "11", "SKU-11", "asset:image:7:11");
    ExecutableBranch first = branch("branch-a", "11");
    ExecutableBranch failed = branch("branch-b", "11");
    when(expander.expand(typed, List.of(image))).thenReturn(List.of(first, failed));
    WorkUnitSelection retrySelection =
        new WorkUnitSelection(
            List.of(new WorkUnitSelection.Item(UnitKind.BRANCH, "11", "branch-b", List.of(image))));
    ProcessWorker worker =
        new ProcessWorker(
            objectMapper,
            new DagJsonReader(objectMapper),
            dagFacade,
            expander,
            null,
            null,
            null,
            null,
            null,
            null);

    var units =
        worker.plan(
            "99",
            7L,
            "{\"schemaVersion\":\"1.0\",\"nodes\":[],\"edges\":[]}",
            objectMapper.writeValueAsString(retrySelection));

    assertThat(units)
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.executableBranch().branchId()).isEqualTo("branch-b");
              assertThat(unit.imageDescriptors()).containsExactly(image);
            });
  }

  private static ExecutableBranch branch(String branchId, String memberId) {
    return new ExecutableBranch(
        UnitKind.BRANCH,
        branchId,
        memberId,
        List.of(),
        null,
        List.of(),
        ExecutableBranch.EncodeTarget.defaultJpeg());
  }
}
