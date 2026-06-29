package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.SetBackgroundSpec;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SpecMapper 类型化映射测试:覆盖各工具的参数 → spec 转换。
 */
class SpecMapperTest {

    private final SpecMapper mapper = new SpecMapper();

    @Test
    void resize_withWidthAndHeight() {
        DagNode node = new DagNode("n1", PixelTool.RESIZE,
            Map.of("width", 800, "height", 600));
        ResizeSpec spec = (ResizeSpec) mapper.toSpec(node);
        assertThat(spec.width()).isEqualTo(800);
        assertThat(spec.height()).isEqualTo(600);
        assertThat(spec.mode()).isEqualTo(ResizeSpec.Mode.FIT);
    }

    @Test
    void resize_withMode() {
        DagNode node = new DagNode("n1", PixelTool.RESIZE,
            Map.of("width", 800, "mode", "FILL"));
        ResizeSpec spec = (ResizeSpec) mapper.toSpec(node);
        assertThat(spec.mode()).isEqualTo(ResizeSpec.Mode.FILL);
    }

    @Test
    void compress_quality() {
        DagNode node = new DagNode("n1", PixelTool.COMPRESS,
            Map.of("quality", 85));
        CompressSpec spec = (CompressSpec) mapper.toSpec(node);
        assertThat(spec.quality()).isEqualTo(85);
        assertThat(spec.targetBytes()).isNull();
    }

    @Test
    void compress_targetBytes() {
        DagNode node = new DagNode("n1", PixelTool.COMPRESS,
            Map.of("targetBytes", 100000));
        CompressSpec spec = (CompressSpec) mapper.toSpec(node);
        assertThat(spec.targetBytes()).isEqualTo(100000L);
    }

    @Test
    void setBackground_withColor() {
        DagNode node = new DagNode("n1", PixelTool.SET_BACKGROUND,
            Map.of("color", "#FFFFFF"));
        SetBackgroundSpec spec = (SetBackgroundSpec) mapper.toSpec(node);
        assertThat(spec.color()).isNotNull();
    }

    @Test
    void composeGroup_layout() {
        DagNode node = new DagNode("c", PixelTool.COMPOSE_GROUP,
            Map.of("layout", "HORIZONTAL"));
        ComposeSpec spec = (ComposeSpec) mapper.toSpec(node);
        assertThat(spec.layout()).isEqualTo(ComposeSpec.Layout.HORIZONTAL);
    }

    @Test
    void convertFormat_defaultJpeg() {
        DagNode node = new DagNode("n1", PixelTool.CONVERT_FORMAT,
            Map.of("targetFormat", "WEBP"));
        ConvertFormatSpec spec = (ConvertFormatSpec) mapper.toSpec(node);
        assertThat(spec.targetFormat().name()).isEqualTo("WEBP");
    }

    @Test
    void watermark_requiresImage() {
        DagNode node = new DagNode("n1", PixelTool.WATERMARK, Map.of());
        // watermark spec 需要真实 RasterImage 注入;纯参数层只校验 watermarkImage 必填
        assertThatThrownBy(() -> mapper.toSpec(node))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("watermarkImage");
    }

    @Test
    void encodeSpec_usesConvertFormatNode() {
        DagNode convert = new DagNode("c", PixelTool.CONVERT_FORMAT,
            Map.of("targetFormat", "PNG", "quality", 90));
        var spec = mapper.toEncodeSpec(convert,
            new ExecutableBranch.EncodeTarget("JPEG", 80));
        assertThat(spec.targetFormat().name()).isEqualTo("PNG");
        assertThat(spec.quality()).isEqualTo(90);
    }

    @Test
    void encodeSpec_fallsBackToBranchTarget() {
        var spec = mapper.toEncodeSpec(null,
            new ExecutableBranch.EncodeTarget("WEBP", 75));
        assertThat(spec.targetFormat().name()).isEqualTo("WEBP");
        assertThat(spec.quality()).isEqualTo(75);
    }
}