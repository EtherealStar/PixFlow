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
import static com.pixflow.module.rubrics.subject.ImageSubjectSnapshotResolver.sha256;

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
        try {
            var published = publishedAssets.require(subject.referenceKey());
            if (published.imageId() != subject.generatedImageId()) {
                throw new IllegalStateException("published image identity mismatch");
            }
            byte[] bytes = storage.getBytes(published.location());
            var probe = codec.probe(new ByteArrayInputStream(bytes));
            Map<String, Object> metadata = new java.util.TreeMap<>();
            metadata.put("format", probe.format().name());
            metadata.put("hasAlpha", probe.hasAlpha());
            metadata.put("height", probe.height());
            metadata.put("size", bytes.length);
            metadata.put("width", probe.width());
            Instant capturedAt = clock.instant();
            String sourceRef = subject.referenceKey();
            var image = new EvidenceEntry("E1", EvidenceType.OUTPUT_IMAGE, sourceRef,
                    sha256(bytes), capturedAt, Map.of("size", bytes.length));
            byte[] canonical = mapper.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
            var meta = new EvidenceEntry("E2", EvidenceType.IMAGE_METADATA, sourceRef,
                    sha256(canonical), capturedAt, metadata);
            List<EvidenceEntry> entries = List.of(image, meta);
            String packIdentity = entries.stream().map(entry -> entry.id() + "|" + entry.type() + "|"
                    + entry.sourceRef() + "|" + entry.contentHash()).collect(java.util.stream.Collectors.joining("\n"));
            return new EvidencePack(sha256(packIdentity.getBytes(StandardCharsets.UTF_8)), entries);
        } catch (Exception e) {
            String failure = e.getClass().getSimpleName();
            return new EvidencePack(sha256((subject.snapshotHash() + "|unavailable|" + failure)
                    .getBytes(StandardCharsets.UTF_8)), List.of(), failure);
        }
    }
}
