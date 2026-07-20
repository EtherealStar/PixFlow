package com.pixflow.module.dag.exec;

public record WatermarkBindingSpec(String imageRef, String position, double opacity,
                                   double scale, int margin) {
}
