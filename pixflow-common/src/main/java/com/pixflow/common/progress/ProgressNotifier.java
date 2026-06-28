package com.pixflow.common.progress;

/**
 * 传输无关的进度发布接缝。业务模块只发布逻辑频道和事件，WebSocket/STOMP 等实现由 app 层注入。
 */
@FunctionalInterface
public interface ProgressNotifier {
    void publish(String channel, Object event);
}
