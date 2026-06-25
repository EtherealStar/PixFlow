package com.etherealstar.pixflow.infra.image;

import java.awt.image.BufferedImage;

/**
 * 像素处理流水线在节点之间传递的图像中间态（需求 8.4）。
 *
 * <p>承载当前解码后的 {@link BufferedImage}、目标输出格式（如 {@code JPG}/{@code PNG}/{@code WebP}）
 * 以及可选的压缩目标体积 {@code maxKb}。各像素工具节点接收并返回 {@code ImageData}，最终在汇节点
 * 由 {@link ImageCodec#encode(ImageData)} 依据 {@code format} 与 {@code maxKb} 编码为字节流落盘。</p>
 */
public final class ImageData {

    private final BufferedImage image;
    private final String format;
    private final Integer maxKb;

    public ImageData(BufferedImage image, String format, Integer maxKb) {
        this.image = image;
        this.format = format;
        this.maxKb = maxKb;
    }

    public ImageData(BufferedImage image, String format) {
        this(image, format, null);
    }

    public BufferedImage getImage() {
        return image;
    }

    /** 目标输出格式（wire name，如 {@code JPG}/{@code PNG}/{@code WebP}）。 */
    public String getFormat() {
        return format;
    }

    /** 压缩目标体积（KB），为空表示不做体积压缩。 */
    public Integer getMaxKb() {
        return maxKb;
    }

    /** 以新的图像替换，保留 format 与 maxKb。 */
    public ImageData withImage(BufferedImage newImage) {
        return new ImageData(newImage, format, maxKb);
    }

    /** 以新的格式替换，保留 image 与 maxKb。 */
    public ImageData withFormat(String newFormat) {
        return new ImageData(image, newFormat, maxKb);
    }

    /** 以新的压缩目标体积替换，保留 image 与 format。 */
    public ImageData withMaxKb(Integer newMaxKb) {
        return new ImageData(image, format, newMaxKb);
    }
}
