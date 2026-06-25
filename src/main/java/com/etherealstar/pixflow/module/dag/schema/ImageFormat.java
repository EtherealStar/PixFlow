package com.etherealstar.pixflow.module.dag.schema;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图片输出格式枚举（需求 7.5，{@code convert_format} 工具的 {@code format} 必填枚举参数）。
 *
 * <p>每个常量携带其在 DAG JSON 中使用的格式标识（wire name）。校验时以不区分大小写方式匹配。
 */
public enum ImageFormat {

    JPG("JPG"),
    PNG("PNG"),
    WEBP("WebP");

    private final String wireName;

    ImageFormat(String wireName) {
        this.wireName = wireName;
    }

    /** DAG JSON 中 {@code format} 字段使用的标识。 */
    public String getWireName() {
        return wireName;
    }

    /** 全部合法格式标识集合，供枚举校验使用。 */
    public static Set<String> wireNames() {
        return Arrays.stream(values())
                .map(ImageFormat::getWireName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
