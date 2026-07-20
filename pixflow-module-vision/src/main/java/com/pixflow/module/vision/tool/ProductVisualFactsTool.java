package com.pixflow.module.vision.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInputValidator;
import com.pixflow.harness.tools.ToolResultPolicy;
import com.pixflow.module.vision.api.ProductVisualFactsLookup;
import java.util.List;
import java.util.Map;

public final class ProductVisualFactsTool {
    private ProductVisualFactsTool() {
    }

    public static ToolDescriptor descriptor(ProductVisualFactsLookup lookup, ObjectMapper objectMapper) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("referenceKey"),
                "properties", Map.of("referenceKey", Map.of("type", "string", "minLength", 1, "maxLength", 512)));
        return new ToolDescriptor(
                "get_product_visual_facts",
                "Read current observation-only product visual facts for a canonical asset reference",
                schema,
                Map.of("type", "object"),
                "Use before making appearance claims, comparing visible product details, or drafting redraw YAML.",
                true,
                invocation -> {
                    Object value = invocation.arguments().get("referenceKey");
                    if (!(value instanceof String referenceKey) || referenceKey.isBlank()) {
                        throw new IllegalArgumentException("referenceKey is required");
                    }
                    try {
                        return ToolHandlerOutput.of(objectMapper.writeValueAsString(lookup.lookup(referenceKey)));
                    } catch (JsonProcessingException failure) {
                        throw new IllegalStateException("unable to encode visual facts lookup result", failure);
                    }
                },
                null,
                ToolInputValidator.noop(),
                new ToolResultPolicy(50_000, true, 4_000));
    }
}
