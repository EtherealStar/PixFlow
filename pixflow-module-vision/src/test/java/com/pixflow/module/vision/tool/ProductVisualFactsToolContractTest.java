package com.pixflow.module.vision.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.vision.api.ProductVisualFactsLookup;
import com.pixflow.module.vision.api.VisualFactsLookupResult;
import com.pixflow.module.vision.api.VisualFactsLookupStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductVisualFactsToolContractTest {

    @Test
    void descriptorIsTheOnlyExplicitVisualFactsReadSurface() {
        ProductVisualFactsLookup lookup = key -> new VisualFactsLookupResult(
                VisualFactsLookupStatus.UNAVAILABLE, key, java.util.List.of(), false, "pending");
        var descriptor = ProductVisualFactsTool.descriptor(lookup, new ObjectMapper());

        assertThat(descriptor.name()).isEqualTo("get_product_visual_facts");
        assertThat(descriptor.readOnlyHint()).isTrue();
        assertThat(descriptor.inputSchema()).containsEntry("required", java.util.List.of("referenceKey"));
        assertThat(descriptor.inputSchema().get("properties").toString()).contains("referenceKey");
        assertThat(descriptor.inputSchema().get("properties").toString())
                .doesNotContain("packageId", "skuId", "imageId", "provider", "prompt");
        assertThat(descriptor.handler()).isNotNull();
    }
}
