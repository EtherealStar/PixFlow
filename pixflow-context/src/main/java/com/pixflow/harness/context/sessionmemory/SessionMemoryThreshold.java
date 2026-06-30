package com.pixflow.harness.context.sessionmemory;

import java.time.Instant;
import java.util.Objects;

/**
 * Session Memory 运行时阈值状态（不持久化）。
 *
 * <p>对应 {@code agent.md §7.3.3} 的不可变 record。退出/重入会话时，事实部分
 * （{@code lastSummarizedSeq}、{@code coveredTurnCount}）从 MySQL 加载，
 * 增量部分（{@code sinceLastExtractTurn}、{@code sinceLastExtractTokens}）
 * 在重新计算后填充。
 *
 * <p>关键不变量：<b>阈值状态不持久化</b>——只在进程内存在；事实状态才落库。
 *
 * @param lastSummarizedSeq    已覆盖到的 message.seq（事实，持久化）
 * @param coveredTurnCount     已覆盖回合数（事实，持久化）
 * @param lastExtractTime      上次提取时间（运行时，进程内）
 * @param sinceLastExtractTurn 自上次提取以来的回合数（运行时计算）
 * @param sinceLastExtractTokens 自上次提取以来的累计 token（运行时计算，jtokkit 估算）
 */
public record SessionMemoryThreshold(
        long lastSummarizedSeq,
        int coveredTurnCount,
        Instant lastExtractTime,
        int sinceLastExtractTurn,
        long sinceLastExtractTokens
) {

    public SessionMemoryThreshold {
        Objects.requireNonNull(lastExtractTime, "lastExtractTime");
        if (coveredTurnCount < 0) {
            throw new IllegalArgumentException("coveredTurnCount must be >= 0");
        }
        if (sinceLastExtractTurn < 0) {
            throw new IllegalArgumentException("sinceLastExtractTurn must be >= 0");
        }
        if (sinceLastExtractTokens < 0) {
            throw new IllegalArgumentException("sinceLastExtractTokens must be >= 0");
        }
    }

    /**
     * 判定当前是否应该触发 Session Memory 提取。
     *
     * @param thresholdTokens 累积 token 阈值（默认 10K）
     * @param thresholdTurns  累积回合数阈值（默认 3）
     * @return 任一阈值被突破即返回 true
     */
    public boolean shouldExtract(long thresholdTokens, int thresholdTurns) {
        return sinceLastExtractTokens >= thresholdTokens
                || sinceLastExtractTurn >= thresholdTurns;
    }
}
