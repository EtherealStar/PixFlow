package com.pixflow.module.vision.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.vision.VisionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class VisionServiceAutoConfigurationSentinelTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, VisionServiceAutoConfiguration.class))
            .withUserConfiguration(TestStubs.class);

    @Test
    void defaultContextExposesVisionServiceOnlyAsCapability() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(VisionService.class);
            assertThat(ctx).doesNotHaveBean("visionToolHandler");
            assertThat(ctx).doesNotHaveBean("visionToolDescriptor");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestStubs {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        VisionModelClient visionModelClient() {
            return request -> new ChatResult("{}", List.of(), StopReason.STOP, new TokenUsage(0, 0, 0));
        }

        @Bean
        ImagePipeline imagePipeline() {
            return new ImagePipeline() {
                @Override
                public byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode) {
                    return new byte[] {1};
                }

                @Override
                public byte[] runComposed(
                        List<ReopenableImageSource> members,
                        List<ImageOp> perMemberOps,
                        com.pixflow.infra.image.op.MultiImageOp compose,
                        List<ImageOp> postOps,
                        EncodeSpec encode) {
                    throw new UnsupportedOperationException("not used");
                }
            };
        }

        @Bean
        ObjectStorage objectStorage() {
            return new ObjectStorage() {
                @Override public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
                    return new ObjectRef(loc.bucket(), loc.key(), size, "etag");
                }
                @Override public InputStream getStream(ObjectLocation loc) {
                    return new ByteArrayInputStream(new byte[] {1});
                }
                @Override public byte[] getBytes(ObjectLocation loc) { return new byte[] {1}; }
                @Override public boolean exists(ObjectLocation loc) { return true; }
                @Override public StoredObjectMetadata stat(ObjectLocation loc) {
                    return new StoredObjectMetadata(1, "image/png", "etag", Instant.now());
                }
                @Override public void delete(ObjectLocation loc) {}
                @Override public void deleteByPrefix(BucketType bucket, String prefix) {}
                @Override public URL presignGet(ObjectLocation loc, Duration ttl) { throw new UnsupportedOperationException(); }
                @Override public URL presignPut(ObjectLocation loc, Duration ttl) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
