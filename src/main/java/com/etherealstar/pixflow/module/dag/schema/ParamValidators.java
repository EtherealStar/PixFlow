package com.etherealstar.pixflow.module.dag.schema;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 复用的参数取值校验器工厂（需求 7.5）。
 *
 * <p>提供正整数、枚举值、合法颜色、非空字符串、任意字符串等常见取值校验，供
 * {@link ToolSchemaRegistry} 构造各工具的参数 schema 使用。
 */
public final class ParamValidators {

    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    /** 兜底支持的具名颜色（不区分大小写）。 */
    private static final Set<String> NAMED_COLORS = Set.of(
            "white", "black", "red", "green", "blue", "yellow",
            "cyan", "magenta", "gray", "grey", "orange", "pink", "transparent");

    private ParamValidators() {
    }

    /**
     * 正整数校验：取值须为整数且 {@code > 0}。
     *
     * <p>接受整型 {@link Number}（如 {@code 800}）、无小数部分的浮点 {@link Number}（如 {@code 800.0}）
     * 以及可解析为整数的字符串（如 {@code "800"}）。
     */
    public static ParamValueValidator positiveInteger() {
        return (name, value) -> {
            Long intValue = toIntegral(value);
            if (intValue == null) {
                return Optional.of("参数 " + name + " 必须为整数");
            }
            if (intValue <= 0) {
                return Optional.of("参数 " + name + " 必须为正整数（> 0）");
            }
            return Optional.empty();
        };
    }

    /**
     * 枚举值校验：取值须为字符串且属于允许集合。
     *
     * @param allowedValues   允许的取值集合
     * @param caseInsensitive 是否不区分大小写匹配
     */
    public static ParamValueValidator enumValue(Set<String> allowedValues, boolean caseInsensitive) {
        return (name, value) -> {
            if (!(value instanceof String s) || s.isBlank()) {
                return Optional.of("参数 " + name + " 必须为枚举字符串，允许值：" + allowedValues);
            }
            boolean matched = allowedValues.stream()
                    .anyMatch(allowed -> caseInsensitive ? allowed.equalsIgnoreCase(s) : allowed.equals(s));
            if (!matched) {
                return Optional.of("参数 " + name + " 取值非法：" + s + "，允许值：" + allowedValues);
            }
            return Optional.empty();
        };
    }

    /**
     * 合法颜色校验：接受十六进制颜色（{@code #RGB} 或 {@code #RRGGBB}，不区分大小写）
     * 以及常见具名颜色（不区分大小写）。
     */
    public static ParamValueValidator color() {
        return (name, value) -> {
            if (!(value instanceof String s) || s.isBlank()) {
                return Optional.of("参数 " + name + " 必须为合法颜色字符串");
            }
            if (HEX_COLOR.matcher(s).matches() || NAMED_COLORS.contains(s.toLowerCase())) {
                return Optional.empty();
            }
            return Optional.of("参数 " + name + " 不是合法颜色：" + s);
        };
    }

    /** 非空字符串校验：取值须为非空白字符串。 */
    public static ParamValueValidator nonBlankString() {
        return (name, value) -> {
            if (!(value instanceof String s) || s.isBlank()) {
                return Optional.of("参数 " + name + " 必须为非空字符串");
            }
            return Optional.empty();
        };
    }

    /** 任意字符串校验：取值出现时须为字符串（允许空串）。 */
    public static ParamValueValidator anyString() {
        return (name, value) -> {
            if (!(value instanceof String)) {
                return Optional.of("参数 " + name + " 必须为字符串");
            }
            return Optional.empty();
        };
    }

    /**
     * 将取值转换为整数值；非整数（含带小数部分的数值、不可解析字符串、其它类型）返回 {@code null}。
     */
    private static Long toIntegral(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                return null;
            }
            return (long) d;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
