package com.pixflow.module.conversation.attachment;

import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.file.pkg.ImageReference;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AttachmentCollector {
    private final PackageReferenceResolver packageReferenceResolver;

    public AttachmentCollector(PackageReferenceResolver packageReferenceResolver) {
        this.packageReferenceResolver = packageReferenceResolver;
    }

    public List<Attachment> collect(UserPrompt prompt, PackageBinding binding) {
        List<Attachment> result = new ArrayList<>();
        if (prompt != null && prompt.attachments() != null) {
            for (UserAttachmentInput input : prompt.attachments()) {
                result.add(fromInput(input));
            }
        }
        if (binding != null && binding.present()) {
            result.addAll(fromPackage(binding.packageId()));
        }
        return List.copyOf(result);
    }

    private Attachment fromInput(UserAttachmentInput input) {
        if (input == null || input.type() != AttachmentType.UPLOAD_IMAGE) {
            throw new BusinessException(ConversationErrorCode.ATTACHMENT_INVALID,
                    "only upload image attachment is supported");
        }
        return new Attachment(
                blankToGenerated(input.attachmentId()),
                AttachmentType.UPLOAD_IMAGE,
                input.sourceRef(),
                input.packageId(),
                input.metadata());
    }

    private List<Attachment> fromPackage(String packageId) {
        try {
            List<ImageReference> images = packageReferenceResolver.listImages(packageId);
            return images.stream()
                    .map(image -> {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        putIfPresent(metadata, "imageId", image.imageId());
                        putIfPresent(metadata, "originalPath", image.originalPath());
                        putIfPresent(metadata, "skuId", image.skuId());
                        putIfPresent(metadata, "groupKey", image.groupKey());
                        putIfPresent(metadata, "viewId", image.viewId());
                        return new Attachment(
                                "pkg-" + packageId + "-" + image.imageId(),
                                AttachmentType.PACKAGE_REFERENCE,
                                image.objectKey(),
                                packageId,
                                metadata);
                    })
                    .toList();
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ConversationErrorCode.PACKAGE_REFERENCE_INVALID,
                    "package reference invalid: " + packageId,
                    ex,
                    Map.of("packageId", packageId));
        }
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String blankToGenerated(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }
}
