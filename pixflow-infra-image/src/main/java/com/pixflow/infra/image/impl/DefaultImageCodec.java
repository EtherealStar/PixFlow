package com.pixflow.infra.image.impl;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.config.ImageProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;

public class DefaultImageCodec implements ImageCodec {
    private final ImageProperties properties;

    public DefaultImageCodec(ImageProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public ImageProbe probe(InputStream data) {
        byte[] bytes = readAll(data);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInput == null) {
                throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED, null, null, null, "无法创建图片输入流");
            }
            ImageReader reader = nextReader(imageInput);
            try {
                reader.setInput(imageInput, true, true);
                String formatName = reader.getFormatName();
                ImageFormat format = ImageFormat.fromName(formatName)
                        .orElseThrow(() -> new ImageProcessingException(
                                ImageProcessingException.Reason.UNSUPPORTED_DECODE_FORMAT,
                                null,
                                null,
                                null,
                                "不支持的图片格式: " + formatName));
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                boolean alpha = hasAlpha(reader);
                ImageProbe probe = new ImageProbe(format, width, height, alpha);
                guardSize(probe);
                return probe;
            } finally {
                reader.dispose();
            }
        } catch (ImageProcessingException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.CORRUPTED_IMAGE, null, null, null, "图片探测失败", ex);
        }
    }

    @Override
    public RasterImage decode(InputStream data) {
        byte[] bytes = readAll(data);
        ImageProbe probe = probe(new ByteArrayInputStream(bytes));
        guardSize(probe);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = nextReader(imageInput);
            try {
                reader.setInput(imageInput, true, true);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new ImageProcessingException(ImageProcessingException.Reason.CORRUPTED_IMAGE, probe.format(), probe.width(), probe.height(), "图片首帧为空");
                }
                return RasterImage.of(toSrgb(applyExifOrientation(decoded, bytes, probe.format())), probe.format());
            } finally {
                reader.dispose();
            }
        } catch (ImageProcessingException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED, probe.format(), probe.width(), probe.height(), "图片解码失败", ex);
        }
    }

    @Override
    public byte[] encode(RasterImage image, EncodeSpec spec) {
        Objects.requireNonNull(image, "image must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        ImageFormat format = spec.targetFormat();
        if (!format.canEncode()) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.UNSUPPORTED_ENCODE_FORMAT,
                    format,
                    image.width(),
                    image.height(),
                    "目标格式无法编码: " + format);
        }
        try {
            if (spec.targetBytes() != null) {
                return encodeToTargetSize(image, spec);
            }
            return encodeOnce(image, spec, quality(spec));
        } catch (ImageProcessingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.ENCODE_FAILED, format, image.width(), image.height(), "图片编码失败", ex);
        }
    }

    private byte[] encodeToTargetSize(RasterImage image, EncodeSpec spec) {
        int low = 1;
        int high = quality(spec);
        byte[] best = null;
        byte[] smallest = null;
        int iterations = Math.max(1, properties.getTargetSizeMaxIterations());
        for (int i = 0; i < iterations && low <= high; i++) {
            int mid = (low + high) / 2;
            byte[] candidate = encodeOnce(image, spec, mid);
            if (smallest == null || candidate.length < smallest.length) {
                smallest = candidate;
            }
            if (candidate.length <= spec.targetBytes()) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best != null ? best : smallest;
    }

    private byte[] encodeOnce(RasterImage image, EncodeSpec spec, int quality) {
        BufferedImage output = normalizeForFormat(image.buffer(), spec);
        return switch (spec.targetFormat()) {
            case WEBP -> encodeWithWebpLibrary(output, quality);
            case JPEG, PNG, BMP -> encodeWithImageIo(output, spec.targetFormat(), quality);
            default -> throw new ImageProcessingException(
                    ImageProcessingException.Reason.UNSUPPORTED_ENCODE_FORMAT,
                    spec.targetFormat(),
                    image.width(),
                    image.height(),
                    "目标格式无法编码: " + spec.targetFormat());
        };
    }

    private byte[] encodeWithImageIo(BufferedImage output, ImageFormat format, int quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.writerName());
        if (!writers.hasNext()) {
            throw new ImageProcessingException(ImageProcessingException.Reason.UNSUPPORTED_ENCODE_FORMAT, format, output.getWidth(), output.getHeight(), "没有可用写出器: " + format);
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality / 100f);
            }
            writer.write(null, new IIOImage(output, null, null), param);
            imageOutput.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.ENCODE_FAILED, format, output.getWidth(), output.getHeight(), "ImageIO 写出失败", ex);
        } finally {
            writer.dispose();
        }
    }

    private byte[] encodeWithWebpLibrary(BufferedImage output, int quality) {
        try {
            return ImmutableImage.fromAwt(output)
                    .forWriter(WebpWriter.DEFAULT.withQ(quality))
                    .bytes();
        } catch (IOException | RuntimeException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.ENCODE_FAILED, ImageFormat.WEBP, output.getWidth(), output.getHeight(), "WebP 写出失败", ex);
        }
    }

    private int quality(EncodeSpec spec) {
        return spec.quality() != null ? spec.quality() : properties.defaultQualityFor(spec.targetFormat());
    }

    private BufferedImage normalizeForFormat(BufferedImage input, EncodeSpec spec) {
        if (spec.targetFormat().supportsAlpha()) {
            return toSrgb(input);
        }
        // JPEG/BMP 没有 alpha 通道，透明图必须先铺到底色上，避免黑底或脏边。
        Color background = spec.flattenBackground() != null ? spec.flattenBackground() : properties.flattenBackgroundColor();
        BufferedImage flattened = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flattened.createGraphics();
        try {
            applyQualityHints(g);
            g.setColor(background);
            g.fillRect(0, 0, flattened.getWidth(), flattened.getHeight());
            g.drawImage(input, 0, 0, null);
            return flattened;
        } finally {
            g.dispose();
        }
    }

    private BufferedImage toSrgb(BufferedImage input) {
        if (!input.getColorModel().hasAlpha()
                && input.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_RGB
                && input.getType() == BufferedImage.TYPE_INT_RGB) {
            return input;
        }
        int type = input.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage converted = new BufferedImage(input.getWidth(), input.getHeight(), type);
        Graphics2D g = converted.createGraphics();
        try {
            applyQualityHints(g);
            if (type == BufferedImage.TYPE_INT_RGB) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, converted.getWidth(), converted.getHeight());
            }
            g.drawImage(input, 0, 0, null);
            return converted;
        } finally {
            g.dispose();
        }
    }

    private BufferedImage applyExifOrientation(BufferedImage input, byte[] bytes, ImageFormat format) {
        if (format != ImageFormat.JPEG) {
            return input;
        }
        int orientation = readExifOrientation(bytes);
        if (orientation <= 1) {
            return input;
        }
        int width = input.getWidth();
        int height = input.getHeight();
        boolean swap = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8;
        BufferedImage output = new BufferedImage(swap ? height : width, swap ? width : height, input.getType() == 0 ? BufferedImage.TYPE_INT_RGB : input.getType());
        Graphics2D g = output.createGraphics();
        try {
            AffineTransform transform = new AffineTransform();
            // EXIF orientation 是相机写入的视觉方向标记；解码后立即归正，后续水印坐标才稳定。
            switch (orientation) {
                case 2 -> {
                    transform.scale(-1, 1);
                    transform.translate(-width, 0);
                }
                case 3 -> {
                    transform.translate(width, height);
                    transform.rotate(Math.PI);
                }
                case 4 -> {
                    transform.scale(1, -1);
                    transform.translate(0, -height);
                }
                case 5 -> {
                    transform.rotate(Math.PI / 2);
                    transform.scale(1, -1);
                }
                case 6 -> {
                    transform.translate(height, 0);
                    transform.rotate(Math.PI / 2);
                }
                case 7 -> {
                    transform.translate(height, width);
                    transform.rotate(Math.PI / 2);
                    transform.scale(-1, 1);
                }
                case 8 -> {
                    transform.translate(0, width);
                    transform.rotate(-Math.PI / 2);
                }
                default -> {
                    return input;
                }
            }
            g.drawImage(input, transform, null);
            return output;
        } finally {
            g.dispose();
        }
    }

    private int readExifOrientation(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 < bytes.length) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = bytes[offset + 1] & 0xFF;
            int length = u16(bytes, offset + 2, false);
            if (marker == 0xE1 && offset + 2 + length <= bytes.length && hasExifHeader(bytes, offset + 4)) {
                return readTiffOrientation(bytes, offset + 10, length - 8);
            }
            offset += 2 + length;
        }
        return 1;
    }

    private boolean hasExifHeader(byte[] bytes, int offset) {
        return offset + 6 <= bytes.length
                && bytes[offset] == 'E'
                && bytes[offset + 1] == 'x'
                && bytes[offset + 2] == 'i'
                && bytes[offset + 3] == 'f'
                && bytes[offset + 4] == 0
                && bytes[offset + 5] == 0;
    }

    private int readTiffOrientation(byte[] bytes, int tiffStart, int tiffLength) {
        if (tiffLength < 8 || tiffStart + tiffLength > bytes.length) {
            return 1;
        }
        boolean littleEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        int ifdOffset = u32(bytes, tiffStart + 4, littleEndian);
        int ifd = tiffStart + ifdOffset;
        if (ifd < tiffStart || ifd + 2 > tiffStart + tiffLength) {
            return 1;
        }
        int entries = u16(bytes, ifd, littleEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > tiffStart + tiffLength) {
                return 1;
            }
            int tag = u16(bytes, entry, littleEndian);
            if (tag == 0x0112) {
                return u16(bytes, entry + 8, littleEndian);
            }
        }
        return 1;
    }

    private int u16(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
        }
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private int u32(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xFF)
                    | ((bytes[offset + 1] & 0xFF) << 8)
                    | ((bytes[offset + 2] & 0xFF) << 16)
                    | ((bytes[offset + 3] & 0xFF) << 24);
        }
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private void guardSize(ImageProbe probe) {
        long pixels = Math.multiplyExact((long) probe.width(), (long) probe.height());
        if (pixels > properties.getMaxSourcePixels()
                || probe.width() > properties.getMaxDimension()
                || probe.height() > properties.getMaxDimension()) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.SOURCE_TOO_LARGE,
                    probe.format(),
                    probe.width(),
                    probe.height(),
                    "图片尺寸超过处理上限");
        }
    }

    private ImageReader nextReader(ImageInputStream imageInput) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
        if (!readers.hasNext()) {
            throw new ImageProcessingException(ImageProcessingException.Reason.UNSUPPORTED_DECODE_FORMAT, null, null, null, "没有可用读取器");
        }
        return readers.next();
    }

    private boolean hasAlpha(ImageReader reader) throws IOException {
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        return types.hasNext() && types.next().getColorModel().hasAlpha();
    }

    private byte[] readAll(InputStream data) {
        Objects.requireNonNull(data, "data must not be null");
        try {
            return data.readAllBytes();
        } catch (IOException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED, null, null, null, "读取图片字节失败", ex);
        }
    }

    static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }
}
