package com.pixflow.module.vision.api;

/**
 * 扁平的类别特有观察项；禁止嵌套任意 JSON。
 */
public record VisualAttribute(String name, String value) {
}
