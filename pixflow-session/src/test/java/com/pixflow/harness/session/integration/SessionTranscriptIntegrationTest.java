package com.pixflow.harness.session.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.context.model.ToolResultReference;
import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.chain.ActiveChainResolver;
import com.pixflow.harness.session.config.SessionProperties;
import com.pixflow.harness.session.externalize.SessionToolResultExternalizer;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.persistence.CompactionEntity;
import com.pixflow.harness.session.persistence.CompactionMapper;
import com.pixflow.harness.session.persistence.MessageEntity;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.harness.session.persistence.MessageWriteMapper;
import com.pixflow.harness.session.persistence.TranscriptService;
import com.pixflow.harness.session.seq.SequenceAllocator;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.DefaultStorageBucketResolver;
import com.pixflow.infra.storage.MinioObjectStorage;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageInitializer;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.infra.storage.StorageProperties;
import com.pixflow.infra.storage.StorageBucketResolver;
import com.pixflow.infra.storage.toolresult.ObjectStorageToolResultStorage;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SessionTranscriptIntegrationTest {
    private static final String MYSQL_DATABASE = "pixflow";
    private static final String MYSQL_USERNAME = "pixflow";
    private static final String MYSQL_PASSWORD = "pixflow";
    private static final String MINIO_USERNAME = "minioadmin";
    private static final String MINIO_PASSWORD = "minioadmin";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(MYSQL_DATABASE)
            .withUsername(MYSQL_USERNAME)
            .withPassword(MYSQL_PASSWORD);

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", MINIO_USERNAME)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withCommand("server", "/data");

    private static TranscriptService service;
    private static ToolResultStorage toolResultStorage;
    private static MinioClient minioClient;
    private static ObjectStorage objectStorage;
    private static String jdbcUrl;

    @BeforeAll
    static void setUp() throws Exception {
        MYSQL.start();
        MINIO.start();

        jdbcUrl = MYSQL.getJdbcUrl();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, MYSQL_USERNAME, MYSQL_PASSWORD)) {
            runMigration(connection);
        }

        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        storageProperties.setAccessKey(MINIO_USERNAME);
        storageProperties.setSecretKey(MINIO_PASSWORD);
        storageProperties.setUploadPartSize(DataSize.ofMegabytes(5));
        storageProperties.getBuckets().setToolResults("pixflow-tool-results");

        StorageBucketResolver bucketResolver = new DefaultStorageBucketResolver(storageProperties);
        minioClient = MinioClient.builder()
                .endpoint(storageProperties.getEndpoint())
                .credentials(MINIO_USERNAME, MINIO_PASSWORD)
                .build();
        new StorageInitializer(minioClient, bucketResolver, storageProperties).run(null);
        objectStorage = new MinioObjectStorage(minioClient, bucketResolver, storageProperties);
        toolResultStorage = new ObjectStorageToolResultStorage(objectStorage);

        SessionProperties properties = new SessionProperties();
        properties.setWriteMode(SessionProperties.WriteMode.SYNC);
        properties.getExternalize().setToolResultThreshold(DataSize.ofBytes(4));
        properties.getExternalize().setPreviewChars(3);
        properties.getLoad().setMaxMessages(20);
        properties.getSeq().setAllocationRetry(3);

        MessageMapper messageMapper = new MessageMapper(new ObjectMapper());
        MysqlMessageWriteMapper writeMapper = new MysqlMessageWriteMapper(jdbcUrl, MYSQL_USERNAME, MYSQL_PASSWORD);
        MysqlMessageReadMapper readMapper = new MysqlMessageReadMapper(jdbcUrl, MYSQL_USERNAME, MYSQL_PASSWORD);
        MysqlCompactionMapper compactionMapper = new MysqlCompactionMapper(jdbcUrl, MYSQL_USERNAME, MYSQL_PASSWORD);
        SessionToolResultExternalizer externalizer = new SessionToolResultExternalizer(toolResultStorage,
                properties.getExternalize().getToolResultThreshold().toBytes(),
                properties.getExternalize().getPreviewChars());

        service = new TranscriptService(
                writeMapper,
                readMapper,
                compactionMapper,
                messageMapper,
                new SequenceAllocator(writeMapper),
                new TranscriptBuffer(10, 1024),
                new ActiveChainResolver(readMapper, compactionMapper),
                externalizer,
                properties,
                null);
    }

    @AfterAll
    static void tearDown() {
        if (MINIO.isRunning()) {
            MINIO.stop();
        }
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void appendLoadAndCompactionRoundTripAgainstRealMysqlAndMinio() throws Exception {
        List<Message> appended = service.append("conv-1", List.of(
                new Message("m1", MessageRole.USER, "hello", null, MessageMetadata.empty(), Instant.now()),
                new Message("m2", MessageRole.TOOL_RESULT, "123456789", "tool-1", MessageMetadata.empty(), Instant.now()),
                Message.user("process", List.of(
                        new MessageReference("package:1", "summer.zip"),
                        new MessageReference("package:1/image:2", "summer.zip / front.png")))));

        assertThat(appended).hasSize(3);
        assertThat(appended.get(1).metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)).isTrue();
        assertThat(service.load("conv-1")).extracting(Message::id)
                .containsExactly("m1", "m2", appended.get(2).id());
        assertThat(service.load("conv-1").get(1).content()).isEqualTo("123456789");
        assertThat(service.load("conv-1").get(2).metadata().references()).containsExactly(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"));

        Message boundary = new Message("b1", MessageRole.USER, "boundary", null,
                MessageMetadata.empty().with(MessageMetadata.COMPACT_BOUNDARY, true), Instant.now());
        Message summary = new Message("s1", MessageRole.USER, "summary", null,
                MessageMetadata.empty().with(MessageMetadata.COMPACT_SUMMARY, true), Instant.now());
        List<Message> compacted = service.replaceForCompaction(
                "conv-1",
                List.of(boundary, summary, appended.get(2)),
                CompactionTrigger.AUTO,
                Map.of("reason", "it"));

        assertThat(compacted).extracting(Message::id)
                .containsExactly("b1", "s1", appended.get(2).id());
        List<Message> reloaded = service.load("conv-1");
        assertThat(reloaded).extracting(Message::id)
                .containsExactly("b1", "s1", appended.get(2).id());
        assertThat(reloaded.get(2).metadata().references()).containsExactly(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"));
    }

    @Test
    void loadReturnsPreviewWhenExternalObjectMissing() {
        service.append("conv-2", List.of(new Message("m3", MessageRole.TOOL_RESULT, "abcdef", "tool-2",
                MessageMetadata.empty(), Instant.now())));

        List<Message> loaded = service.load("conv-2");
        assertThat(loaded).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("abcdef");
            assertThat(message.metadata().flag(SessionToolResultExternalizer.MISSING_EXTERNAL_TOOL_RESULT)).isFalse();
        });

        ToolResultReference reference = (ToolResultReference) loaded.get(0).metadata().values().get(MessageMetadata.TOOL_RESULT_REF);
        objectStorage.delete(StorageKeys.toolResult(reference.id()));

        List<Message> degraded = service.load("conv-2");
        assertThat(degraded).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("abc");
            assertThat(message.metadata().flag(SessionToolResultExternalizer.MISSING_EXTERNAL_TOOL_RESULT)).isTrue();
        });
    }

    private static void runMigration(Connection connection) throws Exception {
        String sql = """
                CREATE TABLE IF NOT EXISTS message (
                  id                  VARCHAR(64) PRIMARY KEY,
                  conversation_id     VARCHAR(64)  NOT NULL,
                  seq                 BIGINT       NOT NULL,
                  role                VARCHAR(32)  NOT NULL,
                  content             MEDIUMTEXT   NULL,
                  tool_call_id        VARCHAR(128) NULL,
                  compaction_marker   VARCHAR(32)  NULL,
                  metadata            JSON         NULL,
                  task_id             VARCHAR(64)  NULL,
                  created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  UNIQUE KEY uk_message_conversation_seq (conversation_id, seq),
                  KEY idx_message_conversation_marker (conversation_id, compaction_marker),
                  KEY idx_message_conversation_seq (conversation_id, seq)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

                CREATE TABLE IF NOT EXISTS message_compaction (
                  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
                  conversation_id     VARCHAR(64) NOT NULL,
                  boundary_message_id VARCHAR(64) NOT NULL,
                  summary_message_id  VARCHAR(64) NOT NULL,
                  covered_up_to_seq   BIGINT      NOT NULL,
                  compaction_trigger  VARCHAR(32) NOT NULL,
                  metadata            JSON        NULL,
                  created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  KEY idx_message_compaction_conversation_id (conversation_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
        try (Statement statement = connection.createStatement()) {
            for (String fragment : sql.split(";")) {
                String trimmed = fragment.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private static final class MysqlMessageWriteMapper implements MessageWriteMapper {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private MysqlMessageWriteMapper(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public long maxSeq(String conversationId) {
            return queryLong("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = ?", conversationId);
        }

        @Override
        public int insertIgnoreBatch(List<MessageEntity> messages) {
            int inserted = 0;
            for (MessageEntity message : messages) {
                String sql = """
                   INSERT IGNORE INTO message
                     (id, conversation_id, seq, role, content, tool_call_id, compaction_marker, metadata,
                      task_id, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                        """;
                inserted += executeUpdate(sql,
                        message.getId(),
                        message.getConversationId(),
                        message.getSeq(),
                        message.getRole(),
                        message.getContent(),
                   message.getToolCallId(),
                   message.getCompactionMarker(),
                   message.getMetadata(),
                   message.getTaskId(),
                        message.getCreatedAt());
            }
            return inserted;
        }

        private long queryLong(String sql, Object arg) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, arg);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private int executeUpdate(String sql, Object... args) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                return statement.executeUpdate();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class MysqlMessageReadMapper implements MessageReadMapper {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private MysqlMessageReadMapper(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public MessageEntity findById(String id) {
            List<MessageEntity> found = queryMessages("WHERE id = ?", id);
            return found.isEmpty() ? null : found.get(0);
        }

        @Override
        public List<MessageEntity> findNormalMessages(String conversationId) {
            return queryMessages("WHERE conversation_id = ? AND compaction_marker IS NULL ORDER BY seq", conversationId);
        }

        @Override
        public List<MessageEntity> findNormalMessagesAfter(String conversationId, long coveredUpToSeq) {
            return queryMessages(
                    "WHERE conversation_id = ? AND compaction_marker IS NULL AND seq > ? ORDER BY seq",
                    conversationId,
                    coveredUpToSeq);
        }

        @Override
        public List<MessageEntity> findByIds(List<String> ids) {
            if (ids.isEmpty()) {
                return List.of();
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            return queryMessages("WHERE id IN (" + placeholders + ")", ids.toArray());
        }

        @Override
        public long maxNormalSeq(String conversationId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(
                         "SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = ? AND compaction_marker IS NULL")) {
                statement.setObject(1, conversationId);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
    public List<MessageEntity> findMessagesByConversation(
                String conversationId,
                long offset,
                long limit) {
        return queryMessages("WHERE conversation_id = ? ORDER BY seq LIMIT ? OFFSET ?",
                conversationId,
                limit,
                offset);
        }

        @Override
        public long countMessagesByConversation(String conversationId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM message WHERE conversation_id = ?")) {
                statement.setObject(1, conversationId);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private List<MessageEntity> queryMessages(String suffix, Object... args) {
            String sql = """
               SELECT id, conversation_id AS conversationId, seq, role, content,
                      tool_call_id AS toolCallId, compaction_marker AS compactionMarker, metadata,
                      task_id AS taskId, created_at AS createdAt
                    FROM message
                    """ + suffix;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                try (var rs = statement.executeQuery()) {
                    List<MessageEntity> result = new ArrayList<>();
                    while (rs.next()) {
                        MessageEntity entity = new MessageEntity();
                        entity.setId(rs.getString("id"));
                        entity.setConversationId(rs.getString("conversationId"));
                        entity.setSeq(rs.getLong("seq"));
                        entity.setRole(rs.getString("role"));
                        entity.setContent(rs.getString("content"));
                        entity.setToolCallId(rs.getString("toolCallId"));
                   entity.setCompactionMarker(rs.getString("compactionMarker"));
                   entity.setMetadata(rs.getString("metadata"));
                   entity.setTaskId(rs.getString("taskId"));
                        entity.setCreatedAt(rs.getTimestamp("createdAt").toInstant());
                        result.add(entity);
                    }
                    return result;
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class MysqlCompactionMapper implements CompactionMapper {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private MysqlCompactionMapper(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public CompactionEntity findLatest(String conversationId) {
            String sql = """
                    SELECT id, conversation_id AS conversationId, boundary_message_id AS boundaryMessageId,
                           summary_message_id AS summaryMessageId, covered_up_to_seq AS coveredUpToSeq,
                           compaction_trigger AS compactionTrigger, metadata, created_at AS createdAt
                    FROM message_compaction
                    WHERE conversation_id = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, conversationId);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    CompactionEntity entity = new CompactionEntity();
                    entity.setId(rs.getLong("id"));
                    entity.setConversationId(rs.getString("conversationId"));
                    entity.setBoundaryMessageId(rs.getString("boundaryMessageId"));
                    entity.setSummaryMessageId(rs.getString("summaryMessageId"));
                    entity.setCoveredUpToSeq(rs.getLong("coveredUpToSeq"));
                    entity.setTrigger(rs.getString("compactionTrigger"));
                    entity.setMetadata(rs.getString("metadata"));
                    entity.setCreatedAt(rs.getTimestamp("createdAt").toInstant());
                    return entity;
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public int insert(CompactionEntity entity) {
                    String sql = """
                    INSERT INTO message_compaction
                      (conversation_id, boundary_message_id, summary_message_id, covered_up_to_seq,
                       compaction_trigger, metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setObject(1, entity.getConversationId());
                statement.setObject(2, entity.getBoundaryMessageId());
                statement.setObject(3, entity.getSummaryMessageId());
                statement.setObject(4, entity.getCoveredUpToSeq());
                statement.setObject(5, entity.getTrigger());
                statement.setObject(6, entity.getMetadata());
                statement.setObject(7, entity.getCreatedAt());
                return statement.executeUpdate();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
