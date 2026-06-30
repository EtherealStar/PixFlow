package com.pixflow.agent.sessionmemory;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.error.AgentErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.context.sessionmemory.SessionMemoryPort;
import com.pixflow.harness.context.sessionmemory.SessionMemoryThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    private final SessionMemoryRepository repository;
    private final SessionMemoryCache cache;
    private final SessionMemoryExtractor extractor;
    private final SessionMemoryUpdater updater;
    private final AgentProperties props;
    private final ExecutorService extractionPool;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public SessionMemoryService(SessionMemoryRepository repository,
                                SessionMemoryCache cache,
                                SessionMemoryExtractor extractor,
                                SessionMemoryUpdater updater,
                                AgentProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.extractor = extractor;
        this.updater = updater;
        this.props = props;
        // 独立线程池：core=2/max=4/queue=100（agent.md §十四 默认）
        this.extractionPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-session-memory-extractor");
            t.setDaemon(true);
            return t;
        });
        log.info("SessionMemoryService initialized: threshold tokens={}, turns={}, maxFailures={}",
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
        SessionMemory memory = repository.findByConversationId(conversationId)
                .orElseGet(() -> {
                    SessionMemory m = new SessionMemory();
                    m.setConversationId(conversationId);
                    m.setCreatedAt(Instant.now());
                    m.setCoveredTurnCount(0);
                    return m;
                });
        memory.setContent(content.markdown());
        memory.setLastSummarizedSeq(lastSummarizedSeq);
        memory.setContentHash(content.contentHash());
        memory.setSource(SessionMemorySource.EXTRACTION.name());
        memory.setUpdatedAt(Instant.now());
        memory.setCoveredTurnCount(memory.getCoveredTurnCount() + 1);
        // 1. MySQL（事实源）
        repository.upsert(memory);
        // 2. Redis cache
        cache.set(conversationId, content.markdown());
        log.debug("SessionMemoryService: saved conversationId={}, lastSummarizedSeq={}, contentHash={}",
                conversationId, lastSummarizedSeq, content.contentHash());
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
        extractionPool.submit(() -> doExtraction(conversationId, turnNo));
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
            // lastSummarizedSeq 用 turnNo 估算（本期简化）
            save(conversationId, content, turnNo);
            // 成功 → 重置断路器
            consecutiveFailures.set(0);
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("SessionMemoryService: extraction failed (consecutive={}): {}",
                    failures, e.getMessage());
            // 断路器触发 → 切 fallback
            if (failures >= props.getSessionMemory().getCircuitBreaker().getMaxConsecutiveFailures()) {
                log.warn("SessionMemoryService: circuit breaker triggered, switching to FALLBACK_RULE");
                doFallbackExtraction(conversationId, turnNo);
                consecutiveFailures.set(0);
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
            SessionMemory memory = repository.findByConversationId(conversationId)
                    .orElseGet(() -> {
                        SessionMemory m = new SessionMemory();
                        m.setConversationId(conversationId);
                        m.setCreatedAt(Instant.now());
                        m.setCoveredTurnCount(0);
                        return m;
                    });
            memory.setContent(content.markdown());
            memory.setLastSummarizedSeq((long) turnNo);
            memory.setContentHash(content.contentHash());
            memory.setSource(SessionMemorySource.FALLBACK_RULE.name());
            memory.setUpdatedAt(Instant.now());
            memory.setCoveredTurnCount(memory.getCoveredTurnCount() + 1);
            repository.upsert(memory);
            cache.set(conversationId, content.markdown());
        } catch (Exception e) {
            log.error("SessionMemoryService: fallback extraction also failed: {}", e.getMessage());
        }
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