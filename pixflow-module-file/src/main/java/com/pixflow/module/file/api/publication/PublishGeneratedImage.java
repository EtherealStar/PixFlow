package com.pixflow.module.file.api.publication;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import java.util.LinkedHashSet;
import java.util.List;

/** File 发布 Generated Image 的完整命令。 */
public record PublishGeneratedImage(
        long sourceTaskId,
        long sourceResultId,
        String sourceUnitKey,
        long sourceRunEpoch,
        long packageId,
        ObjectLocation candidate,
        long size,
        String contentType,
        String extension,
        GeneratedImageKind kind,
        List<SourceImageRef> sourceImages,
        GeneratedImageProducer producer) {
    public PublishGeneratedImage {
        if (sourceTaskId <= 0 || sourceResultId <= 0 || sourceRunEpoch <= 0
                || packageId <= 0 || size <= 0) {
            throw new IllegalArgumentException("ids, epoch and size must be positive");
        }
        sourceUnitKey = requireText(sourceUnitKey, "sourceUnitKey");
        if (candidate == null || candidate.bucket() != BucketType.TMP) {
            throw new IllegalArgumentException("candidate must be in TMP");
        }
        contentType = requireText(contentType, "contentType").toLowerCase(java.util.Locale.ROOT);
        extension = requireText(extension, "extension").toLowerCase(java.util.Locale.ROOT);
        String expectedContentType = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> throw new IllegalArgumentException("extension is not supported");
        };
        if (!expectedContentType.equals(contentType)) {
            throw new IllegalArgumentException("contentType does not match extension");
        }
        int extensionSeparator = candidate.key().lastIndexOf('.');
        String candidateExtension = extensionSeparator < 0
                ? "" : candidate.key().substring(extensionSeparator + 1).toLowerCase(java.util.Locale.ROOT);
        if (!extension.equals(candidateExtension)) {
            throw new IllegalArgumentException("extension does not match candidate key");
        }
        if (kind == null || producer == null || producer.kind() != kind) {
            throw new IllegalArgumentException("kind and producer must agree");
        }
        if (sourceImages == null || sourceImages.isEmpty()) {
            throw new IllegalArgumentException("sourceImages must not be empty");
        }
        sourceImages = List.copyOf(new LinkedHashSet<>(sourceImages));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
