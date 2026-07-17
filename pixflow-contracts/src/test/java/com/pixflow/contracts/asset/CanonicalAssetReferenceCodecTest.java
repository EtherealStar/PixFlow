package com.pixflow.contracts.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CanonicalAssetReferenceCodecTest {

    private final CanonicalAssetReferenceCodec codec = new CanonicalAssetReferenceCodec();

    static List<AssetReferenceKey> references() {
        return List.of(
                new PackageAssetReferenceKey(1),
                new PackageAssetReferenceKey(Long.MAX_VALUE),
                new SkuAssetReferenceKey(2, "SKU-001._~"),
                new SkuAssetReferenceKey(3, "中文 空格/百分号%加号+"),
                new ImageAssetReferenceKey(4, 9),
                new ImageAssetReferenceKey(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    static List<String> invalidReferences() {
        return java.util.Arrays.asList(
                null, "", " ", "PACKAGE:1", "unknown:1", "package:",
                "package:0", "package:-1", "package:+1", "package:01",
                "package:9223372036854775808", "package:1/", "package:1/image:",
                "package:1/image:0", "package:1/image:01", "package:1/image:2/extra",
                "package:1/sku:", "package:1/sku: ", "package:1/sku:a/b",
                "package:1/sku:a+z", "package:1/sku:%", "package:1/sku:%2",
                "package:1/sku:%GG", "package:1/sku:%2f", "package:1/sku:%41",
                "package:1/sku:%C0%AF", "package:1/sku:%ED%A0%80",
                "package:1/sku:%F4%90%80%80", "package:1//image:2");
    }

    @ParameterizedTest
    @MethodSource("references")
    @DisplayName("所有可构造 key 都能 canonical round-trip")
    void roundTripsEveryConstructibleKey(AssetReferenceKey reference) {
        String serialized = codec.serialize(reference);

        assertEquals(reference, codec.parse(serialized));
        assertEquals(serialized, codec.serialize(codec.parse(serialized)));
    }

    @Test
    @DisplayName("SKU 使用 UTF-8 RFC 3986 百分号编码")
    void serializesSkuWithCanonicalUtf8PercentEncoding() {
        String serialized = codec.serialize(new SkuAssetReferenceKey(3, "中文 空格/百分号%加号+"));

        assertEquals(
                "package:3/sku:%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC%2F"
                        + "%E7%99%BE%E5%88%86%E5%8F%B7%25%E5%8A%A0%E5%8F%B7%2B",
                serialized);
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    @DisplayName("非法或非 canonical 文本被稳定拒绝且异常不回显输入")
    void rejectsInvalidOrNonCanonicalText(String referenceKey) {
        InvalidAssetReferenceException exception = assertThrows(
                InvalidAssetReferenceException.class, () -> codec.parse(referenceKey));

        assertEquals(
                new InvalidAssetReferenceException(exception.reason()).getMessage(),
                exception.getMessage());
    }

    @Test
    @DisplayName("值对象拒绝非法局部不变量")
    void rejectsInvalidValueObjectState() {
        assertThrows(IllegalArgumentException.class, () -> new PackageAssetReferenceKey(0));
        assertThrows(IllegalArgumentException.class, () -> new ImageAssetReferenceKey(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new SkuAssetReferenceKey(1, "  "));
        assertThrows(IllegalArgumentException.class, () -> new SkuAssetReferenceKey(1, "\uD800"));
    }

    @Test
    @DisplayName("共享 codec 可被多个线程安全复用")
    void isSafeForConcurrentReuse() throws Exception {
        AssetReferenceKey expected = new SkuAssetReferenceKey(7, "并发/SKU+1");
        Callable<AssetReferenceKey> task = () -> codec.parse(codec.serialize(expected));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<AssetReferenceKey>> tasks = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> task)
                    .toList();
            for (var result : executor.invokeAll(tasks)) {
                assertEquals(expected, result.get());
            }
        }
    }
}
