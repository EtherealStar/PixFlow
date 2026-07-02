package com.pixflow.module.vision.config;

import com.pixflow.infra.image.ImageFormat;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.vision")
public class VisionProperties {
    private final Image image = new Image();
    private final Analyze analyze = new Analyze();
    private final Enrich enrich = new Enrich();

    public Image getImage() {
        return image;
    }

    public Analyze getAnalyze() {
        return analyze;
    }

    public Enrich getEnrich() {
        return enrich;
    }

    public static class Image {
        private int maxLongEdge = 1280;
        private ImageFormat outputFormat = ImageFormat.JPEG;
        private double jpegQuality = 0.85d;
        private long maxImageBytes = 10L * 1024L * 1024L;
        private TransparentBackground transparentBackground = TransparentBackground.WHITE;

        public int getMaxLongEdge() {
            return maxLongEdge;
        }

        public void setMaxLongEdge(int maxLongEdge) {
            this.maxLongEdge = maxLongEdge;
        }

        public ImageFormat getOutputFormat() {
            return outputFormat;
        }

        public void setOutputFormat(ImageFormat outputFormat) {
            this.outputFormat = outputFormat;
        }

        public double getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(double jpegQuality) {
            this.jpegQuality = jpegQuality;
        }

        public long getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(long maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public TransparentBackground getTransparentBackground() {
            return transparentBackground;
        }

        public void setTransparentBackground(TransparentBackground transparentBackground) {
            this.transparentBackground = transparentBackground;
        }

        public int jpegQualityPercent() {
            return Math.max(1, Math.min(100, (int) Math.round(jpegQuality * 100.0d)));
        }
    }

    public static class Analyze {
        private int imagesPerCall = 6;
        private Sampling sampling = Sampling.MAIN_FIRST;

        public int getImagesPerCall() {
            return imagesPerCall;
        }

        public void setImagesPerCall(int imagesPerCall) {
            this.imagesPerCall = imagesPerCall;
        }

        public Sampling getSampling() {
            return sampling;
        }

        public void setSampling(Sampling sampling) {
            this.sampling = sampling;
        }
    }

    public static class Enrich {
        private int imagesPerSku = 2;
        private FillPolicy fillPolicy = FillPolicy.GAP_ONLY;
        private int consumerConcurrency = 2;
        private int intraPackageParallelism = 4;
        private Duration consumerTimeout = Duration.ofMinutes(30);
        private boolean expose = false;

        public int getImagesPerSku() {
            return imagesPerSku;
        }

        public void setImagesPerSku(int imagesPerSku) {
            this.imagesPerSku = imagesPerSku;
        }

        public FillPolicy getFillPolicy() {
            return fillPolicy;
        }

        public void setFillPolicy(FillPolicy fillPolicy) {
            this.fillPolicy = fillPolicy;
        }

        public int getConsumerConcurrency() {
            return consumerConcurrency;
        }

        public void setConsumerConcurrency(int consumerConcurrency) {
            this.consumerConcurrency = consumerConcurrency;
        }


        public int getIntraPackageParallelism() {
            return intraPackageParallelism;
        }

        public void setIntraPackageParallelism(int intraPackageParallelism) {
            this.intraPackageParallelism = intraPackageParallelism;
        }

        public Duration getConsumerTimeout() {
            return consumerTimeout;
        }

        public void setConsumerTimeout(Duration consumerTimeout) {
            this.consumerTimeout = consumerTimeout;
        }

        public boolean isExpose() {
            return expose;
        }

        public void setExpose(boolean expose) {
            this.expose = expose;
        }
    }

    public enum TransparentBackground {
        WHITE,
        PNG_KEEP
    }

    public enum Sampling {
        MAIN_FIRST,
        HEAD
    }

    public enum FillPolicy {
        GAP_ONLY,
        SKIP_IF_ANY
    }
}
