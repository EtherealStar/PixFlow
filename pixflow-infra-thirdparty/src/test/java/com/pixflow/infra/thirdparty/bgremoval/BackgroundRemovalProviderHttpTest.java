package com.pixflow.infra.thirdparty.bgremoval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import com.pixflow.infra.thirdparty.bgremoval.provider.async.AsyncPollingBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.configurable.ConfigurableHttpBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.bgremoval.provider.removebg.RemoveBgBackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import com.pixflow.infra.thirdparty.http.DefaultThirdPartyAuthStrategy;
import com.pixflow.infra.thirdparty.http.RestClientThirdPartyHttpInvoker;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallTemplate;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyResilienceRegistry;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class BackgroundRemovalProviderHttpTest {

    @Test
    void removeBgProjectsMultipartRequestAndReturnsBinaryImage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(new Buffer().write(new byte[] {9, 8, 7})));

            ThirdPartyProperties.Provider provider = provider("removebg", server.url("/removebg").toString(),
                    new ThirdPartyProperties.Auth("api-key", Map.of("api-key", "secret")));
            RemoveBgBackgroundRemovalProvider client = new RemoveBgBackgroundRemovalProvider(
                    "removebg", provider, callTemplate("removebg", provider), httpInvoker(), new DefaultThirdPartyAuthStrategy(), new ThirdPartyErrorMapper(), metrics());

            BackgroundRemovalResult result = client.remove(new BackgroundRemovalRequest(new byte[] {1, 2, 3}, "image/jpeg", null, null));

            assertThat(result.contentType()).isEqualTo("image/png");
            assertThat(result.image()).containsExactly(9, 8, 7);
            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getHeader("X-Api-Key")).isEqualTo("secret");
            assertThat(recorded.getHeader("Content-Type")).contains("multipart/form-data").contains("boundary=");
            String body = recorded.getBody().readUtf8();
            assertThat(body).contains("name=\"image_file\"").contains("Content-Type: image/jpeg");
        }
    }

    @Test
    void configurableProviderSupportsJsonBase64RequestAndResponse() throws Exception {
        byte[] output = new byte[] {5, 4, 3};
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"image\":\"" + Base64.getEncoder().encodeToString(output) + "\"}}"));

            ThirdPartyProperties.Provider provider = new ThirdPartyProperties.Provider(
                    "configurable-http",
                    true,
                    server.url("/matting").toString(),
                    new ThirdPartyProperties.Auth("bearer", Map.of("token", "token-1")),
                    new ThirdPartyProperties.Request("json-base64", null, "image", null, null, null),
                    new ThirdPartyProperties.Response("json-base64-field", "data.image", null, null),
                    null,
                    null,
                    null);
            ConfigurableHttpBackgroundRemovalProvider client = new ConfigurableHttpBackgroundRemovalProvider(
                    "cloud", provider, callTemplate("cloud", provider), httpInvoker(), new DefaultThirdPartyAuthStrategy(), new ThirdPartyErrorMapper(), metrics(), new ObjectMapper());

            BackgroundRemovalResult result = client.remove(new BackgroundRemovalRequest(new byte[] {1, 2}, "image/png", null, null));

            assertThat(result.image()).containsExactly(output);
            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer token-1");
            assertThat(recorded.getBody().readUtf8()).contains(Base64.getEncoder().encodeToString(new byte[] {1, 2}));
        }
    }

    @Test
    void configurableMultipartBoundaryDoesNotUseProviderId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(new Buffer().write(new byte[] {4, 4, 4})));

            ThirdPartyProperties.Provider provider = new ThirdPartyProperties.Provider(
                    "configurable-http",
                    true,
                    server.url("/matting").toString(),
                    new ThirdPartyProperties.Auth("none", Map.of()),
                    new ThirdPartyProperties.Request("multipart-bytes", "image", null, null, null, null),
                    new ThirdPartyProperties.Response("binary-body", null, null, null),
                    null,
                    null,
                    null);
            ConfigurableHttpBackgroundRemovalProvider client = new ConfigurableHttpBackgroundRemovalProvider(
                    "bad provider id", provider, callTemplate("bad provider id", provider), httpInvoker(), new DefaultThirdPartyAuthStrategy(), new ThirdPartyErrorMapper(), metrics(), new ObjectMapper());

            BackgroundRemovalResult result = client.remove(new BackgroundRemovalRequest(new byte[] {1, 2}, "image/png", null, null));

            assertThat(result.image()).containsExactly(4, 4, 4);
            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getHeader("Content-Type"))
                    .contains("multipart/form-data")
                    .contains("pixflow-configurable-")
                    .doesNotContain("bad provider id");
        }
    }

    @Test
    void asyncProviderEncapsulatesSubmitAndPollingBehindSynchronousRemove() throws Exception {
        byte[] output = new byte[] {6, 6, 6};
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"jobId\":\"job-1\"}"));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"status\":\"SUCCEEDED\",\"image\":\"" + Base64.getEncoder().encodeToString(output) + "\"}"));

            ThirdPartyProperties.Polling polling = new ThirdPartyProperties.Polling(
                    "/submit", "/status/{jobId}", null, "status", "SUCCEEDED", "FAILED", "image", null, Duration.ofSeconds(2), Duration.ofMillis(10));
            ThirdPartyProperties.Provider provider = new ThirdPartyProperties.Provider(
                    "async",
                    true,
                    server.url("").toString(),
                    new ThirdPartyProperties.Auth("header", Map.of("header", "X-Signature", "value", "signed")),
                    null,
                    null,
                    polling,
                    null,
                    null);
            AsyncPollingBackgroundRemovalProvider client = new AsyncPollingBackgroundRemovalProvider(
                    "async-cloud", provider, callTemplate("async-cloud", provider), httpInvoker(), new DefaultThirdPartyAuthStrategy(), new ThirdPartyErrorMapper(), metrics(), new ObjectMapper());

            BackgroundRemovalResult result = client.remove(new BackgroundRemovalRequest(new byte[] {1}, "image/png", null, null));

            assertThat(result.image()).containsExactly(output);
            RecordedRequest submit = server.takeRequest();
            RecordedRequest poll = server.takeRequest();
            assertThat(submit.getPath()).isEqualTo("/submit");
            assertThat(poll.getPath()).isEqualTo("/status/job-1");
            assertThat(submit.getHeader("X-Signature")).isEqualTo("signed");
            assertThat(poll.getHeader("X-Signature")).isEqualTo("signed");
        }
    }

    @Test
    void asyncProviderFailsFastWhenEndpointIsMissing() {
        ThirdPartyProperties.Polling polling = new ThirdPartyProperties.Polling(
                "/submit", "/status/{jobId}", null, "status", "SUCCEEDED", "FAILED", "image", null, Duration.ofSeconds(2), Duration.ofMillis(10));
        ThirdPartyProperties.Provider provider = new ThirdPartyProperties.Provider(
                "async",
                true,
                "",
                new ThirdPartyProperties.Auth("none", Map.of()),
                null,
                null,
                polling,
                null,
                null);
        AsyncPollingBackgroundRemovalProvider client = new AsyncPollingBackgroundRemovalProvider(
                "async-cloud", provider, callTemplate("async-cloud", provider), httpInvoker(), new DefaultThirdPartyAuthStrategy(), new ThirdPartyErrorMapper(), metrics(), new ObjectMapper());

        assertThatThrownBy(() -> client.remove(new BackgroundRemovalRequest(new byte[] {1}, "image/png", null, null)))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("endpoint");
    }

    private static ThirdPartyProperties.Provider provider(String type, String endpoint, ThirdPartyProperties.Auth auth) {
        return new ThirdPartyProperties.Provider(type, true, endpoint, auth, null, null, null, null, null);
    }

    private static ThirdPartyCallTemplate callTemplate(String providerId, ThirdPartyProperties.Provider provider) {
        CacheNamespace namespace = new DefaultCacheNamespace("test", Duration.ofMinutes(1));
        ThirdPartyProperties properties = new ThirdPartyProperties(null, Map.of(providerId, provider), null,
                new ThirdPartyProperties.Resilience(2, Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofSeconds(2), 8, 8));
        return new ThirdPartyCallTemplate(new NoopSemaphore(), namespace, new ThirdPartyResilienceRegistry(properties), new ThirdPartyErrorMapper());
    }

    private static RestClientThirdPartyHttpInvoker httpInvoker() {
        return new RestClientThirdPartyHttpInvoker(RestClient.builder().build());
    }

    private static ThirdPartyMetrics metrics() {
        return new ThirdPartyMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static final class NoopSemaphore implements DistributedSemaphore {
        @Override
        public Permit acquire(CacheKey key, int permits, Duration waitTime) {
            return () -> {
            };
        }
    }
}
