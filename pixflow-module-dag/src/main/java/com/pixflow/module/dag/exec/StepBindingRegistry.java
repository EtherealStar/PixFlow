package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.PixelTool;
import java.util.EnumMap;
import java.util.Map;

public final class StepBindingRegistry {
    public enum Binding { LOCAL_IMAGE, GROUP, EXTERNAL, COPY }
    public enum ExecutorBinding { PIPELINE, GROUP, COPY }

    private final Map<PixelTool, Binding> bindings = new EnumMap<>(PixelTool.class);
    private final Map<PixelTool, ExecutorBinding> executors = new EnumMap<>(PixelTool.class);

    public StepBindingRegistry() {
        bind(PixelTool.RESIZE, Binding.LOCAL_IMAGE, ExecutorBinding.PIPELINE);
        bind(PixelTool.COMPRESS, Binding.LOCAL_IMAGE, ExecutorBinding.PIPELINE);
        bind(PixelTool.SET_BACKGROUND, Binding.LOCAL_IMAGE, ExecutorBinding.PIPELINE);
        bind(PixelTool.WATERMARK, Binding.LOCAL_IMAGE, ExecutorBinding.PIPELINE);
        bind(PixelTool.CONVERT_FORMAT, Binding.LOCAL_IMAGE, ExecutorBinding.PIPELINE);
        bind(PixelTool.COMPOSE_GROUP, Binding.GROUP, ExecutorBinding.GROUP);
        bind(PixelTool.REMOVE_BG, Binding.EXTERNAL, ExecutorBinding.PIPELINE);
        bind(PixelTool.GENERATE_COPY, Binding.COPY, ExecutorBinding.COPY);
        if (bindings.size() != PixelTool.values().length || executors.size() != PixelTool.values().length) {
            throw new IllegalStateException("PixelTool binding 不完整");
        }
    }

    public Binding require(PixelTool tool) {
        Binding binding = bindings.get(tool);
        if (binding == null) throw new IllegalArgumentException("未绑定工具: " + tool);
        return binding;
    }

    public ExecutorBinding requireExecutor(PixelTool tool) {
        ExecutorBinding binding = executors.get(tool);
        if (binding == null) throw new IllegalArgumentException("未绑定执行器: " + tool);
        return binding;
    }

    private void bind(PixelTool tool, Binding binding, ExecutorBinding executor) {
        if (bindings.put(tool, binding) != null) throw new IllegalStateException("重复工具 binding: " + tool);
        if (executors.put(tool, executor) != null) throw new IllegalStateException("重复执行器 binding: " + tool);
    }
}
