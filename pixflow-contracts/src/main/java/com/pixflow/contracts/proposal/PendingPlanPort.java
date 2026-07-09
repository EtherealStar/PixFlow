package com.pixflow.contracts.proposal;

import java.util.Optional;

/**
 * 待确认提案的共享 SPI。
 *
 * <p>实现方必须保证同一个 {@code toolCallId} 重复入队时返回同一个 planId,
 * 不创建重复的 pending plan。
 */
public interface PendingPlanPort {
    String enqueue(PendingPlanProposal proposal);

    Optional<PendingPlanProposal> find(String planId);
}
