package com.pixflow.infra.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.RabbitMessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.RabbitManagedListenerContainerFactory;
import com.pixflow.infra.mq.observability.MicrometerMqMetrics;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.topology.RabbitTopologyRegistrar;
import com.pixflow.infra.mq.topology.TopologyRegistrar;
import com.pixflow.infra.mq.trace.MdcTraceHeaderPropagator;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper, "com.pixflow.infra.mq");
    }

    @Bean
    @ConditionalOnMissingBean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        configurePublisherCallbacks(connectionFactory);
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    private void configurePublisherCallbacks(ConnectionFactory connectionFactory) {
        if (connectionFactory instanceof CachingConnectionFactory cachingConnectionFactory) {
            cachingConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            cachingConnectionFactory.setPublisherReturns(true);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceHeaderPropagator traceHeaderPropagator() {
        return new MdcTraceHeaderPropagator();
    }

    @Bean
    @ConditionalOnMissingBean
    public MqMetrics mqMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new MicrometerMqMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagePublisher messagePublisher(
            RabbitTemplate rabbitTemplate,
            TraceHeaderPropagator traceHeaderPropagator,
            MqMetrics metrics) {
        return new RabbitMessagePublisher(rabbitTemplate, traceHeaderPropagator, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public TopologyRegistrar topologyRegistrar(RabbitAdmin rabbitAdmin) {
        return new RabbitTopologyRegistrar(rabbitAdmin);
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorNormalizer errorNormalizer() {
        return new ErrorNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedListenerContainerFactory managedListenerContainerFactory(
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            ErrorNormalizer errorNormalizer,
            TraceHeaderPropagator traceHeaderPropagator,
            MqMetrics metrics,
            MqProperties properties) {
        return new RabbitManagedListenerContainerFactory(
                connectionFactory,
                rabbitTemplate,
                objectMapper,
                errorNormalizer,
                traceHeaderPropagator,
                metrics,
                properties);
    }
}
