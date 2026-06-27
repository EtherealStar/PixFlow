package com.pixflow.infra.mq.topology;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

public class RabbitTopologyRegistrar implements TopologyRegistrar {
    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String X_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private final RabbitAdmin rabbitAdmin;

    public RabbitTopologyRegistrar(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public void register(QueueTopology topology) {
        DirectExchange mainExchange = new DirectExchange(topology.exchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(topology.deadLetterExchange(), true, false);
        Queue mainQueue = QueueBuilder.durable(topology.queue())
                .withArguments(mainQueueArguments(topology))
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(topology.deadLetterQueue()).build();

        rabbitAdmin.declareExchange(mainExchange);
        rabbitAdmin.declareExchange(deadLetterExchange);
        rabbitAdmin.declareQueue(mainQueue);
        rabbitAdmin.declareQueue(deadLetterQueue);
        rabbitAdmin.declareBinding(bind(mainQueue, mainExchange, topology.routingKey()));
        rabbitAdmin.declareBinding(bind(deadLetterQueue, deadLetterExchange, topology.deadLetterRoutingKey()));

        if (topology.retryEnabled()) {
            Queue retryQueue = QueueBuilder.durable(topology.retryQueue())
                    // retry.q 没有消费者；TTL 到期后靠 DLX 回到主 exchange。
                    .withArgument(X_DEAD_LETTER_EXCHANGE, topology.exchange())
                    .withArgument(X_DEAD_LETTER_ROUTING_KEY, topology.routingKey())
                    .build();
            rabbitAdmin.declareQueue(retryQueue);
            rabbitAdmin.declareBinding(bind(retryQueue, deadLetterExchange, topology.retryRoutingKey()));
        }
    }

    private Map<String, Object> mainQueueArguments(QueueTopology topology) {
        Map<String, Object> args = new LinkedHashMap<>(topology.queueArguments());
        args.putIfAbsent(X_DEAD_LETTER_EXCHANGE, topology.deadLetterExchange());
        args.putIfAbsent(X_DEAD_LETTER_ROUTING_KEY, topology.deadLetterRoutingKey());
        return args;
    }

    private Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
