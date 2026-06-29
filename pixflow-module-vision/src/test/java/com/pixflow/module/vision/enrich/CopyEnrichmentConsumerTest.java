package com.pixflow.module.vision.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.module.vision.config.VisionProperties;
import com.pixflow.module.vision.metrics.VisionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopyEnrichmentConsumerTest {

    @Test
    void fillsMissingCopyAndSkipsExistingCopy() {
        FakeImageMapper imageMapper = new FakeImageMapper();
        imageMapper.rows.add(row("A", "main", "1/images/a.png"));
        imageMapper.rows.add(row("B", "main", "1/images/b.png"));
        FakeCopyMapper copyMapper = new FakeCopyMapper();
        AssetCopyRow existing = new AssetCopyRow();
        existing.setSkuId("B");
        existing.setProductName("doc name");
        existing.setKeywords("doc kw");
        existing.setDescription("doc desc");
        copyMapper.existing.add(existing);

        CopyEnrichmentConsumer consumer = new CopyEnrichmentConsumer(
                imageMapper,
                copyMapper,
                new StubExtractor(),
                new CopyFillPolicy(new VisionProperties()),
                new VisionMetrics(new SimpleMeterRegistry()));

        consumer.handle(MessageEnvelope.current(new CopyEnrichmentMessage(1), Map.of()));

        assertThat(copyMapper.writes).hasSize(1);
        assertThat(copyMapper.writes.get(0).skuId()).isEqualTo("A");
    }

    @Test
    void singleSkuFailureDoesNotStopOtherSku() {
        FakeImageMapper imageMapper = new FakeImageMapper();
        imageMapper.rows.add(row("A", "main", "1/images/a.png"));
        imageMapper.rows.add(row("B", "main", "1/images/b.png"));
        FakeCopyMapper copyMapper = new FakeCopyMapper();

        CopyEnrichmentConsumer consumer = new CopyEnrichmentConsumer(
                imageMapper,
                copyMapper,
                new StubExtractor("A"),
                new CopyFillPolicy(new VisionProperties()),
                new VisionMetrics(new SimpleMeterRegistry()));

        consumer.handle(MessageEnvelope.current(new CopyEnrichmentMessage(1), Map.of()));

        assertThat(copyMapper.writes).hasSize(1);
        assertThat(copyMapper.writes.get(0).skuId()).isEqualTo("B");
    }

    private AssetImageRow row(String skuId, String viewId, String key) {
        AssetImageRow row = new AssetImageRow();
        row.setPackageId(1L);
        row.setSkuId(skuId);
        row.setViewId(viewId);
        row.setMinioKey(key);
        return row;
    }

    private static class FakeImageMapper implements AssetImageReadMapper {
        final List<AssetImageRow> rows = new ArrayList<>();

        @Override
        public List<AssetImageRow> findByPackageId(long packageId) {
            return rows;
        }
    }

    private static class FakeCopyMapper implements AssetCopyWriteMapper {
        final List<AssetCopyRow> existing = new ArrayList<>();
        final List<Write> writes = new ArrayList<>();

        @Override
        public AssetCopyRow find(long packageId, String skuId) {
            return existing.stream().filter(row -> skuId.equals(row.getSkuId())).findFirst().orElse(null);
        }

        @Override
        public int upsertGapOnly(long packageId, String skuId, ProductCopyDraft draft) {
            writes.add(new Write(packageId, skuId, draft));
            return 1;
        }
    }

    private record Write(long packageId, String skuId, ProductCopyDraft draft) {
    }

    private static class StubExtractor extends ProductCopyExtractor {
        private final String failSku;

        StubExtractor() {
            this(null);
        }

        StubExtractor(String failSku) {
            super(request -> {
                throw new UnsupportedOperationException("not used");
            }, new VisionProperties());
            this.failSku = failSku;
        }

        @Override
        public ProductCopyDraft extract(long packageId, String skuId, List<AssetImageRow> rows) {
            if (skuId.equals(failSku)) {
                throw new RuntimeException("failed");
            }
            return new ProductCopyDraft("name-" + skuId, "kw-" + skuId, "desc-" + skuId);
        }
    }
}
