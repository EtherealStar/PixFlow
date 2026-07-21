package com.pixflow.app.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

public final class JdbcActivityProjectionRepository implements ActivityProjectionRepository {
    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public JdbcActivityProjectionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredActivity> find(ActivitySourceKind sourceKind, String sourceId) {
        List<StoredActivity> rows = jdbc.query(
                """
                select administrator_id, source_revision, view_json
                from app_activity_projection
                where source_kind = ? and source_id = ?
                """,
                (resultSet, rowNumber) -> new StoredActivity(
                        resultSet.getLong("administrator_id"),
                        resultSet.getLong("source_revision"),
                        readNullableView(resultSet.getString("view_json"))),
                sourceKind.name(), sourceId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ActivityFrame> upsert(ActivitySourceEvent event) {
        String viewJson = write(event.view().withSequence(0));
        int changed = insertOrAdvance(event, false, viewJson);
        if (changed == 0) {
            ensureOwner(event);
            return Optional.empty();
        }
        long sequence = appendOutbox(event, event.view().activityId(), viewJson);
        ActivityView sequenced = event.view().withSequence(sequence);
        String sequencedJson = write(sequenced);
        updateStoredFrame(event, sequence, sequencedJson);
        updateOutboxFrame(sequence, sequencedJson);
        return Optional.of(new ActivityFrame(sequence, ActivityOperation.UPSERT,
                sequenced.activityId(), sequenced));
    }

    @Override
    @Transactional
    public Optional<ActivityFrame> remove(ActivitySourceEvent event) {
        String activityId = activityId(event.sourceKind(), event.sourceId());
        int changed = insertOrAdvance(event, true, null);
        if (changed == 0) {
            ensureOwner(event);
            return Optional.empty();
        }
        long sequence = appendOutbox(event, activityId, null);
        updateStoredFrame(event, sequence, null);
        return Optional.of(new ActivityFrame(sequence, ActivityOperation.REMOVE, activityId, null));
    }

    @Override
    public Optional<ActivityView> get(long administratorId, String activityId) {
        List<ActivityView> rows = jdbc.query(
                """
                select view_json from app_activity_projection
                where administrator_id = ? and activity_id = ? and removed = false
                """,
                (resultSet, rowNumber) -> readView(resultSet.getString("view_json")),
                administratorId, activityId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ActivityCommandTarget> getCommandTarget(long administratorId, String activityId) {
        List<ActivityCommandTarget> rows = jdbc.query(
                """
                select source_kind, source_id, view_json from app_activity_projection
                where administrator_id = ? and activity_id = ? and removed = false
                """,
                (resultSet, rowNumber) -> new ActivityCommandTarget(
                        ActivitySourceKind.valueOf(resultSet.getString("source_kind")),
                        resultSet.getString("source_id"),
                        readView(resultSet.getString("view_json"))),
                administratorId, activityId);
        return rows.stream().findFirst();
    }

    @Override
    public ActivityPage list(long administratorId, ActivityFilter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" where administrator_id = ? and removed = false");
        List<Object> arguments = new ArrayList<>();
        arguments.add(administratorId);
        if (filter != null && filter.status() != null) {
            where.append(" and activity_status = ?");
            arguments.add(filter.status().name());
        }
        if (filter != null && filter.kind() != null) {
            where.append(" and activity_kind = ?");
            arguments.add(filter.kind().name());
        }
        Long total = jdbc.queryForObject(
                "select count(*) from app_activity_projection" + where,
                Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((page - 1L) * size);
        List<ActivityView> records = jdbc.query(
                "select view_json from app_activity_projection" + where
                        + " order by updated_at desc, activity_id desc limit ? offset ?",
                (resultSet, rowNumber) -> readView(resultSet.getString("view_json")),
                pageArguments.toArray());
        Long cursor = jdbc.queryForObject(
                "select coalesce(max(sequence), 0) from app_activity_projection where administrator_id = ?",
                Long.class, administratorId);
        return new ActivityPage(records, value(total), page, size, value(cursor));
    }

    @Override
    public List<StoredSource> currentSources(long administratorId, ActivitySourceKind sourceKind) {
        return jdbc.query(
                """
                select source_id, source_revision from app_activity_projection
                where administrator_id = ? and source_kind = ? and removed = false
                """,
                (resultSet, rowNumber) -> new StoredSource(
                        resultSet.getString("source_id"), resultSet.getLong("source_revision")),
                administratorId, sourceKind.name());
    }

    @Override
    public List<PendingActivityFrame> pending(int limit) {
        return jdbc.query(
                """
                select sequence, administrator_id, operation, activity_id, view_json
                from app_activity_event_outbox
                where delivered_at is null
                order by sequence
                limit ?
                """,
                (resultSet, rowNumber) -> {
                    long sequence = resultSet.getLong("sequence");
                    ActivityOperation operation = ActivityOperation.valueOf(resultSet.getString("operation"));
                    ActivityView view = readNullableView(resultSet.getString("view_json"));
                    ActivityFrame frame = new ActivityFrame(
                            sequence, operation, resultSet.getString("activity_id"), view);
                    return new PendingActivityFrame(resultSet.getLong("administrator_id"), frame);
                },
                limit);
    }

    @Override
    public void markDelivered(long sequence) {
        jdbc.update(
                """
                update app_activity_event_outbox set delivered_at = ?
                where sequence = ? and delivered_at is null
                """,
                Timestamp.from(clock.instant()), sequence);
    }

    private int insertOrAdvance(ActivitySourceEvent event, boolean removed, String viewJson) {
        try {
            jdbc.update(
                    """
                    insert into app_activity_projection
                        (source_kind, source_id, administrator_id, activity_id, source_revision,
                         sequence, removed, activity_kind, activity_status, view_json, updated_at)
                    values (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                    """,
                    event.sourceKind().name(), event.sourceId(), event.administratorId(),
                    activityId(event.sourceKind(), event.sourceId()), event.sourceRevision(), removed,
                    event.view() == null ? null : event.view().kind().name(),
                    event.view() == null ? null : event.view().status().name(), viewJson,
                    Timestamp.from(clock.instant()));
            return 1;
        } catch (DuplicateKeyException ignored) {
            return jdbc.update(
                    """
                    update app_activity_projection
                    set source_revision = ?, removed = ?, activity_kind = ?, activity_status = ?,
                        view_json = ?, updated_at = ?
                    where source_kind = ? and source_id = ? and administrator_id = ?
                      and source_revision < ?
                    """,
                    event.sourceRevision(), removed,
                    event.view() == null ? null : event.view().kind().name(),
                    event.view() == null ? null : event.view().status().name(), viewJson,
                    Timestamp.from(clock.instant()), event.sourceKind().name(), event.sourceId(),
                    event.administratorId(), event.sourceRevision());
        }
    }

    private long appendOutbox(ActivitySourceEvent event, String activityId, String viewJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    insert into app_activity_event_outbox
                        (administrator_id, operation, activity_id, view_json, created_at)
                    values (?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, event.administratorId());
            statement.setString(2, event.operation().name());
            statement.setString(3, activityId);
            statement.setString(4, viewJson);
            statement.setTimestamp(5, Timestamp.from(clock.instant()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("activity outbox did not return a sequence");
        }
        return key.longValue();
    }

    private void updateStoredFrame(ActivitySourceEvent event, long sequence, String viewJson) {
        jdbc.update(
                """
                update app_activity_projection set sequence = ?, view_json = ?
                where source_kind = ? and source_id = ? and source_revision = ?
                """,
                sequence, viewJson, event.sourceKind().name(), event.sourceId(), event.sourceRevision());
    }

    private void updateOutboxFrame(long sequence, String viewJson) {
        jdbc.update("update app_activity_event_outbox set view_json = ? where sequence = ?",
                viewJson, sequence);
    }

    private void ensureOwner(ActivitySourceEvent event) {
        Optional<StoredActivity> current = find(event.sourceKind(), event.sourceId());
        if (current.isPresent() && current.orElseThrow().administratorId() != event.administratorId()) {
            throw new IllegalStateException("activity source ownership cannot change");
        }
    }

    private ActivityView readNullableView(String json) {
        return json == null ? null : readView(json);
    }

    private ActivityView readView(String json) {
        try {
            return objectMapper.readValue(json, ActivityView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid activity projection JSON", exception);
        }
    }

    private String write(ActivityView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize activity projection", exception);
        }
    }

    private static String activityId(ActivitySourceKind sourceKind, String sourceId) {
        return sourceKind.name().toLowerCase(java.util.Locale.ROOT) + ":" + sourceId;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }
}
