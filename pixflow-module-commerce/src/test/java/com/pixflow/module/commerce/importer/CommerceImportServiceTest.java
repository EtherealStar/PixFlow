package com.pixflow.module.commerce.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.store.CommerceData;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommerceImportServiceTest {
    private final CommerceDataMapper mapper = mock(CommerceDataMapper.class);
    private final CsvCommerceParser csvParser = new CsvCommerceParser();
    private final ExcelCommerceParser excelParser = new ExcelCommerceParser();
    private final CommerceProperties properties = new CommerceProperties();
    private final CommerceImportService service = new CommerceImportService(
            mapper,
            List.of(csvParser, excelParser),
            csvParser,
            excelParser,
            new RowValidator(Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC)),
            properties);

    @Test
    void importsValidRowsAndSkipsInvalidRows() {
        String csv = """
                sku_id,category,impressions,ctr,add_cart_rate,purchase_rate,period_type,period_start,period_end
                SKU001,dress,100,0.10,0.03,0.02,DAY,2026-06-01,2026-06-01
                SKU_BAD,dress,100,1.20,0.03,0.02,DAY,2026-06-01,2026-06-01
                """;

        ImportReport report = service.importLocal(input(csv), "commerce.csv", options(CategoryConflictPolicy.WARN));

        assertThat(report.getTotal()).isEqualTo(2);
        assertThat(report.getSucceeded()).isEqualTo(1);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailures().getFirst().rowNumber()).isEqualTo(3);
        assertThat(report.getFailures().getFirst().reason()).contains("ctr");
        ArgumentCaptor<CommerceData> captor = ArgumentCaptor.forClass(CommerceData.class);
        verify(mapper).upsert(captor.capture());
        assertThat(captor.getValue().getSkuId()).isEqualTo("SKU001");
        assertThat(captor.getValue().getCtr()).isEqualByComparingTo("0.10");
    }

    @Test
    void warnsAndKeepsImportingWhenSkuCategoryConflictsInWarnMode() {
        String csv = """
                sku_id,category,impressions,ctr,add_cart_rate,purchase_rate,period_type,period_start,period_end
                SKU001,dress,100,0.10,0.03,0.02,DAY,2026-06-01,2026-06-01
                SKU001,shoes,120,0.11,0.04,0.03,DAY,2026-06-02,2026-06-02
                """;

        ImportReport report = service.importLocal(input(csv), "commerce.csv", options(CategoryConflictPolicy.WARN));

        assertThat(report.getSucceeded()).isEqualTo(2);
        assertThat(report.getWarnings()).singleElement().asString().contains("category conflict");
        verify(mapper, times(2)).upsert(any(CommerceData.class));
    }

    @Test
    void skipsConflictingRowInFailMode() {
        String csv = """
                sku_id,category,impressions,ctr,add_cart_rate,purchase_rate,period_type,period_start,period_end
                SKU001,dress,100,0.10,0.03,0.02,DAY,2026-06-01,2026-06-01
                SKU001,shoes,120,0.11,0.04,0.03,DAY,2026-06-02,2026-06-02
                """;

        ImportReport report = service.importLocal(input(csv), "commerce.csv", options(CategoryConflictPolicy.FAIL));

        assertThat(report.getSucceeded()).isEqualTo(1);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailures().getFirst().reason()).contains("category conflict");
        verify(mapper).upsert(any(CommerceData.class));
    }

    @Test
    void missingRequiredColumnFailsWholeImport() {
        String csv = """
                sku_id,category,impressions,ctr,add_cart_rate,period_type,period_start,period_end
                SKU001,dress,100,0.10,0.03,DAY,2026-06-01,2026-06-01
                """;

        assertThatThrownBy(() -> service.importLocal(input(csv), "commerce.csv", options(CategoryConflictPolicy.WARN)))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("missing required commerce column")
                .hasMessageContaining("purchase_rate");
    }

    private static ByteArrayInputStream input(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static ImportOptions options(CategoryConflictPolicy policy) {
        return new ImportOptions("LOCAL_IMPORT", PeriodType.DAY, null, null, policy);
    }
}
