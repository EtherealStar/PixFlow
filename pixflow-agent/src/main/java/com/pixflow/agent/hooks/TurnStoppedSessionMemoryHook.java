package com.pixflow.agent.hooks;

import com.pixflow.agent.sessionmemory.SessionMemoryService;
import com.pixflow.harness.hooks.HookCallback;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.HookPayload;
import com.pixflow.harness.hooks.payload.TurnStoppedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * TURN_STOPPED → Session Memory 异步累积触发器。
 *
 * <p>对应 {@code agent.md §七} + 阶段 7 hook 接线：
 * <ul>
 *   <li>订阅 TURN_STOPPED（order=100）</li>
 *   <li>校验 subagent==false（child 不触发，避免噪声）</li>
 *   <li>立即返回 noop，sessionMemoryService.scheduleExtraction 异步提交</li>
 * </ul>
 */
@Component
public class TurnStoppedSessionMemoryHook implements HookCallback {

    private static final Logger log = LoggerFactory.getLogger(TurnStoppedSessionMemoryHook.class);

    private final SessionMemoryService sessionMemoryService;

    public TurnStoppedSessionMemoryHook(SessionMemoryService sessionMemoryService) {
        this.sessionMemoryService = sessionMemoryService;
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.TURN_STOPPED);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public HookResult handle(HookEvent event, HookPayload payload) {
        if (event != HookEvent.TURN_STOPPED) {
            return HookResult.noop();
        }
        if (!(payload instanceof TurnStoppedPayload turnStopped)) {
            return HookResult.noop();
        }
        if (turnStopped.runtime() != null && turnStopped.runtime().subagent()) {
            // child subagent 不触发 Session Memory 提取
            return HookResult.noop();
        }
        try {
            sessionMemoryService.scheduleExtraction(
                    turnStopped.conversationId(),
                    turnStopped.turnNo() == null ? 0 : turnStopped.turnNo()
            );
            log.debug("TurnStoppedSessionMemoryHook: scheduled extraction for conversationId={}",
                    turnStopped.conversationId());
        } catch (Exception e) {
            log.warn("TurnStoppedSessionMemoryHook: schedule failed: {}", e.getMessage());
        }
        return HookResult.noop();
    }
}