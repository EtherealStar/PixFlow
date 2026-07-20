package com.pixflow.app.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationSchemaMigrationConfiguration {
    @Bean(initMethod = "migrate")
    public Flyway fileFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/file", "flyway_schema_history_file");
    }

    @Bean(initMethod = "migrate")
    public Flyway taskFlyway(DataSource dataSource) {
        return ownerFlyway(dataSource, "db/task", "flyway_schema_history_task");
    }

    @Bean(initMethod = "migrate")
    public Flyway appActivityFlyway(DataSource dataSource) {
        return ownerFlyway(
                dataSource, "db/app-activity", "flyway_schema_history_app_activity");
    }

    private static Flyway ownerFlyway(
            DataSource dataSource, String location, String historyTable) {
        // 每个 owner 使用独立历史表，避免各模块的目标 V1 基线互相冲突。
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + location)
                .table(historyTable)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load();
    }
}
