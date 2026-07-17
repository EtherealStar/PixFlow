package com.pixflow.infra.mq.rocket;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.rocketmq.common.message.Message;

public class RocketMessageCodec {
    private final ObjectMapper objectMapper;

    private final TraceHeaderPropagator traceHeaderPropagator;

    public RocketMessageCodec(ObjectMapper objectMapper, TraceHeaderPropagator traceHeaderPropagator) {
        this.objectMapper = objectMapper;
        this.traceHeaderPropagator = traceHeaderPropagator;
    }

    public Message encode(PublishRequest request) throws IOException {
        Map<String, Object> headers = traceHeaderPropagator.inject(request.headers());
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(request.schemaVersion(), request.payload(), headers);
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        Message message = new Message(request.topic(), request.tag(), String.join(" ", request.keys()), body);
        headers.forEach((key, value) -> {
            if (value != null) {
                message.putUserProperty(key, String.valueOf(value));
            }
        });
        message.putUserProperty("x-schema-version", String.valueOf(request.schemaVersion()));
        return message;
    }

    public <T> MessageEnvelope<T> decode(
            byte[] body,
            Map<String, Object> userProperties,
            Class<T> payloadType) throws IOException {
        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(MessageEnvelope.class, payloadType);
        MessageEnvelope<T> envelope = objectMapper.readValue(body, envelopeType);
        if (userProperties == null || userProperties.isEmpty()) {
            return envelope;
        }
        Map<String, Object> headers = new LinkedHashMap<>(userProperties);
        headers.putAll(envelope.headers());
        return new MessageEnvelope<>(envelope.schemaVersion(), envelope.payload(), headers);
    }

    public String bodyPreview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int length = Math.min(body.length, 256);
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }
}
