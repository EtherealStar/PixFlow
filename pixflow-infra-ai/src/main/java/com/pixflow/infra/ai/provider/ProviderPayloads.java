package com.pixflow.infra.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.TokenUsage;
import java.util.Base64;
import java.util.Map;

/**
 * Provider 请求/响应里的小型投影工具。
 */
public final class ProviderPayloads {
    public static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0);

    private ProviderPayloads() {
    }

    public static String imageUrl(ChatMessage.ImageContent content) {
        if (content instanceof ChatMessage.DataUriImageContent dataUri) {
            return dataUri.dataUri();
        }
        if (content instanceof ChatMessage.UrlImageContent url) {
            return url.url().toString();
        }
        if (content instanceof ChatMessage.BytesImageContent bytes) {
            String contentType = bytes.contentType() == null || bytes.contentType().isBlank()
                    ? "application/octet-stream"
                    : bytes.contentType();
            // 供应商多模态接口只看 data URI；这里绝不记录原始图片 bytes。
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes.bytes());
        }
        throw new PixFlowException(
                AiErrorCode.MODEL_UNSUPPORTED_CAPABILITY,
                "Unsupported image content",
                null,
                Map.of(),
                RecoveryHint.TERMINATE,
                null,
                null);
    }

    public static TokenUsage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return ZERO_USAGE;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        long total = usage.path("total_tokens").asLong(prompt + completion);
        return new TokenUsage(prompt, completion, total);
    }

    public static byte[] decodeImageBytes(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw providerError("Model provider response missing image bytes");
        }
        String base64 = encoded.strip();
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma >= 0) {
            base64 = base64.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_PROVIDER_ERROR,
                    "Model provider returned invalid image bytes",
                    ex,
                    Map.of(),
                    RecoveryHint.RETRY,
                    null,
                    null);
        }
    }

    public static PixFlowException providerError(String message) {
        return new PixFlowException(
                AiErrorCode.MODEL_PROVIDER_ERROR,
                message,
                null,
                Map.of(),
                RecoveryHint.RETRY,
                null,
                null);
    }
}
