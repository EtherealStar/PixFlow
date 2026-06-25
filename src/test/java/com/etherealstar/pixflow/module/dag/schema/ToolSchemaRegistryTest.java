package com.etherealstar.pixflow.module.dag.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工具参数 Schema 定义单元测试（任务 10.1，需求 7.5）。
 *
 * <p>覆盖 7 个工具的必填/可选参数约束的代表性示例与边界，包括 set_background 颜色默认、
 * resize 正整数、watermark 二选一、convert_format 枚举等。
 */
class ToolSchemaRegistryTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static ToolParamSchema schema(ToolType type) {
        return ToolSchemaRegistry.get(type);
    }

    @Test
    void whitelistContainsExactlySevenTools() {
        assertThat(ToolType.whitelist()).containsExactlyInAnyOrder(
                "remove_bg", "set_background", "resize", "compress",
                "watermark", "convert_format", "generate_copy");
        assertThat(ToolSchemaRegistry.all()).hasSize(7);
    }

    @Test
    void findByToolNameRespectsWhitelist() {
        assertThat(ToolSchemaRegistry.findByToolName("resize")).isPresent();
        assertThat(ToolSchemaRegistry.findByToolName("delete_disk")).isEmpty();
        assertThat(ToolSchemaRegistry.isWhitelisted("convert_format")).isTrue();
        assertThat(ToolSchemaRegistry.isWhitelisted("RESIZE")).isFalse();
    }

    @Test
    void removeBgAcceptsEmptyParamsAndRejectsUnknown() {
        assertThat(schema(ToolType.REMOVE_BG).validate(Map.of()).valid()).isTrue();
        assertThat(schema(ToolType.REMOVE_BG).validate(null).valid()).isTrue();
        assertThat(schema(ToolType.REMOVE_BG).validate(params("color", "#FFF")).valid()).isFalse();
    }

    @Test
    void setBackgroundColorIsOptionalWithDefault() {
        ToolParamSchema s = schema(ToolType.SET_BACKGROUND);
        assertThat(s.validate(Map.of()).valid()).isTrue();
        assertThat(s.validate(params("color", "#1a2B3c")).valid()).isTrue();
        assertThat(s.validate(params("color", "white")).valid()).isTrue();
        assertThat(s.validate(params("color", "not-a-color")).valid()).isFalse();
        assertThat(s.withDefaults(Map.of())).containsEntry("color", "#FFFFFF");
        // 已提供的值不应被默认值覆盖
        assertThat(s.withDefaults(params("color", "#000000"))).containsEntry("color", "#000000");
    }

    @Test
    void resizeRequiresPositiveIntegerWidthAndHeight() {
        ToolParamSchema s = schema(ToolType.RESIZE);
        assertThat(s.validate(params("width", 800, "height", 600)).valid()).isTrue();
        assertThat(s.validate(params("width", 800.0, "height", 600.0)).valid()).isTrue();
        assertThat(s.validate(params("width", "800", "height", "600")).valid()).isTrue();
        assertThat(s.validate(params("width", 800)).valid()).isFalse(); // 缺 height
        assertThat(s.validate(params("width", 0, "height", 600)).valid()).isFalse(); // 非正
        assertThat(s.validate(params("width", -5, "height", 600)).valid()).isFalse();
        assertThat(s.validate(params("width", 1.5, "height", 600)).valid()).isFalse(); // 非整数
        assertThat(s.requiredParamNames()).containsExactlyInAnyOrder("width", "height");
    }

    @Test
    void compressRequiresPositiveMaxKb() {
        ToolParamSchema s = schema(ToolType.COMPRESS);
        assertThat(s.validate(params("max_kb", 200)).valid()).isTrue();
        assertThat(s.validate(Map.of()).valid()).isFalse();
        assertThat(s.validate(params("max_kb", 0)).valid()).isFalse();
    }

    @Test
    void watermarkRequiresPositionAndOneOfTextOrImage() {
        ToolParamSchema s = schema(ToolType.WATERMARK);
        assertThat(s.validate(params("position", "bottom-right", "text", "SALE")).valid()).isTrue();
        assertThat(s.validate(params("position", "CENTER", "image", "logo.png")).valid()).isTrue();
        // 缺 position
        assertThat(s.validate(params("text", "SALE")).valid()).isFalse();
        // 既无 text 也无 image
        assertThat(s.validate(params("position", "center")).valid()).isFalse();
        // 非法 position 枚举
        assertThat(s.validate(params("position", "middle", "text", "SALE")).valid()).isFalse();
        assertThat(s.requiredParamNames()).containsExactly("position");
    }

    @Test
    void convertFormatRequiresAllowedFormat() {
        ToolParamSchema s = schema(ToolType.CONVERT_FORMAT);
        assertThat(s.validate(params("format", "PNG")).valid()).isTrue();
        assertThat(s.validate(params("format", "webp")).valid()).isTrue(); // 不区分大小写
        assertThat(s.validate(params("format", "GIF")).valid()).isFalse();
        assertThat(s.validate(Map.of()).valid()).isFalse();
    }

    @Test
    void generateCopyStyleIsOptional() {
        ToolParamSchema s = schema(ToolType.GENERATE_COPY);
        assertThat(s.validate(Map.of()).valid()).isTrue();
        assertThat(s.validate(params("style", "简约")).valid()).isTrue();
        assertThat(s.validate(params("style", 123)).valid()).isFalse(); // 非字符串
        assertThat(s.optionalParamNames()).containsExactly("style");
    }
}
