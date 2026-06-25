package com.etherealstar.pixflow.infra.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * 图像编解码工具（需求 8.4）。
 *
 * <p>统一负责字节流与 {@link BufferedImage} 之间的转换，并在编码时依据 {@link ImageData} 的
 * {@code format} 与 {@code maxKb} 选择写出格式与压缩质量。当指定 {@code maxKb} 且目标为有损格式
 * （JPG/WebP）时，逐步降低写出质量直到满足目标体积或达到最低质量（需求中的 {@code compress} 工具语义）。</p>
 *
 * <p>解码失败、目标格式无可用 ImageIO 写出器等情况以 {@link ImageProcessingException} 抛出，
 * 由执行引擎的失败隔离逻辑捕获并标记对应支路失败（需求 11.1）。</p>
 */
@Component
public class ImageCodec {

    /** 有损压缩的最低质量下限，避免无限降质。 */
    private static final float MIN_QUALITY = 0.1f;

    /** 压缩时每轮质量递减步长。 */
    private static final float QUALITY_STEP = 0.1f;

    /**
     * 将字节流解码为 {@link BufferedImage}。
     *
     * @throws ImageProcessingException 当字节流无法被解码为图片时
     */
    public BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImageProcessingException("图片字节为空，无法解码");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ImageProcessingException("无法识别的图片格式，解码失败");
            }
            return image;
        } catch (IOException e) {
            throw new ImageProcessingException("图片解码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 依据 {@link ImageData} 的目标格式与压缩目标体积编码为字节流。
     *
     * @throws ImageProcessingException 当目标格式不受支持或写出失败时
     */
    public byte[] encode(ImageData data) {
        if (data == null || data.getImage() == null) {
            throw new ImageProcessingException("待编码图像为空");
        }
        String writerFormat = toWriterFormat(data.getFormat());
        BufferedImage image = ensureCompatible(data.getImage(), writerFormat);

        if (data.getMaxKb() != null && data.getMaxKb() > 0 && supportsQuality(writerFormat)) {
            return encodeWithMaxKb(image, writerFormat, data.getMaxKb());
        }
        return encodePlain(image, writerFormat);
    }

    private byte[] encodePlain(BufferedImage image, String writerFormat) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, writerFormat, out)) {
                throw new ImageProcessingException("没有可用于格式 " + writerFormat + " 的图片写出器");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ImageProcessingException("图片编码失败: " + e.getMessage(), e);
        }
    }

    private byte[] encodeWithMaxKb(BufferedImage image, String writerFormat, int maxKb) {
        long maxBytes = (long) maxKb * 1024L;
        ImageWriter writer = firstWriter(writerFormat);
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            }
            byte[] best = null;
            for (float quality = 1.0f; quality >= MIN_QUALITY - 1e-6; quality -= QUALITY_STEP) {
                byte[] encoded = writeWithQuality(writer, param, image, quality);
                best = encoded;
                if (encoded.length <= maxBytes) {
                    return encoded;
                }
            }
            // 即使在最低质量下仍超出目标体积，返回最小体积的结果（尽力而为）。
            return best != null ? best : encodePlain(image, writerFormat);
        } finally {
            writer.dispose();
        }
    }

    private byte[] writeWithQuality(ImageWriter writer, ImageWriteParam param,
                                    BufferedImage image, float quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                if (param.canWriteCompressed()) {
                    param.setCompressionQuality(Math.max(MIN_QUALITY, Math.min(1.0f, quality)));
                }
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ImageProcessingException("图片压缩写出失败: " + e.getMessage(), e);
        }
    }

    private ImageWriter firstWriter(String writerFormat) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(writerFormat);
        if (!writers.hasNext()) {
            throw new ImageProcessingException("没有可用于格式 " + writerFormat + " 的图片写出器");
        }
        return writers.next();
    }

    private boolean supportsQuality(String writerFormat) {
        return "jpg".equals(writerFormat) || "jpeg".equals(writerFormat) || "webp".equals(writerFormat);
    }

    /**
     * JPEG 不支持透明通道，若目标为 JPEG 而图像含 alpha，则铺白底转为 RGB，避免写出异常或黑底。
     */
    private BufferedImage ensureCompatible(BufferedImage image, String writerFormat) {
        boolean opaqueOnly = "jpg".equals(writerFormat) || "jpeg".equals(writerFormat);
        if (opaqueOnly && image.getColorModel().hasAlpha()) {
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return rgb;
        }
        return image;
    }

    /**
     * 将 wire 格式名（JPG/PNG/WebP）映射为 ImageIO 写出器格式名。
     */
    private String toWriterFormat(String format) {
        if (format == null || format.isBlank()) {
            return "png";
        }
        String f = format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "jpg", "jpeg" -> "jpg";
            case "png" -> "png";
            case "webp" -> "webp";
            default -> f;
        };
    }
}
