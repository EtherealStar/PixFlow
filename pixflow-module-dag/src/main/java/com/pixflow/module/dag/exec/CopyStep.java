package com.pixflow.module.dag.exec;
import com.pixflow.module.dag.ir.PixelTool;
public record CopyStep(String nodeId, PixelTool tool, CopyBindingSpec typedSpec)
        implements ExecutionStep {}
