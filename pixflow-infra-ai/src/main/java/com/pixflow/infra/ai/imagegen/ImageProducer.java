package com.pixflow.infra.ai.imagegen;

/** 实际完成生图调用的 provider/model，必须在 AI 边界确定。 */
public record ImageProducer(String provider, String model) {
    public ImageProducer {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
