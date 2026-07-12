package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.SetBackgroundSpec;
import com.pixflow.module.dag.ir.DagNode;
import java.awt.Color;
import java.util.List;
import java.util.Map;

/** 在 canonical DAG 与执行计划之间完成唯一一次 raw params 到类型化 spec 的编译。 */
public final class StepSpecCompiler {
    public Object compile(DagNode node) {
        Map<String, Object> p = node.params();
        return switch (node.tool()) {
            case RESIZE -> new ResizeSpec(integer(p.get("width")), integer(p.get("height")),
                    ResizeSpec.Mode.valueOf(string(p.get("mode"), "FIT")), bool(p.get("upscale"), false));
            case COMPRESS -> new CompressSpec(integer(p.get("quality")), longValue(p.get("targetBytes")));
            case SET_BACKGROUND -> new SetBackgroundBindingSpec(color(p.get("color")),
                    string(p.get("backgroundImage"), null),
                    SetBackgroundSpec.Fit.valueOf(string(p.get("fit"), "STRETCH")));
            case WATERMARK -> watermark(node);
            case CONVERT_FORMAT -> new ConvertFormatSpec(format(p.get("targetFormat")),
                    integer(p.get("quality")), color(p.get("flattenBackground")));
            case COMPOSE_GROUP -> new ComposeSpec(ComposeSpec.Layout.valueOf(string(p.get("layout"), "GRID")),
                    intList(p.get("order")), number(p.get("gap"), 0), color(p.get("background")));
            case REMOVE_BG -> new BackgroundRemovalBindingSpec(string(p.get("outputFormat"), "png"),
                    bool(p.get("crop"), false), integer(p.get("featherRadius")));
            case GENERATE_COPY -> new CopyBindingSpec(string(p.get("style"), "PROMOTIONAL"),
                    number(p.get("maxLength"), 200), string(p.get("language"), "zh"), strings(p.get("includeKeywords")));
        };
    }

    private static WatermarkBindingSpec watermark(DagNode node) {
        Map<String, Object> p = node.params();
        String ref = string(p.get("watermarkImage"), null);
        if (ref == null) throw new IllegalArgumentException(node.id() + " 缺 watermarkImage");
        return new WatermarkBindingSpec(ref, string(p.get("position"), "BOTTOM_RIGHT"),
                decimal(p.get("opacity"), 1), decimal(p.get("scale"), .2), number(p.get("margin"), 0));
    }

    private static ImageFormat format(Object value) {
        try { return ImageFormat.valueOf(string(value, "JPEG")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("不支持的图片格式: " + value, e); }
    }
    private static Integer integer(Object v) { return v == null ? null : number(v, 0); }
    private static Long longValue(Object v) { return v == null ? null : v instanceof Number n ? n.longValue() : Long.valueOf(v.toString()); }
    private static int number(Object v, int d) { return v == null ? d : v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()); }
    private static double decimal(Object v, double d) { return v == null ? d : v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()); }
    private static boolean bool(Object v, boolean d) { return v == null ? d : v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString()); }
    private static String string(Object v, String d) { return v == null || v.toString().isBlank() ? d : v.toString(); }
    private static Color color(Object v) { return v == null ? null : Color.decode(v.toString()); }
    private static List<Integer> intList(Object v) {
        return v instanceof List<?> values ? values.stream().map(x -> number(x, 0)).toList() : List.of();
    }
    private static List<String> strings(Object v) {
        return v instanceof List<?> values ? values.stream().map(Object::toString).toList() : List.of();
    }
}
