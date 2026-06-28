package com.pixflow.module.commerce.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class CsvCommerceParser implements CommerceFileParser {
    @Override
    public boolean supports(String filename, String contentType) {
        return filename != null && filename.toLowerCase().endsWith(".csv");
    }

    public List<String> headers(InputStream input) throws IOException {
        CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        return parser.getHeaderNames();
    }

    @Override
    public List<RawCommerceRow> parse(InputStream input, ColumnMapping mapping) throws IOException {
        CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        List<RawCommerceRow> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows.add(new RawCommerceRow(
                    Math.toIntExact(record.getRecordNumber() + 1),
                    value(record, mapping.skuId()),
                    value(record, mapping.category()),
                    value(record, mapping.impressions()),
                    value(record, mapping.ctr()),
                    value(record, mapping.addCartRate()),
                    value(record, mapping.purchaseRate()),
                    value(record, mapping.periodType()),
                    value(record, mapping.periodStart()),
                    value(record, mapping.periodEnd())));
        }
        return rows;
    }

    private static String value(CSVRecord record, String column) {
        if (column == null || !record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
