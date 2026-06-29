package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.SetBackgroundSpec;
import com.pixflow.infra.image.op.WatermarkSpec;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SpecMapper:DAG 节点参数 → infra 类型化 spec 的映射器(对齐 dag.md §8.4)。
 *
 * <p>节点参数已过 ParamSchemaRegistry 校验;SpecMapper 只做结构转换 + 防御式兜底
 * (缺必填字段抛 {@link DagErrorCode#DAG_INVALID_PARAMS})。
 */
@Component
public class SpecMapper {

    /**
     * 把 DagNode 映射为统一的 ResizeSpec/SetBackgroundSpec/... 类型化 spec。
     * 缺必填字段抛 {@link IllegalArgumentException}(spec 层断言)+ DagErrorCode 包装。
     */
    public Object toSpec(DagNode node) {
        if (node == null || node.tool() == null) {
            throw specException(node, "节点为空或 tool 未知");
        }
        Map<String, Object> p = node.params();
        try {
            return switch (node.tool()) {
                case RESIZE -> mapResize(p);
                case COMPRESS -> mapCompress(p);
                case SET_BACKGROUND -> mapSetBackground(p);
                case WATERMARK -> mapWatermark(p, node);
                case CONVERT_FORMAT -> mapConvertFormat(p);
                case COMPOSE_GROUP -> mapComposeGroup(p);
                case REMOVE_BG, GENERATE_COPY -> null; // 不产 spec
            };
        } catch (IllegalArgumentException e) {
            throw specException(node, e.getMessage());
        }
    }

    /** 把 convert_format 节点 + 整链路 encode target 拼成最终 EncodeSpec。 */
    public EncodeSpec toEncodeSpec(DagNode convertNode,
                                   ExecutableBranch.EncodeTarget target) {
        if (convertNode != null) {
            ConvertFormatSpec spec = mapConvertFormat(convertNode.params());
            return spec.toEncodeSpec();
        }
        ImageFormat fmt = parseImageFormat(target == null ? null : target.format());
        Integer q = target == null ? null : target.quality();
        return new EncodeSpec(fmt, q, null, null);
    }

    private ResizeSpec mapResize(Map<String, Object> p) {
        Integer w = intOrNull(p.get("width"));
        Integer h = intOrNull(p.get("height"));
        ResizeSpec.Mode mode = ResizeSpec.Mode.valueOf(
            stringOr(p.get("mode"), "FIT"));
        boolean upscale = boolOr(p.get("upscale"), false);
        return new ResizeSpec(w, h, mode, upscale);
    }

    private CompressSpec mapCompress(Map<String, Object> p) {
        Integer q = intOrNull(p.get("quality"));
        Long bytes = longOrNull(p.get("targetBytes"));
        return new CompressSpec(q, bytes);
    }

    private SetBackgroundSpec mapSetBackground(Map<String, Object> p) {
        Color color = colorOrNull(p.get("color"));
        RasterImage background = null; // 背景图走 watermark 同款流程:由 dag 从 storage 取 + decode
        SetBackgroundSpec.Fit fit = p.get("fit") == null ? null
            : SetBackgroundSpec.Fit.valueOf(stringOr(p.get("fit"), "STRETCH"));
        return new SetBackgroundSpec(color, background, fit);
    }

    private WatermarkSpec mapWatermark(Map<String, Object> p, DagNode node) {
        // 水印图由 dag 解码为 RasterImage 注入 spec;此 mapper 只做参数校验
        Object wmRef = p.get("watermarkImage");
        if (wmRef == null || wmRef.toString().isBlank()) {
            throw new IllegalArgumentException("watermarkImage 必填");
        }
        // 此处返回占位 RasterImage=null 的非法 spec;真实执行时 dag 注入
        // 调用方需在 SpecMapper 之后再用 mapWatermarkFull 注入 RasterImage
        return null; // 由 NodeDispatcher 注入完整 spec
    }

    private ConvertFormatSpec mapConvertFormat(Map<String, Object> p) {
        String fmtStr = stringOr(p.get("targetFormat"), "JPEG");
        ImageFormat fmt = parseImageFormat(fmtStr);
        Integer q = intOrNull(p.get("quality"));
        Color bg = colorOrNull(p.get("flattenBackground"));
        return new ConvertFormatSpec(fmt, q, bg);
    }

    private ComposeSpec mapComposeGroup(Map<String, Object> p) {
        ComposeSpec.Layout layout = ComposeSpec.Layout.valueOf(
            stringOr(p.get("layout"), "GRID"));
        int gap = intOr(p.get("gap"), 0);
        Color bg = colorOrNull(p.get("background"));
        @SuppressWarnings("unchecked")
        List<Object> order = p.get("order") instanceof List<?> l
            ? (List<Object>) l : List.of();
        return new ComposeSpec(layout, List.of(), gap, bg);
    }

    public static ImageFormat parseImageFormat(String name) {
        if (name == null) {
            return ImageFormat.JPEG;
        }
        try {
            return ImageFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ImageFormat.JPEG;
        }
    }

    private static IllegalArgumentException specException(DagNode node, String detail) {
        return new IllegalArgumentException(
            "工具 " + (node == null || node.tool() == null ? "?" : node.tool().wireName())
                + " 映射 spec 失败: " + detail);
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) return Integer.parseInt(s);
        return null;
    }

    private static Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) return Long.parseLong(s);
        return null;
    }

    private static int intOr(Object o, int def) {
        Integer v = intOrNull(o);
        return v == null ? def : v;
    }

    private static boolean boolOr(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static String stringOr(Object o, String def) {
        if (o == null) return def;
        String s = o.toString();
        return s.isBlank() ? def : s;
    }

    private static Color colorOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        if (!s.startsWith("#")) return null;
        try {
            return Color.decode(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}