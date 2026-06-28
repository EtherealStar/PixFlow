package com.pixflow.module.file.naming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNameParserTest {
    private final FileNameParser parser = new FileNameParser(new DefaultSkuExtractor());

    @Test
    void parsesThreeSegmentsAsGroupSkuView() {
        ParsedName parsed = parser.parse("folder/G1_SKU9_FRONT.png");

        assertThat(parsed.groupKey()).isEqualTo("G1");
        assertThat(parsed.skuId()).isEqualTo("SKU9");
        assertThat(parsed.viewId()).isEqualTo("FRONT");
    }

    @Test
    void parsesTwoSegmentsAsSkuView() {
        ParsedName parsed = parser.parse("SKU9_FRONT.jpg");

        assertThat(parsed.groupKey()).isNull();
        assertThat(parsed.skuId()).isEqualTo("SKU9");
        assertThat(parsed.viewId()).isEqualTo("FRONT");
    }

    @Test
    void fallsBackWhenSegmentsAreAmbiguous() {
        ParsedName parsed = parser.parse("中文商品__主图_额外.webp");

        assertThat(parsed.groupKey()).isNull();
        assertThat(parsed.skuId()).isEqualTo("中文商品");
        assertThat(parsed.viewId()).isNull();
    }
}
