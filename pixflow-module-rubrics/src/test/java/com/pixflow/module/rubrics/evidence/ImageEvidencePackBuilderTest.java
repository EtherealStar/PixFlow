package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageEvidencePackBuilderTest {

    @Test
    void distinguishesIdentityMismatchFromTransientStorageFailure() {
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        when(assets.require("IMAGE:2:10")).thenReturn(
                content(11, new byte[10]));
        ImageEvidencePackBuilder builder = builder(assets, mock(ImageCodec.class));

        EvidencePack mismatch = builder.build(subject());

        assertThat(mismatch.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_IDENTITY);

        when(assets.require("IMAGE:2:10")).thenReturn(
                failingContent(10));

        EvidencePack unavailable = builder.build(subject());

        assertThat(unavailable.failure().kind()).isEqualTo(EvidenceFailureKind.TRANSIENT_DEPENDENCY);
        assertThat(unavailable.hash()).isNotEqualTo(mismatch.hash());
    }

    @Test
    void publishedAssetMissingIsNonReplayable() {
        // 对象已被删除或发布资产不可读：属于不可回放，不能伪装成质量失败。
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        when(assets.require("IMAGE:2:10")).thenThrow(new IllegalStateException("asset not found"));

        EvidencePack pack = builder(assets, mock(ImageCodec.class)).build(subject());

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.NON_REPLAYABLE);
        assertThat(pack.failure().code()).isEqualTo("PUBLISHED_ASSET_UNAVAILABLE");
        assertThat(pack.entries()).isEmpty();
    }

    @Test
    void corruptedImageBytesAreInvalidContent() {
        // 字节存在但无法 probe（损坏图片）：独立于对象缺失与存储故障的失败语义。
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        when(assets.require("IMAGE:2:10")).thenReturn(
                content(10, "bad-image!".getBytes(StandardCharsets.UTF_8)));
        ImageCodec codec = mock(ImageCodec.class);
        when(codec.probe(any(InputStream.class))).thenThrow(new IllegalStateException("decode failed"));

        EvidencePack pack = builder(assets, codec).build(subject());

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_CONTENT);
        assertThat(pack.failure().code()).isEqualTo("IMAGE_PROBE_FAILED");
    }

    @Test
    void pixelBudgetRejectionHasAnIndependentFailureCode() {
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        when(assets.require("IMAGE:2:10")).thenReturn(content(10, new byte[10]));
        ImageCodec codec = mock(ImageCodec.class);
        when(codec.probe(any(InputStream.class))).thenThrow(new ImageProcessingException(
                ImageProcessingException.Reason.SOURCE_TOO_LARGE,
                ImageFormat.PNG, 10_000, 10_000, "too large"));

        EvidencePack pack = builder(assets, codec).build(subject());

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_CONTENT);
        assertThat(pack.failure().code()).isEqualTo("IMAGE_PIXEL_BUDGET_REJECTED");
    }

    @Test
    void exposesTheFrozenProducerIdentityForSelfJudgedDetection() {
        assertThat(subject().productionModel()).get().satisfies(identity -> {
            assertThat(identity.provider()).isEqualTo("provider");
            assertThat(identity.model()).isEqualTo("model");
        });
    }

    @Test
    void replacedBytesWithTheSamePublishedIdentityAreRejected() {
        // imageId 与长度都相同仍不足以证明对象未变；必须核对 File 冻结的内容哈希。
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        byte[] original = "png-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] replaced = "bad-bytes!".getBytes(StandardCharsets.UTF_8);
        when(assets.require("IMAGE:2:10")).thenReturn(
                content(10, replaced, EvidenceHashing.sha256(original)));

        EvidencePack pack = builder(assets, mock(ImageCodec.class)).build(subject());

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_IDENTITY);
        assertThat(pack.failure().code()).isEqualTo("PUBLISHED_ASSET_CONTENT_MISMATCH");
    }

    @Test
    void buildsOutputImageAndMetadataEntriesWithStableIdentity() {
        // 正常路径：OUTPUT_IMAGE + IMAGE_METADATA 两条证据，content hash 基于实际字节与 canonical metadata，
        // 不使用 referenceKey/ETag/文件名替代内容身份；重放相同字节得到相同 pack hash。
        byte[] bytes = "png-bytes!".getBytes(StandardCharsets.UTF_8);
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        when(assets.require("IMAGE:2:10")).thenReturn(
                content(10, bytes));
        ImageCodec codec = mock(ImageCodec.class);
        when(codec.probe(any(InputStream.class)))
                .thenReturn(new ImageProbe(ImageFormat.PNG, 1024, 1024, false));

        ImageEvidencePackBuilder builder = builder(assets, codec);

        EvidencePack first = builder.build(subject());
        EvidencePack second = builder.build(subject());

        assertThat(first.failure()).isNull();
        assertThat(first.view(Set.of(EvidenceType.OUTPUT_IMAGE)))
                .singleElement()
                .satisfies(image -> {
                    assertThat(image.type()).isEqualTo(EvidenceType.OUTPUT_IMAGE);
                    assertThat(image.sourceRef()).isEqualTo("IMAGE:2:10");
                    // OUTPUT_IMAGE 的 content hash 必须是实际字节的哈希，而非 referenceKey 或 ETag。
                    assertThat(image.contentHash())
                            .isEqualTo(EvidenceHashing.sha256(bytes));
                    assertThat(image.metadata()).containsEntry("size", bytes.length);
                });
        assertThat(first.view(Set.of(EvidenceType.IMAGE_METADATA)))
                .singleElement()
                .satisfies(meta -> {
                    assertThat(meta.type()).isEqualTo(EvidenceType.IMAGE_METADATA);
                    assertThat(meta.metadata()).containsEntry("format", "PNG");
                    assertThat(meta.metadata()).containsEntry("width", 1024);
                    assertThat(meta.metadata()).containsEntry("height", 1024);
                });
        // 相同字节重放产生稳定 identity。
        assertThat(second.hash()).isEqualTo(first.hash());
    }

    private static ImageEvidencePackBuilder builder(PublishedAssetReader assets,
            ImageCodec codec) {
        return new ImageEvidencePackBuilder(
                assets, codec, new ObjectMapper(), Clock.systemUTC());
    }

    private static PublishedAssetReader.PublishedAssetContent content(long imageId, byte[] bytes) {
        return content(imageId, bytes, EvidenceHashing.sha256(bytes));
    }

    private static PublishedAssetReader.PublishedAssetContent content(
            long imageId, byte[] bytes, String contentHash) {
        return new PublishedAssetReader.PublishedAssetContent(
                imageId, "image/png", contentHash, bytes.length,
                new PublishedAssetReader.ContentAccess() {
                    @Override
                    public InputStream open() {
                        return new ByteArrayInputStream(bytes);
                    }

                    @Override
                    public java.net.URL presign(java.time.Duration ttl) {
                        return null;
                    }
                });
    }

    private static PublishedAssetReader.PublishedAssetContent failingContent(long imageId) {
        return new PublishedAssetReader.PublishedAssetContent(
                imageId, "image/png", EvidenceHashing.sha256(new byte[10]), 10,
                new PublishedAssetReader.ContentAccess() {
                    @Override
                    public InputStream open() {
                        throw new IllegalStateException("storage unavailable");
                    }

                    @Override
                    public java.net.URL presign(java.time.Duration ttl) {
                        return null;
                    }
                });
    }

    private static ImageResultSubject subject() {
        return new ImageResultSubject(
                "1", 2, "sku", "STANDARD", "image", null, null, "branch",
                10, "IMAGE:2:10", 10, "provider", "model", "snapshot");
    }
}
