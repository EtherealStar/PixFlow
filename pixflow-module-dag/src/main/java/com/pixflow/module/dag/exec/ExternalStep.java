package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.PixelTool;

public record ExternalStep(String nodeId, PixelTool tool, BackgroundRemovalBindingSpec typedSpec)
        implements ExecutionStep {
}
