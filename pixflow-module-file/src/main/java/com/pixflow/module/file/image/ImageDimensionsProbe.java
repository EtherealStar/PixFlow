package com.pixflow.module.file.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 在已通过大小限制的图片字节上只读取头部尺寸，不执行完整像素解码。 */
public final class ImageDimensionsProbe {
    private ImageDimensionsProbe() {
    }

    public static Dimensions require(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("unsupported image dimensions");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to read image dimensions", failure);
        }
    }

    public record Dimensions(int width, int height) {
        public Dimensions {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("image dimensions must be positive");
            }
        }
    }
}
