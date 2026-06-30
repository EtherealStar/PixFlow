package com.pixflow.harness.context.sessionmemory;

import java.util.Optional;

/**
 * Session Memory 累积提取 SPI。
 *
 * <p>由 {@code harness/context} 定义，{@code agent} 模块在 Wave 5 实现。
 * 沿用 PixFlow 跨模块 SPI 倒置手法（与
 * {@code context.TranscriptPort} ← session、
 * {@code contracts.ConfirmationTokenStore} ← cache、
 * {@code common.ErrorRecorder} ← eval 同款）。
 *
 * <p>对应 {@code agent.md §7.7} 的 SPI 契约：4 方法
 * load/save/computeThreshold/scheduleExtraction。
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@code load}：先 Redis 缓存，miss 走 MySQL 事实源</li>
 *   <li>{@code save}：先 MySQL 单事务 upsert，再刷 Redis 缓存</li>
 *   <li>{@code computeThreshold}：从 load 拿事实 + 增量 messages 重算</li>
 *   <li>{@code scheduleExtraction}：异步提交到独立线程池，立即返回</li>
 * </ul>
 *
 * <p><b>关键不变量</b>：本 SPI 不知道 LLM、不知道具体存储；agent 实现自行
 * 选择 Redis/MySQL/Subagent runner 等具体技术。
 */
public interface SessionMemoryPort {

    /**
     * 加载指定会话的当前 Session Memory。
     *
     * <p>实现约定：先 Redis 缓存，miss 走 MySQL 事实源。
     *
     * @param conversationId 会话 ID
     * @return 内容（可能为空）——空 content 渲染为空白 section
     */
    Optional<SessionMemoryContent> load(String conversationId);

    /**
     * 写入 Session Memory（事实源 + 缓存同步）。
     *
     * <p>实现约定：单事务 INSERT ... ON DUPLICATE KEY UPDATE，
     * 写 MySQL 成功后 set Redis 缓存。
     *
     * @param conversationId    会话 ID
     * @param content           新内容（替换式）
     * @param lastSummarizedSeq 已覆盖到的 message.seq
     */
    void save(String conversationId, SessionMemoryContent content, long lastSummarizedSeq);

    /**
     * 计算运行时阈值（从 lastSummarizedSeq 之后开始算）。
     *
     * <p>实现约定：从 load 拿 lastSummarizedSeq + coveredTurnCount；
     * 增量 messages = MessageStore.currentMessages() 过滤 seq > lastSummarizedSeq；
     * token 估算用 jtokkit。
     *
     * @param conversationId  会话 ID
     * @param currentHeadSeq  当前 message head seq
     * @param currentTurnNo   当前回合号
     * @return 阈值状态（不持久化）
     */
    SessionMemoryThreshold computeThreshold(String conversationId, long currentHeadSeq, int currentTurnNo);

    /**
     * 异步触发 Session Memory 提取（投递线程池，立即返回 noop）。
     *
     * <p>实现约定：提交到独立线程池（core=2/max=4/queue=100），
     * 立即返回不阻塞。失败计数 +1，连续失败 3 次切 fallback。
     *
     * @param conversationId 会话 ID
     * @param turnNo         当前回合号
     */
    void scheduleExtraction(String conversationId, int turnNo);
}
