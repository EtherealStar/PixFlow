package com.etherealstar.pixflow.module.file.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * 文案文档格式与体积校验属性测试（任务 4.3）。
 *
 * <p>Feature: pixflow, Property 9: For any 文案文档，当其后缀不属于 {@code .xls/.xlsx/.csv} 或体积
 * 超过配置上限或数据行数超过上限（默认 10000）时，应被拒绝并返回对应错误；同时当首行表头
 * （去首尾空白、不区分大小写匹配）不含 {@code id} 列时应被拒绝并返回缺少 {@code id} 列错误。
 * Validates: Requirements 3.2, 3.3, 3.4, 3.5
 */
class CopyDocumentValidationPropertyTest {

    private static CopyDocumentParser parser(AssetProperties props) {
        return new CopyDocumentParser(props);
    }

    private static CopyDocumentParser defaultParser() {
        return parser(new AssetProperties());
    }

    // ---- 后缀校验（需求 3.2）----

    @Provide
    Arbitrary<String> illegalExtensions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('A', 'Z')
                .ofMinLength(1).ofMaxLength(5)
                .filter(ext -> {
                    String e = ext.toLowerCase(Locale.ROOT);
                    return !e.equals("xls") && !e.equals("xlsx") && !e.equals("csv");
                });
    }

    @Property(tries = 300)
    void illegalExtensionIsRejected(@ForAll("illegalExtensions") String ext) {
        byte[] content = "id\n1\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> defaultParser().parse("doc." + ext, content))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOC_FORMAT_INVALID);
    }

    // ---- 体积校验（需求 3.2）----

    @Property(tries = 200)
    void sizeBeyondLimitIsRejected(@ForAll @IntRange(min = 101, max = 4096) int size) {
        AssetProperties props = new AssetProperties();
        props.setDocMaxSize(100);
        byte[] content = new byte[size];
        assertThatThrownBy(() -> parser(props).parse("doc.csv", content))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOC_FORMAT_INVALID);
    }

    // ---- 数据行数校验（需求 3.3）----

    @Property(tries = 200)
    void dataRowCountBeyondLimitIsRejected(@ForAll @IntRange(min = 0, max = 12) int dataRows) {
        int limit = 5;
        AssetProperties props = new AssetProperties();
        props.setDocMaxRows(limit);
        CopyDocumentParser parser = parser(props);

        List<List<String>> matrix = new ArrayList<>();
        matrix.add(List.of("id", "product_name"));
        for (int i = 0; i < dataRows; i++) {
            matrix.add(List.of("sku" + i, "name" + i));
        }

        if (dataRows > limit) {
            assertThatThrownBy(() -> parser.parseMatrix(matrix))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DOC_ROWS_EXCEEDED);
        } else {
            assertThatCode(() -> parser.parseMatrix(matrix)).doesNotThrowAnyException();
        }
    }

    // ---- 表头缺少 id 列（需求 3.4、3.5）----

    @Provide
    Arbitrary<List<String>> headersWithoutId() {
        Arbitrary<String> col = Arbitraries.of(
                "product_name", "keywords", "description", "name", "title", " sku ", "ID2", "");
        return col.list().ofMinSize(0).ofMaxSize(6);
    }

    @Property(tries = 200)
    void headerWithoutIdColumnIsRejected(
            @ForAll("headersWithoutId") List<String> header) {
        List<List<String>> matrix = new ArrayList<>();
        matrix.add(header);
        matrix.add(List.of("x", "y"));

        assertThatThrownBy(() -> defaultParser().parseMatrix(matrix))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOC_MISSING_ID_COLUMN);
    }

    @Test
    void headerWithIdVariantsIsAccepted() {
        // 去首尾空白 + 不区分大小写匹配
        List<List<String>> matrix = new ArrayList<>();
        matrix.add(List.of("  ID ", "Product_Name"));
        matrix.add(List.of("s1", "n1"));
        assertThatCode(() -> defaultParser().parseMatrix(matrix)).doesNotThrowAnyException();
    }
}
