package com.pixflow.harness.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {

    @Test
    void rejectsInputSchemaThatAllowsUnknownProperties() {
        ToolDescriptor descriptor = descriptor(Map.of(
                "type", "object",
                "additionalProperties", true));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultToolRegistry(List.of(descriptor), null));
    }

    @Test
    void rejectsDynamicToolThatAllowsUnknownProperties() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(), null);
        ToolDescriptor descriptor = descriptor(Map.of(
                "type", "object",
                "additionalProperties", true));

        assertThrows(IllegalArgumentException.class, () -> registry.registerDynamic(descriptor));
    }

    @Test
    void rejectsEmptyInputSchema() {
        ToolDescriptor descriptor = descriptor(Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultToolRegistry(List.of(descriptor), null));
    }

    @Test
    void rejectsDynamicToolWithEmptyInputSchema() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(), null);

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerDynamic(descriptor(Map.of())));
    }

    private static ToolDescriptor descriptor(Map<String, Object> inputSchema) {
        return new ToolDescriptor(
                "get_product_visual_facts",
                "读取当前商品视觉事实",
                inputSchema,
                Map.of("type", "object"),
                "Read product visual facts before making appearance claims.",
                true,
                invocation -> ToolHandlerOutput.of("{}"),
                null,
                null,
                null);
    }
}
