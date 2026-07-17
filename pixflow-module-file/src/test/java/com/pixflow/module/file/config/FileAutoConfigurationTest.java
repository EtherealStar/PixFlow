package com.pixflow.module.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.common.time.TimeAutoConfiguration;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.cache.state.ExpiringHashStore;
import com.pixflow.infra.cache.state.ExpiringStateStore;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.pkg.ConservativePackageReferenceChecker;
import com.pixflow.module.file.pkg.PackageReferenceChecker;
import com.pixflow.module.file.web.FileController;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FileAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TimeAutoConfiguration.class, FileAutoConfiguration.class))
            .withUserConfiguration(RequiredPorts.class);

    @Test
    void fileServiceAndControllerAreCreatedTogetherWhenRequiredPortsExist() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FileService.class);
            assertThat(context).hasSingleBean(FileController.class);
            assertThat(context).hasSingleBean(SourceImageReader.class);
            assertThat(context.getBean(PackageReferenceChecker.class))
                    .isInstanceOf(ConservativePackageReferenceChecker.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredPorts {
        @Bean
        SqlSessionFactory sqlSessionFactory() {
            SqlSessionFactory sqlSessionFactory = mock(SqlSessionFactory.class);
            var environment = new Environment("test", new JdbcTransactionFactory(), mock(DataSource.class));
            when(sqlSessionFactory.getConfiguration()).thenReturn(new org.apache.ibatis.session.Configuration(environment));
            return sqlSessionFactory;
        }

        @Bean
        ObjectStorage objectStorage() {
            return new ObjectStorage() {
                @Override
                public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
                    return new ObjectRef(loc.bucket(), loc.key(), size, "etag");
                }

                @Override
                public InputStream getStream(ObjectLocation loc) {
                    return new ByteArrayInputStream(new byte[] {1, 2, 3});
                }

                @Override
                public byte[] getBytes(ObjectLocation loc) {
                    return new byte[] {1, 2, 3};
                }

                @Override
                public boolean exists(ObjectLocation loc) {
                    return true;
                }

                @Override
                public StoredObjectMetadata stat(ObjectLocation loc) {
                    return new StoredObjectMetadata(3L, "image/png", "etag", Instant.now());
                }

                @Override
                public void delete(ObjectLocation loc) {
                }

                @Override
                public void deleteByPrefix(BucketType bucket, String prefix) {
                }

                @Override
                public ObjectRef copy(ObjectLocation source, ObjectLocation target) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public URL presignGet(ObjectLocation loc, Duration ttl) {
                    throw new UnsupportedOperationException("not used in auto-configuration test");
                }

                @Override
                public URL presignPut(ObjectLocation loc, Duration ttl) {
                    throw new UnsupportedOperationException("not used in auto-configuration test");
                }
            };
        }

        @Bean
        MessagePublisher messagePublisher() {
            return request -> PublishResult.confirmed(request.topic(), request.tag(), "message-id", "queue");
        }

        @Bean
        CacheNamespace cacheNamespace() {
            return new DefaultCacheNamespace("test", Duration.ofHours(1));
        }

        @Bean
        ExpiringStateStore expiringStateStore() {
            return new ExpiringStateStore() {
                private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();

                @Override
                public <T> Optional<T> get(CacheKey key, Class<T> type) {
                    Object value = values.get(key.value());
                    if (value == null) {
                        return Optional.empty();
                    }
                    return Optional.of(type.cast(value));
                }

                @Override
                public <T> void put(CacheKey key, T value, Duration ttl) {
                    values.put(key.value(), value);
                }

                @Override
                public <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl) {
                    return values.putIfAbsent(key.value(), value) == null;
                }

                @Override
                public void expire(CacheKey key, Duration ttl) {
                }

                @Override
                public void delete(CacheKey key) {
                    values.remove(key.value());
                }
            };
        }

        @Bean
        ExpiringHashStore expiringHashStore() {
            return new ExpiringHashStore() {
                private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> values = new ConcurrentHashMap<>();

                @Override
                public <T> Optional<T> get(CacheKey key, String field, Class<T> type) {
                    return Optional.ofNullable(values.getOrDefault(key.value(), new ConcurrentHashMap<>()).get(field)).map(type::cast);
                }

                @Override
                public <T> void put(CacheKey key, String field, T value, Duration ttl) {
                    values.computeIfAbsent(key.value(), ignored -> new ConcurrentHashMap<>()).put(field, value);
                }

                @Override
                public <T> Map<String, T> entries(CacheKey key, Class<T> type) {
                    Map<String, T> result = new java.util.HashMap<>();
                    values.getOrDefault(key.value(), new ConcurrentHashMap<>())
                            .forEach((field, value) -> result.put(field, type.cast(value)));
                    return result;
                }

                @Override
                public Set<String> fields(CacheKey key) {
                    return Set.copyOf(values.getOrDefault(key.value(), new ConcurrentHashMap<>()).keySet());
                }

                @Override
                public void deleteField(CacheKey key, String field) {
                    values.getOrDefault(key.value(), new ConcurrentHashMap<>()).remove(field);
                }

                @Override
                public void expire(CacheKey key, Duration ttl) {
                }

                @Override
                public void delete(CacheKey key) {
                    values.remove(key.value());
                }
            };
        }

        @Bean
        LockTemplate lockTemplate() {
            return new LockTemplate() {
                @Override
                public <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action) {
                    return action.get();
                }

                @Override
                public void runWithLock(CacheKey key, Duration waitTime, Runnable action) {
                    action.run();
                }

                @Override
                public boolean tryRunWithLock(CacheKey key, Duration waitTime, java.util.function.Consumer<com.pixflow.infra.cache.lock.LockGuard> action) {
                    action.accept(() -> true);
                    return true;
                }
            };
        }
    }
}
