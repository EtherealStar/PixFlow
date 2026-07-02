package com.pixflow.infra.mq.rocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.trace.MdcTraceHeaderPropagator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RocketMessageCodecTest {
    @Test
    void encodesEnvelopeAndUserProperties() throws Exception {
        RocketMessageCodec codec = new RocketMessageCodec(new ObjectMapper(), new MdcTraceHeaderPropagator());
        PublishRequest request = PublishRequest.of("pixflow-task", "TASK_EXECUTE", new Payload("1"))
                .withKey("task:1")
                .withHeader("source", "test");
        org.apache.rocketmq.common.message.Message message = codec.encode(request);
        MessageEnvelope<Payload> envelope = codec.decode(message.getBody(), Map.of("source", "test"), Payload.class);
        assertThat(message.getTopic()).isEqualTo("pixflow-task");
        assertThat(message.getTags()).isEqualTo("TASK_EXECUTE");
        assertThat(message.getKeys()).isEqualTo("task:1");
        assertThat(envelope.payload().id()).isEqualTo("1");
        assertThat(envelope.headers()).containsEntry("source", "test");
    }
    record Payload(String id) {}
}
