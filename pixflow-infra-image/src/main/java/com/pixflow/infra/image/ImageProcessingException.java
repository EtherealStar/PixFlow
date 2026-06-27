package com.pixflow.infra.image;

public class ImageProcessingException extends RuntimeException {
    public enum Reason {
        DECODE_FAILED,
        ENCODE_FAILED,
        UNSUPPORTED_DECODE_FORMAT,
        UNSUPPORTED_ENCODE_FORMAT,
        SOURCE_TOO_LARGE,
        CORRUPTED_IMAGE,
        INVALID_OP_PARAM
    }

    private final Reason reason;
    private final ImageFormat format;
    private final Integer width;
    private final Integer height;

    public ImageProcessingException(Reason reason, ImageFormat format, Integer width, Integer height, String message) {
        this(reason, format, width, height, message, null);
    }

    public ImageProcessingException(
            Reason reason,
            ImageFormat format,
            Integer width,
            Integer height,
            String message,
            Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.format = format;
        this.width = width;
        this.height = height;
    }

    public Reason reason() {
        return reason;
    }

    public ImageFormat format() {
        return format;
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }
}
