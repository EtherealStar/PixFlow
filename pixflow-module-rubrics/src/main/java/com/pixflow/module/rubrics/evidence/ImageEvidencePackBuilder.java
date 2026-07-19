package com.pixflow.module.rubrics.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;

public final class ImageEvidencePackBuilder {
    private final ObjectStorage storage;

    private final PublishedAssetReader publishedAssets;

    private final ImageCodec codec;

    private final ObjectMapper mapper;

    private final Clock clock;

    public ImageEvidencePackBuilder(ObjectStorage storage, PublishedAssetReader publishedAssets,
            ImageCodec codec, ObjectMapper mapper) {
        this(storage, publishedAssets, codec, mapper, Clock.systemUTC());
    }

    public ImageEvidencePackBuilder(ObjectStorage storage, PublishedAssetReader publishedAssets,
            ImageCodec codec, ObjectMapper mapper, Clock clock) {
        this.storage = storage;
        this.publishedAssets = publishedAssets;
        this.codec = codec;
        this.mapper = mapper;
        this.clock = clock;
    }

    public EvidencePack build(ImageResultSubject subject) {
        com.pixflow.module.task.api.publication.PublishedAssetReader.PublishedAssetContent published;
        try {
            published = publishedAssets.require(subject.referenceKey());
        } catch (RuntimeException error) {
            return unavailable(subject, EvidenceFailureKind.NON_REPLAYABLE, "PUBLISHED_ASSET_UNAVAILABLE");
        }
        if (published.imageId() != subject.generatedImageId()) {
            return unavailable(subject, EvidenceFailureKind.INVALID_IDENTITY, "PUBLISHED_ASSET_MISMATCH");
        }
        byte[] bytes;
        try {
            bytes = storage.getBytes(published.location());
        } catch (RuntimeException error) {
            return unavailable(subject, EvidenceFailureKind.TRANSIENT_DEPENDENCY, "STORAGE_READ_FAILED");
        }
        com.pixflow.infra.image.ImageProbe probe;
        try {
            probe = codec.probe(new ByteArrayInputStream(bytes));
        } catch (RuntimeException error) {
            return unavailable(subject, EvidenceFailureKind.INVALID_CONTENT, "IMAGE_PROBE_FAILED");
        }
        try {
            Map<String, Object> metadata = new java.util.TreeMap<>();
            metadata.put("format", probe.format().name());
            metadata.put("hasAlpha", probe.hasAlpha());
            metadata.put("height", probe.height());
            metadata.put("size", bytes.length);
            metadata.put("width", probe.width());
            Instant capturedAt = clock.instant();
            String sourceRef = subject.referenceKey();
            var image = new EvidenceEntry("E1", EvidenceType.OUTPUT_IMAGE, sourceRef,
                    EvidenceHashing.sha256(bytes), capturedAt, Map.of("size", bytes.length));
            byte[] canonical = mapper.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
            var meta = new EvidenceEntry("E2", EvidenceType.IMAGE_METADATA, sourceRef,
                    EvidenceHashing.sha256(canonical), capturedAt, metadata);
            List<EvidenceEntry> entries = List.of(image, meta);
            return EvidencePack.create(subject.snapshotHash(), entries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return unavailable(subject, EvidenceFailureKind.INTERNAL, "METADATA_SERIALIZATION_FAILED");
        }
    }

    private static EvidencePack unavailable(
            ImageResultSubject subject, EvidenceFailureKind kind, String code) {
        return EvidencePack.unavailable(subject.snapshotHash(), new EvidenceFailure(kind, code));
    }
}
