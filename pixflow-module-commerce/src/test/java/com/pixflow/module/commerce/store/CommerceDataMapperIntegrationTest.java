package com.pixflow.module.commerce.store;

import static com.pixflow.module.commerce.CommerceTestData.commerceData;
import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.commerce.query.PeriodType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = CommerceDataMapperIntegrationTest.TestApp.class,
        properties = {
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/commerce/V1__create_commerce_tables.sql",
                "spring.autoconfigure.exclude=com.pixflow.module.commerce.config.CommerceAutoConfiguration"
        })
class CommerceDataMapperIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pixflow_commerce")
            .withUsername("pixflow")
            .withPassword("pixflow");

    @Autowired
    private CommerceDataMapper mapper;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Test
    void upsertKeepsNaturalKeyUniqueAndReplacesMetrics() {
        Instant fetchedAt = Instant.parse("2026-06-28T00:00:00Z");
        LocalDate day = LocalDate.parse("2026-06-01");
        mapper.upsert(commerceData("SKU_UPSERT", "dress", 100, "0.100000", "0.020000", "0.010000", day, "LOCAL_IMPORT", fetchedAt));
        mapper.upsert(commerceData("SKU_UPSERT", "dress", 250, "0.200000", "0.040000", "0.030000", day, "LOCAL_IMPORT", fetchedAt.plusSeconds(60)));

        List<CommerceAggregateRow> rows = mapper.aggregateBySku(
                List.of("SKU_UPSERT"),
                PeriodType.DAY,
                day,
                day,
                List.of("LOCAL_IMPORT"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().impressions()).isEqualTo(250);
        assertThat(rows.getFirst().ctr()).isEqualByComparingTo("0.200000");
        assertThat(mapper.selectList(null)).filteredOn(row -> "SKU_UPSERT".equals(row.getSkuId())).hasSize(1);
    }

    @Test
    void aggregatesWeightedRatesBenchmarkTrendAndSourceFilters() {
        Instant fresh = Instant.parse("2026-06-28T00:00:00Z");
        mapper.upsert(commerceData("SKU_SQL", "dress", 100, "0.100000", "0.020000", "0.010000",
                LocalDate.parse("2026-06-01"), "LOCAL_IMPORT", fresh));
        mapper.upsert(commerceData("SKU_SQL", "dress", 300, "0.300000", "0.060000", "0.030000",
                LocalDate.parse("2026-06-02"), "LOCAL_IMPORT", fresh.plusSeconds(1)));
        mapper.upsert(commerceData("SKU_OTHER", "dress", 200, "0.200000", "0.040000", "0.020000",
                LocalDate.parse("2026-06-01"), "LOCAL_IMPORT", fresh));
        mapper.upsert(commerceData("SKU_EXTERNAL", "dress", 500, "0.900000", "0.090000", "0.050000",
                LocalDate.parse("2026-06-01"), "EXTERNAL_API:fake", fresh));

        List<CommerceAggregateRow> localRows = mapper.aggregateBySku(
                List.of("SKU_SQL"),
                PeriodType.DAY,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-02"),
                List.of("LOCAL_IMPORT"));
        CommerceAggregateRow row = localRows.getFirst();

        assertThat(row.impressions()).isEqualTo(400);
        assertThat(row.ctr()).isEqualByComparingTo("0.250000");
        assertThat(row.addCartRate()).isEqualByComparingTo("0.050000");
        assertThat(row.purchaseRate()).isEqualByComparingTo("0.025000");

        CommerceBenchmarkRow benchmark = mapper.categoryBenchmark(
                "dress",
                PeriodType.DAY,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-02"),
                List.of("LOCAL_IMPORT"));
        assertThat(benchmark.sampleCount()).isEqualTo(2);
        assertThat(benchmark.impressions()).isEqualTo(600);
        assertThat(benchmark.ctr()).isEqualByComparingTo("0.2333333333");

        List<CommerceData> trend = mapper.trend(
                "SKU_SQL",
                PeriodType.DAY,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-02"),
                List.of("LOCAL_IMPORT"));
        assertThat(trend).extracting(CommerceData::getPeriodStart)
                .containsExactly(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-02"));

        assertThat(mapper.aggregateBySku(
                List.of("SKU_EXTERNAL"),
                PeriodType.DAY,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01"),
                List.of("LOCAL_IMPORT"))).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.pixflow.module.commerce.store")
    static class TestApp {
    }
}
