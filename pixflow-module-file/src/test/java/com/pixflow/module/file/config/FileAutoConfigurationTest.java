package com.pixflow.module.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.common.time.TimeAutoConfiguration;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.web.FileController;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
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
    }
}
