package com.pixflow.harness.tools;

import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.tools.schema.ToolSchemaExporter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public class DefaultToolRegistry implements ToolRegistry {
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final Map<String, ToolDescriptor> descriptors;

    private final PermissionPolicy permissionPolicy;

    public DefaultToolRegistry(List<ToolDescriptor> descriptors, PermissionPolicy permissionPolicy) {
        this.permissionPolicy = permissionPolicy;
        this.descriptors = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            validateDescriptor(descriptor);
            if (this.descriptors.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalArgumentException("重复的工具名: " + descriptor.name());
            }
        }
    }

    @Override
    public Optional<ToolDescriptor> get(String name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    @Override
    public List<ToolDescriptor> visibleDescriptors(ToolVisibilityContext context) {
        List<ToolDescriptor> visible = new ArrayList<>();
        for (ToolDescriptor descriptor : descriptors.values()) {
            if (context.hiddenTools().contains(descriptor.name())) {
                continue;
            }
            if (context.permissionContext() != null
                    && permissionPolicy != null
                    && !permissionPolicy.isToolVisible(
                            descriptor.name(), descriptor.readOnlyHint(), context.permissionContext())) {
                continue;
            }
            if (context.planMode() && !descriptor.readOnlyHint() && !"plan_exit".equals(descriptor.name())) {
                continue;
            }
            if (context.planMode() && "plan".equals(descriptor.name())) {
                continue;
            }
            visible.add(descriptor);
        }
        return List.copyOf(visible);
    }

    @Override
    public List<Map<String, Object>> toolSchemas(ToolVisibilityContext context) {
        return visibleDescriptors(context).stream().map(ToolSchemaExporter::export).toList();
    }

    @Override
    public List<String> toolPromptSections(ToolVisibilityContext context) {
        return visibleDescriptors(context).stream()
                .map(descriptor -> descriptor.name() + ": " + descriptor.prompt())
                .toList();
    }

    @Override
    public synchronized void registerDynamic(ToolDescriptor descriptor) {
        validateDescriptor(descriptor);
        if (descriptors.putIfAbsent(descriptor.name(), descriptor) != null) {
            throw new IllegalStateException("重复的动态工具名: " + descriptor.name());
        }
    }

    @Override
    public synchronized void unregisterDynamic(String name) {
        descriptors.remove(name);
    }

    private static void validateDescriptor(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!TOOL_NAME.matcher(descriptor.name()).matches()) {
            throw new IllegalArgumentException("工具名必须是 snake_case: " + descriptor.name());
        }
        if (!"object".equals(descriptor.inputSchema().get("type"))) {
            throw new IllegalArgumentException("工具输入 schema 必须是 object: " + descriptor.name());
        }
        // 工具入参是模型到业务模块的安全边界，必须拒绝 schema 未声明的字段。
        if (!Boolean.FALSE.equals(descriptor.inputSchema().get("additionalProperties"))) {
            throw new IllegalArgumentException(
                    "工具输入 schema 必须设置 additionalProperties=false: " + descriptor.name());
        }
    }
}
