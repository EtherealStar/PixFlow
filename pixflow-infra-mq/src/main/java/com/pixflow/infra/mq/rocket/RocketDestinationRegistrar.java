package com.pixflow.infra.mq.rocket;

import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.infra.mq.destination.MessageDestination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocketDestinationRegistrar implements DestinationRegistrar {
    private static final Logger log = LoggerFactory.getLogger(RocketDestinationRegistrar.class);
    private final MqProperties properties;

    public RocketDestinationRegistrar(MqProperties properties) { this.properties = properties; }

    @Override
    public void register(MessageDestination destination) {
        log.info("Registered RocketMQ destination topic={}, tag={}, keys={}", destination.topic(), destination.tag(), destination.keys());
        if (properties.isTopicAutoCreate()) log.info("RocketMQ topic auto-create is enabled by broker policy for topic={}", destination.topic());
    }

    @Override
    public void register(ConsumerBinding binding) {
        log.info("Registered RocketMQ consumer binding topic={}, tagExpression={}, group={}", binding.topic(), binding.tagExpression(), binding.consumerGroup());
    }
}
