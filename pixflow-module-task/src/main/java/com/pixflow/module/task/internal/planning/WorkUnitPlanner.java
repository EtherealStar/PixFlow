package com.pixflow.module.task.internal.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.dag.DagFacade;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import com.pixflow.harness.state.model.UnitKind;
import java.util.List;

public final class WorkUnitPlanner {
    private final ObjectMapper objectMapper;
    private final DagJsonReader reader;
    private final DagFacade dagFacade;
    private final TaskAssetReader assets;

    public WorkUnitPlanner(ObjectMapper objectMapper, DagJsonReader reader,
                           DagFacade dagFacade, TaskAssetReader assets) {
        this.objectMapper = objectMapper;
        this.reader = reader;
        this.dagFacade = dagFacade;
        this.assets = assets;
    }

    public WorkUnitSelection plan(long packageId, String canonicalJson) {
        try {
            var root = objectMapper.readTree(canonicalJson);
            var version = root.path("schemaVersion").asText(null);
            if (version == null) throw new IllegalArgumentException("canonical DAG 缺 schemaVersion");
            var typed = dagFacade.compile(dagFacade.validateToCanonical(
                    reader.readTree(root), new DagSchemaVersion(version)));
            List<ImageDescriptor> images = assets.listImages(packageId);
            return new WorkUnitSelection(dagFacade.expand(typed, images).stream()
                    .map(branch -> new WorkUnitSelection.Item(branch.kind(), branch.memberId(),
                            branch.branchId(), inputImages(branch, images)))
                    .toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("无法从 canonical DAG 生成冻结执行选择", e);
        }
    }

    public WorkUnitSelection planGenerative(String imagegenPlanJson) {
        try {
            ImagegenPlan plan = objectMapper.readValue(imagegenPlanJson, ImagegenPlan.class);
            return new WorkUnitSelection(plan.sourceImageIds().stream()
                    .map(imageId -> new WorkUnitSelection.Item(
                            UnitKind.GENERATIVE, imageId, "GENERATIVE", List.of()))
                    .toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("无法从 Imagegen Plan 生成冻结执行选择", e);
        }
    }

    private static List<ImageDescriptor> inputImages(ExecutableBranch branch, List<ImageDescriptor> all) {
        return branch.kind() == com.pixflow.harness.state.model.UnitKind.GROUP
                ? all.stream().filter(image -> branch.memberId().equals(image.groupKey())).toList()
                : all.stream().filter(image -> branch.memberId().equals(image.imageId())).toList();
    }
}
