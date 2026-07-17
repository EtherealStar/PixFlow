package com.pixflow.module.file.copydoc;

import com.pixflow.module.file.config.FileProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class ExcelCopyDocParser implements CopyDocParser {
    private final FileProperties properties;

    private final DataFormatter formatter = new DataFormatter();

    public ExcelCopyDocParser(FileProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public List<ParsedCopyRow> parse(InputStream inputStream) throws IOException {
        try (var workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return List.of();
            }
            Row header = sheet.getRow(sheet.getFirstRowNum());
            List<String> headers = headers(header);
            CopyDocColumnMapping mapping = new CopyDocColumnMapping(headers, properties.getCopydoc());
            List<ParsedCopyRow> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    rows.add(toRow(row, headers, mapping));
                }
            }
            return rows;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("failed to parse excel copy doc", ex);
        }
    }

    private List<String> headers(Row header) {
        List<String> headers = new ArrayList<>();
        if (header == null) {
            return headers;
        }
        for (int i = 0; i < header.getLastCellNum(); i++) {
            headers.add(formatter.formatCellValue(header.getCell(i)).trim());
        }
        return headers;
    }

    private ParsedCopyRow toRow(Row row, List<String> headers, CopyDocColumnMapping mapping) {
        return new ParsedCopyRow(
                value(row, headers, mapping.skuIdColumn().orElse(null)),
                value(row, headers, mapping.productNameColumn().orElse(null)),
                value(row, headers, mapping.keywordsColumn().orElse(null)),
                value(row, headers, mapping.descriptionColumn().orElse(null)),
                row.getRowNum() + 1);
    }

    private String value(Row row, List<String> headers, String column) {
        if (column == null) {
            return null;
        }
        int index = headers.indexOf(column);
        if (index < 0) {
            return null;
        }
        String value = formatter.formatCellValue(row.getCell(index));
        return value == null || value.isBlank() ? null : value.trim();
    }
}
