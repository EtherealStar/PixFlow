package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import com.etherealstar.pixflow.module.file.SkuExtractor;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.dto.PackageUploadResponse;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.support.AssetServiceFixture;
import com.etherealstar.pixflow.module.file.support.InMemoryZips;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 图片入库路径与字段一致性属性测试（任务 3.4）。
 *
 * <p>Feature: pixflow, Property 7: For any 含嵌套子文件夹的 zip，每张成功识别图片入库的
 * {@code asset_image} 记录其 {@code original_path} 应等于该图片相对 zip 根目录的完整相对路径，
 * 且 {@code package_id}、{@code sku_id}、{@code original_path} 三字段均非空并与扫描结果一致。
 * Validates: Requirements 2.4, 2.5
 *
 * <p>图片解码以恒为可解码的 {@link com.etherealstar.pixflow.module.file.image.ImageDecoder} 替身建模，
 * 因此「成功识别图片」= 扩展名在白名单内的条目，无需真实图片资源。
 */
class ImagePersistencePropertyTest {

    private record Entry(String path, boolean whitelisted) {
    }

    @Provide
    Arbitrary<List<Entry>> entrySets() {
        Arbitrary<String> folder = Arbitraries.of("", "a/", "a/b/", "x/y/z/");
        Arbitrary<String> base = Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('0', '9')
                .ofMinLength(1).ofMaxLength(8);
        // 白名单与非白名单扩展名混合
        Arbitrary<String> ext = Arbitraries.of("jpg", "JPG", "png", "webp", "txt", "gif", "bin");
        Arbitrary<Entry> entry = Combinators.combine(folder, base, ext).as((f, b, e) -> {
            String path = f + b + "." + e;
            boolean wl = e.equalsIgnoreCase("jpg") || e.equalsIgnoreCase("jpeg")
                    || e.equalsIgnoreCase("png") || e.equalsIgnoreCase("webp");
            return new Entry(path, wl);
        });
        return entry.list().ofMinSize(1).ofMaxSize(20);
    }

    /** 为保证 zip 条目路径唯一，给每个条目附加序号前缀；返回去重后的条目列表与 zip 字节。 */
    private static Map<String, Entry> uniquePaths(List<Entry> raw) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            Entry e = raw.get(i);
            int dot = e.path().lastIndexOf('.');
            String withIndex = e.path().substring(0, dot) + "_" + i + e.path().substring(dot);
            result.put(withIndex, new Entry(withIndex, e.whitelisted()));
        }
        return result;
    }

    private static String baseNameOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    @Property(tries = 200)
    void recognizedImagesArePersistedWithConsistentFields(
            @ForAll("entrySets") List<Entry> raw) {
        Map<String, Entry> entries = uniquePaths(raw);

        Map<String, byte[]> zipEntries = new LinkedHashMap<>();
        List<String> expectedRecognized = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            zipEntries.put(e.getKey(), new byte[] {1, 2, 3});
            if (e.getValue().whitelisted()) {
                expectedRecognized.add(e.getKey());
            }
        }
        byte[] zip = InMemoryZips.zipOf(zipEntries);

        AssetServiceFixture f = new AssetServiceFixture(new AssetProperties(), content -> true);
        f.withZip(zip);
        MockMultipartFile upload = new MockMultipartFile("zip_file", "p.zip", "application/zip", zip);

        PackageUploadResponse response = f.service.upload(upload, null);

        ArgumentCaptor<AssetImage> captor = ArgumentCaptor.forClass(AssetImage.class);
        verify(f.imageMapper, atLeast(0)).insert(captor.capture());
        List<AssetImage> inserted = captor.getAllValues();

        // 入库记录数 == 成功识别图片数 == 响应中的 imageCount
        assertThat(inserted).hasSize(expectedRecognized.size());
        assertThat(response.imageCount()).isEqualTo(expectedRecognized.size());

        List<String> insertedPaths = new ArrayList<>();
        for (AssetImage image : inserted) {
            // 三字段均非空
            assertThat(image.getPackageId()).isNotNull();
            assertThat(image.getSkuId()).isNotNull();
            assertThat(image.getOriginalPath()).isNotNull();
            // original_path 等于该图片相对 zip 根目录的完整相对路径
            assertThat(entries).containsKey(image.getOriginalPath());
            // sku_id 与扫描时按文件名提取的结果一致
            assertThat(image.getSkuId())
                    .isEqualTo(SkuExtractor.extract(baseNameOf(image.getOriginalPath())));
            insertedPaths.add(image.getOriginalPath());
        }
        // 与扫描结果一致：入库路径集合恰为识别出的白名单图片集合
        assertThat(insertedPaths).containsExactlyInAnyOrderElementsOf(expectedRecognized);
    }
}
