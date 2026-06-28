package com.pixflow.module.commerce.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.commerce.query.PeriodType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CommerceImportParsingTest {
    @Test
    void parsesChineseHeadersAndNormalizesPercentRates() throws Exception {
        String csv = """
                商品sku,类目,曝光量,点击率,加购率,购买率,周期类型,开始日期,结束日期
                SKU001,dress,100,12%,0.03,0.02,DAY,2026-06-01,2026-06-01
                """;
        CsvCommerceParser parser = new CsvCommerceParser();
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        ColumnMapping mapping = new ColumnMapping(parser.headers(new ByteArrayInputStream(bytes)), true);
        RawCommerceRow raw = parser.parse(new ByteArrayInputStream(bytes), mapping).getFirst();

        RowValidator validator = new RowValidator(Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
        var data = validator.normalize(raw, new ImportOptions("LOCAL_IMPORT", PeriodType.DAY, null, null, CategoryConflictPolicy.WARN));

        assertThat(data.getSkuId()).isEqualTo("SKU001");
        assertThat(data.getCategory()).isEqualTo("dress");
        assertThat(data.getCtr()).isEqualByComparingTo("0.12");
        assertThat(data.getPeriodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
    }
}
