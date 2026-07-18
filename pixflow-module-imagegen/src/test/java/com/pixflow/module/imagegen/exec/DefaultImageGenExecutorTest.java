package com.pixflow.module.imagegen.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.imagegen.ImageGenClient;
import com.pixflow.infra.ai.imagegen.ImageGenRequest;
import com.pixflow.infra.ai.imagegen.ImageGenResult;
import com.pixflow.infra.ai.imagegen.ImageProducer;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageException;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DefaultImageGenExecutor 单测(对齐 imagegen.md §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常路径:源图字节 → ai → 落 GENERATED 桶 → 返回 GeneratedArtifact</li>
 *   <li>源图字节超过 max-read-bytes → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,不调 getStream</li>
 *   <li>生成图字节超过 max-output-bytes → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,不调 put</li>
 *   <li>ai 抛 PixFlowException(MODEL_RATE_LIMITED) 原样上抛</li>
 *   <li>ai 抛 PixFlowException(MODEL_PROVIDER_ERROR) 细化为 IMAGEGEN_CONTENT_POLICY_VIOLATION</li>
 *   <li>objectStorage.put 失败 → IMAGEGEN_STORAGE_WRITE_FAILED</li>
 * </ul>
 */
class DefaultImageGenExecutorTest {

    private ImageGenClient imageGenClient;
    private ObjectStorage objectStorage;
    private ImagegenProperties properties;
    private DefaultImageGenExecutor executor;

    private static final String TASK_ID = "1";
    private static final String SKU_ID = "sku-1";
    private static final String IMAGE_ID = "123";
    private static final ObjectLocation SOURCE_LOC =
        new ObjectLocation(BucketType.PACKAGES, "pkg-1/images/" + IMAGE_ID + ".png");
    private static final byte[] SOURCE_BYTES = new byte[]{1, 2, 3, 4, 5};

    @BeforeEach
    void setUp() {
        imageGenClient = mock(ImageGenClient.class);
        objectStorage = mock(ObjectStorage.class);
        properties = new ImagegenProperties();
        executor = new DefaultImageGenExecutor(imageGenClient, objectStorage, properties);

        // 默认 stat 行为:正常大小、image/png
        when(objectStorage.stat(SOURCE_LOC)).thenReturn(
            new StoredObjectMetadata(SOURCE_BYTES.length, "image/png", "etag-1", Instant.now()));
        // 每次调用返回新流(避免 readAllBytes 把同一流读完)
        when(objectStorage.getStream(SOURCE_LOC)).thenAnswer(
            inv -> new ByteArrayInputStream(SOURCE_BYTES));
    }

    private GenerativeUnitSpec spec() {
        return new GenerativeUnitSpec(
            TASK_ID, "unit-hash-1", 3, SKU_ID, IMAGE_ID, SOURCE_LOC,
            "用 A 风格重绘", Map.of("style", "A"), "png");
    }

    @Test
    @DisplayName("正常路径:源图字节 → ai → 落 GENERATED 桶 → 返回 GeneratedArtifact")
    void redraw_happyPath() {
        byte[] generated = new byte[1024];
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenReturn(
            new ImageGenResult(generated, "image/png", new TokenUsage(10L, 0L, 10L), producer()));
        when(objectStorage.put(any(ObjectLocation.class), any(InputStream.class),
            anyLong(), anyString())).thenAnswer(inv -> {
                ObjectLocation loc = inv.getArgument(0);
                long size = inv.getArgument(2);
                return new ObjectRef(loc.bucket(), loc.key(), size, "etag-out");
            });

        GeneratedArtifact artifact = executor.redraw(spec());

        // 桶: GENERATED
        assertThat(artifact.output().bucket()).isEqualTo(BucketType.GENERATED);
        assertThat(artifact.output().key())
            .isEqualTo("results/1/units/unit-hash-1/epochs/3/output.png");
        // size 与 generated.length 一致
        assertThat(artifact.output().size()).isEqualTo(generated.length);
        // contentType 透传
        assertThat(artifact.contentType()).isEqualTo("image/png");
        // usage 透传
        assertThat(artifact.usage().totalTokens()).isEqualTo(10L);

        // 验证 ai 收到的 sourceImage 是源图字节、sourceContentType 是 image/png
        ArgumentCaptor<ImageGenRequest> captor = ArgumentCaptor.forClass(ImageGenRequest.class);
        verify(imageGenClient).generate(captor.capture());
        assertThat(captor.getValue().sourceImage()).isEqualTo(SOURCE_BYTES);
        assertThat(captor.getValue().sourceContentType()).isEqualTo("image/png");
        assertThat(captor.getValue().prompt()).isEqualTo("用 A 风格重绘");
    }

    @Test
    @DisplayName("源图字节超过 max-read-bytes → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,不调 getStream")
    void redraw_sourceTooLarge_throws() {
        // 50 MiB + 1 byte
        long oversize = properties.getSource().getMaxReadBytes() + 1;
        when(objectStorage.stat(SOURCE_LOC))
            .thenReturn(new StoredObjectMetadata(oversize, "image/png", "etag-big", Instant.now()));

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE);

        verify(objectStorage, never()).getStream(any(ObjectLocation.class));
        verify(imageGenClient, never()).generate(any(ImageGenRequest.class));
    }

    @Test
    @DisplayName("生成图字节超过 max-output-bytes → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,不调 put")
    void redraw_outputTooLarge_throws_andDoesNotCallPut() {
        byte[] oversized = new byte[(int) (properties.getOutput().getMaxOutputBytes() + 1)];
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenReturn(
            new ImageGenResult(oversized, "image/png", new TokenUsage(0L, 0L, 0L), producer()));

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE);

        verify(objectStorage, never()).put(any(), any(InputStream.class), anyLong(), any());
    }

    @Test
    @DisplayName("ai 抛 PixFlowException(MODEL_RATE_LIMITED) 原样上抛,不吞不重试")
    void redraw_aiException_propagatesUnchanged() {
        PixFlowException aiException = new PixFlowException(AiErrorCode.MODEL_RATE_LIMITED, "rate limited");
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenThrow(aiException);

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .isSameAs(aiException); // 原样上抛,不是被 imagegen 包装过
    }

    @Test
    @DisplayName("ai 抛 PixFlowException(MODEL_PROVIDER_ERROR) 细化为 IMAGEGEN_CONTENT_POLICY_VIOLATION")
    void redraw_aiProviderError_mapsToContentPolicyViolation() {
        PixFlowException providerError = new PixFlowException(AiErrorCode.MODEL_PROVIDER_ERROR, "content refused");
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenThrow(providerError);

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_CONTENT_POLICY_VIOLATION);
    }

    @Test
    @DisplayName("objectStorage.put 失败 → IMAGEGEN_STORAGE_WRITE_FAILED")
    void redraw_putFailure_throwsImagegenStorageWriteFailed() {
        byte[] generated = new byte[1024];
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenReturn(
            new ImageGenResult(generated, "image/png", new TokenUsage(0L, 0L, 0L), producer()));
        when(objectStorage.put(any(), any(InputStream.class), anyLong(), anyString()))
            .thenThrow(new StorageException("put", BucketType.GENERATED, "key", false, "io error", null));

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED);
    }

    @Test
    @DisplayName("源图 stat 抛 StorageException → IMAGEGEN_STORAGE_WRITE_FAILED(归 storage 类)")
    void redraw_statFailure_throwsImagegenStorageWriteFailed() {
        when(objectStorage.stat(SOURCE_LOC))
            .thenThrow(new StorageException("stat", BucketType.PACKAGES, "key", true, "not found", null));

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED);
    }

    @Test
    @DisplayName("源图 getStream 抛 StorageException → IMAGEGEN_STORAGE_WRITE_FAILED")
    void redraw_getStreamFailure_throwsImagegenStorageWriteFailed() {
        when(objectStorage.getStream(SOURCE_LOC))
            .thenThrow(new StorageException("get", BucketType.PACKAGES, "key", true, "io", null));

        assertThatThrownBy(() -> executor.redraw(spec()))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED);
    }

    @Test
    @DisplayName("Work Unit hash 与 epoch 生成稳定对象 key")
    void redraw_nonNumericImageId_fallsBackToShaLong_andKeyIsStable() {
        GenerativeUnitSpec nonNumericSpec = new GenerativeUnitSpec(
            TASK_ID, "unit-hash-2", 4, SKU_ID, "uuid-style-abc-xyz", SOURCE_LOC,
            "用 A 风格重绘", Map.of(), "png");
        byte[] generated = new byte[]{9, 9, 9};
        when(imageGenClient.generate(any(ImageGenRequest.class))).thenReturn(
            new ImageGenResult(generated, "image/png", new TokenUsage(0L, 0L, 0L), producer()));
        when(objectStorage.put(any(ObjectLocation.class), any(InputStream.class),
            anyLong(), anyString())).thenAnswer(inv -> {
                ObjectLocation loc = inv.getArgument(0);
                return new ObjectRef(loc.bucket(), loc.key(), 3L, "etag");
            });

        GeneratedArtifact a1 = executor.redraw(nonNumericSpec);
        GeneratedArtifact a2 = executor.redraw(nonNumericSpec);
        // 同样 imageId 两次跑,落桶 key 一致(幂等)
        assertThat(a1.output().key()).isEqualTo(a2.output().key());
        // key 仍以 task/sku 开头
        assertThat(a1.output().key())
            .isEqualTo("results/" + TASK_ID + "/units/unit-hash-2/epochs/4/output.png");
        assertThat(a1.output().key()).endsWith(".png");
    }

    private static ImageProducer producer() {
        return new ImageProducer("test-provider", "test-model");
    }
}
