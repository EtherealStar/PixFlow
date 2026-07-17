package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CopyContextTest {

    @Test
    void constructor_preservesNullableComponents() {
        CopyContext context = new CopyContext(null, null, null, null);

        assertThat(context.skuId()).isNull();
        assertThat(context.productName()).isNull();
        assertThat(context.keywords()).isEmpty();
        assertThat(context.description()).isNull();
    }

    @Test
    void constructor_preservesValuesAndCopiesKeywords() {
        List<String> keywords = new java.util.ArrayList<>(List.of("简约"));

        CopyContext context = new CopyContext("sku-1", "白色T恤", keywords, "舒适");
        keywords.add("百搭");

        assertThat(context.skuId()).isEqualTo("sku-1");
        assertThat(context.productName()).isEqualTo("白色T恤");
        assertThat(context.keywords()).containsExactly("简约");
        assertThat(context.description()).isEqualTo("舒适");
    }
}
