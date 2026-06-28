package com.pixflow.module.file.copydoc;

import com.pixflow.module.file.config.FileProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class CsvCopyDocParser implements CopyDocParser {
    private final FileProperties properties;

    public CsvCopyDocParser(FileProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".csv");
    }

    @Override
    public List<ParsedCopyRow> parse(InputStream inputStream) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();
        CSVParser parser = format.parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        List<CSVRecord> materialized = new ArrayList<>();
        parser.forEach(materialized::add);
        if (materialized.isEmpty()) {
            return List.of();
        }
        CopyDocColumnMapping mapping = new CopyDocColumnMapping(
                parser.getHeaderNames(),
                properties.getCopydoc());
        return materialized.stream()
                .map(record -> toRow(record, mapping))
                .toList();
    }

    private static ParsedCopyRow toRow(CSVRecord record, CopyDocColumnMapping mapping) {
        return new ParsedCopyRow(
                value(record, mapping.skuIdColumn().orElse(null)),
                value(record, mapping.productNameColumn().orElse(null)),
                value(record, mapping.keywordsColumn().orElse(null)),
                value(record, mapping.descriptionColumn().orElse(null)),
                Math.toIntExact(record.getRecordNumber() + 1));
    }

    private static String value(CSVRecord record, String column) {
        if (column == null || !record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
