package com.etherealstar.pixflow.module.file.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * 基于 {@link javax.imageio.ImageIO} 的 {@link ImageDecoder} 默认实现。
 *
 * <p>通过 {@code ImageIO.read} 尝试解码字节内容，解码返回 {@code null} 或抛出异常均视为无法解码
 * （损坏或内容与扩展名不符，需求 1.7）。</p>
 *
 * <p>注意：标准 JDK 的 ImageIO 默认不内置 WebP 解码器，若未引入相应插件，WebP 文件可能无法解码。
 * 该限制不影响白名单过滤逻辑（需求 1.5），相关文件会按「无法解码」记入跳过清单。</p>
 */
public final class ImageIoImageDecoder implements ImageDecoder {

    /** 无状态单例，可安全并发复用。 */
    public static final ImageIoImageDecoder INSTANCE = new ImageIoImageDecoder();

    @Override
    public boolean canDecode(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(in);
            return image != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
