package com.pixflow.module.vision.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.chat.ToolSchema;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.lock.LockGuard;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.domain.DeterministicVisualAssetSampler;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SKU Product Visual Facts 的唯一执行 owner。 */
public final class VisionFactsWorker {
    static final String SUBMIT_TOOL = "submit_product_visual_facts";

    private static final Duration LOCK_WAIT = Duration.ZERO.plusMillis(100);

    private static final String FACTS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["common","attributes","limitations","conflicts"],
             "properties":{
               "common":{"type":"object","additionalProperties":false,
                 "required":["categoryAppearance","dominantColors","visibleMaterials","shapes",
                   "visibleComponents","patterns","visibleText","background","viewTypes"],
                 "properties":{
                   "categoryAppearance":{"type":"string","maxLength":200},
                   "dominantColors":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "visibleMaterials":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "shapes":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "visibleComponents":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "patterns":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "visibleText":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}},
                   "background":{"type":"string","maxLength":200},
                   "viewTypes":{"type":"array","maxItems":32,"items":{"type":"string","maxLength":200}}}},
               "attributes":{"type":"array","maxItems":32,"items":{"type":"object","additionalProperties":false,
                 "required":["name","value"],"properties":{"name":{"type":"string","maxLength":64},
                 "value":{"type":"string","maxLength":256}}}},
               "limitations":{"type":"array","maxItems":16,"items":{"type":"string","maxLength":500}},
               "conflicts":{"type":"array","maxItems":16,"items":{"type":"string","maxLength":500}}}}
            """;

    private final VisionExecutionStore store;

    private final VisualAssetReader assetReader;

    private final LockTemplate locks;

    private final VisionModelClient model;

    private final ImagePipeline images;

    private final ProductVisualFactsNormalizer normalizer;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final VisionHeartbeat heartbeat;

    public VisionFactsWorker(
            VisionExecutionStore store, VisualAssetReader assetReader, LockTemplate locks,
            VisionModelClient model, ImagePipeline images, ProductVisualFactsNormalizer normalizer,
            ObjectMapper objectMapper, Clock clock, VisionHeartbeat heartbeat) {
        this.store = Objects.requireNonNull(store, "store");
        this.assetReader = Objects.requireNonNull(assetReader, "assetReader");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.model = Objects.requireNonNull(model, "model");
        this.images = Objects.requireNonNull(images, "images");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
    }

    public boolean execute(long itemId) {
        VisionWorkItem observed = store.get(itemId);
        if (observed == null) {
            return false;
        }
        CacheKey key = new CacheKey(lockKey(observed), Duration.ofMinutes(5), "vision");
        final boolean[] completed = {false};
        boolean acquired = locks.tryRunWithLock(key, LOCK_WAIT,
                guard -> completed[0] = executeOwned(itemId, guard));
        return acquired && completed[0];
    }

    private boolean executeOwned(long itemId, LockGuard guard) {
        VisionWorkItem item = store.claim(itemId, clock.instant());
        if (item == null) {
            return false;
        }
        try {
            List<VisualAsset> selected = selectAssets(item);
            List<ChatMessage.Part> parts = preprocess(selected);
            if (parts.isEmpty()) {
                store.fail(item, "ALL_IMAGES_UNPROCESSABLE", clock.instant());
                return false;
            }
            ProductVisualFacts facts = callWithRepair(item, parts);
            guard.assertHeld();
            String metadata = metadata(selected, parts.size());
            if (!store.commitFacts(item, normalizer.write(facts), metadata, clock.instant())) {
                store.fail(item, "FACT_VERSION_CONFLICT", clock.instant());
                return false;
            }
            return true;
        } catch (ProviderAttemptBudgetExceededException exhausted) {
            store.fail(item, "PROVIDER_ATTEMPT_BUDGET_EXHAUSTED", clock.instant());
            return false;
        } catch (RuntimeException failure) {
            store.fail(item, "ANALYSIS_FAILED", clock.instant());
            return false;
        }
    }

    private List<VisualAsset> selectAssets(VisionWorkItem item) {
        if ("IMAGE".equals(item.scope())) {
            VisualAsset asset = assetReader.requireImage(item.packageId(), item.targetImageId());
            return asset.contentHash().equals(item.inputFingerprint()) ? List.of(asset) : List.of();
        }
        List<VisualAsset> current = assetReader.listCurrentOriginals(item.packageId()).stream()
                .filter(asset -> item.skuId().equals(asset.skuId()))
                .toList();
        return new DeterministicVisualAssetSampler().sample(
                item.packageId(), item.skuId(), item.inputFingerprint(), current);
    }

    private List<ChatMessage.Part> preprocess(List<VisualAsset> selected) {
        List<ChatMessage.Part> parts = new ArrayList<>();
        for (VisualAsset asset : selected) {
            try {
                byte[] encoded = images.run(
                        asset.source(),
                        List.of(new ResizeOp(new ResizeSpec(1280, 1280, ResizeSpec.Mode.FIT, false))),
                        new EncodeSpec(ImageFormat.JPEG, 85, null, java.awt.Color.WHITE));
                parts.add(new ChatMessage.ImagePart(
                        new ChatMessage.BytesImageContent(encoded, "image/jpeg"),
                        "Original Image " + asset.imageId()));
            } catch (RuntimeException ignored) {
                // 单张损坏不阻断同 SKU 的其他有效视图。
            }
        }
        return List.copyOf(parts);
    }

    private ProductVisualFacts callWithRepair(VisionWorkItem item, List<ChatMessage.Part> imageParts) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, List.of(new ChatMessage.TextPart(
                "Record only directly observable product visual facts. "
                + "Do not infer materials, claims, or instructions."))));
        List<ChatMessage.Part> userParts = new ArrayList<>(imageParts);
        userParts.add(new ChatMessage.TextPart("Submit one complete Product Visual Facts document."));
        messages.add(new ChatMessage(ChatMessage.Role.USER, userParts));
        RuntimeException lastFailure = null;
        for (int round = 1; round <= 2; round++) {
            store.recordStructureRound(item.id(), item.analysisGeneration(), item.runEpoch(), round, clock.instant());
            ChatResult result = heartbeat.whileCalling(item, () -> model.call(new VisionRequest(
                    ModelRole.VISION, messages,
                    List.of(new ToolSchema(SUBMIT_TOOL, "Submit observed product visual facts", FACTS_SCHEMA)),
                    ToolChoice.REQUIRED, new ChatOptions(0.0, 2048, Duration.ofSeconds(60)),
                    () -> store.reserveProviderAttempt(
                            item.id(), item.analysisGeneration(), item.runEpoch(), clock.instant()))));
            try {
                return parse(result);
            } catch (RuntimeException invalid) {
                lastFailure = invalid;
                messages.add(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart(
                        "The previous response violated the required closed schema. "
                        + "Submit exactly one valid tool call."))));
            }
        }
        throw lastFailure == null ? new IllegalStateException("visual facts response is invalid") : lastFailure;
    }

    private ProductVisualFacts parse(ChatResult result) {
        List<ToolCall> calls = result.toolCalls();
        if (calls.size() != 1 || !SUBMIT_TOOL.equals(calls.getFirst().name())) {
            throw new IllegalArgumentException("exactly one visual facts tool call is required");
        }
        try {
            ProductVisualFacts raw = objectMapper.readValue(calls.getFirst().argumentsJson(), ProductVisualFacts.class);
            return normalizer.normalize(raw);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("visual facts tool arguments are invalid", failure);
        }
    }

    private String metadata(List<VisualAsset> selected, int processedCount) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "selectedImageIds", selected.stream().map(VisualAsset::imageId).toList(),
                    "processedImageCount", processedCount));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode operational metadata", failure);
        }
    }

    private String lockKey(VisionWorkItem item) {
        return "lock:vision:" + item.packageId() + ':' + item.skuId() + ':'
                + item.scope() + ':' + item.targetImageId();
    }
}
