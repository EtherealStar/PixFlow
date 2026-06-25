package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.entity.AssetCopy;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.support.AssetServiceFixture;
import com.etherealstar.pixflow.module.file.support.InMemoryZips;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * SKU 软关联宽松性属性测试（任务 4.5）。
 *
 * <p>Feature: pixflow, Property 11: For any 图片集合与文案条目集合，按 {@code sku_id} 绑定后，
 * SKU 在文案中无对应条目的图片仍保留且可处理，SKU 在图片中无对应图片的文案条目仍保留为合法状态
 * （无图无文案均不导致丢弃或报错）。
 * Validates: Requirements 3.9, 3.10
 */
class SkuSoftAssociationPropertyTest {

    private static final List<String> SKU_POOL = List.of("ABC", "X1", "Z", "Q9", "sku5", "M");

    @Provide
    Arbitrary<List<String>> skuLists() {
        return Arbitraries.of(SKU_POOL).list().ofMinSize(0).ofMaxSize(15);
    }

    private static byte[] buildCsv(List<String> copySkus) {
        StringBuilder sb = new StringBuilder("id,product_name,keywords,description\n");
        for (String sku : copySkus) {
            sb.append(sku).append(",name,kw,desc\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildZip(List<String> imageSkus) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < imageSkus.size(); i++) {
            entries.put(imageSkus.get(i) + "-" + i + ".jpg", new byte[] {1});
        }
        return InMemoryZips.zipOf(entries);
    }

    @Property(tries = 200)
    void imagesAndCopiesAreRetainedIndependentlyOfOverlap(
            @ForAll("skuLists") List<String> imageSkus,
            @ForAll("skuLists") List<String> copySkus) {
        byte[] zip = buildZip(imageSkus);
        byte[] csv = buildCsv(copySkus);

        AssetServiceFixture f = new AssetServiceFixture(new AssetProperties(), content -> true);
        f.withZip(zip);
        MockMultipartFile zipUpload =
                new MockMultipartFile("zip_file", "p.zip", "application/zip", zip);
        MockMultipartFile docUpload =
                new MockMultipartFile("doc_file", "copy.csv", "text/csv", csv);

        // 无论 sku 是否重叠 / 缺失，均不应抛错
        assertThatCode(() -> f.service.upload(zipUpload, docUpload)).doesNotThrowAnyException();

        ArgumentCaptor<AssetImage> imgCaptor = ArgumentCaptor.forClass(AssetImage.class);
        verify(f.imageMapper, atLeast(0)).insert(imgCaptor.capture());
        ArgumentCaptor<AssetCopy> copyCaptor = ArgumentCaptor.forClass(AssetCopy.class);
        verify(f.copyMapper, atLeast(0)).insert(copyCaptor.capture());

        // 图片全量保留（含 SKU 在文案中无对应条目的图片）
        assertThat(imgCaptor.getAllValues()).hasSize(imageSkus.size());
        // 文案条目全量保留（含 SKU 在图片中无对应图片的条目）
        assertThat(copyCaptor.getAllValues()).hasSize(copySkus.size());

        // 每条记录的 sku 来自各自来源，不因缺少对端而被改写或丢弃
        assertThat(copyCaptor.getAllValues().stream().map(AssetCopy::getSkuId).toList())
                .containsExactlyInAnyOrderElementsOf(copySkus);
    }
}
