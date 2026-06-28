package com.pixflow.module.file.copydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.file.config.FileProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelCopyDocParserTest {
    @Test
    void parsesChineseHeaders() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("copy");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品编号");
            header.createCell(1).setCellValue("标题");
            header.createCell(2).setCellValue("关键词");
            header.createCell(3).setCellValue("描述");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("SKU1");
            row.createCell(1).setCellValue("标题1");
            row.createCell(2).setCellValue("词1");
            row.createCell(3).setCellValue("描述1");
            workbook.write(out);
        }

        ExcelCopyDocParser parser = new ExcelCopyDocParser(new FileProperties());
        List<ParsedCopyRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).skuId()).isEqualTo("SKU1");
    }
}
