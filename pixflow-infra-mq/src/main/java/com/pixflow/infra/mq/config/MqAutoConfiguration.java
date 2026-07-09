package com.pixflow.infra.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.infra.mq.observability.MicrometerMqMetrics;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.rocket.RocketDestinationRegistrar;
import com.pixflow.infra.mq.rocket.RocketManagedListenerContainerFactory;
import com.pixflow.infra.mq.rocket.RocketMessageCodec;
import com.pixflow.infra.mq.rocket.RocketMessagePublisher;
import com.pixflow.infra.mq.trace.MdcTraceHeaderPropagator;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public DefaultMQProducer rocketMqProducer(MqProperties properties) throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNamesrvAddr());
        producer.setSendMsgTimeout((int) properties.getSendTimeout().toMillis());
        producer.start();
        return producer;
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceHeaderPropagator traceHeaderPropagator() { return new MdcTraceHeaderPropagator(); }

    @Bean
    @ConditionalOnMissingBean
    public RocketMessageCodec rocketMessageCodec(ObjectMapper objectMapper, TraceHeaderPropagator traceHeaderPropagator) {
        return new RocketMessageCodec(objectMapper, traceHeaderPropagator);
    }

    @Bean
    @ConditionalOnMissingBean
    public MqMetrics mqMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        io.micrometer.core.instrument.MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry == null ? new NoopMqMetrics() : new MicrometerMqMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagePublisher messagePublisher(DefaultMQProducer producer, RocketMessageCodec codec, MqMetrics metrics) {
        return new RocketMessagePublisher(producer, codec, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public DestinationRegistrar destinationRegistrar(MqProperties properties) { return new RocketDestinationRegistrar(properties); }

    @Bean
    @ConditionalOnMissingBean
    public ErrorNormalizer errorNormalizer() { return new ErrorNormalizer(); }

    @Bean
    @ConditionalOnMissingBean
    public ManagedListenerContainerFactory managedListenerContainerFactory(MqProperties properties, RocketMessageCodec codec,
            ErrorNormalizer errorNormalizer, TraceHeaderPropagator traceHeaderPropagator, MqMetrics metrics) {
        return new RocketManagedListenerContainerFactory(properties, codec, errorNormalizer, traceHeaderPropagator, metrics);
    }

    private static final class NoopMqMetrics implements MqMetrics {
        @Override
        public void recordPublishConfirmed(String topic, String tag) {
        }

        @Override
        public void recordPublishFailed(String topic, String tag, com.pixflow.infra.mq.PublishFailureType failureType) {
        }

        @Override
        public void recordConsumeAck(String topic, String consumerGroup) {
        }

        @Override
        public void recordConsumeRetry(String topic, String consumerGroup, int retryCount) {
        }

        @Override
        public void recordConsumeDeadLetter(String topic, String consumerGroup) {
        }

        @Override
        public void recordConsumeAckDrop(String topic, String consumerGroup) {
        }

        @Override
        public void recordDlqDepth(String topic, String consumerGroup, long depth) {
        }
    }
}
