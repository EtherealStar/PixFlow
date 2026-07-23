package com.pixflow.app.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationSchemaMigrationConfiguration {
    @Bean(initMethod = "migrate")
    public Flyway authFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/auth", "flyway_schema_history_auth");
    }

    @Bean(initMethod = "migrate")
    public Flyway commerceFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/commerce", "flyway_schema_history_commerce");
    }

    @Bean(initMethod = "migrate")
    public Flyway fileFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/file", "flyway_schema_history_file");
    }

    @Bean(initMethod = "migrate")
    public Flyway memoryFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/memory", "flyway_schema_history_memory");
    }

    @Bean(initMethod = "migrate")
    public Flyway taskFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/task", "flyway_schema_history_task");
    }

    @Bean(initMethod = "migrate")
    public Flyway agentFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/agent", "flyway_schema_history_agent");
    }

    @Bean(initMethod = "migrate")
    public Flyway conversationFlyway(DataSource dataSource) {
        return ownerFlyway(
                dataSource, "db/conversation", "flyway_schema_history_conversation");
    }

    @Bean(initMethod = "migrate")
    public Flyway sessionFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/session", "flyway_schema_history_session");
    }

    @Bean(initMethod = "migrate")
    public Flyway visionFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/vision", "flyway_schema_history_vision");
    }

    @Bean(initMethod = "migrate")
    public Flyway rubricsFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/rubrics", "flyway_schema_history_rubrics");
    }

    @Bean(initMethod = "migrate")
    public Flyway appActivityFlyway(DataSource dataSource) {
        return ownerFlyway(
                dataSource, "db/app-activity", "flyway_schema_history_app_activity");
    }

    private static Flyway ownerFlyway(
            DataSource dataSource, String location, String historyTable) {
        // 每个 owner 使用独立历史表，避免各模块的目标 V1 基线互相冲突。
        // 各 owner 共享同一 schema 且启动有先后：后跑的 owner 会看到兄弟模块已建的表，
        // 因此必须 baselineOnMigrate；基线版本固定为 0，保证各自的 V1 迁移仍会执行
        // （默认基线版本为 1，会错误地跳过 V1 脚本）。
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(false)
                .cleanDisabled(true)
                .load();
    }
}
