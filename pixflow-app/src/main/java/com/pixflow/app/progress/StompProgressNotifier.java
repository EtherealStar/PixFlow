package com.pixflow.app.progress;

import com.pixflow.common.progress.ProgressNotifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class StompProgressNotifier implements ProgressNotifier {
    private final SimpMessagingTemplate messagingTemplate;

    public StompProgressNotifier(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(String channel, Object event) {
        messagingTemplate.convertAndSend("/topic/" + normalize(channel), event);
    }

    private static String normalize(String channel) {
        String normalized = channel == null ? "" : channel.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
