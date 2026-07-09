package com.pixflow.module.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.common.time.TimeAutoConfiguration;
import com.pixflow.module.commerce.importer.RowValidator;
import com.pixflow.module.commerce.source.PlatformApiClient;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommerceMapperScanBoundaryTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    TimeAutoConfiguration.class,
                    CommerceAutoConfiguration.class))
            .withUserConfiguration(RequiredPorts.class);

    @Test
    void platformApiClientIsNotRegisteredAsMyBatisMapper() {
        contextRunner.run(context -> {
            PlatformApiClient client = context.getBean(PlatformApiClient.class);
            assertThat(client).isNotNull();

            Map<String, MapperFactoryBean> mapperFactoryBeans = context.getBeansOfType(MapperFactoryBean.class);
            assertThat(mapperFactoryBeans.values())
                    .extracting(MapperFactoryBean::getObjectType)
                    .doesNotContain(PlatformApiClient.class);

            String[] beanDefinitionNames = context.getBeanFactory().getBeanDefinitionNames();
            assertThat(Arrays.stream(beanDefinitionNames)
                    .map(context.getBeanFactory()::getBeanDefinition)
                    .map(BeanDefinition::getBeanClassName)
                    .filter(MapperFactoryBean.class.getName()::equals)
                    .count()).isEqualTo(2);
        });
    }

    @Test
    void commerceUsesSinglePlatformClock() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(Clock.class)).containsOnlyKeys("pixflowClock");
            assertThat(context).hasSingleBean(RowValidator.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredPorts {
        @Bean
        SqlSessionFactory sqlSessionFactory() {
            SqlSessionFactory sqlSessionFactory = mock(SqlSessionFactory.class);
            var environment = new Environment("test", new JdbcTransactionFactory(), mock(DataSource.class));
            when(sqlSessionFactory.getConfiguration()).thenReturn(new org.apache.ibatis.session.Configuration(environment));
            return sqlSessionFactory;
        }

        @Bean
        MessagePublisher messagePublisher() {
            return request -> PublishResult.confirmed(request.topic(), request.tag(), "message-id", "queue");
        }
    }

}
