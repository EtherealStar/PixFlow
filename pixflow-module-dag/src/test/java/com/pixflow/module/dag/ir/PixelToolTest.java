package com.pixflow.module.dag.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PixelTool 白名单枚举与 fromWireName 反查测试。
 */
class PixelToolTest {

    @Test
    void allWireNames_arePresent() {
        assertThat(PixelTool.values()).hasSize(8);
        assertThat(PixelTool.REMOVE_BG.wireName()).isEqualTo("remove_bg");
        assertThat(PixelTool.SET_BACKGROUND.wireName()).isEqualTo("set_background");
        assertThat(PixelTool.RESIZE.wireName()).isEqualTo("resize");
        assertThat(PixelTool.COMPRESS.wireName()).isEqualTo("compress");
        assertThat(PixelTool.WATERMARK.wireName()).isEqualTo("watermark");
        assertThat(PixelTool.CONVERT_FORMAT.wireName()).isEqualTo("convert_format");
        assertThat(PixelTool.COMPOSE_GROUP.wireName()).isEqualTo("compose_group");
        assertThat(PixelTool.GENERATE_COPY.wireName()).isEqualTo("generate_copy");
    }

    @Test
    void fromWireName_resolvesAll() {
        for (PixelTool tool : PixelTool.values()) {
            assertThat(PixelTool.fromWireName(tool.wireName())).isEqualTo(tool);
        }
    }

    @Test
    void fromWireName_returnsNullForUnknown() {
        assertThat(PixelTool.fromWireName("unknown_tool")).isNull();
        assertThat(PixelTool.fromWireName(null)).isNull();
    }

    @Test
    void composeGroup_isOnlyNToOne() {
        for (PixelTool tool : PixelTool.values()) {
            if (tool.arity() == PixelTool.Arity.N_TO_ONE) {
                assertThat(tool).isEqualTo(PixelTool.COMPOSE_GROUP);
            }
        }
    }

    @Test
    void generateCopy_isTextArity() {
        assertThat(PixelTool.GENERATE_COPY.arity()).isEqualTo(PixelTool.Arity.TEXT);
        assertThat(PixelTool.GENERATE_COPY.target()).isEqualTo(PixelTool.Target.AI);
    }
}