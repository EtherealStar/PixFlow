package com.pixflow.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ApplicationSchemaMigrationConfigurationTest {
    private final ApplicationSchemaMigrationConfiguration configuration =
            new ApplicationSchemaMigrationConfiguration();

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void ownerBaselinesUseIndependentStrictHistoryTables() {
        assertStrictOwner(
                configuration.fileFlyway(dataSource),
                "db/file",
                "flyway_schema_history_file");
        assertStrictOwner(
                configuration.taskFlyway(dataSource),
                "db/task",
                "flyway_schema_history_task");
        assertStrictOwner(
                configuration.appActivityFlyway(dataSource),
                "db/app-activity",
                "flyway_schema_history_app_activity");
    }

    private static void assertStrictOwner(
            Flyway flyway, String location, String historyTable) {
        var flywayConfiguration = flyway.getConfiguration();
        assertThat(flywayConfiguration.getTable()).isEqualTo(historyTable);
        assertThat(flywayConfiguration.getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:" + location);
        assertThat(flywayConfiguration.isBaselineOnMigrate()).isFalse();
        assertThat(flywayConfiguration.isOutOfOrder()).isFalse();
        assertThat(flywayConfiguration.isCleanDisabled()).isTrue();
    }
}
