package com.pixflow.infra.image;

import java.util.Locale;
import java.util.Optional;

public enum ImageFormat {
    JPEG(true, true, false, "jpeg", "jpg"),
    PNG(true, true, true, "png"),
    WEBP(true, true, true, "webp"),
    BMP(true, true, false, "bmp"),
    TIFF(true, false, true, "tiff", "tif"),
    GIF(true, false, true, "gif");

    private final boolean canDecode;
    private final boolean canEncode;
    private final boolean supportsAlpha;
    private final String writerName;
    private final String[] aliases;

    ImageFormat(boolean canDecode, boolean canEncode, boolean supportsAlpha, String writerName, String... aliases) {
        this.canDecode = canDecode;
        this.canEncode = canEncode;
        this.supportsAlpha = supportsAlpha;
        this.writerName = writerName;
        this.aliases = aliases;
    }

    public boolean canDecode() {
        return canDecode;
    }

    public boolean canEncode() {
        return canEncode;
    }

    public boolean supportsAlpha() {
        return supportsAlpha;
    }

    public String writerName() {
        return writerName;
    }

    public static Optional<ImageFormat> fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ImageFormat format : values()) {
            if (format.writerName.equals(normalized) || format.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(format);
            }
            for (String alias : format.aliases) {
                if (alias.equals(normalized)) {
                    return Optional.of(format);
                }
            }
        }
        return Optional.empty();
    }
}
