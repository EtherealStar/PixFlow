package com.pixflow.harness.eval.store;

import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.model.TraceQueryCriteria;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class InMemoryAgentTraceRepository implements AgentTraceRepository {
    private final Map<Key, AgentTraceEntity> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @Override
    public synchronized void upsertBatch(List<AgentTraceEntity> entities) {
        for (AgentTraceEntity entity : entities) {
            Key key = new Key(entity.conversationId(), entity.turnNo());
            AgentTraceEntity existing = rows.get(key);
            // trace 状态只能单向升级：迟到的 OPEN 不能覆盖已经完成或中止的回合。
            if (entity.turnStatus().canReplace(existing == null ? null : existing.turnStatus())) {
                rows.put(key, withIdAndCreatedAt(entity, existing));
            }
        }
    }

    @Override
    public synchronized Optional<AgentTraceEntity> findByTurn(String conversationId, int turnNo) {
        return Optional.ofNullable(rows.get(new Key(conversationId, turnNo)));
    }

    @Override
    public synchronized PageResponse<AgentTraceEntity> listByConversation(String conversationId, Pagination page) {
        List<AgentTraceEntity> filtered = rows.values().stream()
                .filter(row -> row.conversationId().equals(conversationId))
                .sorted(Comparator.comparingInt(AgentTraceEntity::turnNo))
                .toList();
        return page(filtered, page);
    }

    @Override
    public synchronized PageResponse<AgentTraceEntity> query(TraceQueryCriteria criteria, Pagination page) {
        Stream<AgentTraceEntity> stream = rows.values().stream();
        if (criteria != null) {
            if (criteria.from() != null) {
                stream = stream.filter(row -> !row.createdAt().isBefore(criteria.from()));
            }
            if (criteria.to() != null) {
                stream = stream.filter(row -> !row.createdAt().isAfter(criteria.to()));
            }
            if (criteria.conversationId() != null) {
                stream = stream.filter(row -> row.conversationId().equals(criteria.conversationId()));
            }
            if (criteria.traceId() != null) {
                stream = stream.filter(row -> row.traceId().equals(criteria.traceId()));
            }
            if (criteria.turnStatus() != null) {
                stream = stream.filter(row -> row.turnStatus() == criteria.turnStatus());
            }
            if (criteria.runtimeScope() != null) {
                stream = stream.filter(row -> row.runtimeScope() == criteria.runtimeScope());
            }
            if (criteria.toolName() != null) {
                stream = stream.filter(row -> row.toolCallsJson() != null && row.toolCallsJson().contains(criteria.toolName()));
            }
            if (criteria.hasError() != null) {
                stream = stream.filter(row -> hasJsonContent(row.errorJson()) == criteria.hasError());
            }
            if (criteria.hasPrune() != null) {
                stream = stream.filter(row -> hasJsonContent(row.pruneLogJson()) == criteria.hasPrune());
            }
        }
        List<AgentTraceEntity> filtered = stream.sorted(Comparator.comparing(AgentTraceEntity::createdAt)).toList();
        return page(filtered, page);
    }

    @Override
    public synchronized List<AgentTraceEntity> findExpired(Instant cutoff, int limit) {
        return rows.values().stream()
                .filter(row -> row.createdAt().isBefore(cutoff))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void deleteByIds(List<Long> ids) {
        rows.entrySet().removeIf(entry -> ids.contains(entry.getValue().id()));
    }

    private static boolean hasJsonContent(String json) {
        return json != null && !json.isBlank() && !"[]".equals(json);
    }

    private static PageResponse<AgentTraceEntity> page(List<AgentTraceEntity> rows, Pagination page) {
        int from = (int) Math.min(rows.size(), (page.page() - 1) * page.size());
        int to = (int) Math.min(rows.size(), from + page.size());
        return PageResponse.of(rows.subList(from, to), rows.size(), page.page(), page.size());
    }

    private AgentTraceEntity withIdAndCreatedAt(AgentTraceEntity entity, AgentTraceEntity existing) {
        return new AgentTraceEntity(
                existing == null ? ids.getAndIncrement() : existing.id(),
                entity.conversationId(),
                entity.turnNo(),
                entity.traceId(),
                entity.schemaVersion(),
                entity.turnStatus(),
                entity.runtimeScope(),
                entity.inputJson(),
                entity.toolCallsJson(),
                entity.recallJson(),
                entity.pruneLogJson(),
                entity.errorJson(),
                existing == null ? entity.createdAt() : existing.createdAt(),
                entity.updatedAt());
    }

    private record Key(String conversationId, int turnNo) {
    }
}
