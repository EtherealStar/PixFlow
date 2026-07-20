package com.pixflow.infra.thirdparty.http;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public final class ThirdPartyMutableRequest {
    private final String api;

    private final String providerId;

    private HttpMethod method;

    private URI uri;

    private final HttpHeaders headers = new HttpHeaders();

    private byte[] body = new byte[0];

    private MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;

    private final Map<String, String> queryParams = new LinkedHashMap<>();

    public ThirdPartyMutableRequest(String api, String providerId) {
        this.api = api;
        this.providerId = providerId;
    }

    public String api() {
        return api;
    }

    public String providerId() {
        return providerId;
    }

    public HttpMethod method() {
        return method;
    }

    public void method(HttpMethod method) {
        this.method = method;
    }

    public URI uri() {
        return uri;
    }

    public void uri(URI uri) {
        this.uri = uri;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public void body(byte[] body) {
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public MediaType contentType() {
        return contentType;
    }

    public void contentType(MediaType contentType) {
        this.contentType = contentType;
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public ThirdPartyHttpRequest toImmutable() {
        return new ThirdPartyHttpRequest(
                api,
                providerId,
                method,
                uri,
                HttpHeaders.readOnlyHttpHeaders(headers),
                body(),
                contentType);
    }
}
