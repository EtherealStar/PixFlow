package com.pixflow.harness.tools.result;

public class NoopToolTraceSink implements ToolTraceSink {
    @Override
    public void record(ToolTraceEvent event) {
        // 默认实现不做任何事，便于工具模块在没有 eval 时独立启动。
    }
}
