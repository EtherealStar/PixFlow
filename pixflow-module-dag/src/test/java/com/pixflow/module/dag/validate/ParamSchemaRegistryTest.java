package com.pixflow.module.dag.validate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.dag.ir.PixelTool;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ParamSchemaRegistry 加载与校验测试:覆盖每工具 schema 加载成功 + 关键字段越界。
 */
class ParamSchemaRegistryTest {

    private ParamSchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ParamSchemaRegistry();
    }

    @Test
    void allTools_haveSchemas() {
        assertThat(registry.registeredTools())
            .containsExactlyInAnyOrder(PixelTool.values());
    }

    @Test
    void allVersions_areExposed() {
        assertThat(registry.allVersions())
            .containsKeys("remove_bg", "set_background", "resize", "compress",
                "watermark", "convert_format", "compose_group", "generate_copy");
    }

    @Test
    void resize_acceptsWidthOnly() throws Exception {
        var node = new ObjectMapper().readTree("{\"width\":800}");
        List<String> errors = registry.validate(PixelTool.RESIZE, node);
        assertThat(errors).isEmpty();
    }

    @Test
    void resize_rejectsEmptyParams() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        List<String> errors = registry.validate(PixelTool.RESIZE, node);
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("DAG_INVALID_PARAMS");
    }

    @Test
    void resize_rejectsModeOutOfEnum() throws Exception {
        var node = new ObjectMapper().readTree("{\"width\":100,\"mode\":\"FOO\"}");
        List<String> errors = registry.validate(PixelTool.RESIZE, node);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void compress_acceptsQualityInRange() throws Exception {
        var node = new ObjectMapper().readTree("{\"quality\":85}");
        List<String> errors = registry.validate(PixelTool.COMPRESS, node);
        assertThat(errors).isEmpty();
    }

    @Test
    void compress_rejectsQualityOutOfRange() throws Exception {
        var node = new ObjectMapper().readTree("{\"quality\":200}");
        List<String> errors = registry.validate(PixelTool.COMPRESS, node);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void composeGroup_requiresLayout() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        List<String> errors = registry.validate(PixelTool.COMPOSE_GROUP, node);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void composeGroup_acceptsLayout() throws Exception {
        var node = new ObjectMapper().readTree("{\"layout\":\"HORIZONTAL\"}");
        List<String> errors = registry.validate(PixelTool.COMPOSE_GROUP, node);
        assertThat(errors).isEmpty();
    }

    @Test
    void watermark_requiresWatermarkImage() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        List<String> errors = registry.validate(PixelTool.WATERMARK, node);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void convertFormat_requiresTargetFormat() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        List<String> errors = registry.validate(PixelTool.CONVERT_FORMAT, node);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void setBackground_requiresColorOrBackgroundImage() throws Exception {
        var empty = new ObjectMapper().readTree("{}");
        List<String> emptyErrors = registry.validate(PixelTool.SET_BACKGROUND, empty);
        assertThat(emptyErrors).isNotEmpty();

        var colorOnly = new ObjectMapper().readTree("{\"color\":\"#FFFFFF\"}");
        List<String> colorErrors = registry.validate(PixelTool.SET_BACKGROUND, colorOnly);
        assertThat(colorErrors).isEmpty();
    }
}