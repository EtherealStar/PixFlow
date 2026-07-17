package com.pixflow.module.task.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import com.pixflow.harness.state.model.UnitKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageGenWorkerPlanTest {
    @Test
    void rebuildsDerivedRetryFromFrozenSelectionWithoutRereadingAssets() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = mapper.writeValueAsString(
                new ImagegenPlan(
                        "package:7/image:12", "redraw", Map.of(), null, "c1", 7L));
        WorkUnitSelection retrySelection = new WorkUnitSelection(List.of(
                new WorkUnitSelection.Item(UnitKind.GENERATIVE, "12", "GENERATIVE",
                        List.of(new ImageDescriptor("12", "SKU-12", null, null,
                                "7/frozen-12.png", "image/png")))));
        ImageGenWorker worker = new ImageGenWorker(mapper, null, null, null, null, null);

        var units = worker.plan("99", 4L, payload, mapper.writeValueAsString(retrySelection));

        assertThat(units).hasSize(1);
        var spec = units.getFirst().generativeSpec();
        assertThat(spec.sourceImageId()).isEqualTo("12");
        assertThat(spec.sourceLocation().bucket()).isEqualTo(BucketType.PACKAGES);
        assertThat(spec.sourceLocation().key()).isEqualTo("7/frozen-12.png");
        assertThat(spec.runEpoch()).isEqualTo(4L);
    }
}
