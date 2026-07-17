package com.pixflow.module.task.internal.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.module.task.api.port.TaskAssetReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkUnitPlannerGenerativeTest {
    @Test
    void freezesGenerativeSourceMetadataDuringTaskCreation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskAssetReader assets = new TaskAssetReader() {
            @Override public List<com.pixflow.module.dag.expand.ImageDescriptor> listImages(long packageId) {
                return List.of();
            }

            @Override public GenerativeSource sourceImage(long packageId, String sourceImageId) {
                return new GenerativeSource(sourceImageId, "SKU-" + sourceImageId,
                        ObjectLocation.of(BucketType.PACKAGES, packageId + "/" + sourceImageId + ".png"));
            }
        };
        WorkUnitPlanner planner = new WorkUnitPlanner(objectMapper, null, null, assets);
        String payload = objectMapper.writeValueAsString(
                new ImagegenPlan(
                        "package:7/image:11", "redraw", Map.of(), null, "c1", 7L));

        WorkUnitSelection selection = planner.planGenerative(7L, payload);

        assertThat(selection.items()).hasSize(1);
        var item = selection.items().getFirst();
        assertThat(item.memberId()).isEqualTo("11");
        assertThat(item.images()).singleElement().satisfies(image -> {
            assertThat(image.skuId()).isEqualTo("SKU-11");
            assertThat(image.objectKey()).isEqualTo("7/11.png");
        });
    }
}
