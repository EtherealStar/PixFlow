package com.pixflow.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pixflow.contracts.confirmation.ConfirmationAction;
import com.pixflow.contracts.confirmation.ConfirmationLevel;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.infra.cache.confirmation.RedisConfirmationTokenStore;
import com.pixflow.infra.cache.counter.RedissonAtomicCounter;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.observability.NoopCacheMetrics;
import com.pixflow.infra.cache.store.RedissonCacheStore;
import com.pixflow.infra.cache.state.RedissonExpiringHashStore;
import com.pixflow.infra.cache.state.RedissonExpiringStateStore;
import com.pixflow.infra.cache.tokenbucket.RedisLuaTokenBucket;
import com.pixflow.infra.cache.tokenbucket.TokenBucketDecision;
import com.pixflow.infra.cache.tokenbucket.TokenBucketPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static RedissonClient redissonClient;
    private static ObjectMapper objectMapper;
    private static CacheNamespace namespace;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        namespace = new DefaultCacheNamespace("test", Duration.ofSeconds(5));
    }

    @AfterAll
    static void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @Test
    void cacheStoreRoundTripsValueAndExpires() throws InterruptedException {
        RedissonCacheStore store = new RedissonCacheStore(redissonClient, objectMapper, new NoopCacheMetrics());
        CacheKey key = namespace.key("alpha", "roundtrip");

        store.put(key, new SampleValue("ok", 7), Duration.ofMillis(300));

        Optional<SampleValue> value = store.get(key, SampleValue.class);
        assertThat(value).contains(new SampleValue("ok", 7));

        Thread.sleep(450);
        assertThat(store.get(key, SampleValue.class)).isEmpty();
    }

    @Test
    void putIfAbsentIsAtomicForSameKey() {
        RedissonCacheStore store = new RedissonCacheStore(redissonClient, objectMapper, new NoopCacheMetrics());
        CacheKey key = namespace.key("alpha", "once");

        assertThat(store.putIfAbsent(key, "first", Duration.ofSeconds(2))).isTrue();
        assertThat(store.putIfAbsent(key, "second", Duration.ofSeconds(2))).isFalse();
        assertThat(store.get(key, String.class)).contains("first");
    }

    @Test
    void expiringStateStoreRoundTripsPlainJsonAndExpires() throws InterruptedException {
        RedissonExpiringStateStore store = new RedissonExpiringStateStore(redissonClient, objectMapper);
        CacheKey key = namespace.key("state", "authoritative");

        store.put(key, new SampleValue("ok", 9), Duration.ofMillis(300));

        assertThat(store.get(key, SampleValue.class)).contains(new SampleValue("ok", 9));
        assertThat(redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).get()).doesNotContain("@class");
        Thread.sleep(450);
        assertThat(store.get(key, SampleValue.class)).isEmpty();
    }

    @Test
    void expiringHashStoreReadsEntriesAndFieldsInOneKey() {
        RedissonExpiringHashStore store = new RedissonExpiringHashStore(redissonClient, objectMapper);
        CacheKey key = namespace.key("hash", "snapshot");

        store.put(key, "2", new SampleValue("two", 2), Duration.ofSeconds(5));
        store.put(key, "0", new SampleValue("zero", 0), Duration.ofSeconds(5));

        assertThat(store.fields(key)).containsExactlyInAnyOrder("0", "2");
        assertThat(store.entries(key, SampleValue.class)).containsAllEntriesOf(Map.of(
                "0", new SampleValue("zero", 0),
                "2", new SampleValue("two", 2)));
        store.deleteField(key, "0");
        assertThat(store.get(key, "0", SampleValue.class)).isEmpty();
    }

    @Test
    void cacheStoreReadsPlainJsonWithoutClassMetadata() {
        RedissonCacheStore store = new RedissonCacheStore(redissonClient, objectMapper, new NoopCacheMetrics());
        CacheKey key = namespace.key("auth", "refresh", "plain-json");
        String json = """
                {"refreshJwtId":"r1","userId":1,"username":"demo","tokenHash":"hash","createdAt":"2026-07-07T06:00:00Z","expiresAt":"2026-08-06T06:00:00Z"}
                """;

        redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE)
                .set(json, 5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(store.get(key, SessionLike.class)).contains(new SessionLike(
                "r1",
                1L,
                "demo",
                "hash",
                Instant.parse("2026-07-07T06:00:00Z"),
                Instant.parse("2026-08-06T06:00:00Z")));
    }

    @Test
    void cacheStoreWritesPlainJsonWithoutClassMetadata() {
        RedissonCacheStore store = new RedissonCacheStore(redissonClient, objectMapper, new NoopCacheMetrics());
        CacheKey key = namespace.key("auth", "refresh", "written-json");
        SessionLike session = new SessionLike(
                "r2",
                2L,
                "demo2",
                "hash2",
                Instant.parse("2026-07-07T07:00:00Z"),
                Instant.parse("2026-08-06T07:00:00Z"));

        store.put(key, session, Duration.ofSeconds(5));

        String raw = redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).get();
        assertThat(raw).isNotBlank();
        assertThat(raw).doesNotContain("@class");
        assertThat(store.get(key, SessionLike.class)).contains(session);
    }

    @Test
    void cacheStoreDegradesInvalidJsonToMiss() {
        RedissonCacheStore store = new RedissonCacheStore(redissonClient, objectMapper, new NoopCacheMetrics());
        CacheKey key = namespace.key("auth", "refresh", "bad-json");
        redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE)
                .set("{bad-json", 5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(store.get(key, SessionLike.class)).isEmpty();
    }

    @Test
    void counterSetsTtlOnlyOnFirstIncrement() throws InterruptedException {
        RedissonAtomicCounter counter = new RedissonAtomicCounter(redissonClient);
        CacheKey key = namespace.key("counter", "ttl");

        assertThat(counter.incrementBy(key, 1, Duration.ofMillis(900))).isEqualTo(1);
        Thread.sleep(350);
        long ttlBefore = redissonClient.getBucket(key.value()).remainTimeToLive();

        assertThat(counter.incrementBy(key, 1, Duration.ofMillis(900))).isEqualTo(2);
        long ttlAfter = redissonClient.getBucket(key.value()).remainTimeToLive();

        assertThat(ttlAfter).isLessThanOrEqualTo(ttlBefore + 75);
    }

    @Test
    void tokenBucketConsumesWeightedAttemptsAndDoesNotChargeRejectedAttempt() {
        RedisLuaTokenBucket bucket = new RedisLuaTokenBucket(redissonClient, new NoopCacheMetrics());
        CacheKey key = namespace.key("bucket", UUID.randomUUID().toString());
        TokenBucketPolicy policy = new TokenBucketPolicy(5, 1, Duration.ofHours(1), Duration.ofMinutes(1));

        TokenBucketDecision first = bucket.tryConsume(key, policy, 3);
        TokenBucketDecision rejected = bucket.tryConsume(key, policy, 3);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isEqualTo(2);
        assertThat(rejected.retryAfter()).isPositive();
    }

    @Test
    void tokenBucketAtomicallyLimitsConcurrentConsumers() throws Exception {
        RedisLuaTokenBucket bucket = new RedisLuaTokenBucket(redissonClient, new NoopCacheMetrics());
        CacheKey key = namespace.key("bucket", UUID.randomUUID().toString());
        TokenBucketPolicy policy = new TokenBucketPolicy(10, 1, Duration.ofDays(1), Duration.ofMinutes(1));
        var pool = Executors.newFixedThreadPool(16);
        try {
            var requests = IntStream.range(0, 100)
                    .<Callable<Boolean>>mapToObj(ignored -> () -> bucket.tryConsume(key, policy, 1).allowed())
                    .toList();
            long allowed = pool.invokeAll(requests).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .count();

            assertThat(allowed).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void confirmationTokenConsumeSucceedsOnlyOnce() {
        RedisConfirmationTokenStore store = new RedisConfirmationTokenStore(
                redissonClient,
                namespace,
                objectMapper,
                new NoopCacheMetrics());
        TokenClaims claims = new TokenClaims(
                ConfirmationAction.SUBMIT_DAG,
                "conversation-1",
                "package-1",
                "hash-1",
                ConfirmationLevel.NORMAL,
                1,
                Instant.now(),
                Instant.now().plusSeconds(60),
                "nonce-1");

        store.save("token-1", claims, Duration.ofSeconds(5));

        assertThat(store.consume("token-1")).contains(claims);
        assertThat(store.consume("token-1")).isEmpty();
    }

    @Test
    void confirmationTokenExpiresAfterTtl() throws InterruptedException {
        RedisConfirmationTokenStore store = new RedisConfirmationTokenStore(
                redissonClient,
                namespace,
                objectMapper,
                new NoopCacheMetrics());
        TokenClaims claims = new TokenClaims(
                ConfirmationAction.SUBMIT_DAG,
                "conversation-2",
                "package-2",
                "hash-2",
                ConfirmationLevel.NORMAL,
                1,
                Instant.now(),
                Instant.now().plusSeconds(1),
                "nonce-2");

        store.save("token-2", claims, Duration.ofMillis(250));

        Thread.sleep(400);

        assertThat(store.consume("token-2")).isEmpty();
    }

    private record SampleValue(String name, int count) {
    }

    private record SessionLike(
            String refreshJwtId,
            Long userId,
            String username,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
    }
}
