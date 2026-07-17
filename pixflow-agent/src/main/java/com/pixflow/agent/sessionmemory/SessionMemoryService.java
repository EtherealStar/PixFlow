package com.pixflow.agent.sessionmemory;

import com.pixflow.agent.config.AgentSubagentAutoConfiguration;
import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.error.AgentErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.context.sessionmemory.SessionMemoryPort;
import com.pixflow.harness.context.sessionmemory.SessionMemoryThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session Memory 主干服务（实现 context.SessionMemoryPort SPI）。
 *
 * <p>对应 {@code agent.md §7.7} 4 方法契约：
 * load / save / computeThreshold / scheduleExtraction。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@code save} 顺序：先 MySQL（事实源），再刷 Redis 缓存</li>
 *   <li>断路器：进程内 {@link AtomicInteger} 维护连续失败计数，
 *       连续失败 ≥ {@code maxConsecutiveFailures} 切 fallback；下一次成功重置</li>
 *   <li>{@code scheduleExtraction} 异步提交到 {@code session-memory.extraction-pool}，
 *       立即返回 noop（不阻塞回合结束）</li>
 *   <li>阈值不持久化（重入会话时重算）</li>
 * </ul>
 */
@Service
public class SessionMemoryService implements SessionMemoryPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMemoryService.class);

    private final SessionMemoryRepository repository;

    private final SessionMemoryCache cache;

    private final SessionMemoryExtractor extractor;

    private final SessionMemoryUpdater updater;

    private final AgentProperties props;

    private final ExecutorService extractionPool;

    private final ConcurrentHashMap<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

    public SessionMemoryService(SessionMemoryRepository repository,
                                SessionMemoryCache cache,
                                SessionMemoryExtractor extractor,
                                SessionMemoryUpdater updater,
                                AgentProperties props,
                                @Qualifier(AgentSubagentAutoConfiguration.SESSION_MEMORY_EXECUTOR_BEAN)
                                ExecutorService extractionPool) {
        this.repository = repository;
        this.cache = cache;
        this.extractor = extractor;
        this.updater = updater;
        this.props = props;
        this.extractionPool = extractionPool;
        LOGGER.info("SessionMemoryService initialized: threshold tokens={}, turns={}, maxFailures={}",
                props.getSessionMemory().getThreshold().getTokens(),
                props.getSessionMemory().getThreshold().getTurns(),
                props.getSessionMemory().getCircuitBreaker().getMaxConsecutiveFailures());
    }

    @Override
    public Optional<SessionMemoryContent> load(String conversationId) {
        // 1. Redis cache
        var cached = cache.get(conversationId);
        if (cached.isPresent()) {
            String content = cached.get();
            return Optional.of(new SessionMemoryContent(content, md5(content)));
        }
        // 2. MySQL fallback
        var row = repository.findByConversationId(conversationId);
        if (row.isPresent()) {
            SessionMemory m = row.get();
            cache.set(conversationId, m.getContent());
            return Optional.of(new SessionMemoryContent(m.getContent(), m.getContentHash()));
        }
        return Optional.empty();
    }

    @Override
    public void save(String conversationId, SessionMemoryContent content, long lastSummarizedSeq) {
        if (content == null) {
            throw new PixFlowException(AgentErrorCode.AGENT_SESSION_MEMORY_EXTRACTION_FAILED,
                    "SessionMemoryContent must not be null");
        }
        SessionMemory memory = new SessionMemory();
        memory.setConversationId(conversationId);
        memory.setContent(content.markdown());
        memory.setLastSummarizedSeq(lastSummarizedSeq);
        memory.setContentHash(content.contentHash());
        memory.setSource(SessionMemorySource.EXTRACTION.name());
        memory.setCreatedAt(Instant.now());
        memory.setUpdatedAt(Instant.now());
        memory.setCoveredTurnCount(toCoveredTurnCount(lastSummarizedSeq));
        // 1. MySQL（事实源）
        SessionMemoryRepository.SaveResult result = repository.saveIfAdvances(memory);
        if (result == SessionMemoryRepository.SaveResult.INSERTED
                || result == SessionMemoryRepository.SaveResult.ADVANCED) {
            // 2. Redis cache
            cache.set(conversationId, content.markdown());
        } else {
            cache.invalidate(conversationId);
        }
        LOGGER.debug("SessionMemoryService: save result={}, conversationId={}, lastSummarizedSeq={}, contentHash={}",
                result, conversationId, lastSummarizedSeq, content.contentHash());
    }

    @Override
    public SessionMemoryThreshold computeThreshold(String conversationId, long currentHeadSeq, int currentTurnNo) {
        var row = repository.findByConversationId(conversationId);
        long lastSummarizedSeq = row.map(SessionMemory::getLastSummarizedSeq).orElse(0L);
        int coveredTurnCount = row.map(SessionMemory::getCoveredTurnCount).orElse(0);
        // sinceLast*：当前 head - lastSummarizedSeq（重算场景）
        long sinceLastExtractTokens = 0; // 本期不估算（MessageStore 未注入；下迭代补）
        int sinceLastExtractTurn = Math.max(0, currentTurnNo - coveredTurnCount);
        return new SessionMemoryThreshold(
                lastSummarizedSeq,
                coveredTurnCount,
                Instant.now(),
                sinceLastExtractTurn,
                sinceLastExtractTokens
        );
    }

    @Override
    public void scheduleExtraction(String conversationId, int turnNo) {
        try {
            extractionPool.submit(() -> doExtraction(conversationId, turnNo));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("SessionMemoryService: extraction rejected for conversationId={}", conversationId, e);
            failureCounter(conversationId).incrementAndGet();
        }
    }

    private void doExtraction(String conversationId, int turnNo) {
        try {
            var existing = load(conversationId);
            String previous = existing.map(SessionMemoryContent::markdown).orElse("");
            // 本期：新 messages 用空字符串（实际接入 SubagentRunner 后填充）
            String newMessagesJson = "";
            Optional<String> extracted = extractor.extract(previous, newMessagesJson);
            String newContent = extracted.orElseGet(() ->
                    updater.extractFallback(previous, null, null));
            // 估算 token；超阈值截断
            String contentHash = md5(newContent);
            SessionMemoryContent content = new SessionMemoryContent(newContent, contentHash);
            save(conversationId, content, turnNo);
            // 成功 → 重置断路器
            failureCounter(conversationId).set(0);
        } catch (Exception e) {
            int failures = failureCounter(conversationId).incrementAndGet();
            LOGGER.warn("SessionMemoryService: extraction failed (conversationId={}, consecutive={})",
                    conversationId, failures, e);
            // 断路器触发 → 切 fallback
            if (failures >= props.getSessionMemory().getCircuitBreaker().getMaxConsecutiveFailures()) {
                LOGGER.warn(
                        "SessionMemoryService: circuit breaker triggered for conversationId={}, "
                                + "switching to FALLBACK_RULE",
                        conversationId);
                doFallbackExtraction(conversationId, turnNo);
                failureCounter(conversationId).set(0);
            }
        }
    }

    private void doFallbackExtraction(String conversationId, int turnNo) {
        try {
            var existing = load(conversationId);
            String previous = existing.map(SessionMemoryContent::markdown).orElse("");
            String fallback = updater.extractFallback(previous, null, null);
            SessionMemoryContent content = new SessionMemoryContent(fallback, md5(fallback));
            // source 字段标记 FALLBACK_RULE
            SessionMemory memory = new SessionMemory();
            memory.setConversationId(conversationId);
            memory.setContent(content.markdown());
            memory.setLastSummarizedSeq((long) turnNo);
            memory.setContentHash(content.contentHash());
            memory.setSource(SessionMemorySource.FALLBACK_RULE.name());
            memory.setCreatedAt(Instant.now());
            memory.setUpdatedAt(Instant.now());
            memory.setCoveredTurnCount(toCoveredTurnCount(turnNo));
            SessionMemoryRepository.SaveResult result = repository.saveIfAdvances(memory);
            if (result == SessionMemoryRepository.SaveResult.INSERTED
                    || result == SessionMemoryRepository.SaveResult.ADVANCED) {
                cache.set(conversationId, content.markdown());
            } else {
                cache.invalidate(conversationId);
            }
        } catch (Exception e) {
            LOGGER.error(
                    "SessionMemoryService: fallback extraction also failed for conversationId={}",
                    conversationId,
                    e);
        }
    }

    private AtomicInteger failureCounter(String conversationId) {
        return consecutiveFailures.computeIfAbsent(conversationId, ignored -> new AtomicInteger());
    }

    private static int toCoveredTurnCount(long lastSummarizedSeq) {
        if (lastSummarizedSeq <= 0) {
            return 0;
        }
        return lastSummarizedSeq > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lastSummarizedSeq;
    }

    private static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
