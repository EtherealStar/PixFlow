package com.pixflow.module.file.output;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.web.PageResponse;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetDeletionService;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.internal.output.GeneratedOutputContextMapper;
import com.pixflow.module.file.internal.output.GeneratedOutputImageRow;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** File-owned Outputs 读模型门面；调用方看不到 Task execution row 或对象位置。 */
public final class OutputQueryService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    private final GeneratedOutputContextMapper outputs;

    private final AssetImageMapper images;

    private final ObjectStorage storage;

    private final CanonicalAssetReferenceCodec references;

    private final AssetDeletionService deletion;

    private final Clock clock;

    public OutputQueryService(
            GeneratedOutputContextMapper outputs,
            AssetImageMapper images,
            ObjectStorage storage,
            CanonicalAssetReferenceCodec references,
            AssetDeletionService deletion,
            Clock clock) {
        this.outputs = outputs;
        this.images = images;
        this.storage = storage;
        this.references = references;
        this.deletion = deletion;
        this.clock = clock;
    }

    public PageResponse<OutputConversationView> conversations(
            long page, long size, String query, OutputConversationSort sort) {
        String normalized = normalizeQuery(query);
        OutputConversationSort effective = sort == null
                ? OutputConversationSort.LATEST_OUTPUT_DESC : sort;
        return PageResponse.of(
                outputs.listConversations(normalized, effective.name(), offset(page, size), size),
                outputs.countConversations(normalized), page, size);
    }

    public PageResponse<OutputTaskView> tasks(String conversationId, long page, long size) {
        String id = requireText(conversationId, "conversationId");
        return PageResponse.of(
                outputs.listTasks(id, offset(page, size), size),
                outputs.countTasks(id), page, size);
    }

    public PageResponse<GeneratedImageView> images(String taskId, long page, long size) {
        long id = parseTaskId(taskId);
        return PageResponse.of(
                outputs.listImages(id, offset(page, size), size).stream().map(this::toView).toList(),
                outputs.countImages(id), page, size);
    }

    public GeneratedImageView rename(String imageId, String displayName) {
        AssetImage image = requireGenerated(imageId);
        String normalized = normalizeDisplayName(displayName);
        AssetImage update = new AssetImage();
        update.setId(image.getId());
        update.setDisplayName(normalized);
        images.updateById(update);
        image.setDisplayName(normalized);
        return toView(new GeneratedOutputImageRow(
                image.getId(), image.getPackageId(), image.getSkuId(), image.getDisplayName(),
                outputContext(image).conversationId(), image.getSourceTaskId(), image.getSourceImageId(),
                image.getWidth(), image.getHeight(), image.getByteSize(), image.getContentType(),
                image.getStableBucket(), image.getMinioKey(), image.getCreatedAt()));
    }

    public void delete(String imageId) {
        AssetImage image = requireGenerated(imageId);
        deletion.deleteImage(image.getPackageId(), image.getId());
    }

    private GeneratedImageView toView(GeneratedOutputImageRow row) {
        ObjectLocation location = ObjectLocation.of(BucketType.valueOf(row.stableBucket()), row.minioKey());
        URL preview = storage.presignGet(location, PREVIEW_TTL);
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        return new GeneratedImageView(
                Long.toString(row.imageId()),
                references.serialize(new ImageAssetReferenceKey(row.packageId(), row.imageId())),
                "GENERATED", row.displayName(), row.packageId(), row.skuId(), row.conversationId(),
                Long.toString(row.taskId()), row.sourceImageId(), row.width(), row.height(),
                row.sizeBytes(), row.contentType(), preview.toString(), expiresAt, row.createdAt());
    }

    private com.pixflow.module.file.internal.output.GeneratedOutputContextRow outputContext(AssetImage image) {
        var context = outputs.find(Long.toString(image.getSourceTaskId()));
        if (context == null) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND, "output context not found");
        }
        return context;
    }

    private AssetImage requireGenerated(String imageId) {
        long id = parsePositive(imageId, "imageId");
        AssetImage image = images.selectById(id);
        if (image == null || !"GENERATED".equals(image.getSourceType())
                || !"READY".equals(image.getPublicationStatus()) || image.getDeletionStatus() != null) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND, "generated image not found");
        }
        return image;
    }

    private static long offset(long page, long size) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page and size must be positive");
        }
        return Math.multiplyExact(page - 1, size);
    }

    private static long parseTaskId(String taskId) {
        return parsePositive(taskId, "taskId");
    }

    private static long parsePositive(String value, String field) {
        try {
            long parsed = Long.parseLong(requireText(value, field));
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be a positive identifier", error);
        }
    }

    private static String normalizeDisplayName(String value) {
        String normalized = requireText(value, "displayName");
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
                || normalized.toLowerCase(Locale.ROOT).contains("..")) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NAME_INVALID, "invalid display name");
        }
        return normalized;
    }

    private static String normalizeQuery(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
