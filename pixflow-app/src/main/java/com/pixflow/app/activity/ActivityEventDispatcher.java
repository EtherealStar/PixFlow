package com.pixflow.app.activity;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import java.util.Objects;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public final class ActivityEventDispatcher {
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final ActivityProjectionRepository repository;

    private final AdministratorEligibility eligibility;

    private final SimpMessagingTemplate messaging;

    public ActivityEventDispatcher(
            ActivityProjectionRepository repository,
            AdministratorEligibility eligibility,
            SimpMessagingTemplate messaging) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.messaging = Objects.requireNonNull(messaging, "messaging");
    }

    @Scheduled(fixedDelayString = "${pixflow.activity.dispatch-interval:1s}")
    public void dispatchPending() {
        dispatch(DEFAULT_BATCH_SIZE);
    }

    public void dispatch(int limit) {
        for (ActivityProjectionRepository.PendingActivityFrame pending : repository.pending(limit)) {
            try {
                AuthPrincipal principal = eligibility.requireEligible(pending.administratorId());
                messaging.convertAndSendToUser(principal.username(), "/queue/activity", pending.frame());
                repository.markDelivered(pending.frame().sequence());
            } catch (RuntimeException ignored) {
                // 发送失败必须保留 outbox；后续调度按原 sequence 重放，不能把通知丢失伪装为成功。
            }
        }
    }
}
