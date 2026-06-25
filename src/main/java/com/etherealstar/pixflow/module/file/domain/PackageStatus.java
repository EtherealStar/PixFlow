package com.etherealstar.pixflow.module.file.domain;

/**
 * 素材包状态码常量（对应 {@code asset_package.status}）。
 *
 * <ul>
 *   <li>{@link #PARSING}（0）：解析中；</li>
 *   <li>{@link #READY}（1）：就绪，至少识别到一张合法图片（需求 1.9）；</li>
 *   <li>{@link #PARSE_FAILED}（2）：解析失败，无任何合法图片（需求 1.10）。</li>
 * </ul>
 */
public final class PackageStatus {

    /** 解析中。 */
    public static final int PARSING = 0;

    /** 就绪（至少一张合法图片）。 */
    public static final int READY = 1;

    /** 解析失败（无合法图片）。 */
    public static final int PARSE_FAILED = 2;

    private PackageStatus() {
    }
}
