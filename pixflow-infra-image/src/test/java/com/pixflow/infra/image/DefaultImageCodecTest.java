package com.pixflow.infra.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.image.config.ImageProperties;
import com.pixflow.infra.image.impl.DefaultImageCodec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DefaultImageCodecTest {

    @Test
    void probesAndDecodesPng() throws Exception {
        ImageCodec codec = new DefaultImageCodec(new ImageProperties());
        byte[] bytes = png(sample(20, 10, Color.RED, true));

        ImageProbe probe = codec.probe(new ByteArrayInputStream(bytes));
        RasterImage decoded = codec.decode(new ByteArrayInputStream(bytes));

        assertThat(probe.format()).isEqualTo(ImageFormat.PNG);
        assertThat(probe.width()).isEqualTo(20);
        assertThat(probe.height()).isEqualTo(10);
        assertThat(decoded.width()).isEqualTo(20);
        assertThat(decoded.height()).isEqualTo(10);
    }

    @Test
    void flattensTransparentImageToWhiteWhenEncodingJpeg() throws Exception {
        ImageCodec codec = new DefaultImageCodec(new ImageProperties());
        BufferedImage transparent = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = transparent.createGraphics();
        try {
            g.setColor(new Color(255, 0, 0, 128));
            g.fillRect(2, 2, 4, 4);
        } finally {
            g.dispose();
        }

        byte[] jpeg = codec.encode(RasterImage.takeOwnership(transparent, ImageFormat.PNG), new EncodeSpec(ImageFormat.JPEG, 90, null, null));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));

        assertThat(new Color(decoded.getRGB(0, 0)).getRed()).isGreaterThan(240);
        assertThat(new Color(decoded.getRGB(0, 0)).getGreen()).isGreaterThan(240);
        assertThat(new Color(decoded.getRGB(0, 0)).getBlue()).isGreaterThan(240);
    }

    @Test
    void rejectsOversizedSourceBeforeFullDecode() throws Exception {
        ImageProperties properties = new ImageProperties();
        properties.setMaxSourcePixels(10);
        ImageCodec codec = new DefaultImageCodec(properties);
        byte[] bytes = png(sample(4, 4, Color.BLUE, false));

        assertThatThrownBy(() -> codec.probe(new ByteArrayInputStream(bytes)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting("reason")
                .isEqualTo(ImageProcessingException.Reason.SOURCE_TOO_LARGE);

        assertThatThrownBy(() -> codec.decode(new ByteArrayInputStream(bytes)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting("reason")
                .isEqualTo(ImageProcessingException.Reason.SOURCE_TOO_LARGE);
    }

    @Test
    void probeDoesNotMaterializeTheWholeSource() throws Exception {
        ImageCodec codec = new DefaultImageCodec(new ImageProperties());
        byte[] png = png(sample(4, 4, Color.BLUE, false));
        byte[] padded = java.util.Arrays.copyOf(png, png.length + 1_000_000);
        CountingInputStream source = new CountingInputStream(new ByteArrayInputStream(padded));

        ImageProbe probe = codec.probe(source);

        assertThat(probe.width()).isEqualTo(4);
        assertThat(source.bytesRead).isLessThan(padded.length);
    }

    @Test
    void decodeRejectsSourceBytesOverConfiguredLimit() throws Exception {
        ImageProperties properties = new ImageProperties();
        byte[] bytes = png(sample(4, 4, Color.BLUE, false));
        properties.setMaxSourceBytes(bytes.length - 1L);
        ImageCodec codec = new DefaultImageCodec(properties);

        assertThatThrownBy(() -> codec.decode(new ByteArrayInputStream(bytes)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting("reason")
                .isEqualTo(ImageProcessingException.Reason.SOURCE_TOO_LARGE);
    }

    @Test
    void rasterImageUsesExplicitSharedOwnershipWithoutCopying() {
        BufferedImage original = sample(2, 2, Color.RED, false);
        RasterImage raster = RasterImage.takeOwnership(original, ImageFormat.PNG);
        RasterImage retained = raster.retain();

        original.setRGB(0, 0, Color.BLUE.getRGB());
        assertThat(raster.borrowBuffer()).isSameAs(original);
        assertThat(raster.borrowBuffer().getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        raster.close();
        assertThatThrownBy(raster::borrowBuffer).isInstanceOf(IllegalStateException.class);
        assertThat(retained.borrowBuffer()).isSameAs(original);
        retained.close();
    }

    @Test
    void encodeSpecRejectsConflictingAndUnboundedTargetSize() {
        assertThatThrownBy(() -> new EncodeSpec(ImageFormat.JPEG, 80, 1024L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> new EncodeSpec(ImageFormat.JPEG, null, (long) Integer.MAX_VALUE + 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBytes");
    }

    @Test
    void imagePropertiesValidateBoundsAndColor() {
        ImageProperties properties = new ImageProperties();

        assertThatThrownBy(() -> properties.setMaxSourcePixels(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxDimension(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setFlattenBackground("not-a-color"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedEncodeFormat() {
        ImageCodec codec = new DefaultImageCodec(new ImageProperties());
        RasterImage image = RasterImage.takeOwnership(sample(4, 4, Color.BLUE, false), ImageFormat.PNG);

        assertThatThrownBy(() -> codec.encode(image, EncodeSpec.of(ImageFormat.TIFF)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting("reason")
                .isEqualTo(ImageProcessingException.Reason.UNSUPPORTED_ENCODE_FORMAT);
    }

    @Test
    void writesWebpWhenNativeLibraryIsAvailable() {
        ImageCodec codec = new DefaultImageCodec(new ImageProperties());
        RasterImage image = RasterImage.takeOwnership(sample(6, 6, Color.BLUE, true), ImageFormat.PNG);

        byte[] encoded;
        try {
            encoded = codec.encode(image, new EncodeSpec(ImageFormat.WEBP, 80, null, null));
        } catch (ImageProcessingException ex) {
            Assumptions.abort("当前平台不可用 scrimage WebP 原生写出: " + ex.getMessage());
            return;
        }

        assertThat(encoded).isNotEmpty();
    }

    @Test
    void commonNormalizerMapsImageExceptionToSkipRecovery() {
        ImageProcessingException source = new ImageProcessingException(
                ImageProcessingException.Reason.CORRUPTED_IMAGE,
                ImageFormat.PNG,
                10,
                10,
                "图片损坏");

        var normalized = new ErrorNormalizer().normalize(source);

        assertThat(normalized.category()).isEqualTo(ErrorCategory.IMAGE_PROCESSING);
        assertThat(normalized.recovery()).isEqualTo(RecoveryHint.SKIP);
    }

    static BufferedImage sample(int width, int height, Color color, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        private CountingInputStream(java.io.InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }
    }
}
