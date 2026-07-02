package com.pixflow.infra.mq.rocket;

import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishFailure;
import com.pixflow.infra.mq.PublishFailureType;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.mq.observability.MqMetrics;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.exception.RemotingException;

public class RocketMessagePublisher implements MessagePublisher {
    private final DefaultMQProducer producer;
    private final RocketMessageCodec codec;
    private final MqMetrics metrics;

    public RocketMessagePublisher(DefaultMQProducer producer, RocketMessageCodec codec, MqMetrics metrics) {
        this.producer = producer;
        this.codec = codec;
        this.metrics = metrics;
    }

    @Override
    public PublishResult publish(PublishRequest request) {
        try {
            SendResult sendResult = producer.send(codec.encode(request), request.sendTimeout().toMillis());
            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                PublishFailure failure = new PublishFailure(PublishFailureType.BROKER_REJECTED,
                        "RocketMQ send status: " + sendResult.getSendStatus(), null, sendResult.getSendStatus().name());
                return failed(request, sendResult.getMsgId(), failure);
            }
            String brokerQueue = sendResult.getMessageQueue() == null ? "" : sendResult.getMessageQueue().toString();
            metrics.recordPublishConfirmed(request.topic(), request.tag());
            return PublishResult.confirmed(request.topic(), request.tag(), sendResult.getMsgId(), brokerQueue);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(request, "", new PublishFailure(PublishFailureType.SEND_TIMEOUT, "RocketMQ send interrupted", null, null));
        } catch (MQBrokerException ex) {
            return failed(request, "", new PublishFailure(PublishFailureType.BROKER_REJECTED,
                    Sanitizer.sanitizeMessage(ex.getErrorMessage()), ex.getResponseCode(), ex.getErrorMessage()));
        } catch (RemotingException ex) {
            return failed(request, "", new PublishFailure(PublishFailureType.SEND_TIMEOUT, Sanitizer.sanitizeMessage(ex.getMessage()), null, null));
        } catch (MQClientException ex) {
            return failed(request, "", new PublishFailure(PublishFailureType.BROKER_UNAVAILABLE, Sanitizer.sanitizeMessage(ex.getMessage()), null, null));
        } catch (RuntimeException ex) {
            return failed(request, "", new PublishFailure(PublishFailureType.SERIALIZATION_FAILED, Sanitizer.sanitizeMessage(ex.getMessage()), null, null));
        } catch (Exception ex) {
            return failed(request, "", new PublishFailure(PublishFailureType.UNKNOWN, Sanitizer.sanitizeMessage(ex.getMessage()), null, null));
        }
    }

    private PublishResult failed(PublishRequest request, String messageId, PublishFailure failure) {
        metrics.recordPublishFailed(request.topic(), request.tag(), failure.type());
        return PublishResult.failed(request.topic(), request.tag(), messageId, failure);
    }
}
