package com.pixflow.infra.image.op;

public record ResizeSpec(Integer width, Integer height, Mode mode, boolean upscale) {
    public enum Mode {
        FIT,
        FILL,
        EXACT
    }

    public ResizeSpec {
        if (width == null && height == null) {
            throw new IllegalArgumentException("width or height must be provided");
        }
        if (width != null && width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        mode = mode == null ? Mode.FIT : mode;
    }
}
