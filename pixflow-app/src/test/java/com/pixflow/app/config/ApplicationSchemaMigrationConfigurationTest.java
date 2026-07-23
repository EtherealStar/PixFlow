package com.pixflow.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ApplicationSchemaMigrationConfigurationTest {
    private final ApplicationSchemaMigrationConfiguration configuration =
            new ApplicationSchemaMigrationConfiguration();

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void ownerBaselinesUseIndependentStrictHistoryTables() {
        assertStrictOwner(
                configuration.authFlyway(dataSource),
                "db/auth",
                "flyway_schema_history_auth");
        assertStrictOwner(
                configuration.commerceFlyway(dataSource),
                "db/commerce",
                "flyway_schema_history_commerce");
        assertStrictOwner(
                configuration.fileFlyway(dataSource),
                "db/file",
                "flyway_schema_history_file");
        assertStrictOwner(
                configuration.memoryFlyway(dataSource),
                "db/memory",
                "flyway_schema_history_memory");
        assertStrictOwner(
                configuration.taskFlyway(dataSource),
                "db/task",
                "flyway_schema_history_task");
        assertStrictOwner(
                configuration.agentFlyway(dataSource),
                "db/agent",
                "flyway_schema_history_agent");
        assertStrictOwner(
                configuration.conversationFlyway(dataSource),
                "db/conversation",
                "flyway_schema_history_conversation");
        assertStrictOwner(
                configuration.sessionFlyway(dataSource),
                "db/session",
                "flyway_schema_history_session");
        assertStrictOwner(
                configuration.visionFlyway(dataSource),
                "db/vision",
                "flyway_schema_history_vision");
        assertStrictOwner(
                configuration.rubricsFlyway(dataSource),
                "db/rubrics",
                "flyway_schema_history_rubrics");
        assertStrictOwner(
                configuration.appActivityFlyway(dataSource),
                "db/app-activity",
                "flyway_schema_history_app_activity");
    }

    @Test
    void everyOwnerMigrationIsAvailableToTheDeployableApplication() {
        assertMigrationResource("db/auth/V1__create_user_account.sql");
        assertMigrationResource("db/commerce/V1__create_commerce_tables.sql");
        assertMigrationResource("db/file/V1__create_asset_library.sql");
        assertMigrationResource("db/memory/V1__create_memory_tables.sql");
        assertMigrationResource("db/task/V1__create_task_execution.sql");
        assertMigrationResource("db/agent/V1__create_skill.sql");
        assertMigrationResource("db/conversation/V1__create_conversation_tables.sql");
        assertMigrationResource("db/session/V1__create_session_transcript.sql");
        assertMigrationResource("db/vision/V1__create_current_visual_facts.sql");
        assertMigrationResource("db/rubrics/V1__create_rubrics_evaluation_facts.sql");
        assertMigrationResource("db/app-activity/V1__create_activity_projection.sql");
    }

    private static void assertStrictOwner(
            Flyway flyway, String location, String historyTable) {
        var flywayConfiguration = flyway.getConfiguration();
        assertThat(flywayConfiguration.getTable()).isEqualTo(historyTable);
        assertThat(flywayConfiguration.getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:" + location);
        assertThat(flywayConfiguration.isBaselineOnMigrate()).isTrue();
        assertThat(flywayConfiguration.getBaselineVersion().getVersion()).isEqualTo("0");
        assertThat(flywayConfiguration.isOutOfOrder()).isFalse();
        assertThat(flywayConfiguration.isCleanDisabled()).isTrue();
    }

    private static void assertMigrationResource(String path) {
        assertThat(new ClassPathResource(path).exists()).isTrue();
    }
}
