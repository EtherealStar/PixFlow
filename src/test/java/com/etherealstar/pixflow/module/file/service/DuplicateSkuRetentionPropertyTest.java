package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import com.etherealstar.pixflow.module.file.SkuExtractor;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
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
 * 重复 SKU 全量保留且路径唯一属性测试（任务 3.5）。
 *
 * <p>Feature: pixflow, Property 8: For any 含重复 SKU ID 文件名的素材包，{@code asset_image}
 * 记录数应等于成功识别图片数，且全部记录的 {@code id} 互不相同、{@code original_path} 互不相同。
 * Validates: Requirements 2.6
 */
class DuplicateSkuRetentionPropertyTest {

    /** 一个文件：基于给定 SKU 前缀构造，多个文件可共享同一 SKU。 */
    private record FileSpec(String sku, String folder, String ext) {
    }

    @Provide
    Arbitrary<List<FileSpec>> fileSets() {
        // 仅用少量 SKU，保证大量重复 SKU 出现
        Arbitrary<String> sku = Arbitraries.of("ABC", "X1", "Z", "sku001");
        Arbitrary<String> folder = Arbitraries.of("", "d1/", "d2/sub/", "g/");
        Arbitrary<String> ext = Arbitraries.of("jpg", "JPG", "png", "webp");
        Arbitrary<FileSpec> spec = Combinators.combine(sku, folder, ext).as(FileSpec::new);
        return spec.list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 200)
    void duplicateSkusAreFullyRetainedWithUniqueIdsAndPaths(
            @ForAll("fileSets") List<FileSpec> specs) {
        // 以序号保证 zip 内路径唯一，但多个文件可映射到同一 SKU
        Map<String, byte[]> zipEntries = new LinkedHashMap<>();
        List<String> expectedSkus = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            FileSpec s = specs.get(i);
            // 文件名形如 ABC-0.jpg：SKU 提取在 '-' 处终止，得到纯 SKU 前缀
            String path = s.folder() + s.sku() + "-" + i + "." + s.ext();
            zipEntries.put(path, new byte[] {7});
            expectedSkus.add(s.sku());
        }
        byte[] zip = InMemoryZips.zipOf(zipEntries);

        AssetServiceFixture f = new AssetServiceFixture(new AssetProperties(), content -> true);
        f.withZip(zip);
        MockMultipartFile upload = new MockMultipartFile("zip_file", "p.zip", "application/zip", zip);

        f.service.upload(upload, null);

        ArgumentCaptor<AssetImage> captor = ArgumentCaptor.forClass(AssetImage.class);
        verify(f.imageMapper, atLeast(0)).insert(captor.capture());
        List<AssetImage> inserted = captor.getAllValues();

        // 全部图片均被识别（白名单 + 可解码），记录数等于成功识别图片数
        assertThat(inserted).hasSize(zipEntries.size());

        List<Long> ids = inserted.stream().map(AssetImage::getId).toList();
        List<String> paths = inserted.stream().map(AssetImage::getOriginalPath).toList();
        List<String> skus = inserted.stream().map(AssetImage::getSkuId).toList();

        // id 互不相同
        assertThat(ids).doesNotContainNull().doesNotHaveDuplicates();
        // original_path 互不相同
        assertThat(paths).doesNotContainNull().doesNotHaveDuplicates();
        // 重复 SKU 全量保留：每条记录的 sku 来自其文件名前缀，且总数不因重复而减少
        assertThat(skus).containsExactlyInAnyOrderElementsOf(expectedSkus);
        for (AssetImage image : inserted) {
            String expectedSku = SkuExtractor.extract(baseNameOf(image.getOriginalPath()));
            assertThat(image.getSkuId()).isEqualTo(expectedSku);
        }
    }

    private static String baseNameOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
