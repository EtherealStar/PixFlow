package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.PixelTool;

public sealed interface ExecutionStep permits ExternalStep, LocalImageStep, GroupStep, CopyStep {
    String nodeId();

    PixelTool tool();
}
