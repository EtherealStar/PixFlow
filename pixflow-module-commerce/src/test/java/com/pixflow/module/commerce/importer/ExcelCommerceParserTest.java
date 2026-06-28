package com.pixflow.module.commerce.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.commerce.query.PeriodType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelCommerceParserTest {
    private final ExcelCommerceParser parser = new ExcelCommerceParser();

    @Test
    void parsesChineseHeadersAndDateCells() throws Exception {
        byte[] workbook = workbookBytes();
        ColumnMapping mapping = new ColumnMapping(parser.headers(new ByteArrayInputStream(workbook)), true);
        RawCommerceRow raw = parser.parse(new ByteArrayInputStream(workbook), mapping).getFirst();

        var data = new RowValidator(Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC))
                .normalize(raw, new ImportOptions("LOCAL_IMPORT", PeriodType.DAY, null, null, CategoryConflictPolicy.WARN));

        assertThat(data.getSkuId()).isEqualTo("SKU_XLSX");
        assertThat(data.getCategory()).isEqualTo("dress");
        assertThat(data.getCtr()).isEqualByComparingTo("0.125");
        assertThat(data.getPeriodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    void corruptedWorkbookFailsParsing() {
        assertThatThrownBy(() -> parser.headers(new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(IOException.class);
    }

    private static byte[] workbookBytes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("commerce");
            var header = sheet.createRow(0);
            String[] headers = {"商品sku", "类目", "曝光量", "点击率", "加购率", "购买率", "周期类型", "开始日期", "结束日期"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("SKU_XLSX");
            row.createCell(1).setCellValue("dress");
            row.createCell(2).setCellValue("200");
            row.createCell(3).setCellValue("12.5%");
            row.createCell(4).setCellValue("0.04");
            row.createCell(5).setCellValue("0.02");
            row.createCell(6).setCellValue("DAY");
            row.createCell(7).setCellValue("2026-06-01");
            row.createCell(8).setCellValue("2026-06-01");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
