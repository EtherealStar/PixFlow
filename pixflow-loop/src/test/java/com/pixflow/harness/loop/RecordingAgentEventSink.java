package com.pixflow.harness.loop;

import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用事件收集器。线程安全（loop 主循环单线程，但提供同步保护以便测试间复用）。
 */
public final class RecordingAgentEventSink implements AgentEventSink {

    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void emit(AgentEvent event) {
        events.add(event);
    }

    public List<AgentEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public List<AgentEvent> eventsOfType(com.pixflow.harness.loop.event.AgentEventType type) {
        return events().stream().filter(e -> e.type() == type).toList();
    }

    public void clear() {
        events.clear();
    }
}