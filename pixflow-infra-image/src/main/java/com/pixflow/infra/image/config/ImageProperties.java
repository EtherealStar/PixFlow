package com.pixflow.infra.image.config;

import java.awt.Color;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.image")
public class ImageProperties {
    private long maxSourcePixels = 40_000_000L;
    private int maxDimension = 12_000;
    private int defaultJpegQuality = 85;
    private int defaultWebpQuality = 80;
    private String flattenBackground = "#FFFFFF";
    private int targetSizeMaxIterations = 8;
    private Resize resize = new Resize();
    private String colorSpace = "sRGB";

    public long getMaxSourcePixels() {
        return maxSourcePixels;
    }

    public void setMaxSourcePixels(long maxSourcePixels) {
        this.maxSourcePixels = maxSourcePixels;
    }

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    public int getDefaultJpegQuality() {
        return defaultJpegQuality;
    }

    public void setDefaultJpegQuality(int defaultJpegQuality) {
        this.defaultJpegQuality = defaultJpegQuality;
    }

    public int getDefaultWebpQuality() {
        return defaultWebpQuality;
    }

    public void setDefaultWebpQuality(int defaultWebpQuality) {
        this.defaultWebpQuality = defaultWebpQuality;
    }

    public String getFlattenBackground() {
        return flattenBackground;
    }

    public void setFlattenBackground(String flattenBackground) {
        this.flattenBackground = flattenBackground;
    }

    public int getTargetSizeMaxIterations() {
        return targetSizeMaxIterations;
    }

    public void setTargetSizeMaxIterations(int targetSizeMaxIterations) {
        this.targetSizeMaxIterations = targetSizeMaxIterations;
    }

    public Resize getResize() {
        return resize;
    }

    public void setResize(Resize resize) {
        this.resize = resize;
    }

    public String getColorSpace() {
        return colorSpace;
    }

    public void setColorSpace(String colorSpace) {
        this.colorSpace = colorSpace;
    }

    public Color flattenBackgroundColor() {
        return Color.decode(flattenBackground);
    }

    public int defaultQualityFor(com.pixflow.infra.image.ImageFormat format) {
        return switch (format) {
            case WEBP -> defaultWebpQuality;
            case JPEG -> defaultJpegQuality;
            default -> 100;
        };
    }

    public static class Resize {
        private boolean allowUpscale;

        public boolean isAllowUpscale() {
            return allowUpscale;
        }

        public void setAllowUpscale(boolean allowUpscale) {
            this.allowUpscale = allowUpscale;
        }
    }
}
