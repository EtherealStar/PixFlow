package com.pixflow.module.commerce.importer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class ExcelCommerceParser implements CommerceFileParser {
    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String filename, String contentType) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    public List<String> headers(InputStream input) throws IOException {
        try (var workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                return List.of();
            }
            return headers(sheet.getRow(sheet.getFirstRowNum()));
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("failed to parse excel commerce headers", ex);
        }
    }

    @Override
    public List<RawCommerceRow> parse(InputStream input, ColumnMapping mapping) throws IOException {
        try (var workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return List.of();
            }
            List<String> headers = headers(sheet.getRow(sheet.getFirstRowNum()));
            List<RawCommerceRow> rows = new ArrayList<>();
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
            throw new IOException("failed to parse excel commerce data", ex);
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

    private RawCommerceRow toRow(Row row, List<String> headers, ColumnMapping mapping) {
        return new RawCommerceRow(
                row.getRowNum() + 1,
                value(row, headers, mapping.skuId()),
                value(row, headers, mapping.category()),
                value(row, headers, mapping.impressions()),
                value(row, headers, mapping.ctr()),
                value(row, headers, mapping.addCartRate()),
                value(row, headers, mapping.purchaseRate()),
                value(row, headers, mapping.periodType()),
                value(row, headers, mapping.periodStart()),
                value(row, headers, mapping.periodEnd()));
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
