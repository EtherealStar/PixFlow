package com.pixflow.module.vision.enrich;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;
import com.pixflow.module.vision.metrics.VisionMetrics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class CopyEnrichmentConsumer implements ManagedMessageHandler<CopyEnrichmentMessage> {
    private final AssetImageReadMapper imageReadMapper;
    private final AssetCopyWriteMapper copyWriteMapper;
    private final ProductCopyExtractor productCopyExtractor;
    private final CopyFillPolicy fillPolicy;
    private final VisionMetrics metrics;

    public CopyEnrichmentConsumer(
            AssetImageReadMapper imageReadMapper,
            AssetCopyWriteMapper copyWriteMapper,
            ProductCopyExtractor productCopyExtractor,
            CopyFillPolicy fillPolicy,
            VisionMetrics metrics) {
        this.imageReadMapper = Objects.requireNonNull(imageReadMapper, "imageReadMapper");
        this.copyWriteMapper = Objects.requireNonNull(copyWriteMapper, "copyWriteMapper");
        this.productCopyExtractor = Objects.requireNonNull(productCopyExtractor, "productCopyExtractor");
        this.fillPolicy = Objects.requireNonNull(fillPolicy, "fillPolicy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void handle(MessageEnvelope<CopyEnrichmentMessage> envelope) {
        long packageId = envelope.payload().packageId();
        Map<String, List<AssetImageRow>> bySku = imageReadMapper.findByPackageId(packageId).stream()
                .filter(row -> row.getSkuId() != null && !row.getSkuId().isBlank())
                .collect(Collectors.groupingBy(AssetImageRow::getSkuId, java.util.TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<AssetImageRow>> entry : bySku.entrySet()) {
            enrichSku(packageId, entry.getKey(), entry.getValue());
        }
    }

    private void enrichSku(long packageId, String skuId, List<AssetImageRow> rows) {
        AssetCopyRow existing = copyWriteMapper.find(packageId, skuId);
        FillDecision beforeExtract = fillPolicy.decide(existing, null);
        if (!beforeExtract.shouldExtract()) {
            metrics.recordEnrich("skipped");
            return;
        }
        try {
            ProductCopyDraft draft = productCopyExtractor.extract(packageId, skuId, rows);
            FillDecision decision = fillPolicy.decide(existing, draft);
            if (decision.shouldWrite()) {
                copyWriteMapper.upsertGapOnly(packageId, skuId, decision.mergedDraft());
                metrics.recordEnrich("filled");
            } else {
                metrics.recordEnrich("skipped");
            }
        } catch (RuntimeException ex) {
            metrics.recordEnrich("failed");
            // 单 SKU 失败隔离:其余 SKU 继续，整包消息仍可 ack。
        }
    }
}
