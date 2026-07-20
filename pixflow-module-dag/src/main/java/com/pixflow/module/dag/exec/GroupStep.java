package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.infra.image.op.ComposeSpec;

public record GroupStep(String nodeId, PixelTool tool, ComposeSpec typedSpec,
                        int expectedCount) implements ExecutionStep {
}
