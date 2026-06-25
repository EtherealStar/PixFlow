package com.etherealstar.pixflow.module.dag.schema;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 水印位置枚举（需求 7.5，{@code watermark} 工具的 {@code position} 必填枚举参数）。
 *
 * <p>每个常量携带其在 DAG JSON 中使用的位置标识（wire name）。校验时以不区分大小写方式匹配。
 */
public enum WatermarkPosition {

    TOP_LEFT("top-left"),
    TOP_CENTER("top-center"),
    TOP_RIGHT("top-right"),
    CENTER_LEFT("center-left"),
    CENTER("center"),
    CENTER_RIGHT("center-right"),
    BOTTOM_LEFT("bottom-left"),
    BOTTOM_CENTER("bottom-center"),
    BOTTOM_RIGHT("bottom-right");

    private final String wireName;

    WatermarkPosition(String wireName) {
        this.wireName = wireName;
    }

    /** DAG JSON 中 {@code position} 字段使用的标识。 */
    public String getWireName() {
        return wireName;
    }

    /** 全部合法位置标识集合，供枚举校验使用。 */
    public static Set<String> wireNames() {
        return Arrays.stream(values())
                .map(WatermarkPosition::getWireName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
