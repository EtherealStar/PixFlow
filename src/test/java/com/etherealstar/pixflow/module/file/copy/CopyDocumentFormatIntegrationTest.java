package com.etherealstar.pixflow.module.file.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.file.config.AssetProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * POI 多格式解析集成测试（任务 4.6）。
 *
 * <p>针对 {@code .xls/.xlsx/.csv} 各给 1 个示例验证解析。示例文档均在内存中程序化生成
 * （{@code .xls} 用 HSSF、{@code .xlsx} 用 XSSF、{@code .csv} 用 UTF-8 文本），不依赖磁盘上的真实资源文件。
 * Validates: Requirements 3.1
 */
class CopyDocumentFormatIntegrationTest {

    private static final List<String> HEADER =
            List.of("id", "product_name", "keywords", "description");
    private static final String[][] DATA = {
            {"1001", "Widget", "kw1", "desc1"},
            {"1002", "Gadget", "", "desc2"}
    };

    private final CopyDocumentParser parser = new CopyDocumentParser(new AssetProperties());

    private static byte[] buildWorkbook(Workbook workbook) {
        try (workbook) {
            Sheet sheet = workbook.createSheet("copy");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADER.size(); c++) {
                header.createCell(c).setCellValue(HEADER.get(c));
            }
            for (int r = 0; r < DATA.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < DATA[r].length; c++) {
                    row.createCell(c).setCellValue(DATA[r][c]);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] buildCsv() {
        StringBuilder sb = new StringBuilder(String.join(",", HEADER)).append('\n');
        for (String[] row : DATA) {
            sb.append(String.join(",", row)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void assertParsedAsExpected(CopyParseResult result) {
        assertThat(result.skippedRowNumbers()).isEmpty();
        assertThat(result.rows()).hasSize(2);

        CopyRow first = result.rows().get(0);
        assertThat(first.skuId()).isEqualTo("1001");
        assertThat(first.productName()).isEqualTo("Widget");
        assertThat(first.keywords()).isEqualTo("kw1");
        assertThat(first.description()).isEqualTo("desc1");

        CopyRow second = result.rows().get(1);
        assertThat(second.skuId()).isEqualTo("1002");
        assertThat(second.productName()).isEqualTo("Gadget");
        // 缺失/空的可选列写空（null），不报错
        assertThat(second.keywords()).isNull();
        assertThat(second.description()).isEqualTo("desc2");
    }

    @Test
    void parsesXls() {
        byte[] bytes = buildWorkbook(new HSSFWorkbook());
        assertParsedAsExpected(parser.parse("sample.xls", bytes));
    }

    @Test
    void parsesXlsx() {
        byte[] bytes = buildWorkbook(new XSSFWorkbook());
        assertParsedAsExpected(parser.parse("sample.xlsx", bytes));
    }

    @Test
    void parsesCsv() {
        assertParsedAsExpected(parser.parse("sample.csv", buildCsv()));
    }
}
