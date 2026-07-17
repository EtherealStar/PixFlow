package com.pixflow.module.commerce.importer;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.error.CommerceErrorCode;
import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.store.CommerceData;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommerceImportService {
    private final CommerceDataMapper mapper;

    private final List<CommerceFileParser> parsers;

    private final CsvCommerceParser csvParser;

    private final ExcelCommerceParser excelParser;

    private final RowValidator rowValidator;

    private final CommerceProperties properties;

    public CommerceImportService(
            CommerceDataMapper mapper,
            List<CommerceFileParser> parsers,
            CsvCommerceParser csvParser,
            ExcelCommerceParser excelParser,
            RowValidator rowValidator,
            CommerceProperties properties) {
        this.mapper = mapper;
        this.parsers = parsers;
        this.csvParser = csvParser;
        this.excelParser = excelParser;
        this.rowValidator = rowValidator;
        this.properties = properties;
    }

    public ImportReport importLocal(InputStream input, String filename, ImportOptions options) {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException ex) {
            throw new PixFlowException(
                    CommerceErrorCode.COMMERCE_IMPORT_FILE_CORRUPTED,
                    "failed to read commerce import file",
                    ex);
        }
        CommerceFileParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(filename, null))
                .findFirst()
                .orElseThrow(() -> new PixFlowException(
                        CommerceErrorCode.COMMERCE_IMPORT_FORMAT_UNSUPPORTED,
                        "unsupported commerce import file: " + filename));
        try {
            List<String> headers = headers(parser, bytes);
            ColumnMapping mapping = new ColumnMapping(headers, properties.getImport().isStrictHeader());
            return importRows(parser.parse(new ByteArrayInputStream(bytes), mapping), effectiveOptions(options));
        } catch (PixFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PixFlowException(
                    CommerceErrorCode.COMMERCE_IMPORT_FILE_CORRUPTED,
                    "failed to parse commerce import file",
                    ex);
        }
    }

    public ImportReport importStandardized(List<CommerceData> rows) {
        ImportReport report = new ImportReport();
        for (CommerceData row : rows) {
            report.countTotal();
            try {
                mapper.upsert(row);
                report.countSucceeded();
            } catch (RuntimeException ex) {
                report.addFailure(report.getTotal(), Sanitizer.sanitizeMessage(ex.getMessage()));
            }
        }
        return report;
    }

    private ImportReport importRows(List<RawCommerceRow> rows, ImportOptions options) {
        ImportReport report = new ImportReport();
        Map<String, String> seenCategories = new HashMap<>();
        for (RawCommerceRow row : rows) {
            report.countTotal();
            try {
                CommerceData data = rowValidator.normalize(row, options);
                // 类目随数据进入，先做软一致性检查，避免静默污染类目基准。
                String previous = seenCategories.putIfAbsent(data.getSkuId(), data.getCategory());
                if (previous != null && !previous.equals(data.getCategory())) {
                    String message = "sku " + data.getSkuId()
                            + " category conflict: " + previous
                            + " -> " + data.getCategory();
                    if (options.categoryConflictPolicy() == CategoryConflictPolicy.FAIL) {
                        throw new IllegalArgumentException(message);
                    }
                    report.addWarning(Sanitizer.sanitizeMessage(message));
                }
                mapper.upsert(data);
                report.countSucceeded();
            } catch (RuntimeException ex) {
                // 行级错误只跳过当前行，保留成功行，符合导入容错设计。
                report.addFailure(row.rowNumber(), Sanitizer.sanitizeMessage(ex.getMessage()));
            }
        }
        return report;
    }

    private ImportOptions effectiveOptions(ImportOptions options) {
        if (options == null) {
            return new ImportOptions(
                    "LOCAL_IMPORT",
                    properties.getQuery().getDefaultPeriodType(),
                    null,
                    null,
                    properties.getImport().getCategoryConflict());
        }
        return new ImportOptions(
                options.source(),
                options.defaultPeriodType() == null ? PeriodType.DAY : options.defaultPeriodType(),
                options.defaultPeriodStart(),
                options.defaultPeriodEnd(),
                options.categoryConflictPolicy() == null
                        ? properties.getImport().getCategoryConflict()
                        : options.categoryConflictPolicy());
    }

    private List<String> headers(CommerceFileParser parser, byte[] bytes) throws IOException {
        if (parser == csvParser) {
            return csvParser.headers(new ByteArrayInputStream(bytes));
        }
        if (parser == excelParser) {
            return excelParser.headers(new ByteArrayInputStream(bytes));
        }
        return List.of();
    }
}
