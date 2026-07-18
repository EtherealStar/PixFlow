package com.pixflow.module.task.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class TaskAutoConfigurationTest {
  @Test
  void missingProductionDependenciesFailApplicationStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).isNotNull();
            });
  }

  @Test
  void requiredTaskBeansAreNeverSilentlySkipped() {
    var beanMethods =
        Arrays.stream(TaskAutoConfiguration.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .toList();

    assertThat(beanMethods).isNotEmpty();
    assertThat(beanMethods)
        .as("task 生产执行链缺少依赖时必须启动失败")
        .allMatch(method -> !method.isAnnotationPresent(ConditionalOnBean.class));
  }
}
