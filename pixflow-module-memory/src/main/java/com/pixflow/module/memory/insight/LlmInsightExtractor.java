package com.pixflow.module.memory.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.module.memory.ingest.MemoryIngestRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class LlmInsightExtractor implements InsightExtractor {
    private final ChatModelClient chatModelClient;
    private final ObjectMapper objectMapper;

    public LlmInsightExtractor(ChatModelClient chatModelClient, ObjectMapper objectMapper) {
        this.chatModelClient = Objects.requireNonNull(chatModelClient, "chatModelClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public List<ExtractedInsight> extract(MemoryIngestRequest request, List<MemoryItemSnapshot> neighborContext) {
        String prompt = buildPrompt(request, neighborContext);
        ChatRequest chatRequest = new ChatRequest(
                ModelRole.PRIMARY_CHAT,
                List.of(
                        new ChatMessage(ChatMessage.Role.SYSTEM, List.of(new ChatMessage.TextPart(systemPrompt()))),
                        new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart(prompt)))),
                List.of(),
                ToolChoice.NONE,
                new ChatOptions(0.1d, 1200, Duration.ofSeconds(60)));
        return parse(chatModelClient.call(chatRequest).finalText());
    }

    private List<ExtractedInsight> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode insights = root.isArray() ? root : root.path("insights");
            if (!insights.isArray()) {
                return List.of();
            }
            List<ExtractedInsight> result = new ArrayList<>();
            for (JsonNode node : insights) {
                ExtractedInsight insight = new ExtractedInsight(
                        node.path("text").asText(""),
                        node.path("category").asText(""),
                        node.path("source").asText("turn_consolidation"),
                        node.path("confidence").asDouble(0.5),
                        node.path("relatedSku").asText(node.path("related_sku").asText("")),
                        node.path("importance").asDouble(0.5),
                        parseInstant(node.path("expiresAt").asText(node.path("expires_at").asText(""))),
                        parseConflicts(node.path("conflictsWith").isMissingNode() ? node.path("conflicts_with") : node.path("conflictsWith")));
                if (insight.valid()) {
                    result.add(insight);
                }
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> parseConflicts(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : node) {
            if (!id.asText("").isBlank()) {
                ids.add(id.asText());
            }
        }
        return ids;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String systemPrompt() {
        return "你是 PixFlow 的记忆抽取器。只输出 JSON，不要输出解释。"
                + "从本轮上下文抽取自含上下文的电商图片处理原子结论；没有可靠结论时输出 {\"insights\":[]}";
    }

    private static String buildPrompt(MemoryIngestRequest request, List<MemoryItemSnapshot> neighbors) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户指令:\n").append(nullToEmpty(request.userPrompt())).append("\n\n");
        builder.append("最终回答:\n").append(nullToEmpty(request.assistantAnswer())).append("\n\n");
        builder.append("相关 SKU: ").append(request.skuIds()).append("\n");
        builder.append("相关类目: ").append(request.categories()).append("\n");
        builder.append("工具观察:\n").append(request.toolObservations()).append("\n\n");
        builder.append("近邻已有记忆，用于去重和冲突判断:\n").append(neighbors).append("\n\n");
        builder.append("输出格式: {\"insights\":[{\"text\":\"...\",\"category\":\"...\",\"source\":\"...\",")
                .append("\"confidence\":0.8,\"importance\":0.7,\"relatedSku\":\"SKU123\",")
                .append("\"conflictsWith\":[\"旧记忆id\"]}]}");
        return builder.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
