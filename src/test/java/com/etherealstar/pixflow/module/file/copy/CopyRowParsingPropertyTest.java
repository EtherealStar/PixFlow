package com.etherealstar.pixflow.module.file.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.file.config.AssetProperties;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 文案行解析与空 id 跳过属性测试（任务 4.4）。
 *
 * <p>Feature: pixflow, Property 10: For any 合法表头的文案文档数据行集合，{@code id} 单元格为空的行
 * 应被跳过并记录行号，其余每行应将 {@code id/product_name/keywords/description} 写入 {@code asset_copy}
 * （缺失的可选列写空且不报错，以 {@code id} 作为 {@code sku_id}）。
 * Validates: Requirements 3.6, 3.7
 */
class CopyRowParsingPropertyTest {

    private static final List<String> HEADER =
            List.of("id", "product_name", "keywords", "description");

    private static final CopyDocumentParser PARSER = new CopyDocumentParser(new AssetProperties());

    /** 单元格取值池：含空串、纯空白、需 trim 的值与普通值。 */
    @Provide
    Arbitrary<String> cells() {
        return Arbitraries.of("", " ", "   ", "abc", "  x  ", "名称", "kw1", "d-1");
    }

    @Provide
    Arbitrary<List<List<String>>> rowSets() {
        Arbitrary<List<String>> oneRow = cells().list().ofMinSize(4).ofMaxSize(4);
        return oneRow.list().ofMinSize(0).ofMaxSize(40);
    }

    /** 复刻被测实现的取值语义：trim 后空白返回 null。 */
    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    @Property(tries = 300)
    void emptyIdRowsSkippedOthersParsed(
            @ForAll("rowSets") List<List<String>> dataRows) {
        List<List<String>> matrix = new ArrayList<>();
        matrix.add(HEADER);
        matrix.addAll(dataRows);

        // Oracle
        List<CopyRow> expectedRows = new ArrayList<>();
        List<Integer> expectedSkipped = new ArrayList<>();
        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            int lineNumber = i + 2; // 表头为第 1 行，数据行从第 2 行开始
            String id = trimOrNull(row.get(0));
            if (id == null) {
                expectedSkipped.add(lineNumber);
            } else {
                expectedRows.add(new CopyRow(id,
                        trimOrNull(row.get(1)), trimOrNull(row.get(2)), trimOrNull(row.get(3))));
            }
        }

        CopyParseResult result = PARSER.parseMatrix(matrix);

        assertThat(result.rows()).isEqualTo(expectedRows);
        assertThat(result.skippedRowNumbers()).isEqualTo(expectedSkipped);
        // 每条解析行以 id 作为 sku_id，且 id 非空
        for (CopyRow row : result.rows()) {
            assertThat(row.skuId()).isNotNull().isNotBlank();
        }
        // 解析行数 + 跳过行数 == 数据行总数
        assertThat(result.rows().size() + result.skippedRowNumbers().size())
                .isEqualTo(dataRows.size());
    }
}
