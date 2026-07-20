package com.pixflow.module.rubrics.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.TaskDecisionSubject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 通过 Eval 只读 {@link TraceQuery} 选择 Task Decision 的 trace span。
 *
 * <p>按 {@code conversationId} 列出有界数量的 turn，把每个含 tool call 的 turn 投影成一条
 * {@link EvidenceType#TRACE_SPAN} 证据。trace 读取失败、为空或超过保留期都返回空列表：
 * 不抛异常、不伪造 span，由声明该 evidence type 的 criterion 自然得到 MISSING_EVIDENCE。
 *
 * <p>条目按 turnNo 升序赋予稳定 ID（T1、T2……），使相同 trace 在重放时产生相同 Evidence Pack identity。
 */
public final class DefaultTraceEvidenceProvider implements TraceEvidenceProvider {

    /** 单次评估最多投影的 turn 数，避免长会话证据无限膨胀。 */
    static final int MAX_TURNS = 50;

    /** 单条 span 的 UTF-8 字节上限，超限按字符边界安全截断并标记 truncated。 */
    static final int MAX_SPAN_BYTES = 8192;

    private final TraceQuery query;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    public DefaultTraceEvidenceProvider(TraceQuery query, Clock clock, ObjectMapper objectMapper) {
        this.query = query;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<EvidenceEntry> trace(TaskDecisionSubject subject) {
        String conversationId = subject.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            // Subject 没有 conversation 引用时无法定位 trace，按 best-effort 返回空。
            return List.of();
        }
        PageResponse<TurnTraceRecord> page;
        List<TurnTraceRecord> records;
        try {
            // TraceQuery 没有倒序/offset seam；先用单行页取得 total，再读取尾部窗口。
            page = query.listByConversation(conversationId, new Pagination(1, 1));
            long lastPage = Math.max(1, (page.total() + MAX_TURNS - 1) / MAX_TURNS);
            if (lastPage > 1) {
                // 末页可能不足 50 条；合并末两页后再精确截取最新窗口，读取量仍固定有界。
                List<TurnTraceRecord> previous = query.listByConversation(
                        conversationId, new Pagination(lastPage - 1, MAX_TURNS)).records();
                List<TurnTraceRecord> latest = query.listByConversation(
                        conversationId, new Pagination(lastPage, MAX_TURNS)).records();
                List<TurnTraceRecord> tail = new ArrayList<>(previous.size() + latest.size());
                tail.addAll(previous);
                tail.addAll(latest);
                records = List.copyOf(tail.subList(
                        Math.max(0, tail.size() - MAX_TURNS), tail.size()));
            } else {
                records = query.listByConversation(
                        conversationId, new Pagination(1, MAX_TURNS)).records();
            }
        } catch (RuntimeException error) {
            // trace 是 best-effort：读取失败不升级为 Subject 失败，也不伪造 span。
            return List.of();
        }
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        // 按 turnNo 排序后赋予稳定 ID，保证相同 trace 重放得到相同 pack identity。
        List<TurnTraceRecord> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparingInt(TurnTraceRecord::turnNo));
        var capturedAt = clock.instant();
        List<EvidenceEntry> entries = new ArrayList<>(ordered.size());
        int index = 1;
        for (TurnTraceRecord turn : ordered) {
            String toolCalls = nonBlank(turn.toolCallsJson());
            if (toolCalls == null || !matchesDecisionRevision(toolCalls, subject.revision())) {
                // 只保留产生当前已确认决定的 turn，避免旧 Proposal 的 trace 泄漏到本次评估。
                continue;
            }
            String bounded = boundBytes(toolCalls);
            boolean truncated = bounded.length() != toolCalls.length();
            String sourceRef = "trace:" + conversationId + ":" + turn.turnNo();
            Map<String, Object> metadata = new TreeMap<>();
            metadata.put("turnNo", turn.turnNo());
            // traceId 为 null 时不写入空串占位，避免用空串冒充缺失的观测身份。
            if (turn.traceId() != null) {
                metadata.put("traceId", turn.traceId());
            }
            metadata.put("toolCalls", bounded);
            if (truncated) {
                metadata.put("truncated", true);
            }
            entries.add(new EvidenceEntry(
                    "T" + index,
                    EvidenceType.TRACE_SPAN,
                    sourceRef,
                    EvidenceHashing.sha256(bounded.getBytes(StandardCharsets.UTF_8)),
                    capturedAt,
                    metadata));
            index++;
        }
        return List.copyOf(entries);
    }

    private boolean matchesDecisionRevision(String toolCalls, String revision) {
        try {
            JsonNode root = objectMapper.readTree(toolCalls);
            if (root == null || !root.isArray()) {
                return false;
            }
            for (JsonNode call : root) {
                String toolName = call.path("name").asText("");
                if (!"submit_image_plan".equals(toolName)
                        && !"submit_imagegen_plan".equals(toolName)) {
                    continue;
                }
                JsonNode result = call.path("result");
                if (result.isObject()
                        && !result.path("error").asBoolean(false)
                        && revision.equals(result.path("payloadHash").asText(null))) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException error) {
            // 无法结构化验证受控 result metadata 的 trace 不进入证据。
            return false;
        }
    }

    /** 把空串、纯空白和空数组都视为无 tool call。 */
    private static String nonBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return "[]".equals(trimmed) ? null : value;
    }

    /** 按 UTF-8 字节上限安全截断，回退到完整字符边界，避免拆开多字节字符产生非法 UTF-8。 */
    private static String boundBytes(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_SPAN_BYTES) {
            return value;
        }
        int end = MAX_SPAN_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
