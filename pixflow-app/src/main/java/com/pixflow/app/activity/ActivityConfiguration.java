package com.pixflow.app.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.task.api.activity.TaskActivitySource;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ActivityConfiguration {
    @Bean
    public ActivityProjectionRepository activityProjectionRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        return new JdbcActivityProjectionRepository(jdbcTemplate, objectMapper, clock);
    }

    @Bean
    public ActivityProjectionService activityProjectionService(ActivityProjectionRepository repository) {
        return new ActivityProjectionService(repository);
    }

    @Bean
    public ActivityEventDispatcher activityEventDispatcher(
            ActivityProjectionRepository repository,
            AdministratorEligibility eligibility,
            SimpMessagingTemplate messagingTemplate) {
        return new ActivityEventDispatcher(repository, eligibility, messagingTemplate);
    }

    @Bean
    public TaskActivityProjector taskActivityProjector(
            TaskActivitySource source,
            AdministratorEligibility eligibility,
            ActivityProjectionService projection) {
        return new TaskActivityProjector(source, eligibility, projection);
    }

    @Bean
    public ActivityCommandRouter activityCommandRouter(
            TaskCommandService taskCommands, FileActivityCommandService fileCommands) {
        return new OwnerActivityCommandRouter(taskCommands, fileCommands);
    }

    @Bean
    public FileActivityProjector fileActivityProjector(
            FileActivitySource source,
            AdministratorEligibility eligibility,
            ActivityProjectionService projection) {
        return new FileActivityProjector(source, eligibility, projection);
    }
}
