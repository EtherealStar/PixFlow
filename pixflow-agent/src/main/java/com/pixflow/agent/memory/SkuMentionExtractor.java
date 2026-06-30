package com.pixflow.agent.memory;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从 assistant message 中抽取 SKU 列表（两阶段：规则 + LLM 兜底）。
 *
 * <p>对应 {@code agent.md §6.4}：
 * <ol>
 *   <li>规则阶段：regex 匹配配置 patterns（默认 {@code ^SKU\\d+$} / {@code ^[A-Z]{2,}\\d{4,}$}），
 *       命中 ≥ 3 个直接返回</li>
 *   <li>LLM 阶段：命中 < 3 时调 LLM 兜底抽取 + Redis 缓存（24h TTL）</li>
 * </ol>
 *
 * <p>缓存键：{@code pixflow:agent:sku_extract:{md5(messageContent)}}。
 */
@Component
public class SkuMentionExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkuMentionExtractor.class);
    private static final String CACHE_PREFIX = "pixflow:agent:sku_extract:";
    private static final int CACHE_TTL_HOURS = 24;
    private static final int MIN_RULE_HITS_TO_SKIP_LLM = 3;
    private static final int MAX_RETURN = 20;

    private final AgentProperties props;
    private final ChatModelClient chatModelClient;
    private final RedissonClient redissonClient;
    private final List<Pattern> compiledPatterns;

    public SkuMentionExtractor(AgentProperties props,
                               ChatModelClient chatModelClient,
                               RedissonClient redissonClient) {
        this.props = props;
        this.chatModelClient = chatModelClient;
        this.redissonClient = redissonClient;
        this.compiledPatterns = props.getMemory().getRecall().getSkuPatterns().stream()
                .map(SkuMentionExtractor::compile)
                .collect(Collectors.toList());
    }

    /**
     * 从一段 assistant 消息文本抽取 SKU 列表。
     */
    public List<String> extract(String messageContent) {
        if (messageContent == null || messageContent.isBlank()) {
            return List.of();
        }
        // 阶段 1: regex
        List<String> ruleHits = ruleExtract(messageContent);
        if (ruleHits.size() >= MIN_RULE_HITS_TO_SKIP_LLM) {
            return ruleHits.stream().limit(MAX_RETURN).toList();
        }
        // 阶段 2: LLM 兜底（带 Redis 缓存）
        if (!props.getMemory().getRecall().isSkuLlmFallback()) {
            return ruleHits;
        }
        String cacheKey = CACHE_PREFIX + md5(messageContent);
        try {
            var bucket = redissonClient.getBucket(cacheKey);
            String cached = bucket.get() == null ? null : bucket.get().toString();
            if (cached != null) {
                return parseList(cached);
            }
        } catch (Exception e) {
            log.debug("SkuMentionExtractor: cache read failed: {}", e.getMessage());
        }
        List<String> llmHits = llmExtract(messageContent);
        try {
            redissonClient.getBucket(cacheKey).set(
                    String.join(",", llmHits),
                    CACHE_TTL_HOURS, TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.debug("SkuMentionExtractor: cache write failed: {}", e.getMessage());
        }
        return llmHits.stream().limit(MAX_RETURN).toList();
    }

    private List<String> ruleExtract(String content) {
        List<String> hits = new ArrayList<>();
        for (Pattern p : compiledPatterns) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                hits.add(m.group());
            }
        }
        return hits.stream().distinct().toList();
    }

    private List<String> llmExtract(String content) {
        try {
            String prompt = "从以下文本中提取提到的 SKU ID 列表。返回纯 JSON 数组（无其它说明）。\n\n"
                    + "文本：\n" + content;
            ChatRequest request = new ChatRequest(
                    null, // role
                    List.of(new ChatMessage(ChatMessage.Role.USER,
                            List.of(new ChatMessage.TextPart(prompt)))),
                    List.of(),  // toolSchemas
                    null,        // toolChoice
                    null         // options
            );
            ChatResult result = chatModelClient.call(request);
            String text = result.finalText();
            return parseList(text);
        } catch (Exception e) {
            log.warn("SkuMentionExtractor: LLM extract failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> parseList(String text) {
        if (text == null) return List.of();
        String trimmed = text.strip();
        if (trimmed.startsWith("[")) {
            int end = trimmed.lastIndexOf(']');
            if (end > 0) {
                trimmed = trimmed.substring(1, end);
            }
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String cleaned = part.strip().replaceAll("^\"|\"$", "");
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex);
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