package com.pixflow.module.vision;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.storage.StorageException;
import com.pixflow.module.vision.analyze.AssessmentParser;
import com.pixflow.module.vision.analyze.VisionAnalysisRequest;
import com.pixflow.module.vision.analyze.VisionAnalysisRequestValidator;
import com.pixflow.module.vision.analyze.VisionAnalysisResult;
import com.pixflow.module.vision.analyze.VisionImageRef;
import com.pixflow.module.vision.analyze.VisionPromptBuilder;
import com.pixflow.module.vision.config.VisionProperties;
import com.pixflow.module.vision.error.VisionErrorCode;
import com.pixflow.module.vision.image.PreparedVisionImage;
import com.pixflow.module.vision.image.ResolvedVisionImage;
import com.pixflow.module.vision.image.VisionImagePreprocessor;
import com.pixflow.module.vision.image.VisionImageResolver;
import com.pixflow.module.vision.metrics.VisionMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class DefaultVisionService implements VisionService {
    private final VisionAnalysisRequestValidator validator;
    private final VisionImageResolver imageResolver;
    private final VisionImagePreprocessor imagePreprocessor;
    private final VisionPromptBuilder promptBuilder;
    private final AssessmentParser assessmentParser;
    private final VisionModelClient visionModelClient;
    private final VisionProperties properties;
    private final VisionMetrics metrics;

    public DefaultVisionService(
            VisionAnalysisRequestValidator validator,
            VisionImageResolver imageResolver,
            VisionImagePreprocessor imagePreprocessor,
            VisionPromptBuilder promptBuilder,
            AssessmentParser assessmentParser,
            VisionModelClient visionModelClient,
            VisionProperties properties,
            VisionMetrics metrics) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.imageResolver = Objects.requireNonNull(imageResolver, "imageResolver");
        this.imagePreprocessor = Objects.requireNonNull(imagePreprocessor, "imagePreprocessor");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.assessmentParser = Objects.requireNonNull(assessmentParser, "assessmentParser");
        this.visionModelClient = Objects.requireNonNull(visionModelClient, "visionModelClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        validator.validate(request);
        metrics.recordImages("received", request.images().size());

        List<VisionImageRef> sampled = sample(request.images());
        List<PreparedVisionImage> prepared = new ArrayList<>();
        List<String> skippedIssues = new ArrayList<>();

        for (VisionImageRef ref : sampled) {
            try {
                if (ref.sizeHintBytes() != null && ref.sizeHintBytes() > properties.getImage().getMaxImageBytes()) {
                    skippedIssues.add(issue(ref, "too_large"));
                    metrics.recordImages("skipped", 1);
                    continue;
                }
                ResolvedVisionImage resolved = imageResolver.resolve(ref);
                if (resolved.sizeBytes() > properties.getImage().getMaxImageBytes()) {
                    skippedIssues.add(issue(ref, "too_large"));
                    metrics.recordImages("skipped", 1);
                    closeQuietly(resolved);
                    continue;
                }
                prepared.add(imagePreprocessor.preprocess(resolved));
            } catch (StorageException ex) {
                skippedIssues.add(issue(ref, "resolve_failed"));
                metrics.recordImages("skipped", 1);
            } catch (ImageProcessingException | IllegalArgumentException ex) {
                skippedIssues.add(issue(ref, "decode_failed"));
                metrics.recordImages("skipped", 1);
            }
        }

        if (prepared.isEmpty()) {
            throw new PixFlowException(
                    VisionErrorCode.VISION_NO_DECODABLE_IMAGE,
                    "no decodable image for vision analysis",
                    null,
                    java.util.Map.of("skippedIssues", skippedIssues),
                    RecoveryHint.TERMINATE,
                    null,
                    request.traceId());
        }

        metrics.recordImages("sent", prepared.size());
        // 不捕获 PixFlowException:infra/ai 的错误码、category、retryAfter 必须原样透传给上层。
        ChatResult result = visionModelClient.call(promptBuilder.build(request, prepared));
        AssessmentParser.ParseOutcome outcome = assessmentParser.parse(result.finalText());
        metrics.recordAnalyze(request.taskType(), outcome.degraded());
        return new VisionAnalysisResult(
                outcome.assessment().withAdditionalIssues(skippedIssues),
                outcome.degraded(),
                result.usage(),
                prepared.size());
    }

    private List<VisionImageRef> sample(List<VisionImageRef> images) {
        int limit = Math.max(1, properties.getAnalyze().getImagesPerCall());
        List<VisionImageRef> ordered = new ArrayList<>(images);
        if (properties.getAnalyze().getSampling() == VisionProperties.Sampling.MAIN_FIRST) {
            ordered.sort(Comparator
                    .comparing((VisionImageRef ref) -> !isMain(ref))
                    .thenComparing(ref -> ref.viewId() == null ? "" : ref.viewId())
                    .thenComparing(ref -> ref.object().key()));
        }
        if (ordered.size() <= limit) {
            return ordered;
        }
        return List.copyOf(ordered.subList(0, limit));
    }

    private boolean isMain(VisionImageRef ref) {
        String viewId = ref.viewId();
        String label = ref.hintLabel();
        return "main".equalsIgnoreCase(viewId) || "main".equalsIgnoreCase(label) || "主图".equals(label);
    }

    private String issue(VisionImageRef ref, String code) {
        return code + ":bucket=" + ref.object().bucket() + ",key=" + ref.object().key();
    }

    private void closeQuietly(ResolvedVisionImage image) {
        try {
            image.stream().close();
        } catch (Exception ignored) {
            // skip path: stream close failure must not mask the original per-image skip reason
        }
    }
}
