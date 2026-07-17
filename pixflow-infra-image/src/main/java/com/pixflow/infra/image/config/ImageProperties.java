package com.pixflow.infra.image.config;

import java.awt.Color;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.image")
public class ImageProperties {
    private long maxSourcePixels = 40_000_000L;

    private long maxSourceBytes = 64L * 1024 * 1024;

    private int maxDimension = 12_000;

    private int defaultJpegQuality = 85;

    private int defaultWebpQuality = 80;

    private String flattenBackground = "#FFFFFF";

    private int targetSizeMaxIterations = 8;

    private PixelBudget pixelBudget = new PixelBudget();

    private Resize resize = new Resize();

    private String colorSpace = "sRGB";

    public long getMaxSourcePixels() {
        return maxSourcePixels;
    }

    public long getMaxSourceBytes() {
        return maxSourceBytes;
    }

    public void setMaxSourceBytes(long maxSourceBytes) {
        if (maxSourceBytes <= 0) {
            throw new IllegalArgumentException("maxSourceBytes must be positive");
        }
        this.maxSourceBytes = maxSourceBytes;
    }

    public void setMaxSourcePixels(long maxSourcePixels) {
        if (maxSourcePixels <= 0) {
            throw new IllegalArgumentException("maxSourcePixels must be positive");
        }
        this.maxSourcePixels = maxSourcePixels;
    }

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive");
        }
        this.maxDimension = maxDimension;
    }

    public int getDefaultJpegQuality() {
        return defaultJpegQuality;
    }

    public void setDefaultJpegQuality(int defaultJpegQuality) {
        validateQuality(defaultJpegQuality, "defaultJpegQuality");
        this.defaultJpegQuality = defaultJpegQuality;
    }

    public int getDefaultWebpQuality() {
        return defaultWebpQuality;
    }

    public void setDefaultWebpQuality(int defaultWebpQuality) {
        validateQuality(defaultWebpQuality, "defaultWebpQuality");
        this.defaultWebpQuality = defaultWebpQuality;
    }

    public String getFlattenBackground() {
        return flattenBackground;
    }

    public void setFlattenBackground(String flattenBackground) {
        this.flattenBackground = validateColor(flattenBackground);
    }

    public int getTargetSizeMaxIterations() {
        return targetSizeMaxIterations;
    }

    public PixelBudget getPixelBudget() {
        return pixelBudget;
    }

    public void setPixelBudget(PixelBudget pixelBudget) {
        this.pixelBudget = Objects.requireNonNull(pixelBudget, "pixelBudget must not be null");
    }

    public void setTargetSizeMaxIterations(int targetSizeMaxIterations) {
        if (targetSizeMaxIterations <= 0) {
            throw new IllegalArgumentException("targetSizeMaxIterations must be positive");
        }
        this.targetSizeMaxIterations = targetSizeMaxIterations;
    }

    public Resize getResize() {
        return resize;
    }

    public void setResize(Resize resize) {
        this.resize = Objects.requireNonNull(resize, "resize must not be null");
    }

    public String getColorSpace() {
        return colorSpace;
    }

    public void setColorSpace(String colorSpace) {
        if (colorSpace == null || colorSpace.isBlank()) {
            throw new IllegalArgumentException("colorSpace must not be blank");
        }
        this.colorSpace = colorSpace;
    }

    public Color flattenBackgroundColor() {
        return Color.decode(flattenBackground);
    }

    public int defaultQualityFor(com.pixflow.infra.image.ImageFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        return switch (format) {
            case WEBP -> defaultWebpQuality;
            case JPEG -> defaultJpegQuality;
            default -> 100;
        };
    }

    private static void validateQuality(int value, String property) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(property + " must be between 1 and 100");
        }
    }

    private static String validateColor(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("flattenBackground must not be blank");
        }
        try {
            Color.decode(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("flattenBackground must be a valid color", ex);
        }
        return value;
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

    public static class PixelBudget {
        private long maxInFlightPixels = 120_000_000L;

        private Duration acquireTimeout = Duration.ofSeconds(30);

        private double targetHeadroomFactor = 1.25;

        public long getMaxInFlightPixels() {
            return maxInFlightPixels;
        }

        public void setMaxInFlightPixels(long value) {
            if (value <= 0) {
                throw new IllegalArgumentException("maxInFlightPixels must be positive");
            }
            this.maxInFlightPixels = value;
        }

        public Duration getAcquireTimeout() {
            return acquireTimeout;
        }

        public void setAcquireTimeout(Duration value) {
            this.acquireTimeout = Objects.requireNonNull(value, "acquireTimeout must not be null");
        }

        public double getTargetHeadroomFactor() {
            return targetHeadroomFactor;
        }

        public void setTargetHeadroomFactor(double value) {
            if (value < 0) {
                throw new IllegalArgumentException("targetHeadroomFactor must not be negative");
            }
            this.targetHeadroomFactor = value;
        }
    }
}
