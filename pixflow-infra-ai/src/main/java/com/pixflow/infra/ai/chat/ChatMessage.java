package com.pixflow.infra.ai.chat;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 供应商无关消息，支持文本和图片片段。
 */
public record ChatMessage(Role role, List<Part> parts) {
    public ChatMessage {
        role = Objects.requireNonNull(role, "role");
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public sealed interface Part permits TextPart, ImagePart, ToolCallPart, ToolResultPart {
    }

    public record TextPart(String text) implements Part {
        public TextPart {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
        }
    }

    public record ImagePart(ImageContent content, String description) implements Part {
        public ImagePart {
            content = Objects.requireNonNull(content, "content");
        }
    }

    public record ToolCallPart(String id, String name, String argumentsJson) implements Part {
        public ToolCallPart {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tool call id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool call name must not be blank");
            }
            id = id.trim();
            name = name.trim();
            argumentsJson = Objects.requireNonNullElse(argumentsJson, "{}");
        }
    }

    public record ToolResultPart(String toolCallId, String content) implements Part {
        public ToolResultPart {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("tool call id must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("tool result content must not be blank");
            }
            toolCallId = toolCallId.trim();
        }
    }

    public sealed interface ImageContent permits BytesImageContent, DataUriImageContent, UrlImageContent {
    }

    public record BytesImageContent(byte[] bytes, String contentType) implements ImageContent {
        public BytesImageContent {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("bytes must not be empty");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record DataUriImageContent(String dataUri) implements ImageContent {
        public DataUriImageContent {
            if (dataUri == null || dataUri.isBlank()) {
                throw new IllegalArgumentException("dataUri must not be blank");
            }
        }
    }

    public record UrlImageContent(URI url) implements ImageContent {
        public UrlImageContent {
            url = Objects.requireNonNull(url, "url");
        }
    }
}
