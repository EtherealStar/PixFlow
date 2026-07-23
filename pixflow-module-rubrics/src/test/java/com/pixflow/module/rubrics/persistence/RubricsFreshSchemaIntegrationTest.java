package com.pixflow.module.rubrics.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class RubricsFreshSchemaIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @BeforeAll
    static void createSchema() throws Exception {
        String schema;
        try (var stream = RubricsFreshSchemaIntegrationTest.class.getResourceAsStream(
                "/db/rubrics/V1__create_rubrics_evaluation_facts.sql")) {
            if (stream == null) {
                throw new IllegalStateException("rubrics schema resource is missing");
            }
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Test
    void createsRecoveryAndEvaluationFactsWithoutLegacyTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.prepareStatement("""
                        select table_name
                        from information_schema.tables
                        where table_schema = database()
                        order by table_name
                        """);
                var results = statement.executeQuery()) {
            while (results.next()) {
                tables.add(results.getString(1));
            }
        }

        assertThat(tables)
                .contains("rubrics_run", "rubrics_run_item", "rubrics_evaluation",
                        "rubrics_dataset", "rubrics_gold_label", "rubrics_validation_report")
                .doesNotContain("rubrics_score", "rubrics_promotion", "rubrics_baseline");
    }
}
