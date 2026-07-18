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
                    number(p.get("maxLength"), 200), string(p.get("language"), "zh"),
                    strings(p.get("includeKeywords")));
        };
    }

    private static WatermarkBindingSpec watermark(DagNode node) {
        Map<String, Object> p = node.params();
        String ref = string(p.get("watermarkImage"), null);
        if (ref == null) {
            throw new IllegalArgumentException(node.id() + " 缺 watermarkImage");
        }
        return new WatermarkBindingSpec(ref, string(p.get("position"), "BOTTOM_RIGHT"),
                decimal(p.get("opacity"), 1), decimal(p.get("scale"), .2), number(p.get("margin"), 0));
    }

    private static ImageFormat format(Object value) {
        try {
            return ImageFormat.valueOf(string(value, "JPEG"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的图片格式: " + value, exception);
        }
    }

    private static Integer integer(Object value) {
        return value == null ? null : number(value, 0);
    }

    private static Long longValue(Object value) {
        return value == null ? null
                : value instanceof Number number
                        ? number.longValue() : Long.parseLong(value.toString());
    }

    private static int number(Object value, int defaultValue) {
        return value == null ? defaultValue
                : value instanceof Number number
                        ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static double decimal(Object value, double defaultValue) {
        return value == null ? defaultValue
                : value instanceof Number number
                        ? number.doubleValue() : Double.parseDouble(value.toString());
    }

    private static boolean bool(Object value, boolean defaultValue) {
        return value == null ? defaultValue
                : value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private static String string(Object value, String defaultValue) {
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private static Color color(Object value) {
        return value == null ? null : Color.decode(value.toString());
    }

    private static List<Integer> intList(Object value) {
        return value instanceof List<?> values
                ? values.stream().map(item -> number(item, 0)).toList() : List.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> values
                ? values.stream().map(Object::toString).toList() : List.of();
    }
}
