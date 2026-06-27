package com.pixflow.infra.image.op;

import com.pixflow.infra.image.RasterImage;
import java.awt.Color;

public record SetBackgroundSpec(Color color, RasterImage background, Fit fit) {
    public enum Fit {
        STRETCH,
        TILE,
        CENTER
    }

    public SetBackgroundSpec {
        if (color == null && background == null) {
            throw new IllegalArgumentException("color or background must be provided");
        }
        fit = fit == null ? Fit.STRETCH : fit;
    }
}
