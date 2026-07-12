package com.pixflow.module.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.infra.storage.StorageException;
import com.pixflow.module.vision.analyze.AssessmentParser;
import com.pixflow.module.vision.analyze.VisionAnalysisRequest;
import com.pixflow.module.vision.analyze.VisionAnalysisRequestValidator;
import com.pixflow.module.vision.analyze.VisionAnalysisResult;
import com.pixflow.module.vision.analyze.VisionImageRef;
import com.pixflow.module.vision.analyze.VisionPromptBuilder;
import com.pixflow.module.vision.analyze.VisionTaskType;
import com.pixflow.module.vision.config.VisionProperties;
import com.pixflow.module.vision.error.VisionErrorCode;
import com.pixflow.module.vision.image.VisionImagePreprocessor;
import com.pixflow.module.vision.image.VisionImageResolver;
import com.pixflow.module.vision.metrics.VisionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultVisionServiceTest {

    @Test
    void analyzeSamplesMainFirstAndParsesResult() {
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < 10; i++) {
            storage.putBytes("1/images/" + i + ".png", new byte[] {1, 2, 3});
        }
        RecordingModelClient model = new RecordingModelClient(new ChatResult(
                "{\"composition\":\"clean\",\"sellingPoints\":[\"soft\"],\"confidence\":0.9}",
                List.of(),
                StopReason.STOP,
                new TokenUsage(1, 2, 3)));

        VisionAnalysisResult result = service(storage, model, new PassthroughPipeline()).analyze(new VisionAnalysisRequest(
                refs(10),
                "is it clear?",
                VisionTaskType.DESCRIBE,
                Map.of(),
                "c1",
                "t1"));

        assertThat(result.imagesSent()).isEqualTo(6);
        assertThat(result.parseDegraded()).isFalse();
        assertThat(result.assessment().composition()).isEqualTo("clean");
        assertThat(model.lastRequest.messages().get(1).parts()).hasSize(7);
    }

    @Test
    void invalidModelJsonDegradesWithoutThrowing() {
        FakeStorage storage = new FakeStorage();
        storage.putBytes("1/images/0.png", new byte[] {1});
        RecordingModelClient model = new RecordingModelClient(new ChatResult(
                "plain text",
                List.of(),
                StopReason.STOP,
                new TokenUsage(0, 0, 0)));

        VisionAnalysisResult result = service(storage, model, new PassthroughPipeline()).analyze(VisionAnalysisRequest.of(
                List.of(VisionImageRef.of(BucketType.PACKAGES, "1/images/0.png", "sku", "main", null)),
                "q",
                VisionTaskType.FREEFORM));

        assertThat(result.parseDegraded()).isTrue();
        assertThat(result.assessment().rawText()).isEqualTo("plain text");
    }

    @Test
    void allImagesSkippedThrowsNoDecodableImage() {
        FakeStorage storage = new FakeStorage();
        storage.failStat = true;

        assertThatThrownBy(() -> service(storage, new RecordingModelClient(null), new PassthroughPipeline()).analyze(
                VisionAnalysisRequest.of(
                        List.of(VisionImageRef.of(BucketType.PACKAGES, "1/images/0.png", "sku", "main", null)),
                        "q",
                        VisionTaskType.DESCRIBE)))
                .isInstanceOfSatisfying(PixFlowException.class,
                        ex -> assertThat(ex.code()).isEqualTo(VisionErrorCode.VISION_NO_DECODABLE_IMAGE));
    }

    @Test
    void errorPassthroughThrowsInfraAiExceptionUnchanged() {
        FakeStorage storage = new FakeStorage();
        storage.putBytes("1/images/0.png", new byte[] {1});
        PixFlowException original = new PixFlowException(AiErrorCode.MODEL_RATE_LIMITED, "rate limited")
                .withRetryAfter(Duration.ofSeconds(30));
        RecordingModelClient model = new RecordingModelClient(original);

        assertThatThrownBy(() -> service(storage, model, new PassthroughPipeline()).analyze(VisionAnalysisRequest.of(
                List.of(VisionImageRef.of(BucketType.PACKAGES, "1/images/0.png", "sku", "main", null)),
                "q",
                VisionTaskType.DESCRIBE)))
                .isSameAs(original);
    }

    private DefaultVisionService service(FakeStorage storage, RecordingModelClient model, ImagePipeline pipeline) {
        VisionProperties properties = new VisionProperties();
        return new DefaultVisionService(
                new VisionAnalysisRequestValidator(),
                new VisionImageResolver(storage),
                new VisionImagePreprocessor(pipeline, properties),
                new VisionPromptBuilder(),
                new AssessmentParser(new ObjectMapper()),
                model,
                properties,
                new VisionMetrics(new SimpleMeterRegistry()));
    }

    private List<VisionImageRef> refs(int count) {
        List<VisionImageRef> refs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String view = i == 7 ? "main" : "view-" + i;
            refs.add(VisionImageRef.of(BucketType.PACKAGES, "1/images/" + i + ".png", "sku", view, null));
        }
        return refs;
    }

    private static class RecordingModelClient implements VisionModelClient {
        final Object resultOrThrowable;
        com.pixflow.infra.ai.vision.VisionRequest lastRequest;

        RecordingModelClient(Object resultOrThrowable) {
            this.resultOrThrowable = resultOrThrowable;
        }

        @Override
        public ChatResult call(com.pixflow.infra.ai.vision.VisionRequest request) {
            lastRequest = request;
            if (resultOrThrowable instanceof RuntimeException ex) {
                throw ex;
            }
            return (ChatResult) resultOrThrowable;
        }
    }

    private static class PassthroughPipeline implements ImagePipeline {
        @Override
        public byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode) {
            return new byte[] {4, 5, 6};
        }

        @Override
        public byte[] runComposed(
                List<ReopenableImageSource> members,
                List<ImageOp> perMemberOps,
                com.pixflow.infra.image.op.MultiImageOp compose,
                List<ImageOp> postOps,
                EncodeSpec encode) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static class FakeStorage implements ObjectStorage {
        final Map<String, byte[]> data = new HashMap<>();
        boolean failStat;

        void putBytes(String key, byte[] bytes) {
            data.put(key, bytes);
        }

        @Override
        public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InputStream getStream(ObjectLocation loc) {
            return new ByteArrayInputStream(data.getOrDefault(loc.key(), new byte[] {1}));
        }

        @Override
        public byte[] getBytes(ObjectLocation loc) {
            return data.get(loc.key());
        }

        @Override
        public boolean exists(ObjectLocation loc) {
            return data.containsKey(loc.key());
        }

        @Override
        public StoredObjectMetadata stat(ObjectLocation loc) {
            if (failStat) {
                throw new StorageException("STAT", loc.bucket(), loc.key(), true, "failed", null);
            }
            byte[] bytes = data.getOrDefault(loc.key(), new byte[] {1});
            return new StoredObjectMetadata(bytes.length, "image/png", "etag", Instant.now());
        }

        @Override
        public void delete(ObjectLocation loc) {
        }

        @Override
        public void deleteByPrefix(BucketType bucket, String prefix) {
        }

        @Override
        public URL presignGet(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public URL presignPut(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
